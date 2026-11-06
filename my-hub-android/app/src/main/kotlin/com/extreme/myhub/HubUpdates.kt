package com.extreme.myhub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class HubRelease(val version: String, val url: String, val sha256: String, val size: Long)
internal object HubUpdatePolicy {
    private const val BASE = "https://github.com/derangedchief-crypto/ARVIO/releases/download/staging-testing/"
    fun version(value: String): List<Int> {
        val match = Regex("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:-dev)?$").matchEntire(value)
            ?: error("Invalid update version")
        return match.groupValues.drop(1).map(String::toInt)
    }
    fun newer(candidate: String, installed: String): Boolean {
        val a = version(candidate); val b = version(installed)
        return a.indices.firstOrNull { a[it] != b[it] }?.let { a[it] > b[it] } ?: false
    }
    fun latest(assets: List<JSONObject>, installed: String): HubRelease? {
        val candidates = assets.mapNotNull { asset ->
            val name = asset.optString("name")
            val match = Regex("^My-Hub-(\\d{1,6}\\.\\d{1,6}\\.\\d{1,6})-testing\\.apk$").matchEntire(name) ?: return@mapNotNull null
            if (asset.optString("state") != "uploaded") return@mapNotNull null
            val v = match.groupValues[1]
            if (!newer(v, installed)) return@mapNotNull null
            val digest = asset.optString("digest").removePrefix("sha256:")
            require(asset.optString("digest").startsWith("sha256:") && digest.matches(Regex("[a-f0-9]{64}"))) { "Update checksum unavailable" }
            require(asset.optString("browser_download_url") == BASE + name) { "Unexpected update location" }
            val size = asset.optLong("size")
            require(size in 1..(128L * 1024 * 1024)) { "Invalid update size" }
            HubRelease(v, BASE + name, digest, size)
        }
        return candidates.reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }
    }
    fun verifyIdentity(expected: HubRelease, packageName: String, installedPackage: String, versionName: String, code: Long, installedCode: Long, signers: Set<String>, installedSigners: Set<String>) {
        require(packageName == installedPackage && version(versionName) == version(expected.version) && code > installedCode) { "Update identity or version mismatch" }
        require(signers.isNotEmpty() && signers == installedSigners) { "Update signing certificate does not match" }
    }
}

internal data class HubUpdateState(val release: HubRelease? = null, val visible: Boolean = false, val busy: Boolean = false, val downloading: Boolean = false, val progress: Int = 0, val ready: Boolean = false, val message: String? = null)

internal class HubUpdates(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(HubUpdateState())
    val state = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var work: Job? = null
    private var lastAttempt = 0L
    private var apk: File? = null

    fun check(manual: Boolean = false) {
        if (work?.isActive == true) return
        if (!manual && System.currentTimeMillis() - lastAttempt < TimeUnit.HOURS.toMillis(1)) return
        lastAttempt = System.currentTimeMillis()
        mutable.update { it.copy(busy = true, message = null, visible = manual || it.visible) }
        work = viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    val assets = mutableListOf<JSONObject>()
                    for (page in 1..20) {
                        coroutineContext.ensureActive()
                        val request = Request.Builder().url("https://api.github.com/repos/derangedchief-crypto/ARVIO/releases/414147175/assets?per_page=100&page=$page")
                            .header("Accept", "application/vnd.github+json").header("User-Agent", "MyHub/${BuildConfig.VERSION_NAME}")
                            .cacheControl(CacheControl.FORCE_NETWORK).build()
                        val rows = http.newCall(request).execute().use { response ->
                            check(response.isSuccessful) { "Update check unavailable" }
                            val source = response.body?.source() ?: error("Empty update response")
                            val buffer = okio.Buffer()
                            while (!source.exhausted()) { coroutineContext.ensureActive(); source.read(buffer, 8192); require(buffer.size <= 4L * 1024 * 1024) { "Update response too large" } }
                            JSONArray(buffer.readUtf8())
                        }
                        for (i in 0 until rows.length()) assets.add(rows.getJSONObject(i))
                        if (rows.length() < 100) break
                        check(page < 20) { "Incomplete update listing" }
                    }
                    val installed = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
                    HubUpdatePolicy.latest(assets, installed.versionName ?: BuildConfig.VERSION_NAME)
                }
                apk = null
                mutable.value = HubUpdateState(release = latest, visible = manual || latest != null, message = if (latest == null) "My Hub is up to date." else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, visible = manual || it.visible, message = if (manual) "Could not check for updates. Please try again." else null) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun dismiss() { if (!mutable.value.busy) mutable.update { it.copy(visible = false) } }
    fun download() {
        val release = mutable.value.release ?: return
        if (work?.isActive == true) return
        mutable.update { it.copy(busy = true, downloading = true, progress = 0, message = null) }
        work = viewModelScope.launch {
            var pending: File? = null
            try {
                apk = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().cacheDir, "self-updates").apply { mkdirs() }
                    directory.listFiles()?.forEach { it.delete() }
                    val file = File(directory, "My-Hub-${release.version}.apk.part"); pending = file
                    val digest = MessageDigest.getInstance("SHA-256")
                    // GitHub redirects public release assets to its HTTPS CDN.
                    val downloader = http.newBuilder().followRedirects(true).followSslRedirects(false).callTimeout(5, TimeUnit.MINUTES).build()
                    downloader.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                        check(response.isSuccessful && response.request.url.isHttps) { "Download unavailable" }
                        val input = response.body?.byteStream() ?: error("Empty download")
                        input.use { stream -> file.outputStream().use { out ->
                            val buffer = ByteArray(32768); var total = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = stream.read(buffer); if (count < 0) break
                                total += count; require(total <= release.size) { "Unexpected APK size" }
                                digest.update(buffer, 0, count); out.write(buffer, 0, count)
                                val progress = (100 * total / release.size).toInt()
                                if (progress != mutable.value.progress) mutable.update { it.copy(progress = progress) }
                            }
                            require(total == release.size) { "Incomplete APK" }
                        } }
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "APK checksum mismatch" }
                    verifyApk(file, release)
                    val final = File(directory, "My-Hub-${release.version}.apk")
                    kotlin.check(file.renameTo(final)); pending = final; final
                }
                pending = null
                mutable.update { it.copy(busy = false, downloading = false, ready = true, message = "Download verified. Ready to install.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, downloading = false, ready = false, message = "Update download or verification failed. Please retry.") } }
            finally { pending?.delete(); mutable.update { it.copy(busy = false, downloading = false) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: HubRelease) {
        val context = getApplication<Application>(); val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        fun signers(info: android.content.pm.PackageInfo) = info.signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        HubUpdatePolicy.verifyIdentity(release, archive.packageName, context.packageName, archive.versionName ?: "", PackageInfoCompat.getLongVersionCode(archive), PackageInfoCompat.getLongVersionCode(installed), signers(archive), signers(installed))
    }

    fun install(context: Context) {
        val file = apk ?: return; val release = mutable.value.release ?: return
        if (work?.isActive == true) return
        mutable.update { it.copy(busy = true) }
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { verifyApk(file, release) }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { mutable.update { it.copy(message = "Could not open the installer. Allow My Hub to install apps, then retry.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
