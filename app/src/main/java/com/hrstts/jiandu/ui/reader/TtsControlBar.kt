package com.hrstts.jiandu.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.tts.TtsStatus
import com.hrstts.jiandu.domain.tts.TtsUiState

/** 底部朗读控制条：引擎、语速、播放/暂停/上下一句/停止 */
@Composable
fun TtsControlBar(
    state: TtsUiState,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = state.isActive || state.status == TtsStatus.Initializing, modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { },
                        label = {
                            Text(
                                text = state.engineName.ifEmpty { stringResource(R.string.tts_engine_unknown) },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    RateSelector(rate = state.speechRate, onRateChange = onRateChange)
                }

                Text(
                    text = state.currentText ?: stringResource(R.string.tts_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(
                            imageVector = Icons.Outlined.SkipPrevious,
                            contentDescription = stringResource(R.string.tts_previous),
                        )
                    }
                    FilledIconButton(onClick = onTogglePlayback) {
                        Icon(
                            imageVector = if (state.isSpeaking) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isSpeaking) R.string.tts_pause else R.string.tts_play,
                            ),
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Outlined.SkipNext,
                            contentDescription = stringResource(R.string.tts_next),
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.tts_stop),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RateSelector(rate: Float, onRateChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rateLabel = stringResource(R.string.tts_rate, "${rate}x")
    Box {
        TextButton(
            onClick = { expanded = true },
            // 无障碍：只有「1.0x」时 TalkBack 读不出含义，补上「语速」说明
            modifier = Modifier.semantics {
                contentDescription = rateLabel
            },
        ) {
            Text(text = "${rate}x", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RATE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option}x") },
                    onClick = {
                        expanded = false
                        onRateChange(option)
                    },
                )
            }
        }
    }
}

private val RATE_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
