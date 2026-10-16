package com.hrstts.jiandu.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.db.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的」抽屉内容：按最近打开时间倒序列出曾打开过的文本。
 *
 * @param selectedUri 当前正在阅读的文本 uri（高亮显示）
 */
@Composable
fun HistoryDrawerContent(
    history: List<HistoryEntry>,
    selectedUri: String?,
    onItemClick: (HistoryEntry) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.drawer_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.drawer_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (history.isEmpty()) {
            HistoryEmptyState(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
            items(items = history, key = { it.uri }) { entry ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(
                                text = entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = formatTime(entry.lastOpenedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    selected = entry.uri == selectedUri,
                    onClick = { onItemClick(entry) },
                    badge = {
                        IconButton(onClick = { onRemove(entry) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.drawer_remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        footer()
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(R.string.drawer_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.drawer_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else timeFormatter.format(Date(timestamp))
