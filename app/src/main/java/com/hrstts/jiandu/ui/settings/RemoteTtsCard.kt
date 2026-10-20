package com.hrstts.jiandu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.viewmodel.RemoteSyncState
import com.hrstts.jiandu.viewmodel.RemoteTtsViewModel

/**
 * 「我的」抽屉里的「电脑合成」卡片。
 *
 * 交互按「最常出错的环节」排序：**先能连上，再谈音色，最后才是整本预下载**。
 * 因为局域网 TTS 的失败绝大多数发生在第一步（地址填错 / 服务没启动 / 防火墙），
 * 所以「测试连接」是一个独立的一级按钮，而不是藏在设置里。
 */
@Composable
fun RemoteTtsCard(
    state: RemoteTtsViewModel.UiState,
    sync: RemoteSyncState,
    onEnabledChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onCommitServerUrl: () -> Unit,
    onVoiceChange: (String) -> Unit,
    onTest: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remote_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        Text(
            text = stringResource(R.string.remote_card_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.enabled) return@Column

        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_server_label)) },
            placeholder = { Text(stringResource(R.string.remote_server_hint)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    onCommitServerUrl()
                    focusManager.clearFocus()
                },
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onCommitServerUrl()
                    onTest()
                },
                enabled = state.probe !is RemoteTtsViewModel.Probe.Testing,
            ) {
                Text(
                    text = if (state.probe is RemoteTtsViewModel.Probe.Testing) {
                        stringResource(R.string.remote_testing)
                    } else {
                        stringResource(R.string.remote_test)
                    },
                )
            }
            Text(
                text = when (val probe = state.probe) {
                    is RemoteTtsViewModel.Probe.Ok ->
                        stringResource(R.string.remote_connected, probe.engine)
                    is RemoteTtsViewModel.Probe.Failed -> probe.reason
                    RemoteTtsViewModel.Probe.Testing -> ""
                    RemoteTtsViewModel.Probe.Idle ->
                        stringResource(R.string.remote_untested)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (state.probe) {
                    is RemoteTtsViewModel.Probe.Ok -> MaterialTheme.colorScheme.primary
                    is RemoteTtsViewModel.Probe.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.voices.isNotEmpty()) {
            VoicePicker(
                voices = state.voices,
                selected = state.voice,
                defaultVoice = state.defaultVoice,
                onSelect = onVoiceChange,
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSync,
                enabled = !sync.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (sync.running) {
                        stringResource(R.string.remote_sync_running, sync.done, sync.total)
                    } else {
                        stringResource(R.string.remote_sync_action)
                    },
                )
            }
        }

        if (sync.running && sync.total > 0) {
            LinearProgressIndicator(
                progress = { (sync.done.toFloat() / sync.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 音色选择：用下拉菜单而不是一堆 chip——换成 GPT-SoVITS 后音色可能有十几个。 */
@Composable
private fun VoicePicker(
    voices: List<com.hrstts.jiandu.data.tts.RemoteTtsClient.Voice>,
    selected: String,
    defaultVoice: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.id == selected }
        ?: voices.firstOrNull { it.id == defaultVoice }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.remote_voice_label) + "：" + (current?.label ?: ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.label) },
                    onClick = {
                        expanded = false
                        onSelect(voice.id)
                    },
                )
            }
        }
    }
}
