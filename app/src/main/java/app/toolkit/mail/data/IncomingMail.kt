package app.toolkit.mail.data

/** 从 IMAP 解析出来、准备落库的一封邮件。 */
data class IncomingMail(
    val messageId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toAddress: String,
    /** Date 头里的时间。发件方可伪造或沿用旧值，不适合排序 */
    val sentAt: Long,
    /** 服务器接收时间（IMAP INTERNALDATE），收件箱按它排序 */
    val receivedAt: Long,
    val snippet: String,
    val body: String,
    /** 原始 HTML 正文，cid 引用已改写为本地图片路径；无 HTML 部分时为空 */
    val html: String = "",
    /** 正文里的链接，去重并按出现顺序 */
    val links: List<String> = emptyList(),
    /** 内嵌图片（已落盘） */
    val images: List<MailImage> = emptyList(),
    /**
     * 服务器返回的原始 MIME 报文（.eml）在私有目录下的绝对路径。
     * 落盘失败时为空串，不影响解析后的数据可用。
     */
    val rawPath: String = "",
    val isRead: Boolean,
)
