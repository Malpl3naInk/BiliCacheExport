package ink.moling.bilicacheexport.data

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/** 列表页 UI 状态。 */
data class CacheUiState(
    val loading: Boolean = true,
    /** 是否已授予「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE） */
    val hasPermission: Boolean = false,
    /** B 站缓存根目录是否存在 */
    val rootExists: Boolean = false,
    val items: List<CachedVideo> = emptyList(),
    /** 已授权但本地直读仍不可用（Android 13+ 常见）→ 需 Shizuku 或 SAF */
    val needsElevatedAccess: Boolean = false,
    /** Shizuku 服务是否在线 */
    val shizukuRunning: Boolean = false,
    /** Shizuku 是否已授予本应用权限 */
    val shizukuGranted: Boolean = false,
    /** 是否已通过 SAF 授权目录 */
    val safGranted: Boolean = false,
    /** 本次结果来源说明 */
    val sourceLabel: String? = null,
    /** 面向用户的提示文案 */
    val notice: String? = null,
    /** 扫描进度：已处理 done / 总数 total（total<=0 表示未知） */
    val scanDone: Int = 0,
    val scanTotal: Int = 0,

    // ---- 导出 ----
    /** 是否正在导出 */
    val exporting: Boolean = false,
    /** 导出进度 0..1 */
    val exportProgress: Float = 0f,
    /** 正在导出的标题 */
    val exportingTitle: String? = null,
    /** 是否查看“已导出”列表 */
    val showExports: Boolean = false,
    /** 已导出的视频 */
    val exports: List<ExportedVideo> = emptyList(),
    /** 导出/另存等操作的反馈文案 */
    val exportNotice: String? = null,
    /** 需要立即交给 UI 播放的已导出视频。
     *  “播放”未导出条目时 VM 会先导出，成功后再把产物填入此项由 UI 播放。 */
    val pendingPlay: ExportedVideo? = null,
)

class CacheViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CacheUiState())
    val state: StateFlow<CacheUiState> = _state.asStateFlow()

    init {
        ShizukuCacheSource.attach(getApplication())
        registerShizukuListeners()
        doLocalScan()
        refreshExports()
    }

    // ---------- 扫描入口 ----------

    /** 顶部刷新按钮 / 初次加载 */
    fun refresh() {
        if (!_state.value.loading) {
            doLocalScan()
        }
    }

    /** Activity onResume：从设置/授权页返回后刷新 */
    fun onAppResumed() {
        val s = _state.value
        if (s.loading) return
        val hasPermission = checkAllFilesAccess()
        if (hasPermission != s.hasPermission) {
            doLocalScan()
        } else if (hasPermission && s.needsElevatedAccess && ShizukuCacheSource.isGranted()) {
            scanWithShizuku()
        } else {
            updateShizukuFlags()
        }
    }

    /** 请求 Shizuku 权限（服务未运行时需先启动 Shizuku）。
     *  注意：不注册 OnRequestPermissionResultListener（Shizuku 13.1.5 在该结果回投的
     *  瞬间偶发 NPE 闪退），改为从授权页返回/服务重启重新绑定时轮询 checkSelfPermission。 */
    fun requestShizukuPermission() {
        updateShizukuFlags()
        if (!ShizukuCacheSource.isRunning()) return
        if (ShizukuCacheSource.isGranted()) {
            scanWithShizuku()
            return
        }
        ShizukuCacheSource.requestPermission()
    }

    /** 以 Shizuku(shell) 扫描 */
    fun useShizuku() {
        scanWithShizuku()
    }

    /** SAF 选择器返回的目录授权结果 */
    fun onSafTreeChosen(uri: Uri) {
        SafCacheSource.saveTree(getApplication(), uri)
        scanWithSaf()
    }

    fun scanWithSaf() {
        _state.value = _state.value.copy(loading = true, notice = null, scanDone = 0, scanTotal = 0)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                SafCacheSource.scan(getApplication())
            }
            _state.value = _state.value.copy(
                loading = false,
                items = items,
                safGranted = true,
                sourceLabel = "SAF 目录",
                needsElevatedAccess = false,
                notice = if (items.isEmpty()) "所选目录下未发现 B 站缓存（需要包含 数字目录/c_xxx/entry.json 的结构）" else null,
            )
        }
    }

    // ---------- 导出 / 已导出 ----------

    fun setShowExports(show: Boolean) {
        _state.update { it.copy(showExports = show) }
        if (show) refreshExports()
    }

    fun refreshExports() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                ExportManager.listExports(getApplication())
            }
            _state.update { it.copy(exports = list) }
        }
    }

    /** 点击某个缓存后调用：合并导出到内部存储。
     *  @param playAfter 导出成功后是否自动请求播放（用于“播放未导出条目：先导后播”）。 */
    fun startExport(item: CachedVideo, playAfter: Boolean = false) {
        if (_state.value.exporting) return
        _state.update {
            it.copy(exporting = true, exportProgress = 0f, exportingTitle = item.displayTitle)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ExportManager.export(
                    getApplication(),
                    item,
                    onProgress = { p: Float -> _state.update { s -> s.copy(exportProgress = p) } },
                )
            }
            when (outcome) {
                is ExportOutcome.Success -> {
                    val e = outcome.export
                    _state.update { s ->
                        s.copy(
                            exporting = false,
                            exportingTitle = null,
                            exports = listOf(e) + s.exports.filterNot { it.dirName == e.dirName },
                            pendingPlay = if (playAfter) e else s.pendingPlay,
                            exportNotice = if (playAfter) null else "已导出：${e.displayTitle}",
                        )
                    }
                }
                is ExportOutcome.Failure -> {
                    _state.update {
                        it.copy(exporting = false, exportingTitle = null, exportNotice = "导出失败：${outcome.message}")
                    }
                }
            }
        }
    }

    /** 重新导出：覆盖更新该缓存已有的一次导出（复用其导出目录）；无已有记录时退化为普通导出。
     *  注意：缓存条目必须仍然可访问（cacheSource 有效），否则无法重新导出。 */
    fun reExport(item: CachedVideo) {
        if (_state.value.exporting) return
        val existing = findExport(item)
        if (existing == null) {
            startExport(item)
            return
        }
        _state.update {
            it.copy(exporting = true, exportProgress = 0f, exportingTitle = item.displayTitle)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ExportManager.export(
                    getApplication(),
                    item,
                    onProgress = { p -> _state.update { s -> s.copy(exportProgress = p) } },
                    targetDirName = existing.dirName,
                )
            }
            when (outcome) {
                is ExportOutcome.Success -> {
                    val e = outcome.export
                    _state.update { s ->
                        s.copy(
                            exporting = false,
                            exportingTitle = null,
                            exports = listOf(e) + s.exports.filterNot { it.dirName == e.dirName },
                            exportNotice = "已重新导出：${e.displayTitle}",
                        )
                    }
                }
                is ExportOutcome.Failure -> {
                    _state.update {
                        it.copy(exporting = false, exportingTitle = null, exportNotice = "重新导出失败：${outcome.message}")
                    }
                }
            }
        }
    }

    /** 播放：已导出则直接请求播放；未导出则先导出，成功后再自动播放。 */
    fun playOrExport(item: CachedVideo) {
        if (_state.value.exporting) return
        findExport(item)?.let { e ->
            _state.update { it.copy(pendingPlay = e) }
            return
        }
        startExport(item, playAfter = true)
    }

    /** 播放一条已导出的视频。 */
    fun playExport(e: ExportedVideo) {
        _state.update { it.copy(pendingPlay = e) }
    }

    /** UI 播放完成（或失败已提示）后清除待播放项。 */
    fun consumePendingPlay() {
        _state.update { it.copy(pendingPlay = null) }
    }

    /** 该缓存最近一次已导出的记录（同 avid_cid 可能有多次，取最新）。 */
    private fun findExport(item: CachedVideo): ExportedVideo? =
        _state.value.exports.firstOrNull { it.avid == item.avid && it.cid == item.cid }

    /** 删除一条已导出记录。 */
    fun deleteExport(e: ExportedVideo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ExportManager.deleteExport(getApplication(), e)
            }
            refreshExports()
        }
    }

    /** 把已导出的 mp4 另存为用户在系统选择器里选定的位置。 */
    fun saveExportTo(uri: Uri, e: ExportedVideo) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val f = ExportManager.exportMp4File(getApplication(), e)
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            _state.update {
                it.copy(exportNotice = if (ok) "已另存到所选位置" else "另存失败")
            }
        }
    }

    fun dismissExportNotice() {
        _state.update { it.copy(exportNotice = null) }
    }

    /** 展示一条一次性反馈（用于播放/操作失败等）。 */
    fun showNotice(text: String) {
        _state.update { it.copy(exportNotice = text) }
    }

    // ---------- 内部实现 ----------

    private fun doLocalScan() {
        val hasPermission = checkAllFilesAccess()
        _state.value = _state.value.copy(
            loading = true,
            hasPermission = hasPermission,
            scanDone = 0,
            scanTotal = 0,
        )
        viewModelScope.launch {
            val root = File(BiliCacheScanner.DOWNLOAD_ROOT)
            val readable = hasPermission && root.isDirectory
            val items = withContext(Dispatchers.IO) {
                if (readable) BiliCacheScanner.scan() else emptyList()
            }
            _state.value = _state.value.copy(
                loading = false,
                rootExists = root.isDirectory,
                items = items,
                sourceLabel = if (items.isNotEmpty()) "本地目录" else null,
                needsElevatedAccess = hasPermission && items.isEmpty(),
                notice = when {
                    items.isNotEmpty() -> null
                    !hasPermission -> null
                    else -> "已授予「所有文件访问」，但 Android 13+ 通常不允许直接读取其他 App 的 Android/data。\n可尝试：① Shizuku ② 手动选择缓存目录(SAF)。"
                },
            )
            updateShizukuFlags()
        }
    }

    private fun scanWithShizuku() {
        _state.value = _state.value.copy(
            loading = true,
            notice = null,
            scanDone = 0,
            scanTotal = 0,
        )
        viewModelScope.launch {
            val running = ShizukuCacheSource.isRunning()
            val granted = ShizukuCacheSource.isGranted()
            val items = withContext(Dispatchers.IO) {
                if (running && granted) {
                    ShizukuCacheSource.scan { done, total ->
                        _state.update { it.copy(scanDone = done, scanTotal = total) }
                    }
                } else {
                    emptyList()
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                items = items,
                shizukuRunning = running,
                shizukuGranted = granted,
                sourceLabel = "Shizuku",
                needsElevatedAccess = false,
                scanDone = 0,
                scanTotal = 0,
                notice = when {
                    !running -> "Shizuku 服务未运行，请先启动 Shizuku（无线调试或 adb 配对）。"
                    !granted -> "请在 Shizuku 弹窗中授予权限。"
                    items.isEmpty() -> "Shizuku 扫描完成，未发现缓存。"
                    else -> null
                },
            )
        }
    }

    private fun updateShizukuFlags() {
        _state.value = _state.value.copy(
            shizukuRunning = ShizukuCacheSource.isRunning(),
            shizukuGranted = ShizukuCacheSource.isGranted(),
        )
    }

    private fun checkAllFilesAccess(): Boolean = runCatching {
        Environment.isExternalStorageManager()
    }.getOrDefault(false)

    // ---------- Shizuku 监听 ----------

    private fun onBinderReady() {
        ShizukuCacheSource.prepare()
        updateShizukuFlags()
        // Shizuku 授权完成后服务常会重启并重新推送 binder：binder 就绪且已授权时自动扫描
        val s = _state.value
        if (!s.loading && s.hasPermission && s.needsElevatedAccess &&
            ShizukuCacheSource.isGranted() && s.items.isEmpty()
        ) {
            scanWithShizuku()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { onBinderReady() }

    private fun registerShizukuListeners() {
        runCatching {
            // 仅注册 binder 监听；不要注册 OnRequestPermissionResultListener（见 requestShizukuPermission 注释）
            Shizuku.addBinderReceivedListenerSticky(binderListener)
        }
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderListener)
        }
        super.onCleared()
    }
}
