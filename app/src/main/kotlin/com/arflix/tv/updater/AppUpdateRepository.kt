package com.arflix.tv.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.arflix.tv.BuildConfig
import com.arflix.tv.R
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("draft") val draft: Boolean,
    @SerializedName("prerelease") val prerelease: Boolean,
    @SerializedName("assets") val assets: List<GitHubAssetDto>
)

@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val gson = Gson()
    // A launch check must not inherit long playback/catalogue timeouts.
    private val updateClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun isPlayStoreInstall(): Boolean {
        val installer = getInstallerPackageName()
        return installer == "com.android.vending"
    }

    fun supportsSelfUpdate(): Boolean = BuildConfig.SELF_UPDATE_ENABLED && !isPlayStoreInstall()

    /**
     * Returns the actually installed version name from PackageManager,
     * which reflects the real installed APK even if the old process is still running.
     */
    fun getInstalledVersionName(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val channelTag = BuildConfig.UPDATE_RELEASE_TAG.trim()
                val releasePath = if (channelTag.isBlank()) {
                    "latest"
                } else {
                    "tags/$channelTag"
                }
                val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/$releasePath"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ARVIO/${BuildConfig.VERSION_NAME}")
                    // Always hit the network for this one. The shared OkHttp client
                    // has a 50MB disk cache for API responses (TMDB, Trakt, etc.), and
                    // without this, a "no update" response gets cached and keeps being
                    // served as-is even after a new release is published — the app
                    // would report "up to date" indefinitely until the cache expires.
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()

                updateClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(context.getString(R.string.update_error_github_api, response.code))
                    }
                    val body = response.body?.string().orEmpty()
                    val dto = gson.fromJson(body, GitHubReleaseDto::class.java)
                        ?: error(context.getString(R.string.update_error_empty_release_response))

                    if (dto.draft || (channelTag.isBlank() && dto.prerelease)) {
                        error(context.getString(R.string.update_error_draft_prerelease))
                    }

                    val tag = dto.tagName?.takeIf { it.isNotBlank() }
                        ?: dto.name?.takeIf { it.isNotBlank() }
                        ?: error(context.getString(R.string.update_error_missing_release_tag))
                    // The rolling staging tag is not a version, so use the
                    // release title (the workflow sets it to 2.0.xxx) for
                    // comparison and for the update dialog.
                    val displayVersion = if (channelTag.isBlank()) {
                        tag
                    } else {
                        dto.name?.takeIf { it.isNotBlank() } ?: tag
                    }
                    val candidates = if (BuildConfig.BRAND_ID.isNotBlank()) {
                        val expectedName = ReleaseVersionPolicy.brandAssetName(displayVersion)
                            ?: error("Invalid branded update version")
                        // Rolling channels retain immutable older APKs. Never let ABI
                        // selection choose another revision or a generic package.
                        dto.assets.filter { it.name == expectedName }
                    } else dto.assets
                    val asset = AbiSelector.chooseBestApkAsset(candidates)
                        ?: error(context.getString(R.string.update_error_no_apk_asset))

                    AppUpdate(
                        tag = displayVersion,
                        title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                        notes = dto.body.orEmpty(),
                        releaseUrl = dto.htmlUrl,
                        assetName = asset.name,
                        assetUrl = asset.browserDownloadUrl,
                        assetSizeBytes = asset.size
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun getInstallerPackageName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
