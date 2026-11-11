package app.toolkit.mail.data

/** 一个邮箱账号。authCode 只在内存里出现，落库时由 [SecretBox] 加密。 */
data class Account(
    val id: Long,
    val email: String,
    val displayName: String,
    /** 解密后的授权码；读取账号列表时为 null，同步前才按需解密 */
    val authCode: String?,
    val lastSyncAt: Long,
    /** 上次同步到的最大 UID，0 表示从未同步 */
    val lastUid: Long,
    /** 上次见到的 UIDVALIDITY，变化说明服务端重建了邮箱，需要整箱重扫 */
    val uidValidity: Long,
    val messageCount: Int,
    /** Keystore 加密后的授权码密文；只在编辑账号时用于保留原值 */
    val authCodeCipher: String = "",
) {
    val title: String get() = displayName.ifBlank { email }
}

/** 正文里的一张内嵌图片（cid: 引用的图）。文件落在应用私有目录，只存相对路径。 */
data class MailImage(
    /** 原始 Content-ID，去掉尖括号 */
    val contentId: String,
    /** 应用私有目录下的绝对路径 */
    val path: String,
    val mimeType: String,
)

/** 统一收件箱里的一封邮件。 */
data class MailMessage(
    val id: Long,
    val accountId: Long,
    /** 列表/详情里展示的来源账号名 */
    val accountLabel: String,
    val folder: String,
    val uid: Long,
    val messageId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toAddress: String,
    /** Date 头时间，仅作参考 */
    val sentAt: Long,
    /** 服务器接收时间，列表排序与显示都用它 */
    val receivedAt: Long,
    val snippet: String,
    val body: String,
    /**
     * 原始 HTML 正文（cid 引用已改写成本地图片路径）。
     * 为空表示这封信没有 HTML 部分，只能按纯文本展示。
     */
    val html: String = "",
    /** 正文里的链接，去重并按出现顺序 */
    val links: List<String> = emptyList(),
    /** 内嵌图片 */
    val images: List<MailImage> = emptyList(),
    /**
     * 服务器返回的原始 MIME 报文（.eml）路径。
     * 为空表示没有保存原始数据（旧记录，或落盘失败）。
     */
    val rawPath: String = "",
    val isRead: Boolean,
) {
    val senderLabel: String get() = fromName.ifBlank { fromAddress }
}

/** 一次同步的结果，用于界面提示。 */
data class SyncResult(
    val added: Int,
    val scanned: Int,
    /** 旧记录补上正文/HTML 的数量 */
    val backfilled: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}
