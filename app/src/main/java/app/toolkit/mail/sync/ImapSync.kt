package app.toolkit.mail.sync

import app.toolkit.mail.data.Account
import app.toolkit.mail.data.IncomingMail
import app.toolkit.mail.data.MailImage
import app.toolkit.mail.data.MailRepository
import app.toolkit.mail.data.SyncResult
import com.sun.mail.imap.IMAPFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

/**
 * QQ 邮箱 IMAP 增量同步。
 *
 * 关键点：
 * 1. 排序用 INTERNALDATE（服务器接收时间），不是 Date 头。Date 头由发件方写入，
 *    可以伪造、也可以沿用旧值——实测该邮箱里有邮件 Date 是 2023 年却刚到达，
 *    用 Date 排序会让它在收件箱里沉到最底部，看起来就是"收错邮件"。
 * 2. 两阶段批量取信。第一阶段只批量取邮件头（不含正文），用于算出去重键并过滤掉
 *    已入库的；第二阶段只对真正新增的邮件批量取整封报文。
 *    逐封 getContent() 是每封一次往返，实测 118 封要 19.6 秒，批量只要 5.9 秒。
 * 3. 只用 UID 做增量。序号（message number）会随删信漂移，UID 不会。
 * 4. UIDVALIDITY 变化说明服务端重建了邮箱，此时旧的 lastUid 失效，必须重扫。
 * 5. 不发 ID 命令。QQ 对不支持的命令回的是缺少 tag 的 `* BAD Command!`，
 *    部分客户端会一直等后续响应直到连接被重置。JavaMail 默认不发 ID。
 */
class ImapSync(private val repository: MailRepository) {

    companion object {
        private const val HOST = "imap.qq.com"
        private const val PORT = 993
        private const val FOLDER = "INBOX"
        /** 增量同步单次最多处理的新邮件数；取最旧的一批，游标才能安全推进 */
        private const val MAX_PER_RUN = 200
        /** 每批取几封。批量取整封报文，批太大会让单次响应过大 */
        private const val BATCH = 20
        /** 首次同步探测 INTERNALDATE 时的批大小，只取头，很便宜 */
        private const val PROBE_BATCH = 100
        /** MIME 嵌套上限，防御异常邮件导致的递归过深 */
        private const val MAX_MIME_DEPTH = 8
        /** 单张内嵌图片上限 5MB，超过就不落盘，避免撑爆私有目录 */
        private const val MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024
        /** 原始报文上限 20MB，超出不落盘，避免超大邮件撑爆私有目录 */
        private const val MAX_RAW_BYTES = 20L * 1024 * 1024
        /** 纯文本邮件里识别裸链接 */
        private val BARE_URL = Regex("(?i)\\bhttps?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
        /** 匹配 src="cid:xxx"，引号可能是单双引号 */
        private val CID_SRC = Regex("(?i)src\\s*=\\s*([\"'])cid:([^\"']+)\\1")
    }

    /**
     * 同步一个账号，只做**增量**：从上一次同步到的位置往后取新邮件。
     *
     * 首次同步（本地没有进度）按接收时间取最近 N 封（N 取自全局设置）；
     * 之后只取 lastUid 之后的部分，绝不重复下载已有邮件，也不会覆盖本地数据。
     *
     * 唯一的例外是「已入库但没存 HTML」的旧记录：那是早期版本只保存了纯文本
     * 留下的，重新拉一次补上排版，不涉及覆盖已有内容。
     *
     * 异常统一转成 [SyncResult.error]。
     */
    fun sync(account: Account): SyncResult {
        val authCode = account.authCode
            ?: return SyncResult(0, 0, error = "授权码不可用，请在账号页重新输入")

        var store: Store? = null
        var openFolder: Folder? = null
        return try {
            store = connect(account.email, authCode)
            // 用局部非空变量持有 Folder：可变变量被 lambda 捕获后无法智能转换
            val folder = store.getFolder(FOLDER).apply { open(Folder.READ_ONLY) }
            openFolder = folder
            val uidFolder = folder as UIDFolder
            val imapFolder = folder as IMAPFolder

            val uidValidity = uidFolder.uidValidity
            val state = repository.syncState(account.id, FOLDER)
            // UIDVALIDITY 变了就不能信旧的 lastUid
            val lastUid = if (state != null && state.first == uidValidity) state.second else 0L

            // 同步数量取自全局设置，重启后沿用上次的配置
            val syncCount = repository.syncCount()
            val uids = collectUids(folder, uidFolder, lastUid, syncCount)
            var added = 0
            var updated = 0
            var maxUid = lastUid

            uids.toList().chunked(BATCH).forEach chunk@{ chunk ->
                val messages = uidFolder.getMessagesByUID(chunk.toLongArray())
                if (messages.isEmpty()) return@chunk

                // 阶段一：只取头与标记。不含正文，所以很快，用来算出 Message-ID
                val headerProfile = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(IMAPFolder.FetchProfileItem.HEADERS)
                    add(IMAPFolder.FetchProfileItem.INTERNALDATE)
                }
                folder.fetch(messages, headerProfile)

                // 算出每封的去重键，并记录 UID 水位
                val candidates = ArrayList<Pair<Long, Message>>(messages.size)
                val keyOf = HashMap<Long, String>(messages.size)
                messages.forEach message@{ msg ->
                    val uid = uidFolder.getUID(msg)
                    if (uid <= 0) return@message
                    if (uid > maxUid) maxUid = uid
                    val key = messageId(msg)
                    keyOf[uid] = key
                    candidates.add(uid to msg)
                }
                if (candidates.isEmpty()) return@chunk

                // 已入库的直接跳过，省掉正文下载——增量同步通常全部命中这里
                val existing = repository.existingMessageIds(account.id, keyOf.values.toList())
                val fresh = candidates.filter { keyOf[it.first] !in existing }

                // 仅回填早期版本只存了纯文本、缺 HTML 的记录，不覆盖已有内容
                val stale = repository.existingWithoutHtml(
                    account.id,
                    candidates.filter { keyOf[it.first] in existing }.map { keyOf[it.first]!! },
                )
                val refresh = candidates.filter { keyOf[it.first] in stale }

                if (fresh.isEmpty() && refresh.isEmpty()) return@chunk

                val toFetch = (fresh + refresh).map { it.second }.toTypedArray()
                val bodyProfile = FetchProfile().apply {
                    add(IMAPFolder.FetchProfileItem.MESSAGE)
                }
                folder.fetch(toFetch, bodyProfile)

                fresh.forEach { (uid, msg) ->
                    val parsed = parse(msg, keyOf[uid] ?: return@forEach) ?: return@forEach
                    if (repository.insertMessage(account.id, FOLDER, uid, parsed)) added++
                }
                refresh.forEach { (uid, msg) ->
                    val parsed = parse(msg, keyOf[uid] ?: return@forEach) ?: return@forEach
                    if (repository.backfillHtml(account.id, parsed)) updated++
                }
            }

            repository.saveSyncState(account.id, FOLDER, uidValidity, maxUid)
            repository.touchAccountSync(account.id, System.currentTimeMillis())
            SyncResult(added = added, scanned = uids.size, backfilled = updated)
        } catch (e: AuthenticationFailedException) {
            SyncResult(0, 0, error = "授权码被拒绝，请到 QQ 邮箱重新生成（改过 QQ 密码会使授权码失效）")
        } catch (e: MessagingException) {
            SyncResult(0, 0, error = describe(e))
        } catch (e: IOException) {
            SyncResult(0, 0, error = "网络错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { openFolder?.close(false) }
            runCatching { store?.close() }
        }
    }

    /** 连接并登录。超时设短一些，避免网络不通时界面一直转圈。 */
    private fun connect(email: String, authCode: String): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", HOST)
            put("mail.imaps.port", PORT.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.trust", "*")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.writetimeout", "30000")
            // 只读浏览，不改变服务端已读状态
            put("mail.imaps.peek", "true")
            put("mail.imaps.fetchsize", (512 * 1024).toString())
        }
        val session = Session.getInstance(props, null)
        val store = session.getStore("imaps")
        store.connect(HOST, PORT, email, authCode)
        return store
    }

    /**
     * 计算本次要拉的 UID 列表。
     *
     * 首次（`lastUid = 0`）：探测全量的 INTERNALDATE，按**接收时间**倒序取最新
     * [count] 封（数值来自全局设置）。不能按 UID 倒序取——实测该邮箱里存在 UID 很大但日期是
     * 三年前的邮件（重新上传/迁移进来的），按 UID 取会把这些老信算进"最近"，
     * 而真正的新邮件反而被挤掉。全量探测 INTERNALDATE 只要 1 秒左右，代价很小。
     *
     * 增量：取 `lastUid+1 : *`。注意 IMAP 里 `n:*` 在 n 大于当前最大 UID 时仍会
     * 返回最后一封，所以必须再过滤一次 `uid > lastUid`，否则每轮重复拉同一封。
     */
    private fun collectUids(
        folder: Folder,
        uidFolder: UIDFolder,
        lastUid: Long,
        count: Int,
    ): LongArray {
        if (lastUid <= 0L) {
            return newestByInternalDate(folder, uidFolder, count.coerceAtLeast(1))
        }

        val newer = uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
        return newer.mapNotNull { runCatching { uidFolder.getUID(it) }.getOrNull() }
            .filter { it > lastUid }        // 挡掉 n:* 的假阳性
            .sorted()
            .take(MAX_PER_RUN)              // 取最旧的一批，游标才能安全推进
            .toLongArray()
    }

    /**
     * 取接收时间最新的 [count] 封，返回按 UID 升序排列的数组。
     *
     * 只拉 INTERNALDATE（不拉信封和正文），实测 1079 封约 1 秒。
     * 返回值仍按 UID 升序：调用方据此推进 lastUid 水位，顺序无关但便于阅读日志。
     */
    private fun newestByInternalDate(
        folder: Folder,
        uidFolder: UIDFolder,
        count: Int,
    ): LongArray {
        val allUids = uidFolder.getMessagesByUID(1, UIDFolder.LASTUID)
            .mapNotNull { runCatching { uidFolder.getUID(it) }.getOrNull() }
            .filter { it > 0 }
            .sorted()
        if (allUids.isEmpty()) return LongArray(0)
        // 总数不超过目标数时，无需探测时间，全取
        if (allUids.size <= count) return allUids.toLongArray()

        // 批量只取 INTERNALDATE
        val profile = FetchProfile().apply {
            add(IMAPFolder.FetchProfileItem.INTERNALDATE)
        }
        val dated = ArrayList<Pair<Long, Long>>(allUids.size)   // uid to internalDate
        allUids.chunked(PROBE_BATCH).forEach chunk@{ chunk ->
            val messages = uidFolder.getMessagesByUID(chunk.toLongArray())
            if (messages.isEmpty()) return@chunk
            runCatching { folder.fetch(messages, profile) }
            messages.forEach { msg ->
                val uid = runCatching { uidFolder.getUID(msg) }.getOrNull() ?: return@forEach
                if (uid <= 0) return@forEach
                // 取不到接收时间时退回发送时间，再退到 0（排在最后）
                val stamp = runCatching { msg.receivedDate?.time ?: msg.sentDate?.time ?: 0L }
                    .getOrDefault(0L)
                dated.add(uid to stamp)
            }
        }
        if (dated.isEmpty()) return allUids.takeLast(count).toLongArray()

        return dated.sortedByDescending { it.second }
            .take(count)
            .map { it.first }
            .sorted()
            .toLongArray()
    }

    /**
     * 解析一封邮件；正文取不到时降级为无正文而不是整批失败。
     * [messageId] 由阶段一算好传入，避免重复计算。
     */
    private fun parse(msg: Message, messageId: String): IncomingMail? = try {
        val subject = decode(msg.subject).ifBlank { "(无主题)" }
        val from = msg.from?.firstOrNull()
        val fromAddress = (from as? InternetAddress)?.address ?: from?.toString().orEmpty()
        val fromName = (from as? InternetAddress)?.personal?.let(::decode).orEmpty()
        val to = msg.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { addressText(it) }
            .orEmpty()
        val sentAt = msg.sentDate?.time ?: 0L
        // INTERNALDATE：服务器写入的接收时间，收件箱排序用它
        val receivedAt = msg.receivedDate?.time ?: sentAt
        val isRead = msg.flags?.contains(Flags.Flag.SEEN) == true

        val content = runCatching { extractContent(msg, messageId) }.getOrNull()
        val body = content?.text.orEmpty()
        // 原始报文（.eml）单独落盘，便于导出与重新解析
        val rawPath = runCatching { saveRawMessage(msg, messageId) }.getOrDefault("")

        IncomingMail(
            messageId = messageId,
            subject = subject,
            fromName = fromName,
            fromAddress = fromAddress,
            toAddress = to,
            sentAt = sentAt,
            receivedAt = receivedAt,
            snippet = body.replace(Regex("\\s+"), " ").trim().take(160),
            body = body,
            links = content?.links.orEmpty(),
            images = content?.images.orEmpty(),
            html = content?.html.orEmpty(),
            rawPath = rawPath,
            isRead = isRead,
        )
    } catch (_: Exception) {
        null
    }

    /**
     * 去重键：优先 Message-ID；缺失时用 发件人+主题+接收时间 的哈希兜底。
     * 同一封信走代收和转发两条链路时 Message-ID 一致，会被合并成一封。
     */
    private fun messageId(msg: Message): String {
        val raw = runCatching { msg.getHeader("Message-ID")?.firstOrNull() }.getOrNull()
        if (!raw.isNullOrBlank()) return raw.trim()
        val from = runCatching {
            (msg.from?.firstOrNull() as? InternetAddress)?.address
        }.getOrNull().orEmpty()
        val subject = runCatching { msg.subject }.getOrNull().orEmpty()
        val stamp = runCatching { msg.receivedDate?.time ?: 0L }.getOrNull() ?: 0L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$from|$subject|$stamp".toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun addressText(a: Address): String =
        (a as? InternetAddress)?.let { it.personal?.let { p -> "$p <${it.address}>" } ?: it.address }
            ?: a.toString()

    /** 一次解析的产物：文本、HTML、链接、已落盘的图片。 */
    private class ParsedContent(
        val text: String,
        val html: String,
        val links: List<String>,
        val images: List<MailImage>,
    )

    /**
     * 遍历 MIME 结构，取出正文文本、链接和内嵌图片。
     *
     * - text/plain 优先；没有时才用 HTML 转文本
     * - 链接从 HTML 的 `<a href>` 里提取
     * - `cid:` 引用的图片从对应 MIME 部件取字节，落盘到应用私有目录
     * - 普通附件仍然跳过
     */
    private fun extractContent(part: Part, messageId: String): ParsedContent {
        var plain = ""
        var html = ""
        val htmlLinks = LinkedHashSet<String>()
        val cidRefs = LinkedHashSet<String>()
        val inlineParts = ArrayList<Pair<String, Part>>()   // cid -> part

        fun walk(p: Part, depth: Int) {
            if (depth > MAX_MIME_DEPTH) return
            val isAttachment = Part.ATTACHMENT.equals(p.disposition, ignoreCase = true)

            if (p.isMimeType("multipart/*")) {
                val mp = runCatching { p.content as Multipart }.getOrNull() ?: return
                for (i in 0 until mp.count) {
                    val child = runCatching { mp.getBodyPart(i) }.getOrNull() ?: continue
                    walk(child, depth + 1)
                }
                return
            }

            if (isAttachment) return

            when {
                p.isMimeType("text/plain") -> if (plain.isEmpty()) {
                    plain = runCatching { p.content as? String }.getOrNull().orEmpty()
                }
                p.isMimeType("text/html") -> if (html.isEmpty()) {
                    html = runCatching { p.content as? String }.getOrNull().orEmpty()
                }
                p.isMimeType("image/*") -> {
                    // 内嵌图片靠 Content-ID 与 HTML 里的 cid: 对应
                    val cid = runCatching {
                        p.getHeader("Content-ID")?.firstOrNull()?.trim()?.trim('<', '>')
                    }.getOrNull().orEmpty()
                    if (cid.isNotEmpty()) inlineParts.add(cid to p)
                }
            }
        }

        walk(part, 0)

        // HTML 转文本时顺带捞链接和 cid 引用
        val converted = if (html.isNotBlank()) HtmlText.convert(html) else null
        if (converted != null) {
            htmlLinks.addAll(converted.links)
            cidRefs.addAll(converted.imageCids)
        }

        // 只保存正文实际引用到的图片，避免把无关内嵌资源也存下来
        val images = ArrayList<MailImage>()
        val cidToPath = HashMap<String, String>()
        inlineParts.forEach { (cid, p) ->
            if (cidRefs.isNotEmpty() && cid !in cidRefs) return@forEach
            val saved = runCatching { saveInlineImage(p, messageId, cid) }.getOrNull()
            if (saved != null) {
                images.add(saved)
                cidToPath[cid] = saved.path
            }
        }

        // 原始 HTML：把 cid:xxx 换成本地 file:// 路径，WebView 才能显示出图片。
        // 不替换的话 WebView 无从解析 cid，图片位置会留空。
        val renderHtml = if (html.isNotBlank()) {
            rewriteCidRefs(html, cidToPath)
        } else {
            ""
        }

        val text = plain.ifBlank { converted?.text.orEmpty() }
        // 纯文本邮件没有 href，用正则兜底把裸链接也提出来
        val links = if (htmlLinks.isNotEmpty()) {
            htmlLinks.toList()
        } else {
            BARE_URL.findAll(text).map { it.value }.distinct().toList()
        }

        return ParsedContent(text = text, html = renderHtml, links = links, images = images)
    }

    /**
     * 把 HTML 里 `src="cid:xxx"` 改写成 `src="file:///本地路径"`。
     * cid 在 HTML 里可能带尖括号或被 URL 编码，这里一并兼容。
     */
    private fun rewriteCidRefs(html: String, cidToPath: Map<String, String>): String {
        if (cidToPath.isEmpty()) return html
        return CID_SRC.replace(html) { m ->
            val quote = m.groupValues[1]
            val raw = m.groupValues[2].trim()
            val cid = raw.substring(4).trim().trim('<', '>')
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val path = cidToPath[cid]
            if (path == null) m.value else "src=$quote${File(path).toURI()}$quote"
        }
    }

    /**
     * 把内嵌图片写到应用私有目录 `files/mailimg/<messageId>/`。
     * 文件名用 cid 的哈希，避免 cid 里出现路径分隔符。
     */
    private fun saveInlineImage(part: Part, messageId: String, cid: String): MailImage? {
        val bytes = runCatching { part.inputStream.use { it.readBytes() } }.getOrNull()
            ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_INLINE_IMAGE_BYTES) return null

        val safeMessage = messageId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val safeCid = Integer.toHexString(cid.hashCode())
        val ext = when (part.contentType?.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }

        val dir = File(imageRoot(), safeMessage).apply { mkdirs() }
        val file = File(dir, "$safeCid.$ext")
        return runCatching {
            file.writeBytes(bytes)
            MailImage(
                contentId = cid,
                path = file.absolutePath,
                mimeType = part.contentType ?: "image/*",
            )
        }.getOrNull()
    }

    /** 内嵌图片根目录，位于应用私有空间，不需要存储权限 */
    private fun imageRoot(): File = File(repository.filesDir, "mailimg")

    /** 原始报文根目录 */
    private fun rawRoot(): File = File(repository.filesDir, "mailraw")

    /**
     * 把服务器返回的整封报文原样写成 `.eml`。
     *
     * 用 `msg.writeTo` 而不是自己拼装：JavaMail 会保留原始头与传输编码，
     * 写出的内容与 `BODY[]` 拿到的字节基本一致，可直接用邮件客户端打开。
     * 超过 [MAX_RAW_BYTES] 的不保存，避免个别超大邮件撑爆私有目录。
     */
    private fun saveRawMessage(msg: Message, messageId: String): String {
        val dir = rawRoot().apply { mkdirs() }
        val safeName = messageId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(dir, "$safeName.eml")

        val ok = runCatching {
            file.outputStream().use { out ->
                msg.writeTo(out)
            }
            // 写完后检查体积，过大就删掉
            if (file.length() > MAX_RAW_BYTES) {
                file.delete()
                false
            } else {
                true
            }
        }.getOrDefault(false)

        return if (ok && file.exists()) file.absolutePath else ""
    }

    /** 头字段可能是 RFC 2047 编码（=?UTF-8?B?...?=），统一解码。 */
    private fun decode(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return runCatching { MimeUtility.decodeText(text) }.getOrDefault(text).trim()
    }

    private fun describe(e: MessagingException): String = when {
        e.message?.contains("Unsafe Login", ignoreCase = true) == true ->
            "服务端拒绝登录（Unsafe Login），请确认已开启 IMAP 服务"
        e.message?.contains("LOGIN", ignoreCase = true) == true ->
            "登录失败：${e.message}"
        else -> "收取失败：${e.message ?: e.javaClass.simpleName}"
    }
}
