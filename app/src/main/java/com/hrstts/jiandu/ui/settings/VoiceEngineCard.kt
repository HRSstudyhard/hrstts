package com.hrstts.jiandu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.tts.Qwen3ModelState

/**
 * 「我的」抽屉底部的语音引擎卡片。
 *
 * 说明端侧 Qwen3-TTS 模型的状态与体积，并提供下载 / 删除入口；
 * 模型不完整时朗读自动使用系统语音，不影响其它功能。
 */
@Composable
fun VoiceEngineCard(
    state: Qwen3ModelState,
    activeEngineName: String,
    memorySufficient: Boolean,
    freeSpaceMb: Long,
    installedGraphs: String,
    probeResult: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.engine_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.engine_active, activeEngineName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            is Qwen3ModelState.Ready -> {
                Text(
                    text = stringResource(R.string.engine_installed, installedGraphs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onDelete) {
                    Text(text = stringResource(R.string.engine_delete))
                }
            }

            is Qwen3ModelState.Downloading -> {
                Text(
                    text = stringResource(
                        R.string.engine_downloading,
                        state.fileName,
                        state.fileIndex,
                        state.fileCount,
                        (state.fraction * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.engine_download_resumable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            is Qwen3ModelState.NotInstalled, is Qwen3ModelState.Failed -> {
                val failed = state as? Qwen3ModelState.Failed
                Text(
                    text = failed?.message ?: stringResource(R.string.engine_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDownload) {
                        Text(text = stringResource(R.string.engine_download))
                    }
                    TextButton(onClick = onProbe) {
                        Text(text = stringResource(R.string.engine_probe))
                    }
                }
                Text(
                    text = stringResource(R.string.engine_requirement, freeSpaceMb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                probeResult?.let { report ->
                    Text(
                        text = report,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!memorySufficient) {
                    Text(
                        text = stringResource(R.string.engine_memory_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
