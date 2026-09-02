package ink.moling.bilicacheexport.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条已缓存的视频（对应外层数字目录下的一个 `c_<cid>` 目录，即一个分P）。
 *
 * 外层数字目录名通常是 avid，目录内每个 `c_xxx` 目录存放一份 entry.json
 * 及若干清晰度目录（如 `80/`，内含 video.m4s / audio.m4s / index.json）。
 */
data class CachedVideo(
    /** 外层数字目录名解析出的 avid；解析失败时为 0 */
    val avid: Long,
    /** `c_` 目录名解析出的 cid；与 entry.json 中不一致时以 entry.json 为准 */
    val cid: Long,
    val bvid: String,
    /** 视频主标题（整个视频共用） */
    val title: String,
    /** 分P标题（page_data.part），单P时通常与 title 相同 */
    val part: String?,
    /** 分P序号（page_data.page），0 表示未知 */
    val page: Int,
    val ownerName: String,
    val ownerAvatarUrl: String?,
    val coverUrl: String?,
    /** 清晰度描述，如 "1080P" */
    val qualityLabel: String?,
    /** 视频总时长（毫秒） */
    val durationMs: Long,
    val danmakuCount: Int,
    /** 缓存创建时间（Unix 毫秒） */
    val createTimeMs: Long,
    /** 总大小 / 已下载大小（字节） */
    val totalBytes: Long,
    val downloadedBytes: Long,
    /** 磁盘上是否找到了 video.m4s / audio.m4s */
    val hasVideoFile: Boolean,
    val hasAudioFile: Boolean,
    /** `c_` 目录绝对路径 */
    val dirPath: String,
    /**
     * 视频/音频/entry.json 的读取来源：
     *  - SAF 通道：content:// 文档 URI
     *  - 本地/Shizuku 通道：绝对文件路径
     */
    val videoSource: String? = null,
    val audioSource: String? = null,
    val entrySource: String? = null,
) {
    /** 列表主标题：优先视频主标题 title（分P名另作次要信息展示） */
    val displayTitle: String
        get() = title.ifBlank { part?.takeIf { it.isNotBlank() } ?: dirPath }

    val isCompleted: Boolean
        get() = totalBytes > 0 && downloadedBytes >= totalBytes

    /** 下载进度 0f..1f，未知时返回 null */
    val progress: Float?
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            null
        }
}

/** 毫秒 -> h:mm:ss / m:ss */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
    else "%d:%02d".format(Locale.US, m, s)
}

/** 字节数 -> 人类可读 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (idx == 0) {
        "$bytes B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[idx])
    }
}

/** Unix 毫秒 -> "yyyy-MM-dd HH:mm" */
fun formatCacheDate(ms: Long): String {
    if (ms <= 0) return "--"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
