package com.hrstts.jiandu.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.update.UpdateState

/**
 * 自更新弹窗。
 *
 * 两条路径：
 *  1) 远程：填更新清单 JSON 地址 → 检查 → 下载 → 校验 → 安装；
 *  2) 本地：选一个 APK 文件 → 校验（包名/版本/签名）→ 安装。
 * 顶部始终显示当前版本与构建时间，便于确认「手机上装的是不是最新构建」。
 */
@Composable
fun UpdateDialog(
    currentVersionName: String,
    currentVersionCode: Int,
    buildTime: String,
    state: UpdateState,
    manifestUrl: String,
    canRequestPackageInstalls: Boolean,
    onManifestUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onPickLocalApk: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    var urlDraft by remember(manifestUrl) { mutableStateOf(manifestUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        R.string.update_current,
                        currentVersionName,
                        currentVersionCode,
                        buildTime,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = {
                        urlDraft = it
                        onManifestUrlChange(it)
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.update_manifest_label)) },
                    placeholder = { Text(stringResource(R.string.update_manifest_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCheck) {
                        Text(text = stringResource(R.string.update_check))
                    }
                    TextButton(onClick = onPickLocalApk) {
                        Text(text = stringResource(R.string.update_pick_local))
                    }
                }

                when (state) {
                    is UpdateState.Checking -> Text(
                        text = stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is UpdateState.UpToDate -> Text(
                        text = stringResource(R.string.update_latest, state.currentVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is UpdateState.Available -> {
                        Text(
                            text = stringResource(
                                R.string.update_available,
                                state.manifest.versionName,
                                state.manifest.versionCode,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        state.manifest.notes?.let { notes ->
                            Text(text = notes, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onDownload) {
                            Text(text = stringResource(R.string.update_download))
                        }
                    }

                    is UpdateState.Downloading -> {
                        val percent = (state.fraction * 100).toInt()
                        Text(
                            text = if (state.total > 0) {
                                stringResource(
                                    R.string.update_downloading,
                                    percent,
                                    state.downloaded / 1024 / 1024,
                                    state.total / 1024 / 1024,
                                )
                            } else {
                                stringResource(R.string.update_downloading_unknown, state.downloaded / 1024 / 1024)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    is UpdateState.ReadyToInstall -> {
                        Text(
                            text = stringResource(R.string.update_ready, state.versionName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (!canRequestPackageInstalls) {
                            Text(
                                text = stringResource(R.string.update_need_install_permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onOpenInstallPermission) {
                                Text(text = stringResource(R.string.update_open_install_permission))
                            }
                        }
                        TextButton(onClick = onInstall) {
                            Text(text = stringResource(R.string.update_install))
                        }
                    }

                    is UpdateState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdateState.Idle -> Text(
                        text = stringResource(R.string.update_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.update_close))
            }
        },
    )
}
