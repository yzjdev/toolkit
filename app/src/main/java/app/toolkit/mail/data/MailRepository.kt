package app.toolkit.mail.data

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：MMKV（mmap 键值存储）。
 *
 * 键布局：
 *  - `accounts`                账号列表（JSON 数组，授权码仍是密文）
 *  - `seq`                     邮件自增 id
 *  - `m:<id>`                  邮件元信息（不含正文）
 *  - `b:<id>`                  邮件正文
 *  - `s:<accountId>:<folder>`  每个文件夹的同步进度
 *
 * 邮件元信息常驻内存，列表、搜索、去重都在内存里完成；正文单独存键，
 * 只在打开邮件或按正文搜索时按需读取——避免几千封正文全压在内存里。
 */
class MailRepository(context: Context) {

    private val appContext = context.applicationContext

    /** 应用私有文件目录，内嵌图片落在这里，不需要存储权限 */
    val filesDir: java.io.File get() = appContext.filesDir

    private val kv: MMKV = run {
        // initialize 必须在 mkvWithID 之前；重复调用是幂等的
        MMKV.initialize(appContext)
        MMKV.mmkvWithID(STORE_ID)
    }

    /** 保护内存索引与所有写操作 */
    private val lock = Any()

    /** id -> 元信息（body 恒为空串，正文按需另取） */
    private val byId = HashMap<Long, MailMessage>()

    /** "accountId|messageId" -> id，用于同步去重 */
    private val keyToId = HashMap<String, Long>()

    private var loaded = false

    // ---------------- 索引 ----------------

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val keys = kv.allKeys() ?: emptyArray()
            for (key in keys) {
                if (!key.startsWith(META_PREFIX)) continue
                val json = kv.decodeString(key) ?: continue
                val msg = runCatching { decodeMeta(JSONObject(json)) }.getOrNull() ?: continue
                byId[msg.id] = msg
                keyToId[dedupKey(msg.accountId, msg.messageId)] = msg.id
            }
            loaded = true
        }
    }

    private fun dedupKey(accountId: Long, messageId: String) = "$accountId|$messageId"

    private fun nextId(): Long {
        val next = kv.decodeLong(KEY_SEQ, 0L) + 1L
        kv.encode(KEY_SEQ, next)
        return next
    }

    private fun bodyKey(id: Long) = BODY_PREFIX + id

    private fun htmlKey(id: Long) = HTML_PREFIX + id

    private fun metaKey(id: Long) = META_PREFIX + id

    private fun syncKey(accountId: Long, folder: String) = SYNC_PREFIX + accountId + ":" + folder

    private fun encodeMeta(m: MailMessage): String = JSONObject().apply {
        put("id", m.id)
        put("acc", m.accountId)
        put("folder", m.folder)
        put("uid", m.uid)
        put("mid", m.messageId)
        put("subj", m.subject)
        put("fn", m.fromName)
        put("fa", m.fromAddress)
        put("to", m.toAddress)
        put("sat", m.sentAt)
        put("rat", m.receivedAt)
        put("snip", m.snippet)
        put("read", m.isRead)
        if (m.rawPath.isNotBlank()) put("raw", m.rawPath)
        if (m.links.isNotEmpty()) {
            put("links", JSONArray().apply { m.links.forEach { put(it) } })
        }
        if (m.images.isNotEmpty()) {
            put(
                "imgs",
                JSONArray().apply {
                    m.images.forEach { img ->
                        put(
                            JSONObject().apply {
                                put("cid", img.contentId)
                                put("p", img.path)
                                put("mime", img.mimeType)
                            }
                        )
                    }
                },
            )
        }
    }.toString()

    private fun decodeMeta(o: JSONObject): MailMessage = MailMessage(
        id = o.getLong("id"),
        accountId = o.getLong("acc"),
        // 账号备注名在查询时从账号表补，这里先留空
        accountLabel = "",
        folder = o.optString("folder", "INBOX"),
        uid = o.optLong("uid", 0L),
        messageId = o.optString("mid", ""),
        subject = o.optString("subj", ""),
        fromName = o.optString("fn", ""),
        fromAddress = o.optString("fa", ""),
        toAddress = o.optString("to", ""),
        sentAt = o.optLong("sat", 0L),
        receivedAt = o.optLong("rat", 0L),
        snippet = o.optString("snip", ""),
        body = "",
        links = o.optJSONArray("links")?.let { arr ->
            buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        }.orEmpty(),
        images = o.optJSONArray("imgs")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val img = arr.optJSONObject(i) ?: continue
                    val path = img.optString("p")
                    if (path.isBlank()) continue
                    add(
                        MailImage(
                            contentId = img.optString("cid"),
                            path = path,
                            mimeType = img.optString("mime", "image/*"),
                        )
                    )
                }
            }
        }.orEmpty(),
        isRead = o.optBoolean("read", false),
        rawPath = o.optString("raw", ""),
    )

    // ---------------- 账号 ----------------

    /** 读账号列表；[withSecret] 为 true 时顺带解密授权码（仅同步前需要）。 */
    fun accounts(withSecret: Boolean = false): List<Account> {
        ensureLoaded()
        val counts = messageCounts()
        return synchronized(lock) { readAccounts() }.map { a ->
            a.copy(
                authCode = if (withSecret) SecretBox.decrypt(a.authCodeCipher) else null,
                messageCount = counts[a.id] ?: 0,
            )
        }
    }

    private fun readAccounts(): List<Account> {
        val raw = kv.decodeString(KEY_ACCOUNTS) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    Account(
                        id = o.getLong("id"),
                        email = o.optString("email", ""),
                        displayName = o.optString("name", ""),
                        authCode = null,
                        lastSyncAt = o.optLong("lastSync", 0L),
                        lastUid = 0,
                        uidValidity = 0,
                        messageCount = 0,
                        authCodeCipher = o.optString("cipher", ""),
                    )
                )
            }
        }
    }

    private fun writeAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("email", a.email)
                    put("name", a.displayName)
                    put("cipher", a.authCodeCipher)
                    put("lastSync", a.lastSyncAt)
                }
            )
        }
        kv.encode(KEY_ACCOUNTS, arr.toString())
    }

    fun findAccount(email: String): Account? {
        val normalized = email.trim().lowercase()
        return accounts().firstOrNull { it.email == normalized }
    }

    /**
     * 新增或更新账号（按邮箱地址去重）。
     * [authCode] 为空时保留原有密文，便于只改备注而不重输授权码。
     * 返回账号 id，失败返回 -1。
     */
    fun upsertAccount(
        email: String,
        displayName: String,
        authCode: String?,
    ): Long {
        ensureLoaded()
        val normalized = email.trim().lowercase()
        synchronized(lock) {
            val list = readAccounts().toMutableList()
            val index = list.indexOfFirst { it.email == normalized }
            val existing = if (index >= 0) list[index] else null

            val cipher = when {
                !authCode.isNullOrBlank() -> SecretBox.encrypt(authCode.trim())
                existing != null -> existing.authCodeCipher
                else -> return -1L
            }

            val id = existing?.id ?: ((list.maxOfOrNull { it.id } ?: 0L) + 1L)
            val updated = (existing ?: Account(
                id = id,
                email = normalized,
                displayName = "",
                authCode = null,
                lastSyncAt = 0L,
                lastUid = 0,
                uidValidity = 0,
                messageCount = 0,
            )).copy(
                email = normalized,
                displayName = displayName.trim(),
                authCodeCipher = cipher,
            )

            if (index >= 0) list[index] = updated else list.add(updated)
            writeAccounts(list)
            return id
        }
    }

    fun deleteAccount(accountId: Long) {
        ensureLoaded()
        synchronized(lock) {
            // 先清掉该账号的邮件与同步进度
            val doomed = byId.values.filter { it.accountId == accountId }
            doomed.forEach { msg ->
                byId.remove(msg.id)
                keyToId.remove(dedupKey(accountId, msg.messageId))
                kv.removeValueForKey(metaKey(msg.id))
                kv.removeValueForKey(bodyKey(msg.id))
                kv.removeValueForKey(htmlKey(msg.id))
                // 内嵌图片落在私有目录，删账号时一并清掉
                msg.images.forEach { img ->
                    runCatching { java.io.File(img.path).delete() }
                }
                // 原始报文同样清理
                if (msg.rawPath.isNotBlank()) {
                    runCatching { java.io.File(msg.rawPath).delete() }
                }
            }
            (kv.allKeys() ?: emptyArray())
                .filter { it.startsWith("$SYNC_PREFIX$accountId:") }
                .forEach { kv.removeValueForKey(it) }

            writeAccounts(readAccounts().filterNot { it.id == accountId })
        }
    }

    fun touchAccountSync(accountId: Long, at: Long) {
        ensureLoaded()
        synchronized(lock) {
            val list = readAccounts().toMutableList()
            val index = list.indexOfFirst { it.id == accountId }
            if (index >= 0) {
                list[index] = list[index].copy(lastSyncAt = at)
                writeAccounts(list)
            }
        }
    }

    private fun messageCounts(): Map<Long, Int> = synchronized(lock) {
        byId.values.groupingBy { it.accountId }.eachCount()
    }

    // ---------------- 同步状态 ----------------

    /** 返回 (uidValidity, lastUid)；没有记录时返回 null。 */
    fun syncState(accountId: Long, folder: String): Pair<Long, Long>? {
        val raw = kv.decodeString(syncKey(accountId, folder)) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return o.optLong("uv", 0L) to o.optLong("lu", 0L)
    }

    fun saveSyncState(accountId: Long, folder: String, uidValidity: Long, lastUid: Long) {
        kv.encode(
            syncKey(accountId, folder),
            JSONObject().apply {
                put("uv", uidValidity)
                put("lu", lastUid)
            }.toString(),
        )
    }

    // ---------------- 邮件 ----------------

    /**
     * 写入一封邮件；`(accountId, messageId)` 冲突时保留已有记录（不覆盖已读状态）。
     * 返回 true 表示确实新增了一条。
     */
    fun insertMessage(accountId: Long, folder: String, uid: Long, m: IncomingMail): Boolean {
        ensureLoaded()
        val key = dedupKey(accountId, m.messageId)
        synchronized(lock) {
            if (keyToId.containsKey(key)) return false
            val id = nextId()
            val msg = MailMessage(
                id = id,
                accountId = accountId,
                accountLabel = "",
                folder = folder,
                uid = uid,
                messageId = m.messageId,
                subject = m.subject,
                fromName = m.fromName,
                fromAddress = m.fromAddress,
                toAddress = m.toAddress,
                sentAt = m.sentAt,
                receivedAt = m.receivedAt,
                snippet = m.snippet,
                body = "",
                links = m.links,
                images = m.images,
                rawPath = m.rawPath,
                isRead = m.isRead,
            )
            byId[id] = msg
            keyToId[key] = id
            kv.encode(metaKey(id), encodeMeta(msg))
            // 正文单独存键，避免几千封正文常驻内存
            kv.encode(bodyKey(id), m.body)
            // 原始 HTML 也单独存，详情页用它渲染
            if (m.html.isNotBlank()) kv.encode(htmlKey(id), m.html)
            return true
        }
    }

    /**
     * 批量查出哪些 Message-ID 已经入库。
     *
     * 同步时先用邮件头算出全部去重键，再一次性问这里，就能在下载正文前
     * 把已存在的邮件全部跳过——增量同步通常绝大多数都命中，省掉整封下载。
     */
    fun existingMessageIds(accountId: Long, messageIds: List<String>): Set<String> {
        if (messageIds.isEmpty()) return emptySet()
        ensureLoaded()
        val found = HashSet<String>(messageIds.size)
        synchronized(lock) {
            messageIds.forEach { mid ->
                if (keyToId.containsKey(dedupKey(accountId, mid))) found.add(mid)
            }
        }
        return found
    }

    /**
     * 查出「已入库但没存 HTML」的邮件。
     *
     * 早期版本只存纯文本正文，升级后详情页要靠 HTML 渲染才有排版，
     * 这些老记录需要重新拉一次正文补上，否则用户看到的仍是没格式的纯文字。
     */
    fun existingWithoutHtml(accountId: Long, messageIds: List<String>): Set<String> {
        if (messageIds.isEmpty()) return emptySet()
        ensureLoaded()
        val found = HashSet<String>()
        synchronized(lock) {
            messageIds.forEach { mid ->
                val id = keyToId[dedupKey(accountId, mid)] ?: return@forEach
                // 有正文但 HTML 键为空，说明是旧数据
                if (kv.decodeString(htmlKey(id)).isNullOrBlank()) found.add(mid)
            }
        }
        return found
    }

    /**
     * 给已入库、但没存 HTML 的旧记录补上正文与 HTML。
     *
     * 只补缺失的部分：早期版本只保存纯文本，详情页需要 HTML 才有排版，
     * 所以重新拉一次补上。已有的元信息（主题、时间、已读状态等）一律不动，
     * 避免用服务端数据覆盖本地记录。
     */
    fun backfillHtml(accountId: Long, m: IncomingMail): Boolean {
        ensureLoaded()
        val key = dedupKey(accountId, m.messageId)
        synchronized(lock) {
            val id = keyToId[key] ?: return false
            val existing = byId[id] ?: return false
            // 已经有 HTML 就不需要回填
            if (!kv.decodeString(htmlKey(id)).isNullOrBlank()) return false

            val updated = existing.copy(
                links = m.links.ifEmpty { existing.links },
                images = m.images.ifEmpty { existing.images },
                // 原始报文已落盘但旧记录没路径，一并补上
                rawPath = existing.rawPath.ifBlank { m.rawPath },
            )
            byId[id] = updated
            kv.encode(metaKey(id), encodeMeta(updated))
            if (m.body.isNotBlank()) kv.encode(bodyKey(id), m.body)
            if (m.html.isNotBlank()) kv.encode(htmlKey(id), m.html)
            return true
        }
    }

    /**
     * 统一收件箱查询：所有账号合并、按服务器接收时间倒序。
     * [accountId] 非空时只看单个账号；[query] 匹配主题、发件人、摘要，
     * 正文里的关键词需要读正文键，仅在内存匹配未命中时才回落到它。
     */
    fun queryMessages(
        accountId: Long? = null,
        query: String = "",
        limit: Int = 300,
    ): List<MailMessage> {
        ensureLoaded()
        val labels = accountLabels()
        val q = query.trim()

        val candidates = synchronized(lock) {
            byId.values.asSequence()
                .filter { accountId == null || it.accountId == accountId }
                .toList()
        }

        val matched = if (q.isEmpty()) {
            candidates
        } else {
            candidates.filter { m ->
                m.subject.contains(q, ignoreCase = true) ||
                    m.fromName.contains(q, ignoreCase = true) ||
                    m.fromAddress.contains(q, ignoreCase = true) ||
                    m.snippet.contains(q, ignoreCase = true) ||
                    bodyOf(m.id).contains(q, ignoreCase = true)
            }
        }

        return matched
            .sortedWith(compareByDescending<MailMessage> { it.receivedAt }.thenByDescending { it.id })
            .take(limit.coerceIn(1, 2000))
            // 列表不读正文：正文单独存键，等打开邮件时再取
            .map { it.copy(accountLabel = labels[it.accountId] ?: "") }
    }

    fun message(id: Long): MailMessage? {
        ensureLoaded()
        val labels = accountLabels()
        return synchronized(lock) { byId[id] }?.let {
            it.copy(
                accountLabel = labels[it.accountId] ?: "",
                body = bodyOf(it.id),
                html = htmlOf(it.id),
            )
        }
    }

    fun setRead(messageId: Long, read: Boolean) {
        ensureLoaded()
        synchronized(lock) {
            val existing = byId[messageId] ?: return
            val updated = existing.copy(isRead = read)
            byId[messageId] = updated
            kv.encode(metaKey(messageId), encodeMeta(updated))
        }
    }

    private fun bodyOf(id: Long): String = kv.decodeString(bodyKey(id)).orEmpty()

    private fun htmlOf(id: Long): String = kv.decodeString(htmlKey(id)).orEmpty()

    // ---------------- 全局设置 ----------------

    /**
     * 每次同步取最近多少封。首次同步决定基线，之后的增量同步受它约束。
     * 持久化在 MMKV，重启后沿用上次的设置。
     *
     * 不设人为下限：用户想同步多少就多少，只保证至少 1 封（0 或负数没有意义）。
     */
    fun syncCount(): Int =
        kv.decodeInt(KEY_SYNC_COUNT, DEFAULT_SYNC_COUNT).coerceIn(1, MAX_SYNC_COUNT)

    fun setSyncCount(count: Int) {
        kv.encode(KEY_SYNC_COUNT, count.coerceIn(1, MAX_SYNC_COUNT))
    }

    /** accountId -> 展示名（备注名优先，否则邮箱地址） */
    private fun accountLabels(): Map<Long, String> =
        synchronized(lock) { readAccounts() }.associate { it.id to it.title }

    companion object {
        /** 默认每次同步最近 200 封 */
        const val DEFAULT_SYNC_COUNT = 200
        /** 上限：一次拉太多容易被服务端限流 */
        const val MAX_SYNC_COUNT = 2000

        private const val STORE_ID = "toolkit_mail"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_SEQ = "seq"
        private const val KEY_SYNC_COUNT = "syncCount"
        private const val META_PREFIX = "m:"
        private const val BODY_PREFIX = "b:"
        private const val HTML_PREFIX = "h:"
        private const val SYNC_PREFIX = "s:"
    }
}

