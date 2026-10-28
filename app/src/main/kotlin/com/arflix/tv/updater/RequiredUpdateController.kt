package com.arflix.tv.updater

import android.content.Context
import com.arflix.tv.BuildConfig
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RequiredUpdateState(
    val checking: Boolean = true,
    val update: AppUpdate? = null,
    val permissionRequired: Boolean = false,
) {
    val blocksApp: Boolean get() = checking || update != null
}

/** App-wide policy, independent of navigation, ignored tags and installer status.
 * Only the installed package version can satisfy a known update requirement. */
@Singleton
class RequiredUpdateController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppUpdateRepository,
    private val downloader: ApkDownloader,
    private val statuses: UpdateStatusManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = Gson()
    private val preferences = context.getSharedPreferences("required_update_v1", Context.MODE_PRIVATE)
    private val preferenceKey = "release:${BuildConfig.UPDATE_RELEASE_TAG.ifBlank { "customer" }}"
    private val supported = repository.supportsSelfUpdate()
    private val _state = MutableStateFlow(RequiredUpdateState(
        checking = supported,
        update = if (supported) readRequirement() else null,
    ))
    val state = _state.asStateFlow()
    val status = statuses.status
    private var lastCheckAtMs: Long? = null
    private var downloadJob: Job? = null
    private var installJob: Job? = null
    @Volatile private var apkFile: File? = null

    init {
        scope.launch {
            statuses.status.collect { status ->
                when (status) {
                    is UpdateStatus.UpdateAvailable -> mutex.withLock { requireUpdate(status.update) }
                    is UpdateStatus.Success -> mutex.withLock { reconcileInstalledVersion() }
                    else -> Unit
                }
            }
        }
    }

    private fun readRequirement(): AppUpdate? = runCatching {
        gson.fromJson(preferences.getString(preferenceKey, null), AppUpdate::class.java)
            ?.takeIf { validRelease(it) && VersionUtils.isRemoteNewer(it.tag, repository.getInstalledVersionName()) }
    }.getOrNull()

    private fun validRelease(update: AppUpdate): Boolean =
        ReleaseVersionPolicy.accepts(update.tag, BuildConfig.BRAND_ID.isNotEmpty()) &&
            update.assetUrl.startsWith("https://") && update.assetName.endsWith(".apk", ignoreCase = true)

    private fun requireUpdate(update: AppUpdate) {
        if (!supported || !validRelease(update) ||
            !VersionUtils.isRemoteNewer(update.tag, repository.getInstalledVersionName())) return
        val current = _state.value.update
        if (current != null && VersionUtils.isRemoteNewer(current.tag, update.tag)) return
        if (current?.tag == update.tag && current.assetUrl == update.assetUrl &&
            current.assetSizeBytes == update.assetSizeBytes) return
        downloadJob?.cancel()
        apkFile = null
        // Keep the requirement even if the process dies or subsequent checks fail.
        preferences.edit().putString(preferenceKey, gson.toJson(update)).commit()
        _state.value = _state.value.copy(update = update, checking = false, permissionRequired = false)
        statuses.updateStatus(UpdateStatus.UpdateAvailable(update))
    }

    private fun reconcileInstalledVersion() {
        val required = _state.value.update ?: return
        if (!VersionUtils.isRemoteNewer(required.tag, repository.getInstalledVersionName())) {
            preferences.edit().remove(preferenceKey).apply()
            _state.value = RequiredUpdateState(checking = false)
            apkFile = null
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        mutex.withLock {
            reconcileInstalledVersion()
            val now = android.os.SystemClock.elapsedRealtime()
            // Installer/settings returns and rotations must not burn API quota.
            if (lastCheckAtMs?.let { now - it < RESUME_DEBOUNCE_MS } == true) return@withLock
            lastCheckAtMs = now
            val result = try {
                repository.getLatestUpdate()
            } catch (cancelled: CancellationException) {
                lastCheckAtMs = null // An interrupted launch check must retry on resume.
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.onSuccess { update ->
                requireUpdate(update)
            }
            // A failed check is not evidence of a mandatory update. A known
            // requirement, however, is never cleared by a failure/older response.
            _state.value = _state.value.copy(checking = false)
        }
        if (_state.value.permissionRequired && ApkInstaller.canRequestPackageInstalls(context)) {
            _state.value = _state.value.copy(permissionRequired = false)
            install()
        }
    }

    @Synchronized
    fun download() {
        val update = _state.value.update ?: return
        if (downloadJob?.isActive == true || installJob?.isActive == true) return
        downloadJob = scope.launch {
            statuses.updateStatus(UpdateStatus.Downloading(0f, update))
            val name = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(File(context.cacheDir, "required-updates"), "${VersionUtils.normalize(update.tag)}-$name")
            val result = downloader.download(update.assetUrl, destination) { downloaded, total ->
                if (_state.value.update == update) statuses.updateStatus(UpdateStatus.Downloading(
                    total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }, update))
            }
            if (_state.value.update != update) return@launch
            result.onSuccess { file ->
                apkFile = file
                statuses.updateStatus(UpdateStatus.ReadyToInstall(file.absolutePath, update))
                install()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                statuses.updateStatus(UpdateStatus.Failure(error.message ?: "Update download failed. Please retry.", update))
            }
        }
    }

    @Synchronized
    fun install() {
        val update = _state.value.update ?: return
        val file = apkFile?.takeIf { it.isFile } ?: run { download(); return }
        if (installJob?.isActive == true) return
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _state.value = _state.value.copy(permissionRequired = true)
            return
        }
        installJob = scope.launch {
            try {
                @Suppress("DEPRECATION")
                val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    ?: error("The downloaded APK is invalid. Please download it again.")
                check(archive.packageName == context.packageName) { "This update is for a different app." }
                val archiveVersion = archive.versionName?.takeIf { it.isNotBlank() }
                    ?: error("The downloaded APK has no version information.")
                check(!VersionUtils.isRemoteNewer(update.tag, archiveVersion) &&
                    VersionUtils.isRemoteNewer(archiveVersion, repository.getInstalledVersionName())) {
                    "The downloaded APK is older than the required update."
                }
                ApkInstaller.checkSignatureConflict(context, file)?.let { error(it) }
                if (_state.value.update != update) return@launch
                statuses.updateStatus(UpdateStatus.Installing(update))
                check(ApkInstaller.launchInstall(context, file)) { "Android could not open the installer. Please retry." }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                apkFile = null
                statuses.updateStatus(UpdateStatus.Failure(error.message ?: "Unable to install update. Please retry.", update))
            }
        }
    }

    fun openInstallPermission() {
        runCatching {
            ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let(context::startActivity)
        }.onFailure {
            _state.value = _state.value.copy(permissionRequired = false)
            statuses.updateStatus(UpdateStatus.Failure("Unable to open installation settings. Please allow app updates in Android settings.", _state.value.update))
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 5 * 60_000L
        const val RESUME_DEBOUNCE_MS = 30_000L
    }
}
