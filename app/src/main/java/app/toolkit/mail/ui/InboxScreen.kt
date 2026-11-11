package app.toolkit.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.toolkit.R
import app.toolkit.mail.data.Account
import app.toolkit.mail.data.MailMessage

/** 统一收件箱：所有账号的邮件按时间合并成一列。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    messages: List<MailMessage>,
    accounts: List<Account>,
    filter: InboxFilter,
    loading: Boolean,
    syncing: Boolean,
    onQueryChange: (String) -> Unit,
    onAccountFilter: (Long?) -> Unit,
    onSync: () -> Unit,
    onOpen: (MailMessage) -> Unit,
    onOpenAccounts: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.inbox_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (loading) stringResource(R.string.inbox_syncing)
                            else stringResource(R.string.inbox_count, messages.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = onSync) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                    IconButton(onClick = onOpenAccounts) {
                        Icon(
                            Icons.Outlined.ManageAccounts,
                            contentDescription = stringResource(R.string.accounts_title),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SearchField(
                query = filter.query,
                onQueryChange = onQueryChange,
            )

            if (accounts.isNotEmpty()) {
                AccountFilterRow(
                    accounts = accounts,
                    selected = filter.accountId,
                    onSelect = onAccountFilter,
                )
            }

            HorizontalDivider()

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                messages.isEmpty() -> EmptyInbox(
                    hasAccounts = accounts.isNotEmpty(),
                    hasQuery = filter.query.isNotBlank() || filter.accountId != null,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(messages, key = { it.id }) { mail ->
                        MailRow(mail = mail, onClick = { onOpen(mail) })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.inbox_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** 账号筛选：横向滚动的 chip，第一个是「全部账号」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountFilterRow(
    accounts: List<Account>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.inbox_filter_all)) },
            )
        }
        items(accounts, key = { it.id }) { account ->
            FilterChip(
                selected = selected == account.id,
                onClick = { onSelect(account.id) },
                label = {
                    Text(
                        text = account.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun MailRow(mail: MailMessage, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SenderAvatar(mail = mail)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.senderLabel,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (mail.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatListTime(mail.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = mail.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (mail.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = mail.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 来源账号标签：一眼看出这封信属于哪个邮箱
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = mail.accountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 圆形头像：取发件人首字母，按地址哈希取色，方便区分不同来源。 */
@Composable
private fun SenderAvatar(mail: MailMessage) {
    val palette = listOf(
        Color(0xFF1D4ED8), Color(0xFF0F766E), Color(0xFFB45309),
        Color(0xFF7C3AED), Color(0xFFBE123C), Color(0xFF0369A1),
    )
    val color = palette[(mail.fromAddress.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size]
    val initial = mail.senderLabel.trim().take(1).ifBlank { "?" }.uppercase()

    Box(
        modifier = Modifier.size(48.dp).background(color = color, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun EmptyInbox(hasAccounts: Boolean, hasQuery: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    hasQuery -> stringResource(R.string.inbox_no_result)
                    hasAccounts -> stringResource(R.string.inbox_empty)
                    else -> stringResource(R.string.inbox_sync_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
