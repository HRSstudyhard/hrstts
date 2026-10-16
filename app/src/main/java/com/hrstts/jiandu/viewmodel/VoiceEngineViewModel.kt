package com.hrstts.jiandu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.tts.Qwen3ModelManager
import com.hrstts.jiandu.data.tts.Qwen3ModelSpec
import com.hrstts.jiandu.data.tts.Qwen3ModelState
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.TextIds
import com.hrstts.jiandu.i18n.UiText
import com.hrstts.jiandu.i18n.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音引擎（Qwen3-TTS 端侧模型）管理。
 *
 * 阶段 P3：模型下载 / 完整性状态 / 存储与内存提示；
 * 端侧推理引擎在模型就绪后由阅读区自动选用（见 ReaderViewModel）。
 * 阶段 P5：提示文案改为资源 ID（[UiText]），支持英文。
 */
class VoiceEngineViewModel(application: Application) : AndroidViewModel(application) {

    private val strings = AndroidStrings(application)
    private val manager = Qwen3ModelManager(application, strings)

    val modelState: StateFlow<Qwen3ModelState> = manager.state

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** 网络自检结果（多行文本，展示在引擎卡片里） */
    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    private var probing = false

    init {
        manager.refresh()
    }

    val memorySufficient: Boolean get() = manager.hasEnoughMemory()

    fun freeSpaceMb(): Long = manager.freeSpaceBytes() / (1024L * 1024L)

    fun installedGraphs(): String = manager.installedGraphs()

    /** 下载缺失模型文件（支持断点续传，中途取消后再次点击可继续） */
    fun download() {
        val free = manager.freeSpaceBytes()
        if (free < Qwen3ModelSpec.REQUIRED_FREE_BYTES) {
            _message.value = uiText(
                TextIds.engineStorageInsufficient,
                free / (1024L * 1024L),
            )
            return
        }
        viewModelScope.launch {
            manager.download { ok ->
                if (!ok) _message.value = uiText(TextIds.engineDownloadIncomplete)
            }
        }
    }

    fun deleteModel() {
        manager.deleteAll()
        _message.value = uiText(TextIds.engineDeleted)
    }

    /** 检测模型镜像是否可达（下载失败时用来定位原因） */
    fun probeMirrors() {
        if (probing) return
        probing = true
        _probeResult.value = strings.get(R.string.engine_probe_running)
        viewModelScope.launch {
            _probeResult.value = try {
                manager.probeMirrors()
            } catch (t: Throwable) {
                strings.get(
                    R.string.engine_probe_failed,
                    t.message ?: t.javaClass.simpleName,
                )
            } finally {
                probing = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
