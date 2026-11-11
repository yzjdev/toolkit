package app.toolkit.mail.sync

/**
 * 把邮件 HTML 转成可读文本，同时把**链接和内嵌图片**捞出来。
 *
 * 原来的实现用 `<[^>]+>` 一刀切掉所有标签，`<a href>` 和 `<img>` 一起被删，
 * 于是正文里只剩文字，链接和图片全丢了。这里分三步处理：
 *  1. `<a href>` 取出 URL，正文保留可见文字
 *  2. `<img src="cid:...">` 记下 Content-ID，交给上层去 MIME 结构里找原图
 *  3. 剩下的标签再剥离
 *
 * 远程图片（http/https）不自动下载：那是最常见的跟踪像素，
 * 下载即暴露"已读"，所以只把它当普通链接列出。
 */
internal object HtmlText {

    data class Result(
        /** 去掉标签后的纯文本 */
        val text: String,
        /** 正文里的链接，去重并按出现顺序 */
        val links: List<String>,
        /** `<img src="cid:xxx">` 引用的 Content-ID（已去尖括号） */
        val imageCids: List<String>,
    )

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a\\s*>")
    private val HREF = Regex("(?is)href\\s*=\\s*[\"']([^\"']+)[\"']")
    private val IMG = Regex("(?is)<img\\b([^>]*)>")
    private val SRC = Regex("(?is)src\\s*=\\s*[\"']([^\"']+)[\"']")
    private val ALT = Regex("(?is)alt\\s*=\\s*[\"']([^\"']*)[\"']")
    private val BREAK = Regex("(?i)<br\\s*/?>")
    private val BLOCK_END = Regex("(?i)</(p|div|tr|li|h[1-6])\\s*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val HORIZONTAL_SPACE = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    /** 只保留可以安全打开的链接；`javascript:`、`data:` 之类一律丢弃 */
    private fun isSafeLink(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("mailto:")
    }

    fun convert(html: String): Result {
        val links = LinkedHashSet<String>()
        val cids = LinkedHashSet<String>()

        // 1. 脚本、样式整体丢弃
        var s = SCRIPT_STYLE.replace(html, " ")

        // 2. 锚点：保留可见文字，收集 URL
        s = ANCHOR.replace(s) { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val href = HREF.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val visible = TAG.replace(inner, " ").trim()
            if (href.isNotEmpty() && isSafeLink(href)) links.add(href)
            // 可见文字为空（纯图标链接）时，用 URL 兜底，避免整段消失
            visible.ifBlank { if (isSafeLink(href)) href else "" }
        }

        // 3. 图片：cid 引用记下来，远程图片只当链接
        s = IMG.replace(s) { m ->
            val attrs = m.groupValues[1]
            val src = SRC.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val alt = ALT.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            when {
                src.startsWith("cid:", ignoreCase = true) -> {
                    cids.add(src.substring(4).trim().trim('<', '>'))
                    if (alt.isNotEmpty()) "[图片: $alt]" else "[图片]"
                }
                isSafeLink(src) -> {
                    // 远程图片不下载，仅作为链接暴露
                    links.add(src)
                    if (alt.isNotEmpty()) "[图片: $alt]" else ""
                }
                else -> if (alt.isNotEmpty()) "[图片: $alt]" else ""
            }
        }

        // 4. 剩余标签剥离
        s = BREAK.replace(s, "\n")
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, " ")

        // 5. 实体解码
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        // 6. 空白整理
        s = HORIZONTAL_SPACE.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = BLANK_LINES.replace(s, "\n\n")
        s = s.trim()

        return Result(
            text = s,
            links = links.toList(),
            imageCids = cids.toList(),
        )
    }
}
