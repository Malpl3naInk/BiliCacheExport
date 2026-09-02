package ink.moling.bilicacheexport.data

import java.io.File

/**
 * 扫描哔哩哔哩离线缓存目录，提取所有可解析的缓存视频。
 *
 * 目标目录结构：
 * /storage/emulated/0/Android/data/tv.danmaku.bili/download/
 *   ├── 1306298717/            ← 外层数字目录（avid）
 *   │   └── c_1633598910/      ← c_<cid> 目录（一个分P）
 *   │       ├── entry.json
 *   │       ├── danmaku.xml
 *   │       └── 80/            ← 清晰度目录
 *   │           ├── video.m4s
 *   │           ├── audio.m4s
 *   │           └── index.json
 */
object BiliCacheScanner {

    /** B 站离线缓存根目录 */
    const val DOWNLOAD_ROOT = "/storage/emulated/0/Android/data/tv.danmaku.bili/download"

    /** 扫描并返回全部缓存视频，按缓存时间从新到旧排序。 */
    fun scan(rootPath: String = DOWNLOAD_ROOT): List<CachedVideo> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()

        val result = mutableListOf<CachedVideo>()

        val avidDirs = root.listFiles { f ->
            f.isDirectory && f.name.isNotEmpty() && f.name.all { it.isDigit() }
        } ?: return emptyList()

        for (avidDir in avidDirs) {
            val avid = avidDir.name.toLongOrNull() ?: 0L
            val cDirs = avidDir.listFiles { f ->
                f.isDirectory && f.name.startsWith("c_") && f.name.length > 2
            } ?: continue

            for (cDir in cDirs) {
                val dirCid = cDir.name.removePrefix("c_").toLongOrNull() ?: 0L
                val entryFile = File(cDir, "entry.json")
                if (!entryFile.isFile) continue

                val text = runCatching { entryFile.readText() }.getOrNull() ?: continue
                val (hasVideo, hasAudio) = collectMediaFlags(cDir)
                val (videoPath, audioPath) = locateM4s(cDir)
                val video = EntryJsonParser.parse(text, cDir.absolutePath, hasVideo, hasAudio)
                    ?: continue

                result.add(
                    video.copy(
                        avid = if (video.avid != 0L) video.avid else avid,
                        cid = if (video.cid != 0L) video.cid else dirCid,
                        videoSource = videoPath,
                        audioSource = audioPath,
                        entrySource = entryFile.absolutePath,
                    )
                )
            }
        }

        // 同一 video（同 avid、同目录）有多个分P时保持目录顺序，整体按创建时间倒序
        return result.sortedWith(compareByDescending<CachedVideo> { it.createTimeMs }
            .thenBy { it.dirPath })
    }

    /**
     * 在 `c_` 目录中（递归，限深 3 层，覆盖多个清晰度子目录）
     * 探测是否存在 video.m4s / audio.m4s。
     */
    private fun collectMediaFlags(cDir: File): Pair<Boolean, Boolean> {
        var hasVideo = false
        var hasAudio = false

        fun walk(dir: File, depth: Int) {
            if (hasVideo && hasAudio) return
            if (depth > 3) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else {
                    when (child.name) {
                        "video.m4s" -> hasVideo = true
                        "audio.m4s" -> hasAudio = true
                    }
                    if (hasVideo && hasAudio) return
                }
            }
        }

        walk(cDir, 0)
        return hasVideo to hasAudio
    }

    /** 定位 c_ 目录下第一个 video.m4s / audio.m4s 的绝对路径（递归，限深 3 层）。 */
    private fun locateM4s(cDir: File): Pair<String?, String?> {
        var videoPath: String? = null
        var audioPath: String? = null

        fun walk(dir: File, depth: Int) {
            if (videoPath != null && audioPath != null) return
            if (depth > 3) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else {
                    if (videoPath == null && child.name == "video.m4s") {
                        videoPath = child.absolutePath
                    }
                    if (audioPath == null && child.name == "audio.m4s") {
                        audioPath = child.absolutePath
                    }
                    if (videoPath != null && audioPath != null) return
                }
            }
        }

        walk(cDir, 0)
        return videoPath to audioPath
    }
}
