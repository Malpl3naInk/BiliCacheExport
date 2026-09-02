package ink.moling.bilicacheexport.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 将 B 站缓存的 video.m4s / audio.m4s（均为标准 MP4，含 ftyp+moov）直接重封装
 * （不转码）合并为一个 MP4。用系统 MediaExtractor + MediaMuxer 实现。
 */
object MediaMux {

    private const val KIND_VIDEO = "video"
    private const val KIND_AUDIO = "audio"

    /**
     * 合并为 MP4。
     *
     * @return null 表示成功，否则返回错误信息。
     */
    fun mergeToMp4(
        videoFile: File,
        audioFile: File?,
        outFile: File,
        durationUs: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): String? {
        val vEx = MediaExtractor()
        val aEx = if (audioFile != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(videoFile.absolutePath)
            val vti = findTrack(vEx, KIND_VIDEO)
            if (vti < 0) return "未在视频文件中找到视频轨道"

            var ati = -1
            if (aEx != null) {
                aEx.setDataSource(audioFile!!.absolutePath)
                ati = findTrack(aEx, KIND_AUDIO)
            }
            val useAudio = ati >= 0 && aEx != null

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vEx.getTrackFormat(vti))
            var aOut = -1
            if (useAudio) {
                aOut = muxer.addTrack(aEx.getTrackFormat(ati))
            }
            muxer.start()

            vEx.selectTrack(vti)
            if (useAudio) {
                aEx.selectTrack(ati)
            }

            var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            var vEos = false
            var aEos = !useAudio
            var lastTimeUs = 0L

            while (!vEos || !aEos) {
                val pickVideo = when {
                    vEos -> false
                    aEos -> true
                    else -> vEx.sampleTime <= aEx!!.sampleTime
                }
                val ex: MediaExtractor = if (pickVideo) vEx else aEx!!
                val outIdx = if (pickVideo) vOut else aOut

                val sampleSize = ex.sampleSize.toInt()
                if (sampleSize < 0) {
                    if (pickVideo) vEos = true else aEos = true
                    continue
                }
                if (buffer.capacity() < sampleSize) {
                    buffer = ByteBuffer.allocate(sampleSize)
                }
                buffer.clear()
                buffer.limit(sampleSize)
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) {
                    if (pickVideo) vEos = true else aEos = true
                    continue
                }
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                muxer.writeSampleData(outIdx, buffer, info)
                lastTimeUs = maxOf(lastTimeUs, info.presentationTimeUs)
                if (durationUs > 0) {
                    onProgress((lastTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                if (!ex.advance()) {
                    if (pickVideo) vEos = true else aEos = true
                }
            }

            onProgress(1f)
            return null
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { muxer?.stop() }
            muxer?.release()
            vEx.release()
            aEx?.release()
            if (outFile.exists() && outFile.length() == 0L) outFile.delete()
        }
    }

    private fun findTrack(extractor: MediaExtractor, kind: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(kind)) return i
        }
        return -1
    }
}
