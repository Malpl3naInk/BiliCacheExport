package ink.moling.bilicacheexport.data

import org.json.JSONObject

/**
 * 解析 B 站缓存目录中的 entry.json。
 *
 * 参考字段：
 * { "title": "...", "bvid": "...", "avid": 1306298717,
 *   "owner_name": "...", "owner_avatar": "...", "cover": "...",
 *   "quality_pithy_description": "1080P", "total_time_milli": 91434,
 *   "danmaku_count": 198, "time_create_stamp": 1724084386221,
 *   "total_bytes": ..., "downloaded_bytes": ...,
 *   "page_data": { "cid": 1633598910, "page": 1, "part": "..." } }
 */
object EntryJsonParser {

    /**
     * 解析 entry.json 文本。
     *
     * @param text            entry.json 内容
     * @param dirPath         `c_` 目录绝对路径
     * @param hasVideoFile    磁盘上是否存在 video.m4s
     * @param hasAudioFile    磁盘上是否存在 audio.m4s
     */
    fun parse(
        text: String,
        dirPath: String,
        hasVideoFile: Boolean,
        hasAudioFile: Boolean,
    ): CachedVideo? {
        return try {
            val root = JSONObject(text)
            val pageData = root.optJSONObject("page_data")

            val avid = root.optLong("avid", 0L)
            val cid = root.optLong("cid", 0L)
                .takeIf { it != 0L }
                ?: pageData?.optLong("cid", 0L) ?: 0L

            CachedVideo(
                avid = avid,
                cid = cid,
                bvid = root.optString("bvid").ifBlank { "" },
                title = root.optString("title").ifBlank { "未知标题" },
                part = pageData?.optString("part")?.takeIf { it.isNotBlank() },
                page = pageData?.optInt("page", 0) ?: 0,
                ownerName = root.optString("owner_name").ifBlank { "未知 UP 主" },
                ownerAvatarUrl = root.optString("owner_avatar").takeIf { it.isNotBlank() },
                coverUrl = root.optString("cover").takeIf { it.isNotBlank() },
                qualityLabel = root.optString("quality_pithy_description").takeIf { it.isNotBlank() },
                durationMs = root.optLong("total_time_milli", 0L),
                danmakuCount = root.optInt("danmaku_count", 0),
                createTimeMs = root.optLong("time_create_stamp", 0L),
                totalBytes = root.optLong("total_bytes", 0L),
                downloadedBytes = root.optLong("downloaded_bytes", 0L),
                hasVideoFile = hasVideoFile,
                hasAudioFile = hasAudioFile,
                dirPath = dirPath,
            )
        } catch (_: Exception) {
            null
        }
    }
}
