package com.hrstts.jiandu.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.permission.PermissionAction
import com.hrstts.jiandu.data.permission.PermissionSnapshot

/**
 * 启动时的权限引导弹窗。
 *
 * 三项：存储 / 网络 / 安装未知应用。
 * 能在应用内弹窗授予的（Android 12 及以下存储权限）直接申请；其余是系统特殊权限或安装时权限，
 * 这里只做状态展示 + 跳转对应设置页（返回后自动刷新）。
 */
@Composable
fun PermissionDialog(
    snapshot: PermissionSnapshot,
    onRequestStorage: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onDismiss: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.perm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.perm_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PermissionRow(
                    title = stringResource(R.string.perm_storage_title),
                    description = stringResource(R.string.perm_storage_desc),
                    ok = snapshot.storageGranted,
                    action = snapshot.storageAction,
                    onRequest = onRequestStorage,
                    onOpenAllFiles = onOpenAllFiles,
                )
                PermissionRow(
                    title = stringResource(R.string.perm_network_title),
                    description = stringResource(R.string.perm_network_desc),
                    ok = snapshot.networkOnline,
                    action = snapshot.networkAction,
                    onOpenNetworkSettings = onOpenNetworkSettings,
                )
                PermissionRow(
                    title = stringResource(R.string.perm_install_title),
                    description = stringResource(R.string.perm_install_desc),
                    ok = !snapshot.installSupported || snapshot.installAllowed,
                    action = snapshot.installAction,
                    onOpenUnknownSources = onOpenUnknownSources,
                )

                Text(
                    text = stringResource(R.string.perm_footnote),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.perm_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDontAskAgain) {
                Text(text = stringResource(R.string.perm_dont_ask))
            }
        },
    )
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    ok: Boolean,
    action: PermissionAction,
    onRequest: () -> Unit = {},
    onOpenAllFiles: () -> Unit = {},
    onOpenUnknownSources: () -> Unit = {},
    onOpenNetworkSettings: () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ok) {
                val label = when (action) {
                    PermissionAction.REQUEST_STORAGE -> stringResource(R.string.perm_action_request)
                    PermissionAction.OPEN_ALL_FILES -> stringResource(R.string.perm_action_all_files)
                    PermissionAction.OPEN_UNKNOWN_SOURCES -> stringResource(R.string.perm_action_unknown)
                    PermissionAction.OPEN_NETWORK_SETTINGS -> stringResource(R.string.perm_action_network)
                    PermissionAction.NONE -> null
                }
                if (label != null) {
                    TextButton(
                        onClick = {
                            when (action) {
                                PermissionAction.REQUEST_STORAGE -> onRequest()
                                PermissionAction.OPEN_ALL_FILES -> onOpenAllFiles()
                                PermissionAction.OPEN_UNKNOWN_SOURCES -> onOpenUnknownSources()
                                PermissionAction.OPEN_NETWORK_SETTINGS -> onOpenNetworkSettings()
                                PermissionAction.NONE -> Unit
                            }
                        },
                    ) {
                        Text(text = label)
                    }
                }
            }
        }
    }
}
