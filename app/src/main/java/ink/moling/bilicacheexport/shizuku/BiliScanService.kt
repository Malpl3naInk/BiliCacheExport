package ink.moling.bilicacheexport.shizuku

import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Shizuku UserService：由 Shizuku 在本应用进程中以 shell/root 身份启动。
 * 进程 uid 为 shell(2000)/root(0)，因此可直接读 /Android/data 下其他 App 的缓存。
 *
 * 扫描为流式：先统计 c_ 目录总数，再逐条回调进度，完成后回调 onDone(行协议)。
 * 另提供分块读取接口供 App 拉取 m4s 以完成合并导出。
 */
class BiliScanService : IScanService.Stub() {

    private companion object {
        const val ROOT = "/storage/emulated/0/Android/data/tv.danmaku.bili/download"
        const val MAX_DEPTH = 4
    }

    /** 流式扫描（在后台线程执行，避免占用 binder 线程）。 */
    override fun scan(callback: IScanProgressCallback?) {
        thread(name = "bili-cache-scan", start = true) {
            runCatching { doScan(callback) }
        }
    }

    private fun doScan(callback: IScanProgressCallback?) {
        val dirs = ArrayList<File>()
        val root = File(ROOT)
        if (root.isDirectory) {
            root.listFiles { f -> f.isDirectory && f.name.isNotEmpty() && f.name.all(Char::isDigit) }
                ?.forEach { avidDir ->
                    avidDir.listFiles { f -> f.isDirectory && f.name.startsWith("c_") }
                        ?.forEach { cDir ->
                            if (File(cDir, "entry.json").isFile) dirs.add(cDir)
                        }
                }
        }

        val total = dirs.size
        var done = 0
        for (d in dirs) {
            val text = runCatching { File(d, "entry.json").readText() }.getOrNull()
            if (text != null) {
                val vp = findFile(d, "video.m4s")
                val ap = findFile(d, "audio.m4s")
                // 逐条推送（每条仅几 KB），避免最后一次性回传巨型协议串触发 binder 事务过大而丢失。
                runCatching {
                    callback?.onItem(
                        if (vp != null) 1 else 0,
                        if (ap != null) 1 else 0,
                        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                        d.absolutePath,
                        vp ?: "",
                        ap ?: "",
                    )
                }
            }
            done++
            runCatching { callback?.onProgress(done, total) }
        }
        // 空负载结束信号，事务极小，必定送达。
        runCatching { callback?.onDone() }
    }

    private fun findFile(dir: File, name: String): String? {
        var found: String? = null
        fun walk(f: File, depth: Int) {
            if (found != null || depth > MAX_DEPTH) return
            val children = f.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) {
                    walk(c, depth + 1)
                } else if (c.name == name) {
                    found = c.absolutePath
                    return
                }
            }
        }
        walk(dir, 0)
        return found
    }

    override fun length(path: String): Long {
        return runCatching { File(path).length() }.getOrDefault(-1L)
    }

    override fun read(path: String, offset: Long, size: Int): ByteArray {
        if (size <= 0 || offset < 0) return ByteArray(0)
        return runCatching {
            RandomAccessFile(path, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(size)
                val n = raf.read(buf)
                if (n <= 0) ByteArray(0) else if (n == size) buf else buf.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
    }
}
