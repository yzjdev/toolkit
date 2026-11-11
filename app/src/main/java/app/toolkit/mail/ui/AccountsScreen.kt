package app.toolkit.mail.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.toolkit.R
import app.toolkit.mail.data.Account

/** 账号管理：添加/编辑/删除 QQ 邮箱，逐个同步。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    accounts: List<Account>,
    syncingIds: Set<Long>,
    onSave: (email: String, displayName: String, authCode: String?) -> Unit,
    onDelete: (Long) -> Unit,
    onSync: (Long) -> Unit,
    onBack: () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.accounts_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "${accounts.size} 个账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.mail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { helpOpen = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.action_help),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.account_add)) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (accounts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.accounts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(accounts, key = { it.id }) { account ->
                        AccountCard(
                            account = account,
                            syncing = account.id in syncingIds,
                            onSync = { onSync(account.id) },
                            onDelete = { pendingDelete = account },
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        AccountEditorDialog(
            onDismiss = { editing = false },
            onConfirm = { email, name, code ->
                editing = false
                onSave(email, name, code)
            },
        )
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.account_delete)) },
            text = { Text(stringResource(R.string.account_delete_confirm, account.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(account.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.account_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = { Text(stringResource(R.string.account_auth_help_title)) },
            text = { Text(stringResource(R.string.account_auth_help_body)) },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    syncing: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.displayName.isNotBlank()) {
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSync) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.account_sync_now))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.account_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.account_message_count, account.messageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (account.lastSyncAt <= 0L) {
                    stringResource(R.string.account_never_synced)
                } else {
                    stringResource(R.string.account_last_sync, formatFullTime(account.lastSyncAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 添加账号表单。授权码只在输入框里存在，保存时交给 SecretBox 加密。 */
@Composable
private fun AccountEditorDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String, name: String, code: String?) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    val emailInvalid = stringResource(R.string.account_email_invalid)
    val codeRequired = stringResource(R.string.account_auth_required)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_add_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = "" },
                    label = { Text(stringResource(R.string.account_email)) },
                    placeholder = { Text(stringResource(R.string.account_email_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = "" },
                    label = { Text(stringResource(R.string.account_auth_code)) },
                    placeholder = { Text(stringResource(R.string.account_auth_code_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.account_display_name)) },
                    placeholder = { Text(stringResource(R.string.account_display_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = email.trim()
                when {
                    !trimmed.contains('@') || !trimmed.contains('.') -> error = emailInvalid
                    code.isBlank() -> error = codeRequired
                    else -> onConfirm(
                        trimmed,
                        name.trim(),
                        code.trim(),
                    )
                }
            }) { Text(stringResource(R.string.account_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.account_cancel))
            }
        },
    )
}
