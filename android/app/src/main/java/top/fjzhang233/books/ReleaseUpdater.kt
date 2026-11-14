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
    const val ORIGIN = "https://books.fjzhang233.top"
    const val MANIFEST_URL = "$ORIGIN/android/update.json"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val VERSION_PATTERN = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
    fun apkUrl(version: String, assetName: String): String {
        require(Regex(VERSION_PATTERN).matches(version))
        require(assetName == "ezbookkeeping-android-$version.apk")
        return "$ORIGIN/android/releases/$version/$assetName"
    }
    fun allowed(url: String): Boolean {
        if (url == MANIFEST_URL) return true
        val match = Regex("${Regex.escape(ORIGIN)}/android/releases/($VERSION_PATTERN)/ezbookkeeping-android-($VERSION_PATTERN)\\.apk").matchEntire(url) ?: return false
        return match.groupValues[1] == match.groupValues[2]
    }
    fun parse(metadata: JSONObject, installed: Long, sdk: Int): AndroidRelease {
        require(metadata.getInt("schemaVersion") == 1 && metadata.getString("channel") == "stable")
        val version = metadata.getString("versionName")
        require(metadata.getString("packageId") == PACKAGE && Regex(VERSION_PATTERN).matches(version))
        require(metadata.get("versionCode").toString().matches(Regex("[1-9][0-9]*")))
        val code = metadata.getLong("versionCode"); require(code > installed && code <= Int.MAX_VALUE)
        val min = metadata.getInt("minSdk"); require(min in 26..sdk)
        val name = metadata.getString("apkAssetName"); val url = apkUrl(version, name)
        val hash = metadata.getString("apkSha256").lowercase(); require(hash.matches(Regex("[0-9a-f]{64}")))
        require(metadata.getString("signingCertificateSha256").lowercase().replace(":", "") == CERTIFICATE)
        require(metadata.getString("sourceCommit").matches(Regex("[0-9a-f]{40}")))
        return AndroidRelease(version, code, min, name, hash, url, metadata.getString("sourceCommit"))
    }
    fun verifyIdentity(packageId: String, code: Long, minSdk: Int, candidateSigners: Set<String>, installedSigners: Set<String>, release: AndroidRelease, sdk: Int) {
        require(packageId == PACKAGE && code == release.versionCode && minSdk == release.minSdk && sdk >= minSdk)
        require(candidateSigners == installedSigners && candidateSigners == setOf(CERTIFICATE)) { "安装包签名与当前应用身份不一致" }
    }
}

/** Anonymous update transport; fixed origin, no redirects, cookies or account client. */
internal class UpdateHttp(client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private fun request(url: String, accept: String): Request.Builder {
        require(UpdatePolicy.allowed(url)) { "更新地址不受信任" }
        return Request.Builder().url(url).header("Accept", accept).header("User-Agent", "FamilyBooks-Android/${BuildConfig.VERSION_NAME}")
    }
    fun manifest(etag: String? = null): Response {
        val builder = request(UpdatePolicy.MANIFEST_URL, "application/json").header("Cache-Control", "no-cache")
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        val response = client.newCall(builder.build()).execute()
        if (response.code in 300..399 && (response.code != 304 || etag.isNullOrBlank())) { response.close(); throw IOException("更新服务不允许跳转或无条件缓存响应") }
        return response
    }
    fun apk(release: AndroidRelease): Call {
        require(release.downloadUrl == UpdatePolicy.apkUrl(release.versionName, release.assetName))
        return client.newCall(request(release.downloadUrl, "application/vnd.android.package-archive").build())
    }
}

class ReleaseUpdater(private val context: Context) {
    private val http = UpdateHttp()
    private val prefs = context.getSharedPreferences("self-hosted-update-cache-v1", 0)
    init { context.getSharedPreferences("update-cache", 0).edit().clear().apply() }
    private fun bounded(response: Response, max: Long): ByteArray {
        val body = response.body ?: throw IOException("更新响应为空")
        require(body.contentLength() <= max) { "更新文件超过大小限制" }
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input -> val chunk = ByteArray(8192); while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size().toLong() + n <= max); out.write(chunk, 0, n) } }
        return out.toByteArray()
    }
    suspend fun check(manual: Boolean): AndroidRelease? = withContext(Dispatchers.IO) {
        val text = http.manifest(if (manual) null else prefs.getString("etag", null)).use { response ->
            if (response.code == 304) prefs.getString("manifest", null) ?: throw IOException("更新缓存失效")
            else {
                if (!response.isSuccessful) throw IOException("家庭账本更新服务暂不可用")
                val body = bounded(response, 64 * 1024).toString(Charsets.UTF_8)
                // Validate before caching; metadata never chooses a network origin or arbitrary path.
                UpdatePolicy.parse(JSONObject(body), 0, Build.VERSION.SDK_INT)
                prefs.edit().putString("manifest", body).putString("etag", response.header("ETag")).apply(); body
            }
        }
        val release = UpdatePolicy.parse(JSONObject(text), 0, Build.VERSION.SDK_INT)
        release.takeIf { it.versionCode > BuildConfig.VERSION_CODE.toLong() }
    }
    suspend fun download(release: AndroidRelease, progress: (Long, Long) -> Unit): File = suspendCancellableCoroutine { continuation ->
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(dir, "download-${java.util.UUID.randomUUID()}.part"); val output = File(dir, release.assetName)
        var call: Call? = null
        continuation.invokeOnCancellation { call?.cancel() }
        fun fetch() {
            if (!continuation.isActive) { partial.delete(); return }
            val next = try { http.apk(release) } catch (_: Exception) { continuation.resumeWithException(IOException("更新地址无效")); return }; call = next
            next.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { partial.delete(); if (continuation.isActive) continuation.resumeWithException(IOException("下载中断，请重试")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
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
        fetch()
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
