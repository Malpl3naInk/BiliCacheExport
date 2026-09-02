package ink.moling.bilicacheexport

import android.media.MediaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.moling.bilicacheexport.data.CachedVideo
import ink.moling.bilicacheexport.data.ExportManager
import ink.moling.bilicacheexport.data.ExportOutcome
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 联调用例：把推到 /sdcard/Download/bilitest 的示例缓存跑一遍完整导出，
 * 校验合并出的 MP4 存在且可被 MediaExtractor 识别（含轨道）。
 */
@RunWith(AndroidJUnit4::class)
class MergeInstrumentedTest {

    @Test
    fun exportProducesPlayableMp4() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File("/storage/emulated/0/Download/bilitest/1306298717/c_1633598910/80")
        val video = File(base, "video.m4s")
        val audio = File(base, "audio.m4s")
        assertTrue("示例 video.m4s 不存在", video.isFile)
        assertTrue("示例 audio.m4s 不存在", audio.isFile)

        val item = CachedVideo(
            avid = 1306298717,
            cid = 1633598910,
            bvid = "BV1iM4m1y7i9",
            title = "联调测试视频",
            part = null,
            page = 1,
            ownerName = "tester",
            ownerAvatarUrl = null,
            coverUrl = null,
            qualityLabel = "1080P",
            durationMs = 91434,
            danmakuCount = 0,
            createTimeMs = 0L,
            totalBytes = video.length() + audio.length(),
            downloadedBytes = video.length() + audio.length(),
            hasVideoFile = true,
            hasAudioFile = true,
            dirPath = base.absolutePath,
            videoSource = video.absolutePath,
            audioSource = audio.absolutePath,
            entrySource = null,
        )

        val outcome = ExportManager.export(ctx, item) { }
        assertTrue("导出失败: ${(outcome as? ExportOutcome.Failure)?.message}", outcome is ExportOutcome.Success)

        val mp4 = ExportManager.exportMp4File(ctx, (outcome as ExportOutcome.Success).export)
        assertTrue("未生成 mp4", mp4.isFile && mp4.length() > 0)

        val ex = MediaExtractor()
        ex.setDataSource(mp4.absolutePath)
        val trackCount = ex.trackCount
        ex.release()
        assertTrue("mp4 无可读轨道", trackCount > 0)
    }
}
