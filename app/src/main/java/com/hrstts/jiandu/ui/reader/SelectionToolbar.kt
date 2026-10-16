package com.hrstts.jiandu.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R

/**
 * 长按选中后出现在选中框上方的工具栏。
 *
 * 主操作是「阅读」——点击即从选中位置开始连续朗读（需求 4）。
 */
@Composable
fun SelectionToolbar(
    onRead: () -> Unit,
    onExpandToParagraph: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onRead) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.action_read),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            TextButton(onClick = onExpandToParagraph) {
                Icon(
                    imageVector = Icons.Outlined.SelectAll,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.action_select_paragraph),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            TextButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.action_copy),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
