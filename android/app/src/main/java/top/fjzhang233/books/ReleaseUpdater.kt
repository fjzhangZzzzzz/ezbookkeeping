package top.fjzhang233.books

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AndroidRelease(val versionName: String, val versionCode: Long, val minSdk: Int, val assetName: String, val sha256: String, val downloadUrl: String, val sourceCommit: String)

object UpdatePolicy {
    const val PACKAGE = "top.fjzhang233.books"
    const val CERTIFICATE = "6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d"
    const val REPOSITORY = "fjzhangZzzzzz/ezbookkeeping"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    fun parse(metadata: JSONObject, tag: String, apkUrl: String, installed: Long, sdk: Int): AndroidRelease {
        require(Regex("android-v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag))
        val version = tag.removePrefix("android-v")
        require(metadata.getInt("schemaVersion") == 1 && metadata.getString("channel") == "stable")
        require(metadata.getString("packageId") == PACKAGE && metadata.getString("versionName") == version)
        val code = metadata.getLong("versionCode"); require(code > installed && code <= Int.MAX_VALUE)
        val min = metadata.getInt("minSdk"); require(min in 26..sdk)
        val name = "ezbookkeeping-android-$version.apk"; require(metadata.getString("apkAssetName") == name)
        val hash = metadata.getString("apkSha256").lowercase(); require(hash.matches(Regex("[0-9a-f]{64}")))
        require(metadata.getString("signingCertificateSha256").lowercase().replace(":", "") == CERTIFICATE)
        require(metadata.getString("sourceCommit").matches(Regex("[0-9a-f]{40}")))
        require(apkUrl == "https://github.com/$REPOSITORY/releases/download/$tag/$name")
        return AndroidRelease(version, code, min, name, hash, apkUrl, metadata.getString("sourceCommit"))
    }
    fun verifyIdentity(packageId: String, code: Long, minSdk: Int, candidateSigners: Set<String>, installedSigners: Set<String>, release: AndroidRelease, sdk: Int) {
        require(packageId == PACKAGE && code == release.versionCode && minSdk == release.minSdk && sdk >= minSdk)
        require(candidateSigners == installedSigners && candidateSigners == setOf(CERTIFICATE)) { "安装包签名与当前应用身份不一致" }
    }
}

class ReleaseUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("update-cache", 0)
    private val hosts = setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private fun allowed(url: String) = Uri.parse(url).let { it.scheme == "https" && it.host in hosts && it.userInfo == null && it.port in listOf(-1, 443) }
    private fun request(url: String, etag: String? = null): Response {
        var next = url
        repeat(5) {
            require(allowed(next)) { "更新地址不受信任" }
            val builder = Request.Builder().url(next).header("Accept", "application/vnd.github+json").header("User-Agent", "FamilyBooks-Android/${BuildConfig.VERSION_NAME}")
            if (next == url && !etag.isNullOrBlank()) builder.header("If-None-Match", etag)
            val response = client.newCall(builder.build()).execute()
            if (response.code !in 300..399 || response.code == 304) return response
            val location = response.header("Location"); val resolved = location?.let { response.request.url.resolve(it) }?.toString(); response.close()
            next = resolved ?: throw IOException("更新地址无效")
        }
        throw IOException("更新跳转次数过多")
    }
    private fun bounded(response: Response, max: Long): ByteArray {
        val body = response.body ?: throw IOException("更新响应为空")
        require(body.contentLength() <= max) { "更新文件超过大小限制" }
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input -> val chunk = ByteArray(8192); while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size().toLong() + n <= max); out.write(chunk, 0, n) } }
        return out.toByteArray()
    }
    suspend fun check(manual: Boolean): AndroidRelease? = withContext(Dispatchers.IO) {
        if (!manual && System.currentTimeMillis() - prefs.getLong("checked", 0) < 300_000) return@withContext null
        val candidates = mutableListOf<AndroidRelease>()
        for (page in 1..5) {
            val url = "https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases?per_page=100&page=$page"
            val key = "page$page"
            val text = request(url, prefs.getString("etag$key", null)).use { response ->
                if (response.code == 304) prefs.getString(key, null) ?: throw IOException("更新缓存失效")
                else {
                    if (!response.isSuccessful) throw IOException("暂时无法连接 GitHub，请稍后重试")
                    val body = bounded(response, 4L * 1024 * 1024).toString(Charsets.UTF_8)
                    prefs.edit().putString(key, body).putString("etag$key", response.header("ETag")).apply(); body
                }
            }
            val rows = JSONArray(text).objects()
            for (release in rows) {
                val tag = release.optString("tag_name")
                if (release.optBoolean("draft") || release.optBoolean("prerelease") || !Regex("android-v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) continue
                val manifest = release.array("assets").singleOrNull { it.optString("name") == "update.json" } ?: continue
                val manifestUrl = "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/update.json"
                if (manifest.optString("browser_download_url") != manifestUrl) continue
                val metadata = request(manifestUrl).use { response -> if (!response.isSuccessful) throw IOException("更新说明暂不可用"); JSONObject(bounded(response, 64 * 1024).toString(Charsets.UTF_8)) }
                val asset = release.array("assets").singleOrNull { it.optString("name") == metadata.optString("apkAssetName") } ?: continue
                if (asset.optLong("size") !in 1..UpdatePolicy.MAX_APK_BYTES) continue
                runCatching { UpdatePolicy.parse(metadata, tag, asset.getString("browser_download_url"), BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }.getOrNull()?.let(candidates::add)
            }
            if (rows.size < 100) break
        }
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        candidates.maxByOrNull { it.versionCode }
    }
    suspend fun download(release: AndroidRelease, progress: (Long, Long) -> Unit): File = suspendCancellableCoroutine { continuation ->
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(dir, "download-${java.util.UUID.randomUUID()}.part"); val output = File(dir, release.assetName)
        var call: Call? = null
        continuation.invokeOnCancellation { call?.cancel() }
        fun fetch(url: String, redirects: Int) {
            if (!continuation.isActive) { partial.delete(); return }
            if (!allowed(url) || redirects > 4) { continuation.resumeWithException(IOException("更新地址无效")); return }
            val next = client.newCall(Request.Builder().url(url).header("User-Agent", "FamilyBooks-Android/${BuildConfig.VERSION_NAME}").build()); call = next
            next.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { partial.delete(); if (continuation.isActive) continuation.resumeWithException(IOException("下载中断，请重试")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (r.code in 300..399) { val redirected = r.header("Location")?.let { r.request.url.resolve(it) }?.toString(); if (redirected != null) fetch(redirected, redirects + 1) else if (continuation.isActive) continuation.resumeWithException(IOException("下载跳转无效")); return }
                        try {
                            require(r.isSuccessful); val body = r.body ?: throw IOException()
                            val total = body.contentLength(); require(total in -1..UpdatePolicy.MAX_APK_BYTES)
                            var read = 0L; val digest = MessageDigest.getInstance("SHA-256")
                            body.byteStream().use { input -> partial.outputStream().use { sink ->
                                val buffer = ByteArray(16384)
                                while (true) { if (!continuation.isActive) throw IOException(); val n = input.read(buffer); if (n < 0) break; read += n; require(read <= UpdatePolicy.MAX_APK_BYTES); sink.write(buffer, 0, n); digest.update(buffer, 0, n); progress(read, total) }
                            } }
                            require(read > 0 && (total < 0 || total == read))
                            require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "下载摘要不一致" }
                            verifyApk(partial, release)
                            if (!continuation.isActive) throw IOException()
                            if (output.exists()) kotlin.check(output.delete()); kotlin.check(partial.renameTo(output))
                            continuation.resume(output)
                        } catch (_: Exception) { partial.delete(); if (continuation.isActive) continuation.resumeWithException(IOException("安装包校验未通过，请重新下载")) }
                    }
                }
            })
        }
        fetch(release.downloadUrl, 0)
    }
    @Suppress("DEPRECATION")
    fun verifyApk(file: File, release: AndroidRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("安装包无法解析")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(candidate.versionName == release.versionName)
        fun signers(info: android.content.pm.PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code > installedCode)
        UpdatePolicy.verifyIdentity(candidate.packageName, code, candidate.applicationInfo?.minSdkVersion ?: 0, signers(candidate), signers(installed), release, Build.VERSION.SDK_INT)
    }
    suspend fun install(file: File, release: AndroidRelease): Boolean {
        withContext(Dispatchers.IO) { verifyDownloaded(file, release) }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    fun verifyDownloaded(file: File, release: AndroidRelease) {
        require(file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile && file.name == release.assetName)
        require(file.isFile && file.length() in 1..UpdatePolicy.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(16384); while(true) { val n=stream.read(buffer); if(n < 0) break; digest.update(buffer,0,n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        verifyApk(file, release)
    }
}
