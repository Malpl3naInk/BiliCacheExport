package ink.moling.bilicacheexport.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.moling.bilicacheexport.data.BiliCacheScanner
import ink.moling.bilicacheexport.data.CacheViewModel
import ink.moling.bilicacheexport.data.CachedVideo
import ink.moling.bilicacheexport.data.formatBytes
import ink.moling.bilicacheexport.data.formatCacheDate
import ink.moling.bilicacheexport.data.formatDuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import ink.moling.bilicacheexport.data.CacheUiState
import ink.moling.bilicacheexport.data.ExportManager
import ink.moling.bilicacheexport.data.ExportedVideo
import java.text.Collator
import java.util.Locale

/** 作者排序：ASCII 符号/数字 → 字母(A-Z，忽略大小写) → 中文(拼音) → 其它 */
private val AUTHOR_COLLATOR: Collator = Collator.getInstance(Locale.CHINA)

private val AUTHOR_COMPARATOR: Comparator<String> = Comparator { a, b ->
    val ga = authorSortGroup(a)
    val gb = authorSortGroup(b)
    when {
        ga != gb -> ga - gb
        ga == 1 -> {
            val c = a.compareTo(b, ignoreCase = true)
            if (c != 0) c else a.compareTo(b)
        }
        ga == 2 -> AUTHOR_COLLATOR.compare(a, b)
        else -> a.compareTo(b)
    }
}

private fun authorSortGroup(name: String): Int {
    val cp = name.codePointAt(0)
    return when {
        cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code -> 1
        cp < 0x80 -> 0
        cp in 0x4E00..0x9FFF -> 2
        else -> 3
    }
}

/** 列表页总入口：缓存列表 / 导出确认 / 进度 / 已导出管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliCacheScreen(viewModel: CacheViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingSave by remember { mutableStateOf<ExportedVideo?>(null) }
    var authorFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // SAF：用户用系统文件选择器手动授予一个目录
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.onSafTreeChosen(uri)
    }

    // “另存为”：用户选位置后复制已导出的 mp4
    val saveDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        val export = pendingSave
        pendingSave = null
        if (uri != null && export != null) viewModel.saveExportTo(uri, export)
    }

    LaunchedEffect(state.exportNotice) {
        val text = state.exportNotice
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.dismissExportNotice()
        }
    }

    // “播放”请求：已导出直接播；未导出先导出，导出成功后 VM 会填入 pendingPlay 再播
    LaunchedEffect(state.pendingPlay) {
        val e = state.pendingPlay ?: return@LaunchedEffect
        if (!tryPlayExported(context, e)) {
            viewModel.showNotice("未找到可播放该视频的应用")
        }
        viewModel.consumePendingPlay()
    }

    // avid_cid -> 最近一次导出（全部页用于标记“已导出”并给出另存/删除/重导目标）
    val latestExportByKey = remember(state.exports) {
        val m = HashMap<String, ExportedVideo>()
        for (e in state.exports) m.putIfAbsent("${e.avid}_${e.cid}", e)
        m
    }
    // avid_cid -> 缓存条目（已导出页需要缓存仍在，才能“重新导出”）
    val cacheByKey = remember(state.items) {
        state.items.associateBy { "${it.avid}_${it.cid}" }
    }

    val handleAction: (CardAction) -> Unit = { action ->
        when (action) {
            is CardAction.Export -> viewModel.startExport(action.item)
            is CardAction.ReExport -> {
                val item = action.item
                if (item == null) viewModel.showNotice("对应缓存已不在设备上，无法重新导出")
                else viewModel.reExport(item)
            }
            is CardAction.Play -> {
                val e = action.export
                if (e != null) viewModel.playExport(e)
                else action.item?.let(viewModel::playOrExport)
            }
            is CardAction.SaveAs -> {
                pendingSave = action.export
                saveDocLauncher.launch("${sanitizeFileName(action.export.displayTitle)}.mp4")
            }
            is CardAction.Delete -> viewModel.deleteExport(action.export)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "BiliCache",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!state.loading) {
                            val sub = if (state.showExports) {
                                "共 ${state.exports.size} 个导出"
                            } else if (state.items.isNotEmpty()) {
                                val src = state.sourceLabel?.let { " · $it" } ?: ""
                                "共 ${state.items.size} 条缓存$src"
                            } else {
                                "未发现缓存"
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (state.showExports) viewModel.refreshExports()
                            else viewModel.refresh()
                        },
                        enabled = !state.loading,
                    ) {
                        Text("刷新")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> LoadingView(done = state.scanDone, total = state.scanTotal)
                !state.hasPermission -> PermissionView(onGrant = {
                    openAllFilesAccessSettings(context)
                })
                else -> {
                    val authors = (if (state.showExports) {
                        state.exports.map { it.ownerName }
                    } else {
                        state.items.map { it.ownerName }
                    }).filter { it.isNotBlank() }.distinct().sortedWith(AUTHOR_COMPARATOR)
                    Column(Modifier.fillMaxSize()) {
                        TabChipsBar(
                            showExports = state.showExports,
                            authorFilter = authorFilter,
                            authors = authors,
                            onShowExports = { show ->
                                viewModel.setShowExports(show)
                                authorFilter = null
                            },
                            onSelectAuthor = { authorFilter = it },
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            when {
                                state.showExports -> {
                                    val list = state.exports
                                        .filter { authorFilter == null || it.ownerName == authorFilter }
                                    when {
                                        list.isEmpty() && authorFilter != null -> AuthorEmptyView(
                                            author = authorFilter!!,
                                            onClear = { authorFilter = null },
                                        )
                                        list.isEmpty() -> EmptyExportsView()
                                        else -> CardList(
                                            models = list.map {
                                                exportToCardModel(it, cacheByKey["${it.avid}_${it.cid}"])
                                            },
                                            onAction = handleAction,
                                        )
                                    }
                                }
                                state.items.isNotEmpty() -> {
                                    val list = state.items
                                        .filter { authorFilter == null || it.ownerName == authorFilter }
                                    if (list.isEmpty()) {
                                        AuthorEmptyView(
                                            author = authorFilter ?: "",
                                            onClear = { authorFilter = null },
                                        )
                                    } else {
                                        CardList(
                                            models = list.map {
                                                cacheToCardModel(it, latestExportByKey["${it.avid}_${it.cid}"])
                                            },
                                            onAction = handleAction,
                                        )
                                    }
                                }
                                state.notice != null -> GuidanceView(
                                    state = state,
                                    onUseShizuku = viewModel::useShizuku,
                                    onRequestShizuku = viewModel::requestShizukuPermission,
                                    onOpenShizuku = { openShizuku(context) },
                                    onPickSaf = { safLauncher.launch(null) },
                                    onRetry = viewModel::refresh,
                                )
                                state.rootExists -> EmptyView()
                                else -> GuidanceView(
                                    state = state,
                                    onUseShizuku = viewModel::useShizuku,
                                    onRequestShizuku = viewModel::requestShizukuPermission,
                                    onOpenShizuku = { openShizuku(context) },
                                    onPickSaf = { safLauncher.launch(null) },
                                    onRetry = viewModel::refresh,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 导出进度
    if (state.exporting) {
        ExportProgressDialog(
            title = state.exportingTitle ?: "合并导出中",
            progress = state.exportProgress,
        )
    }
}

/** 顶部横向 Chip：切换 全部 / 已导出，并按作者下拉筛选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabChipsBar(
    showExports: Boolean,
    authorFilter: String?,
    authors: List<String>,
    onShowExports: (Boolean) -> Unit,
    onSelectAuthor: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = !showExports,
            onClick = { onShowExports(false) },
            label = { Text("全部") },
        )
        FilterChip(
            selected = showExports,
            onClick = { onShowExports(true) },
            label = { Text("已导出") },
        )
        if (authors.isNotEmpty()) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = authorFilter != null,
                    onClick = { menuExpanded = true },
                    label = {
                        Text(
                            text = authorFilter?.let { "作者：$it" } ?: "按作者",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("全部作者") },
                        onClick = {
                            onSelectAuthor(null)
                            menuExpanded = false
                        },
                    )
                    authors.forEach { author ->
                        DropdownMenuItem(
                            text = {
                                Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            onClick = {
                                onSelectAuthor(author)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 作者筛选后没有内容时的空态 */
@Composable
private fun AuthorEmptyView(author: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "暂无「$author」的内容",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "该作者名下没有匹配的缓存/导出。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClear) {
            Text("清除作者筛选")
        }
    }
}

/** “已导出”为空时的提示 */
@Composable
private fun EmptyExportsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有导出",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在「全部」列表点任意缓存卡片，选择“导出为 MP4”即可合并导出。导出后可在此播放、另存为或删除。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 导出进度弹窗 */
@Composable
private fun ExportProgressDialog(title: String, progress: Float, modifier: Modifier = Modifier) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(modifier = modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "正在合并音视频…（${(progress * 100).toInt()}%）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

/** 通过 FileProvider 交给系统播放器播放已导出 mp4；无可播放应用时返回 false。 */
private fun tryPlayExported(context: Context, export: ExportedVideo): Boolean {
    return runCatching {
        val file = ExportManager.exportMp4File(context, export)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/** 去掉文件名里的非法字符 */
private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "video" }

/** 加载中 */
@Composable
private fun LoadingView(done: Int = 0, total: Int = 0, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (total > 0) "正在扫描：$done / $total" else "正在扫描缓存目录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total > 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
            )
        }
    }
}

/** 缺少「所有文件访问」权限 */
@Composable
private fun PermissionView(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "需要「所有文件访问」权限",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "为了读取哔哩哔哩（tv.danmaku.bili）的离线缓存目录，Android 11 及以上系统需要授予本应用「所有文件访问」权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = BiliCacheScanner.DOWNLOAD_ROOT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onGrant) {
            Text("前往授予权限")
        }
    }
}

/** 已授权但本地直读不可用 / 扫描为空时的“读取通道”引导视图 */
@Composable
private fun GuidanceView(
    state: CacheUiState,
    onUseShizuku: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onPickSaf: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.sourceLabel?.let { "「$it」扫描结果为空" } ?: "无法直接读取缓存目录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.notice ?: "Android 13+ 不允许普通 App（即使授予「所有文件访问」）直接读取其他 App 的 Android/data 目录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        // ---- Shizuku ----
        Text(
            text = "通道一：Shizuku（推荐，以 adb/shell 权限读取）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        val shizukuStatus = when {
            !state.shizukuRunning -> "Shizuku 服务未运行"
            state.shizukuGranted -> "Shizuku 已授权"
            else -> "Shizuku 运行中，尚未授权"
        }
        Text(
            text = shizukuStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when {
            !state.shizukuRunning -> Button(onClick = onOpenShizuku) {
                Text("启动 Shizuku")
            }
            !state.shizukuGranted -> Button(onClick = onRequestShizuku) {
                Text("授予 Shizuku 权限")
            }
            else -> Button(onClick = onUseShizuku) {
                Text("用 Shizuku 扫描缓存")
            }
        }
        Text(
            text = "未安装/未运行时：从 https://shizuku.rikka.app 安装，\n然后用「无线调试」或 adb 配对一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // ---- SAF ----
        Text(
            text = "通道二：手动选择目录（SAF，无需额外 App）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onPickSaf) {
            Text("选择缓存目录…")
        }
        Text(
            text = "在系统选择器里定位到哔哩哔哩缓存的 download 目录并「使用此文件夹」。若选择器无法进入 Android/data（系统限制），可先在其它文件管理器里把缓存镜像/导出到公共目录，再选中该目录。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) {
            Text("重试本地读取")
        }
    }
}

/** 打开 Shizuku（未安装则打开官网） */
private fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        runCatching { context.startActivity(launch) }
    } else {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
            )
        }
    }
}

/** 空态 */
@Composable
private fun EmptyView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "缓存列表为空",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "未找到可解析的缓存视频，或缓存文件不完整。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 视频缓存列表 */
/** 卡片操作类型：菜单项点选后统一交给列表页分发 */
private sealed interface CardAction {
    /** 导出缓存（未导出项） */
    data class Export(val item: CachedVideo) : CardAction

    /** 重新导出（已导出项；item 为空表示对应缓存已不在设备上） */
    data class ReExport(val item: CachedVideo?) : CardAction

    /** 播放（export 非空直接播；为空表示该缓存未导出，需先导出再播放） */
    data class Play(val item: CachedVideo?, val export: ExportedVideo?) : CardAction

    /** 另存为已导出 mp4 */
    data class SaveAs(val export: ExportedVideo) : CardAction

    /** 删除导出记录 */
    data class Delete(val export: ExportedVideo) : CardAction
}

/** 统一卡片展示模型（缓存条目与已导出条目映射为同一结构） */
private data class CardUiModel(
    val key: String,
    val title: String,
    val meta: String,
    val partLine: String?,
    val infoLeft: String,
    val infoRight: String,
    val progress: Float?,
    val progressText: String?,
    val exported: Boolean,
    val cache: CachedVideo?,
    val export: ExportedVideo?,
) {
    val hint: String
        get() = if (exported) "已导出 · 点击卡片管理 →" else "点击卡片选择操作 →"
}

/** 缓存条目 -> 统一模型（export 为该条目最近一次导出，非空即视为“已导出”） */
private fun cacheToCardModel(item: CachedVideo, export: ExportedVideo?): CardUiModel {
    val meta = buildList {
        if (item.ownerName.isNotBlank()) add(item.ownerName)
        if (item.bvid.isNotBlank()) add(item.bvid)
        if (item.page > 0) add("P${item.page}")
    }.joinToString("  ·  ")
    val infoLeft = buildList {
        if (!item.qualityLabel.isNullOrBlank()) add(item.qualityLabel)
        if (item.durationMs > 0) add(formatDuration(item.durationMs))
        if (item.danmakuCount > 0) add("${item.danmakuCount} 弹幕")
    }.joinToString(" · ")
    val progress = item.progress
    return CardUiModel(
        key = item.dirPath,
        title = item.displayTitle,
        meta = meta,
        partLine = item.part?.takeIf { it.isNotBlank() && it != item.title }?.let { "分P：$it" },
        infoLeft = infoLeft,
        infoRight = formatCacheDate(item.createTimeMs),
        progress = progress,
        progressText = if (progress != null) {
            buildString {
                append("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}")
                if (item.isCompleted) append(" · 已完成")
            }
        } else null,
        exported = export != null,
        cache = item,
        export = export,
    )
}

/** 已导出条目 -> 统一模型（cache 为仍在设备上的对应缓存，可为 null） */
private fun exportToCardModel(e: ExportedVideo, cache: CachedVideo?): CardUiModel {
    val meta = buildList {
        if (e.ownerName.isNotBlank()) add(e.ownerName)
        if (e.bvid.isNotBlank()) add(e.bvid)
        if (e.quality.isNotBlank()) add(e.quality)
    }.joinToString("  ·  ")
    return CardUiModel(
        key = e.dirName,
        title = e.displayTitle,
        meta = meta,
        partLine = null,
        infoLeft = if (e.durationMs > 0) formatDuration(e.durationMs) else "",
        infoRight = "${formatBytes(e.fileSize)} · 导出于 ${formatCacheDate(e.exportedAt)}",
        progress = null,
        progressText = null,
        exported = true,
        cache = cache,
        export = e,
    )
}

/** 统一列表（全部 / 已导出共用同一套卡片与操作菜单） */
@Composable
private fun CardList(
    models: List<CardUiModel>,
    onAction: (CardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(models, key = { it.key }) { model ->
            UnifiedVideoCard(model = model, onAction = onAction)
        }
    }
}

/** 统一卡片：点击弹出操作菜单（导出/重新导出/播放/另存为/删除导出） */
@Composable
private fun UnifiedVideoCard(
    model: CardUiModel,
    onAction: (CardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        ElevatedCard(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.exported) {
                        Spacer(Modifier.width(8.dp))
                        ExportedBadge()
                    }
                }
                if (model.meta.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = model.meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                model.partLine?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 缓存下载进度（仅缓存条目有）
                val progress = model.progress
                if (progress != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    model.progressText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (model.infoLeft.isNotBlank()) {
                        MetaText(model.infoLeft)
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = model.infoRight,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = model.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (model.exported) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (model.exported) {
                DropdownMenuItem(
                    text = { Text("重新导出") },
                    onClick = {
                        menuExpanded = false
                        onAction(CardAction.ReExport(model.cache))
                    },
                )
                DropdownMenuItem(
                    text = { Text("播放") },
                    onClick = {
                        menuExpanded = false
                        onAction(CardAction.Play(model.cache, model.export))
                    },
                )
                model.export?.let { export ->
                    DropdownMenuItem(
                        text = { Text("另存为…") },
                        onClick = {
                            menuExpanded = false
                            onAction(CardAction.SaveAs(export))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除导出") },
                        onClick = {
                            menuExpanded = false
                            onAction(CardAction.Delete(export))
                        },
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text("导出为 MP4") },
                    onClick = {
                        menuExpanded = false
                        onAction(CardAction.Export(model.cache!!))
                    },
                )
                DropdownMenuItem(
                    text = { Text("播放（先导出）") },
                    onClick = {
                        menuExpanded = false
                        onAction(CardAction.Play(model.cache, null))
                    },
                )
            }
        }
    }
}

/** “已导出”徽标 */
@Composable
private fun ExportedBadge(modifier: Modifier = Modifier) {
    Text(
        text = "已导出",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 打开系统「所有文件访问」授权页 */
private fun openAllFilesAccessSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
