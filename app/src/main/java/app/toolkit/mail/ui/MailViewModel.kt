package app.toolkit.mail.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.toolkit.mail.data.Account
import app.toolkit.mail.data.MailMessage
import app.toolkit.mail.data.MailRepository
import app.toolkit.mail.sync.ImapSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 收件箱的查询条件。 */
data class InboxFilter(
    val accountId: Long? = null,
    val query: String = "",
)

/** 一次性的界面提示，消费后清空。 */
sealed interface UiMessage {
    data class Info(val text: String) : UiMessage
    data class Error(val text: String) : UiMessage
}

class MailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MailRepository(app)
    private val sync = ImapSync(repository)

    private val _messages = MutableStateFlow<List<MailMessage>>(emptyList())
    val messages: StateFlow<List<MailMessage>> = _messages.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _filter = MutableStateFlow(InboxFilter())
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 正在同步的账号 id 集合；只让对应卡片转圈，不是全局开关 */
    private val _syncingIds = MutableStateFlow<Set<Long>>(emptySet())
    val syncingIds: StateFlow<Set<Long>> = _syncingIds.asStateFlow()

    /** 是否有任意同步在进行，用于顶栏按钮防重入 */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /** 正在查看的邮件；列表只带元信息，正文在打开时单独加载 */
    private val _detail = MutableStateFlow<MailMessage?>(null)
    val detail: StateFlow<MailMessage?> = _detail.asStateFlow()

    /** 每次同步取最近多少封；持久化在 MMKV，重启后沿用 */
    private val _syncCount = MutableStateFlow(0)
    val syncCount: StateFlow<Int> = _syncCount.asStateFlow()

    init {
        _syncCount.value = repository.syncCount()
        refresh()
    }

    /** 修改同步数量并立即持久化，下次刷新按新值执行。 */
    fun setSyncCount(count: Int) {
        val clamped = count.coerceIn(1, MailRepository.MAX_SYNC_COUNT)
        repository.setSyncCount(clamped)
        _syncCount.value = repository.syncCount()
        _message.value = UiMessage.Info("已保存：每次同步最近 $clamped 封")
    }

    /** 打开邮件：先占位显示元信息，再补上正文。 */
    fun openMessage(mail: MailMessage) {
        _detail.value = mail
        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) { repository.message(mail.id) }
            // 用户可能已经返回，只在仍是这封时才覆盖
            if (full != null && _detail.value?.id == mail.id) _detail.value = full
        }
        if (!mail.isRead) markRead(mail.id, true)
    }

    fun closeMessage() {
        _detail.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val filter = _filter.value
            val accounts = withContext(Dispatchers.IO) { repository.accounts() }
            val list = withContext(Dispatchers.IO) {
                repository.queryMessages(filter.accountId, filter.query)
            }
            _accounts.value = accounts
            _messages.value = list
            _loading.value = false
        }
    }

    fun setAccountFilter(accountId: Long?) {
        _filter.value = _filter.value.copy(accountId = accountId)
        refresh()
    }

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
        refresh()
    }

    /**
     * 同步全部账号；单个账号失败不影响其他账号。
     * 只做增量：从上一次同步的位置往后取新邮件，不重复下载、不覆盖本地数据。
     */
    fun syncAll() {
        if (_syncing.value) return
        viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) { repository.accounts(withSecret = true) }
            if (targets.isEmpty()) {
                _message.value = UiMessage.Error("还没有账号，先到「账号」页添加")
                return@launch
            }

            _syncing.value = true
            _syncingIds.update { it + targets.map { a -> a.id } }
            var added = 0
            var updated = 0
            val errors = ArrayList<String>()
            try {
                for (account in targets) {
                    val result = withContext(Dispatchers.IO) { sync.sync(account) }
                    if (result.ok) {
                        added += result.added
                        updated += result.backfilled
                    } else {
                        errors.add("${account.title}：${result.error}")
                    }
                    // 一个账号同步完就熄灭它的转圈，不必等全部结束
                    _syncingIds.update { it - account.id }
                }
            } finally {
                _syncingIds.update { it - targets.map { a -> a.id } }
                _syncing.value = false
            }

            val detail = buildString {
                append("新增 $added 封")
                if (updated > 0) append("，补全 $updated 封")
            }
            _message.value = when {
                errors.isEmpty() -> UiMessage.Info("同步完成，$detail")
                added > 0 || updated > 0 -> UiMessage.Error("$detail；${errors.joinToString("；")}")
                else -> UiMessage.Error(errors.joinToString("；"))
            }
            refresh()
        }
    }

    /** 保存账号后立刻同步这一个账号。 */
    fun saveAccount(
        email: String,
        displayName: String,
        authCode: String?,
    ) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                repository.upsertAccount(email, displayName, authCode)
            }
            if (id <= 0) {
                _message.value = UiMessage.Error("首次添加必须填写授权码")
                return@launch
            }
            refresh()
            syncOne(id)
        }
    }

    /** 刷新单个账号；只取上一次同步之后的新邮件。 */
    fun syncOne(accountId: Long) {
        // 只挡住同一个账号的重复同步，其他账号互不影响
        if (accountId in _syncingIds.value) return
        _syncingIds.update { it + accountId }
        viewModelScope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    repository.accounts(withSecret = true).firstOrNull { it.id == accountId }
                }
                if (account == null) {
                    _message.value = UiMessage.Error("账号不存在")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { sync.sync(account) }

                _message.value = if (result.ok) {
                    val detail = buildString {
                        append("新增 ${result.added} 封")
                        if (result.backfilled > 0) append("，补全 ${result.backfilled} 封")
                    }
                    UiMessage.Info("${account.title} 同步完成，$detail")
                } else {
                    UiMessage.Error("${account.title}：${result.error}")
                }
                refresh()
            } finally {
                _syncingIds.update { it - accountId }
            }
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteAccount(accountId) }
            if (_filter.value.accountId == accountId) {
                _filter.value = _filter.value.copy(accountId = null)
            }
            _message.value = UiMessage.Info("已删除账号及其本地邮件")
            refresh()
        }
    }

    fun markRead(messageId: Long, read: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setRead(messageId, read) }
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
