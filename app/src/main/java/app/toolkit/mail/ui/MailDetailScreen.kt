package app.toolkit.mail.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import app.toolkit.R
import app.toolkit.mail.data.MailMessage
import java.io.File

/** 邮件详情：可折叠标题栏 + 正文。有 HTML 时用 WebView 渲染，否则退回纯文本。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDetailScreen(
    mail: MailMessage,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    // 官方可折叠标题栏：MediumTopAppBar + exitUntilCollapsedScrollBehavior。
    // 用 Medium 而不是 Large：Large 展开态固定 152dp，导航栏占 64dp、标题底部对齐，
    // 单行标题时中间会空出一大片；Medium 展开 112dp，留白明显减少。
    // （material3 1.4.0 的 LargeFlexibleTopAppBar 带 subtitle 但标记为 internal，
    //   外部无法调用；升级到 1.5.0-rc01 才能用，代价是体积 +10MB 和一批资源警告。）
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )
    val contentScroll = rememberScrollState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    // 折叠过半后标题只留一行，超出部分尾部省略；
                    // 展开态给两行，长主题也能看全。
                    // collapsedFraction 由 TopAppBarState 的状态支撑，读它会触发重组。
                    val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
                    Text(
                        text = mail.subject,
                        maxLines = if (collapsed) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    // 只有保存了原始报文时才显示导出入口
                    if (mail.rawPath.isNotBlank()) {
                        IconButton(onClick = { exportRaw(context, mail) }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.mail_raw_export),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        // 正文放在滚动容器里；折叠完全由标题栏的嵌套滚动驱动，没有滚动回调。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(contentScroll),
        ) {
            // 发件人与时间：标题栏只放主题，这里补上其余元信息，不重复主题。
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = mail.senderLabel,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = mail.fromAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                MetaRow(stringResource(R.string.mail_date), formatFullTime(mail.receivedAt))
                if (mail.toAddress.isNotBlank()) {
                    MetaRow(stringResource(R.string.mail_to), mail.toAddress)
                }
                MetaRow(stringResource(R.string.mail_account), mail.accountLabel)
            }
            HorizontalDivider()

            if (mail.html.isNotBlank()) {
                HtmlBody(
                    html = mail.html,
                    onOpenLink = openLink,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(16.dp))
                    BodyText(mail = mail, onOpenLink = openLink)
                    if (mail.images.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        InlineImages(mail = mail)
                    }
                    if (mail.links.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        LinkSection(links = mail.links, onOpenLink = openLink)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * 用 WebView 渲染原始 HTML。
 *
 * 为什么必须允许网络图片：实测邮件（如 FT 中文网）的标题 logo 是远程资源，
 * 只把 HTML 转纯文本会丢掉全部 CSS 排版和图片，效果与原客户端差很远。
 *
 * 安全约束：
 *  - 关闭 JavaScript、DOM 存储，杜绝邮件里的脚本执行
 *  - 内嵌图片走本地 `file://`（同步时已把 cid 改写成私有目录路径）
 *  - 链接一律不在 WebView 内跳转，交给系统浏览器
 */
@Composable
private fun HtmlBody(
    html: String,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    // WebView 自身不滚动：把它撑到内容真实高度，外层 Compose 滚动容器统一负责滚动。
    // 这样标题栏折叠完全由官方嵌套滚动驱动，不需要任何滚动回调。
    var contentHeightPx by remember { mutableStateOf(0) }

    val document = remember(html, textColor, linkColor) {
        wrapHtml(html, textColor, linkColor)
    }

    // 邮件声明的布局宽度（CSS 像素）。开启 overview 缩放后，整页按
    // 「WebView 实际宽度 / 这个宽度」的比例缩小，测高时要用同一个比例换算。
    val contentWidth = remember(html) { detectContentWidth(html) }

    // baseUrl 必须是 file:// 来源，否则 WebView 视内容为不透明来源，
    // 会拒绝加载 file:// 的内嵌图片（同步时已把 cid 改写成私有目录路径）。
    val baseUrl = remember(context) {
        "file://" + context.filesDir.absolutePath + "/"
    }

    // 用数组做持有者：factory 里赋值，DisposableEffect 里读取销毁。
    // 不能用普通 var，重组时会被 remember{null} 重置成 null，导致 WebView 泄漏。
    val holder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                holder[0]?.stopLoading()
                holder[0]?.destroy()
            }
            holder[0] = null
        }
    }

    AndroidView(
        modifier = modifier
            // 高度还没测出来时先占位，避免闪一下空白再跳
            .then(
                if (contentHeightPx > 0) {
                    Modifier.height(with(density) { contentHeightPx.toDp() })
                } else {
                    Modifier.height(1.dp)
                }
            ),
        factory = { ctx ->
            WebView(ctx).apply {
                holder[0] = this
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = false
                    domStorageEnabled = false
                    // 必须允许网络图片：实测邮件里的 logo（如 FT 中文网标题图）是远程资源，
                    // 禁掉就只剩文字，效果与原客户端对不上。只放行图片，脚本仍禁用。
                    blockNetworkLoads = false
                    blockNetworkImage = false
                    loadsImagesAutomatically = true
                    // file:// 页面加载 http 图片时避免被判为混合内容而拦掉
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // 内嵌图片是本地 file:// 路径，必须允许文件访问
                    allowFileAccess = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    allowContentAccess = false
                    useWideViewPort = true
                    // 关键：配合注入的 viewport 宽度，把整页等比缩放到屏幕宽，
                    // 邮件原本的表格布局原样保留，只是整体缩小（与 QQ 邮箱一致）
                    loadWithOverviewMode = true
                    // 固定 100%：否则会跟随系统字体大小设置，把邮件正文整体放大
                    textZoom = 100
                    // 自身不滚动：高度已撑到内容高度，缩放交给外层容器统一处理
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        val lower = url.lowercase()
                        if (lower.startsWith("http://") ||
                            lower.startsWith("https://") ||
                            lower.startsWith("mailto:")
                        ) {
                            onOpenLink(url)
                        }
                        // 返回 true：任何跳转都不在 WebView 内发生
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // 图片异步加载，contentHeight 会随后增长。
                        // 固定时间点测不可靠，改成轮询到高度稳定为止。
                        measureUntilStable(view, contentWidth, 0) { height ->
                            contentHeightPx = height
                        }
                    }
                }
                loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            // html 变化（例如从列表占位切到详情）时重新加载
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * 轮询 WebView 内容高度，直到连续两次测量一致（或超过次数上限），再回传设备像素。
 *
 * 不能只测一次：图片和字体都会让内容高度在 onPageFinished 之后继续增长，
 * 单次测量会把 WebView 定得过矮，导致正文被截断。
 *
 * [cssWidth] 是邮件声明的布局宽度。开启 overview 缩放后页面被等比缩小，
 * contentHeight（CSS 像素）必须乘同一个比例才等于屏幕上的设备像素。
 */
private fun measureUntilStable(
    view: WebView,
    cssWidth: Int,
    attempt: Int,
    previous: Int = -1,
    onStable: (Int) -> Unit,
) {
    if (attempt > 30) {
        // 兜底：测不出稳定值时用当前值，避免一直停在 1dp
        if (previous > 0) onStable(previous)
        return
    }
    view.postDelayed({
        // 有声明宽度时，页面按 view.width / cssWidth 缩放；
        // 没有声明宽度时 viewport 是 device-width，1 CSS px 等于 1 dp。
        val factor = if (cssWidth > 0 && view.width > 0) {
            view.width.toFloat() / cssWidth
        } else {
            view.resources.displayMetrics.density
        }
        val height = (view.contentHeight * factor).toInt()
        if (height > 0 && height == previous) {
            onStable(height)
        } else {
            measureUntilStable(view, cssWidth, attempt + 1, height, onStable)
        }
    }, 100L)
}

/**
 * 给邮件 HTML 套一层外壳。
 *
 * 核心原则：**不动邮件自己的样式**。邮件用固定像素宽的表格做布局（实测 FT 是
 * `#containerTable{width:720px}`，55 个 table、最多 6 层嵌套、127 处 !important），
 * 强行改宽度或剥离 !important 会把整套排版压扁，效果和 QQ 邮箱对不上。
 *
 * 正确做法与 QQ 邮箱一致：探测邮件声明的宽度，注入对应的 viewport，
 * 让 WebView 把整页**等比缩放**到屏幕宽——布局原样保留，只是整体缩小。
 */
private fun wrapHtml(body: String, textColor: Int, linkColor: Int): String {
    val text = String.format("#%06X", 0xFFFFFF and textColor)
    val link = String.format("#%06X", 0xFFFFFF and linkColor)

    var clean = body
    // 邮件自带的 viewport 会干扰缩放，先剥掉，用我们探测出的宽度代替
    clean = META_VIEWPORT.replace(clean, "")
    // 没有 src 或尺寸为 0 的 img 会渲染成破图图标，删掉（含追踪像素）
    clean = IMG_NO_SRC.replace(clean, "")
    clean = IMG_EMPTY_SRC.replace(clean, "")
    clean = IMG_ZERO_SIZE.replace(clean, "")
    // 相对路径图片：文档基址是 file://，这类 src 会解析成本地不存在的文件，
    // 必然加载失败显示破图，直接删掉
    clean = IMG_RELATIVE_SRC.replace(clean, "")
    // 剥掉文档骨架标签，由我们统一包裹
    clean = DOC_SKELETON.replace(clean, "")
    clean = DOCTYPE.replace(clean, "")
    // 注意：这里**不剥离** !important，也不改 table 宽度——那会破坏邮件排版

    // 探测邮件声明的布局宽度，作为 viewport 宽度，交给 WebView 等比缩放
    val declaredWidth = detectContentWidth(clean)
    val viewport = if (declaredWidth > 0) {
        """<meta name="viewport" content="width=$declaredWidth, user-scalable=no">"""
    } else {
        """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
    }

    return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        $viewport
        </head><body>
        $clean
        <style>
          /* 只做最小干预：去掉外边距、跟随主题文字色、链接用主题色。
             宽度和表格布局一律交给邮件自己的 CSS。 */
          html, body {
            margin: 0 !important;
            padding: 0 !important;
            background: transparent !important;
            -webkit-text-size-adjust: 100% !important;
          }
          body {
            padding: 12px !important;
            color: $text;
            font-family: system-ui, -apple-system, "Noto Sans CJK SC", sans-serif;
          }
          /* 超大图片收进容器，避免撑破布局（邮件自身通常已有 width 属性） */
          img { max-width: 100%; height: auto; }
          a { color: $link; }
        </style>
        </body></html>
    """.trimIndent()
}

/**
 * 探测邮件声明的布局宽度。
 *
 * 邮件模板普遍写成固定像素宽（`<table width="720px">` 或 `width: 720px`），
 * 取这些值里最大的合理值作为整页宽度，WebView 据此等比缩放。
 * 找不到时返回 0，回退到 device-width。
 */
private fun detectContentWidth(html: String): Int {
    val candidates = ArrayList<Int>()
    TABLE_WIDTH_ATTR.findAll(html).forEach { m ->
        m.groupValues[1].toIntOrNull()?.let { candidates.add(it) }
    }
    STYLE_WIDTH_PX.findAll(html).forEach { m ->
        m.groupValues[1].toIntOrNull()?.let { candidates.add(it) }
    }
    // 只接受手机到平板之间的合理值，避免把 1px 分隔线或 9999 当成布局宽度
    return candidates.filter { it in 280..1400 }.maxOrNull() ?: 0
}

/** `<table ... width="720px">` 或 `width=720` */
private val TABLE_WIDTH_ATTR =
    Regex("(?i)<table\\b[^>]*\\bwidth\\s*=\\s*[\"']?(\\d{2,4})(?:px)?[\"']?")

/** CSS 里的 `width: 720px` */
private val STYLE_WIDTH_PX = Regex("(?i)\\bwidth\\s*:\\s*(\\d{2,4})\\s*px")

/** 邮件自带 viewport，可能与注入的冲突 */
private val META_VIEWPORT =
    Regex("(?is)<meta[^>]*name\\s*=\\s*[\"']?viewport[\"']?[^>]*>")

/** `<img>` 完全没有 src 属性：会渲染成破图图标 */
private val IMG_NO_SRC = Regex("(?is)<img\\b(?![^>]*\\bsrc\\s*=)[^>]*>")

/**
 * `<img src="">` / `src=''` / `src=`（无引号空值）：同样会破图。
 * 属性可能不带引号，所以单独处理这几种写法。
 */
private val IMG_EMPTY_SRC =
    Regex("(?is)<img\\b[^>]*\\bsrc\\s*=\\s*(?=[\\s/>]|[\"']\\s*[\"'])[^>]*>")

/**
 * 尺寸为 0 的图片，典型是**追踪像素**。
 *
 * 实测 FT 邮件里有这样一个标签（属性不带引号）：
 * `<img src=http://newsletters.ftimg.online/s.html?...&email=xxx@qq.com border=0 width=0>`
 * 它的 src 指向一个 HTML 页面而不是图片，必然加载失败 → 显示破图图标；
 * 而且请求里带着收件人邮箱，是隐私泄漏点。属性无引号，所以必须按尺寸识别。
 */
private val IMG_ZERO_SIZE =
    Regex("(?is)<img\\b[^>]*\\b(?:width|height)\\s*=\\s*[\"']?0(?:px)?[\"']?(?=[\\s/>])[^>]*>")

/**
 * 相对路径图片（`src="logo.png"`、`src="/images/a.png"`）。
 *
 * 文档基址是 file://，这类地址会被解析成本地不存在的文件，
 * 必然加载失败显示破图。只有 cid:/http/https/data 这几种能正常加载。
 */
private val IMG_RELATIVE_SRC = Regex(
    "(?is)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?" +
        "(?!(?:cid|https?|data)\\s*:)(?!//)" +
        "[^\"'>\\s]+[^>]*>"
)

/** 文档骨架标签，由外壳统一提供 */
private val DOC_SKELETON = Regex("(?is)</?(html|body)\\b[^>]*>")
private val DOCTYPE = Regex("(?is)<!doctype[^>]*>")

/**
 * 纯文本正文。把正文里出现的链接做成可点击区间。
 * 只有邮件没有 HTML 部分时才走这里。
 */
@Composable
private fun BodyText(mail: MailMessage, onOpenLink: (String) -> Unit) {
    val body = mail.body
    if (body.isBlank()) {
        Text(
            text = stringResource(R.string.mail_body_empty),
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    )
    val color = MaterialTheme.colorScheme.primary

    val annotated = remember(body, mail.links, color) {
        buildAnnotatedString {
            var cursor = 0
            // 按出现位置排序后逐段切分，正文里同一链接可能出现多次
            val hits = ArrayList<Pair<Int, String>>()
            mail.links.forEach { url ->
                var from = 0
                while (true) {
                    val at = body.indexOf(url, from, ignoreCase = true)
                    if (at < 0) break
                    hits.add(at to url)
                    from = at + url.length
                }
            }
            hits.sortBy { it.first }

            hits.forEach { (start, url) ->
                if (start < cursor) return@forEach
                if (start > cursor) append(body.substring(cursor, start))
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = linkStyle,
                        linkInteractionListener = { onOpenLink(url) },
                    )
                ) {
                    append(body.substring(start, start + url.length))
                }
                cursor = start + url.length
            }
            if (cursor < body.length) append(body.substring(cursor))
        }
    }

    Text(
        text = annotated,
        modifier = Modifier.padding(horizontal = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 纯文本邮件的内嵌图片：按原始宽高比铺满宽度。 */
@Composable
private fun InlineImages(mail: MailMessage) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.mail_images),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        mail.images.forEach { img ->
            val bitmap = remember(img.path) { decodeLocalImage(img.path) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/** 解码本地图片；文件缺失或损坏时返回 null，界面直接跳过。 */
private fun decodeLocalImage(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.exists()) return null
    BitmapFactory.decodeFile(path)?.asImageBitmap()
}.getOrNull()

/** 链接列表：正文里可能有多个链接，逐个列出方便点开。 */
@Composable
private fun LinkSection(links: List<String>, onOpenLink: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.mail_links),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        links.forEach { url ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLink(url) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.height(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 导出服务器返回的原始报文（.eml）。
 *
 * 文件在应用私有目录，直接给外部应用 Uri 会被拒绝，必须经 FileProvider 授权。
 * 用 ACTION_SEND 让用户自己选目标应用（邮件客户端、文件管理器、聊天工具等）。
 */
private fun exportRaw(context: Context, mail: MailMessage) {
    val file = File(mail.rawPath)
    if (!file.exists()) return

    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, mail.subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.mail_raw_share_title))
        )
    }
}
