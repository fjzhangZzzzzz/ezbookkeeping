package top.fjzhang233.books

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class UpdateModel(app: Application) : AndroidViewModel(app) {
    private val updater = ReleaseUpdater(app)
    private val prefs = app.getSharedPreferences("pending-update", 0)
    var message by mutableStateOf(""); private set
    var release by mutableStateOf<AndroidRelease?>(null); private set
    var downloading by mutableStateOf(false); private set
    var checking by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var ready by mutableStateOf(false); private set
    private var job: Job? = null
    private var apk: File? = null
    private var waitingPermission = prefs.getBoolean("waiting", false)
    private var lastCheck = 0L
    private var initializing = true
    init { viewModelScope.launch {
        runCatching {
            val text = prefs.getString("release", null) ?: return@runCatching
            val meta = JSONObject(text); val version = meta.getString("versionName"); val tag = "android-v$version"
            val parsed = UpdatePolicy.parse(meta, tag, "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/${meta.getString("apkAssetName")}", BuildConfig.VERSION_CODE.toLong(), android.os.Build.VERSION.SDK_INT)
            val file = File(File(app.cacheDir, "updates"), parsed.assetName)
            if(file.isFile) withContext(Dispatchers.IO) { updater.verifyDownloaded(file, parsed) }
            if(release == null || release!!.versionCode <= parsed.versionCode) { release = parsed; apk = file.takeIf(File::isFile); ready = file.isFile }
        }.onFailure { prefs.edit().clear().apply(); waitingPermission = false }
        initializing=false; resume()
    } }
    fun check(manual: Boolean) {
        if (initializing || checking || downloading || (!manual && System.currentTimeMillis()-lastCheck < 5*60_000)) return
        checking = true; if(manual) message = ""; lastCheck = System.currentTimeMillis()
        viewModelScope.launch {
            try { updater.check(manual)?.let { found ->
                if(release?.versionCode != found.versionCode) { ready=false; apk=null; waitingPermission=false; prefs.edit().clear().apply() }
                release = found; persist(found)
            }; if (manual) message = if(release == null) "当前已是最新兼容版本" else "发现 ${release!!.versionName}，确认后开始下载" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if(manual) message = "无法检查更新，请检查网络后重试" }
            finally { checking = false }
        }
    }
    fun download() {
        val update = release ?: return
        if(downloading) return
        downloading=true; progress=0f
        job=viewModelScope.launch {
            try {
                apk=updater.download(update) { count,total -> if(total>0) progress=count.toFloat()/total }
                ready=true; message="安装包校验通过，请继续系统安装"
                persist(update)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="下载或校验失败，请重试；不会安装未知或错误签名的文件" }
            finally { downloading=false }
        }
    }
    fun cancel() { job?.cancel(); downloading=false; message="已取消下载" }
    private fun persist(update:AndroidRelease) {
        val meta=JSONObject().put("schemaVersion",1).put("channel","stable").put("packageId",UpdatePolicy.PACKAGE).put("versionName",update.versionName).put("versionCode",update.versionCode).put("minSdk",update.minSdk).put("apkAssetName",update.assetName).put("apkSha256",update.sha256).put("signingCertificateSha256",UpdatePolicy.CERTIFICATE).put("sourceCommit",update.sourceCommit)
        prefs.edit().putString("release",meta.toString()).apply()
    }
    fun install() {
        val file=apk?:return; val update=release?:return
        viewModelScope.launch {
            try {
                waitingPermission=!updater.install(file,update)
                prefs.edit().putBoolean("waiting",waitingPermission).apply()
            } catch(_:Exception) { message="安装校验或系统安装未完成，请重试" }
        }
    }
    fun resume() {
        if(waitingPermission && ready) {
            waitingPermission=false; prefs.edit().putBoolean("waiting",false).apply()
            if(getApplication<Application>().packageManager.canRequestPackageInstalls()) install() else message="未授予此来源安装权限，可稍后重试"
        }
        check(false)
    }
}
