package ink.moling.bilicacheexport.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import ink.moling.bilicacheexport.shizuku.BiliScanService
import ink.moling.bilicacheexport.shizuku.IScanProgressCallback
import ink.moling.bilicacheexport.shizuku.IScanService
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通过 Shizuku 读取 /storage/emulated/0/Android/data 下 tv.danmaku.bili 的缓存。
 *
 * Android 13+ 中普通 App（即使有 MANAGE_EXTERNAL_STORAGE）无法直接读取其他 App
 * 的 Android/data；Shizuku 把本应用的 [BiliScanService] 以 shell(uid 2000)/root 身份
 * 拉起（UserService），由它扫描并以行协议返回结果。
 */
object ShizukuCacheSource {

    private const val REQUEST_CODE = 0x4C42 // "LB"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var service: IScanService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            service = IScanService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            service = null
        }
    }

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    /** 供 ViewModel 注入 application context */
    fun attach(context: Context) {
        appContext = context.applicationContext
        prepare()
    }

    /** Shizuku 服务是否在线（已通过 adb/root 启动） */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku 是否已授予本应用调用权限 */
    fun isGranted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 弹窗请求 Shizuku 授权，结果经 ViewModel 的监听器回调 */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Shizuku 就绪时尝试绑定 UserService */
    fun prepare() {
        val ctx = appContext ?: return
        if (service != null) return
        if (!isRunning() || !isGranted()) return
        runCatching {
            val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, BiliScanService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("scan")
                .version(3)
                .also { userServiceArgs = it }
            Shizuku.bindUserService(args, connection)
        }
    }

    /**
     * 用 UserService 流式扫描 B 站缓存，返回解析后的列表。
     *
     * @param onProgress 每处理一条回调一次 (done, total)。
     */
    fun scan(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<CachedVideo> {
        if (!isRunning() || !isGranted()) return emptyList()
        prepare()
        val svc = waitForService(2500) ?: return emptyList()

        val latch = CountDownLatch(1)
        val buffer = StringBuilder(64 * 1024)
        val callback = object : IScanProgressCallback.Stub() {
            override fun onProgress(done: Int, total: Int) {
                onProgress(done, total)
            }

            override fun onItem(
                hasVideo: Int, hasAudio: Int, b64Json: String,
                dir: String, videoPath: String, audioPath: String,
            ) {
                synchronized(buffer) {
                    buffer.append(hasVideo).append('\t').append(hasAudio).append('\t')
                    buffer.append(b64Json).append('\t').append(dir).append('\t')
                    buffer.append(videoPath).append('\t').append(audioPath).append('\n')
                }
            }

            override fun onDone() {
                latch.countDown()
            }
        }
        runCatching { svc.scan(callback) }
        runCatching { latch.await(120, TimeUnit.SECONDS) }
        val protocol = synchronized(buffer) { buffer.toString() }
        return parse(protocol)
    }

    /** 从 shell 身份按块下载文件到 App 本地（用于导出合并 m4s）。 */
    fun downloadFile(path: String, target: File): Boolean {
        if (!isRunning() || !isGranted()) return false
        prepare()
        val svc = waitForService(3000) ?: return false
        val len = runCatching { svc.length(path) }.getOrDefault(-1L)
        if (len < 0) return false
        return runCatching {
            val fos = target.outputStream()
            try {
                var off = 0L
                while (off < len) {
                    val want = minOf(CHUNK.toLong(), len - off).toInt()
                    val chunk = svc.read(path, off, want)
                    if (chunk.isEmpty()) break
                    fos.write(chunk)
                    off += chunk.size
                }
            } finally {
                fos.close()
            }
            target.length() == len
        }.getOrDefault(false)
    }

    private const val CHUNK = 512 * 1024

    private fun waitForService(timeoutMs: Long): IScanService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            service?.let { return it }
            Thread.sleep(50)
        }
        return service
    }

    private fun parse(stdout: String): List<CachedVideo> {
        val result = ArrayList<CachedVideo>()
        for (line in stdout.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split("\t", limit = 6)
            if (parts.size < 6) continue
            val hasVideo = parts[0] == "1"
            val hasAudio = parts[1] == "1"
            val dir = parts[3]
            val videoPath = parts[4].takeIf { it.isNotBlank() }
            val audioPath = parts[5].takeIf { it.isNotBlank() }
            val text = runCatching {
                String(Base64.getDecoder().decode(parts[2].trim()))
            }.getOrNull() ?: continue
            val video = EntryJsonParser.parse(text, dir, hasVideo, hasAudio) ?: continue
            result.add(
                video.copy(
                    videoSource = videoPath,
                    audioSource = audioPath,
                    entrySource = "$dir/entry.json",
                )
            )
        }
        return result.sortedByDescending { it.createTimeMs }
    }
}
