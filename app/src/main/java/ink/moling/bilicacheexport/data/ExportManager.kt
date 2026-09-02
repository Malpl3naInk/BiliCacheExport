package ink.moling.bilicacheexport.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** 一次已完成的导出（位于 filesDir/exports/<avid>_<cid> 目录）。 */
data class ExportedVideo(
    val dirName: String,
    val title: String,
    val ownerName: String,
    val bvid: String,
    val avid: Long,
    val cid: Long,
    val quality: String,
    val durationMs: Long,
    val cachedAt: Long,
    val exportedAt: Long,
    val fileName: String,
    val fileSize: Long,
) {
    val displayTitle: String get() = title.ifBlank { dirName }
}

sealed interface ExportOutcome {
    data class Success(val export: ExportedVideo) : ExportOutcome
    data class Failure(val message: String) : ExportOutcome
}

/**
 * 把一条缓存合并为 MP4（MediaMuxer 重封装，不转码）并连同 entry.json 元数据
 * 一起导出到本应用内部存储 filesDir/exports/。支持从本地/Shizuku/SAF 三种来源读取。
 */
object ExportManager {

    private const val MP4 = "output.mp4"
    private const val INFO = "info.json"
    private const val ENTRY = "entry.json"

    fun exportsRoot(context: Context): File = File(context.filesDir, "exports")

    /** 导出主流程（应在 IO 线程调用）。
     *  @param targetDirName 非空时把产物覆盖写入该已有导出目录（用于“重新导出”更新原记录）；
     *                       为空时自动分配一个新目录（<avid>_<cid>，重名加 _N 后缀）。 */
    fun export(
        context: Context,
        item: CachedVideo,
        onProgress: (Float) -> Unit = {},
        targetDirName: String? = null,
    ): ExportOutcome {
        var dir: File? = null
        val tmpVideo = File(context.cacheDir, "tmp_${item.avid}_${item.cid}_v.m4s")
        val tmpAudio = File(context.cacheDir, "tmp_${item.avid}_${item.cid}_a.m4s")
        try {
            if (item.videoSource == null) {
                return ExportOutcome.Failure("该缓存缺少 video.m4s 来源，无法导出")
            }
            dir = if (targetDirName != null) {
                File(exportsRoot(context), targetDirName).also { it.mkdirs() }
            } else {
                allocExportDir(exportsRoot(context), item)
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return ExportOutcome.Failure("无法创建导出目录")
            }
            tmpVideo.delete()
            tmpAudio.delete()

            if (!fetchToFile(context, item.videoSource, tmpVideo)) {
                return ExportOutcome.Failure("读取视频文件失败（请确认来源可访问）")
            }
            val hasAudio = item.audioSource != null && fetchToFile(context, item.audioSource, tmpAudio)
            if (!hasAudio) tmpAudio.delete()

            val out = File(dir, MP4)
            val durationUs = item.durationMs * 1000L
            val err = MediaMux.mergeToMp4(
                tmpVideo,
                if (hasAudio) tmpAudio else null,
                out,
                durationUs,
                onProgress,
            )
            if (err != null) {
                return ExportOutcome.Failure("合并失败：$err")
            }

            // 元数据：尽可能保存原始 entry.json，同时写入摘要 info.json
            item.entrySource?.let { src ->
                val entryText = fetchText(context, src)
                if (entryText != null) {
                    runCatching { File(dir, ENTRY).writeText(entryText) }
                }
            }
            val export = ExportedVideo(
                dirName = dir.name,
                title = item.displayTitle,
                ownerName = item.ownerName,
                bvid = item.bvid,
                avid = item.avid,
                cid = item.cid,
                quality = item.qualityLabel ?: "",
                durationMs = item.durationMs,
                cachedAt = item.createTimeMs,
                exportedAt = System.currentTimeMillis(),
                fileName = MP4,
                fileSize = out.length(),
            )
            writeInfo(dir, export)
            return ExportOutcome.Success(export)
        } catch (e: Exception) {
            return ExportOutcome.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            tmpVideo.delete()
            tmpAudio.delete()
            // 失败时清理半成品目录
            dir?.takeIf { !File(it, MP4).exists() }?.let { it.deleteRecursively() }
        }
    }

    /** 列出所有已导出视频（新导出在前）。 */
    fun listExports(context: Context): List<ExportedVideo> {
        val root = exportsRoot(context)
        if (!root.isDirectory) return emptyList()
        return (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
            readInfo(dir) ?: run {
                val mp4 = File(dir, MP4)
                if (mp4.isFile) {
                    ExportedVideo(dir.name, dir.name, "", "", 0, 0, "", 0, 0, mp4.lastModified(), MP4, mp4.length())
                } else null
            }
        }.sortedByDescending { it.exportedAt }
    }

    fun exportMp4File(context: Context, export: ExportedVideo): File =
        File(File(exportsRoot(context), export.dirName), export.fileName)

    fun deleteExport(context: Context, export: ExportedVideo): Boolean =
        runCatching {
            File(exportsRoot(context), export.dirName).deleteRecursively()
        }.getOrDefault(false)

    // ---------- 内部 ----------

    private fun allocExportDir(root: File, item: CachedVideo): File {
        val base = "${item.avid}_${item.cid}"
        var dir = File(root, base)
        var i = 2
        while (dir.exists()) {
            dir = File(root, "${base}_$i")
            i++
        }
        return dir
    }

    private fun writeInfo(dir: File, export: ExportedVideo) {
        val jo = JSONObject().apply {
            put("dirName", export.dirName)
            put("title", export.title)
            put("ownerName", export.ownerName)
            put("bvid", export.bvid)
            put("avid", export.avid)
            put("cid", export.cid)
            put("quality", export.quality)
            put("durationMs", export.durationMs)
            put("cachedAt", export.cachedAt)
            put("exportedAt", export.exportedAt)
            put("fileName", export.fileName)
            put("fileSize", export.fileSize)
        }
        runCatching { File(dir, INFO).writeText(jo.toString()) }
    }

    private fun readInfo(dir: File): ExportedVideo? {
        val f = File(dir, INFO)
        if (!f.isFile) return null
        return runCatching {
            val jo = JSONObject(f.readText())
            ExportedVideo(
                dirName = jo.getString("dirName"),
                title = jo.optString("title"),
                ownerName = jo.optString("ownerName"),
                bvid = jo.optString("bvid"),
                avid = jo.optLong("avid"),
                cid = jo.optLong("cid"),
                quality = jo.optString("quality"),
                durationMs = jo.optLong("durationMs"),
                cachedAt = jo.optLong("cachedAt"),
                exportedAt = jo.optLong("exportedAt"),
                fileName = jo.optString("fileName", MP4),
                fileSize = jo.optLong("fileSize"),
            )
        }.getOrNull()
    }

    /** 把 source（content:// 或可读路径或 Shizuku 路径）拷贝到本地目标文件。 */
    private fun fetchToFile(context: Context, source: String, target: File): Boolean {
        return runCatching {
            if (source.startsWith("content://")) {
                val input = context.contentResolver.openInputStream(Uri.parse(source)) ?: return false
                input.use { i ->
                    target.outputStream().use { o -> i.copyTo(o) }
                }
                true
            } else {
                val f = File(source)
                if (f.isFile) {
                    f.inputStream().use { i ->
                        target.outputStream().use { o -> i.copyTo(o) }
                    }
                    true
                } else if (ShizukuCacheSource.isRunning() && ShizukuCacheSource.isGranted()) {
                    ShizukuCacheSource.downloadFile(source, target)
                } else {
                    false
                }
            }
        }.getOrDefault(false)
    }

    private fun fetchText(context: Context, source: String): String? {
        return runCatching {
            if (source.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(source))?.bufferedReader()?.readText()
            } else {
                val f = File(source)
                if (f.isFile) f.readText() else null
            }
        }.getOrNull()
    }
}
