package com.hrstts.jiandu.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.SafSource
import com.hrstts.jiandu.data.permission.AppPermissionCenter
import com.hrstts.jiandu.data.tts.Qwen3ModelState
import com.hrstts.jiandu.data.update.UpdateState
import com.hrstts.jiandu.ui.history.HistoryDrawerContent
import com.hrstts.jiandu.ui.permission.PermissionDialog
import com.hrstts.jiandu.ui.reader.ReaderActions
import com.hrstts.jiandu.ui.reader.ReaderEmptyState
import com.hrstts.jiandu.ui.reader.ReaderScreen
import com.hrstts.jiandu.ui.settings.VoiceEngineCard
import com.hrstts.jiandu.ui.text.resolve
import com.hrstts.jiandu.ui.update.UpdateDialog
import com.hrstts.jiandu.viewmodel.HistoryViewModel
import com.hrstts.jiandu.viewmodel.PermissionViewModel
import com.hrstts.jiandu.viewmodel.ReaderViewModel
import com.hrstts.jiandu.viewmodel.UpdateViewModel
import com.hrstts.jiandu.viewmodel.VoiceEngineViewModel
import kotlinx.coroutines.launch

/**
 * 应用骨架：左侧「我的」抽屉 + 顶部栏（右上「导入」）+ 阅读区。
 *
 * 交互约定（与需求一致）：
 *  - 右上角图标 = 文本导入；
 *  - 左上角图标 = 打开「我的」（从左侧滑出的历史抽屉）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    readerViewModel: ReaderViewModel = viewModel(),
    historyViewModel: HistoryViewModel = viewModel(),
    voiceEngineViewModel: VoiceEngineViewModel = viewModel(),
    permissionViewModel: PermissionViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel(),
) {
    val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()
    val ttsState by readerViewModel.ttsState.collectAsStateWithLifecycle()
    val history by historyViewModel.history.collectAsStateWithLifecycle()
    val modelState by voiceEngineViewModel.modelState.collectAsStateWithLifecycle()
    val engineMessage by voiceEngineViewModel.message.collectAsStateWithLifecycle()
    val probeResult by voiceEngineViewModel.probeResult.collectAsStateWithLifecycle()
    val permissionState by permissionViewModel.snapshot.collectAsStateWithLifecycle()
    val permissionDialogVisible by permissionViewModel.dialogVisible.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val updateDialogVisible by updateViewModel.visible.collectAsStateWithLifecycle()
    val updateMessage by updateViewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 文本导入：SAF 文件选择器（不需要任何存储权限）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(readerViewModel::import)
        }
    }

    // 存储权限：Android 12 及以下可在应用内弹窗授予
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionViewModel.onRuntimePermissionResult() }

    // 特殊权限/网络：只能跳系统设置页，返回后刷新状态
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { permissionViewModel.refresh() }

    val openSettings: (Intent) -> Unit = { intent ->
        runCatching { settingsLauncher.launch(intent) }
            .onFailure { t ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.error_open_settings_failed, t.message.orEmpty()),
                    )
                }
            }
    }

    // 本地 APK 选择（不经网络的自更新/手动分发路径）
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(updateViewModel::useLocalApk) }

    // 自更新提示
    val updateMessageText = updateMessage?.resolve()
    LaunchedEffect(updateMessageText) {
        val message = updateMessageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        updateViewModel.consumeMessage()
    }

    if (updateDialogVisible) {
        UpdateDialog(
            currentVersionName = updateViewModel.currentVersionName,
            currentVersionCode = updateViewModel.currentVersionCode,
            buildTime = updateViewModel.buildTime,
            state = updateState,
            manifestUrl = updateViewModel.manifestUrl,
            canRequestPackageInstalls = updateViewModel.canRequestPackageInstalls(),
            onManifestUrlChange = { updateViewModel.manifestUrl = it },
            onCheck = updateViewModel::check,
            onDownload = {
                (updateState as? UpdateState.Available)?.let { updateViewModel.download(it.manifest) }
            },
            onInstall = {
                (updateState as? UpdateState.ReadyToInstall)?.let {
                    updateViewModel.install(it.file, it.versionName)
                }
            },
            onPickLocalApk = {
                apkPickerLauncher.launch(
                    arrayOf("application/vnd.android.package-archive", "application/octet-stream"),
                )
            },
            onOpenInstallPermission = {
                openSettings(AppPermissionCenter.unknownSourcesIntent(context))
            },
            onDismiss = updateViewModel::hide,
        )
    }

    // 进应用即按需求弹出权限引导（用户点过「不再提示」则不再自动弹出）
    if (permissionDialogVisible) {
        PermissionDialog(
            snapshot = permissionState,
            onRequestStorage = { storagePermissionLauncher.launch(permissionViewModel.requestStoragePermissions()) },
            onOpenAllFiles = { openSettings(AppPermissionCenter.allFilesAccessIntent(context)) },
            onOpenUnknownSources = { openSettings(AppPermissionCenter.unknownSourcesIntent(context)) },
            onOpenNetworkSettings = { openSettings(AppPermissionCenter.networkSettingsIntent()) },
            onDismiss = permissionViewModel::dismiss,
            onDontAskAgain = permissionViewModel::dontAskAgain,
        )
    }

    // 一次性提示（导入失败 / 文件过大被截断等）
    val readerMessageText = readerState.message?.resolve()
    LaunchedEffect(readerMessageText) {
        val message = readerMessageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        readerViewModel.consumeMessage()
    }

    // 语音引擎错误提示
    LaunchedEffect(ttsState.message) {
        val message = ttsState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        readerViewModel.consumeTtsMessage()
    }

    // 端侧模型管理提示
    val engineMessageText = engineMessage?.resolve()
    LaunchedEffect(engineMessageText) {
        val message = engineMessageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        voiceEngineViewModel.consumeMessage()
    }

    // 模型下载完成 → 立即把朗读引擎切到 Qwen3-TTS（无需重启）
    LaunchedEffect(modelState) {
        if (modelState is Qwen3ModelState.Ready) readerViewModel.onModelAvailable()
    }

    val readerActions = remember(readerViewModel) {
        ReaderActions(
            onSelect = readerViewModel::select,
            onClearSelection = readerViewModel::clearSelection,
            onReadSelection = readerViewModel::readSelection,
            onExpandToParagraph = readerViewModel::expandSelectionToParagraph,
            onTogglePlayback = readerViewModel::togglePlayback,
            onPrevious = readerViewModel::previousSegment,
            onNext = readerViewModel::nextSegment,
            onStop = readerViewModel::stopReading,
            onRateChange = readerViewModel::setSpeechRate,
            onVisibleAnchorChanged = readerViewModel::onVisibleAnchorChanged,
            onInitialPositionSettled = readerViewModel::onInitialPositionSettled,
        )
    }

    // 切到后台/离开时保存阅读进度
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    readerViewModel.persistProgress()
                    readerViewModel.stopReading()
                    // 音频缓存：退后台清空内存档（磁盘档保留），即「退后台自动清除」
                    readerViewModel.releaseAudioCacheMemory()
                }

                // 从系统设置页返回后刷新权限状态
                Lifecycle.Event.ON_RESUME -> permissionViewModel.refresh()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                HistoryDrawerContent(
                    history = history,
                    selectedUri = readerState.book?.uri,
                    onItemClick = { entry ->
                        scope.launch {
                            drawerState.close()
                            readerViewModel.openHistory(entry)
                        }
                    },
                    onRemove = historyViewModel::remove,
                    footer = {
                        VoiceEngineCard(
                            state = modelState,
                            activeEngineName = ttsState.engineName.ifEmpty {
                                stringResource(R.string.engine_system_voice)
                            },
                            memorySufficient = voiceEngineViewModel.memorySufficient,
                            freeSpaceMb = voiceEngineViewModel.freeSpaceMb(),
                            installedGraphs = voiceEngineViewModel.installedGraphs(),
                            probeResult = probeResult,
                            onDownload = voiceEngineViewModel::download,
                            onDelete = voiceEngineViewModel::deleteModel,
                            onProbe = voiceEngineViewModel::probeMirrors,
                        )
                        TextButton(
                            onClick = permissionViewModel::show,
                            modifier = Modifier.padding(start = 20.dp),
                        ) {
                            Text(text = stringResource(R.string.perm_reopen))
                        }
                        TextButton(
                            onClick = updateViewModel::show,
                            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.update_version_line,
                                    updateViewModel.currentVersionName,
                                    updateViewModel.currentVersionCode,
                                    updateViewModel.buildTime,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = readerState.bookName ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.action_menu),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                runCatching { importLauncher.launch(SafSource.openDocumentIntent()) }
                                    .onFailure {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.error_open_picker_failed,
                                                    it.message.orEmpty(),
                                                ),
                                            )
                                        }
                                    }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = stringResource(R.string.action_import),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            val book = readerState.book
            Box(modifier = Modifier.padding(innerPadding)) {
                when {
                    readerState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    book == null -> ReaderEmptyState(modifier = Modifier.fillMaxSize())

                    else -> ReaderScreen(
                        book = book,
                        selection = readerState.selection,
                        ttsState = ttsState,
                        actions = readerActions,
                        pendingAnchor = readerState.pendingAnchor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
