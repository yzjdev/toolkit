package app.toolkit.lanshare

import android.content.Context
import app.toolkit.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class LanShareState(
    val running: Boolean = false,
    val port: Int = LanShareServer.DEFAULT_PORT,
    val url: String? = null,
    val rootLabel: String = "",
    val error: String? = null,
    /** 当前是否要求访问者输入授权码 */
    val codeRequired: Boolean = false,
)

/**
 * 局域网 HTTP 文件服务。
 *
 * 手写一个极简 HTTP/1.1 服务端，不引入 Ktor/NanoHTTPD：
 * 只需要 5 个接口，用 ServerSocket 足够，也避免为了一个工具页多打包几百 KB 依赖。
 *
 * 协议约定：
 * - 上传走裸 body（`POST /api/upload?path=&name=`），浏览器用 XHR 直接发送 File 对象，
 *   既省掉 multipart 解析，也能拿到上传进度。
 * - 每个响应都带 `Connection: close`，连接用完即关，避免半开连接堆积。
 * - 所有路径都是相对根目录的虚拟路径，越界由 [TransferRoot] 拦截。
 */
object LanShareServer {

    const val DEFAULT_PORT = 8080

    /** 端口被占用时依次尝试的候选值 */
    val FALLBACK_PORTS = listOf(8080, 8081, 8082, 8090, 9090)

    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val IO_BUFFER = 64 * 1024

    private val _state = MutableStateFlow(LanShareState())
    val state: StateFlow<LanShareState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    // 4 个并发够用：局域网传输的瓶颈是 WiFi，不是 CPU
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "lanshare-io").apply { isDaemon = true }
    }

    fun isRunning(): Boolean = running.get()

    /**
     * 当前要求的授权码，null 表示不校验。
     *
     * 放在服务端而不是每次启动时传参：用户在 App 里改码时服务通常已经在跑，
     * 重新启动服务会让正在传输的连接全断，改个码不该有这个代价。
     */
    @Volatile
    private var accessCode: String? = null

    fun setAccessCode(code: String?) {
        accessCode = code?.takeIf { it.isNotEmpty() }
        _state.value = _state.value.copy(codeRequired = accessCode != null)
    }

    /**
     * 启动服务。端口被占用时按 [FALLBACK_PORTS] 依次重试，
     * 实际端口通过 [state] 的 `port` 返回。
     */
    fun start(context: Context, port: Int, root: TransferRoot): Result<Unit> {
        if (running.get()) return Result.success(Unit)
        val html = loadIndexHtml(context)
        val candidates = (listOf(port) + FALLBACK_PORTS).distinct()

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidate), 50)
                }
            } catch (t: Throwable) {
                // 端口被别的应用占用是常见情况，换下一个候选而不是直接失败
                lastError = t
                continue
            }

            serverSocket = socket
            running.set(true)
            val ip = localIpv4()
            val url = ip?.let { "http://$it:$candidate" }
            _state.value = LanShareState(
                running = true,
                port = candidate,
                url = url,
                rootLabel = root.label,
                // 授权码可能在服务启动前就设好了，重建状态时必须带上，
                // 否则状态里会显示成「不需要授权码」，与实际校验行为不一致
                codeRequired = accessCode != null,
            )
            LanShareLog.success(
                "服务已启动 ${url ?: "(未找到局域网 IPv4)"}，根目录 ${root.label}",
            )
            acceptJob = scope.launch { acceptLoop(socket, root, html) }
            return Result.success(Unit)
        }

        val message = lastError?.message ?: "端口不可用"
        _state.value = _state.value.copy(running = false, error = message)
        LanShareLog.error("启动失败：$message")
        return Result.failure(lastError ?: IllegalStateException(message))
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        _state.value = _state.value.copy(running = false, url = null)
        LanShareLog.info("服务已停止")
    }

    /**
     * 重新计算访问地址。
     *
     * 典型场景是先开服务、后连 WiFi（或中途切了网络），此时启动时算出的地址
     * 已经不对了，回到页面时重新取一次。
     */
    fun refreshAddress() {
        if (!running.get()) return
        val current = _state.value
        val url = localIpv4()?.let { "http://$it:${current.port}" }
        if (url == current.url) return
        _state.value = current.copy(url = url)
        LanShareLog.info(url?.let { "地址更新为 $it" } ?: "网络已断开，暂时没有可用地址")
    }

    private suspend fun acceptLoop(socket: ServerSocket, root: TransferRoot, html: String) {
        while (scope.isActive && running.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running.get()) LanShareLog.error("接受连接失败：${t.message ?: t.javaClass.simpleName}")
                break
            }
            workers.execute {
                try {
                    handle(client, root, html)
                } catch (t: Throwable) {
                    LanShareLog.error("请求处理失败：${t.message ?: t.javaClass.simpleName}")
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handle(client: Socket, root: TransferRoot, html: String) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream(), IO_BUFFER)
        val output = BufferedOutputStream(client.getOutputStream(), IO_BUFFER)

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeText(output, 400, "Bad Request", "bad request")
            return
        }
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]

        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                writeText(output, 431, "Header Fields Too Large", "header too large")
                return
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }

        val rawPath = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        // 页面本身永远放行：访问者要先拿到页面才能输入授权码。
        // 真正需要守住的是 /api/*，尤其是 download/delete 这些能直接动文件的接口。
        if (rawPath.startsWith("/api/") && !isAuthorized(headers, query)) {
            writeJsonError(output, 401, "需要授权码")
            return
        }

        when {
            method == "GET" && (rawPath == "/" || rawPath == "/index.html") ->
                writeBytes(
                    output,
                    200,
                    "OK",
                    "text/html; charset=utf-8",
                    html.toByteArray(Charsets.UTF_8),
                )

            method == "GET" && rawPath == "/api/list" -> handleList(output, root, query)
            method == "GET" && rawPath == "/api/download" -> handleDownload(output, root, query)
            method == "POST" && rawPath == "/api/upload" -> handleUpload(input, output, root, query, headers)
            method == "POST" && rawPath == "/api/mkdir" -> handleMkdir(output, root, query)
            method == "POST" && rawPath == "/api/delete" -> handleDelete(output, root, query)
            rawPath == "/favicon.ico" -> writeBytes(output, 204, "No Content", null, ByteArray(0))
            else -> writeText(output, 404, "Not Found", "not found")
        }
    }

    /**
     * 未开启授权码时一律放行。
     *
     * 开启后认两种携带方式：query 的 `code` 和 `X-Access-Code` 头。
     * 网页端统一用 query，因为下载是 `<a href>` 直连，设不了自定义头。
     */
    private fun isAuthorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val expected = accessCode ?: return true
        val provided = query["code"] ?: headers["x-access-code"] ?: return false
        return constantTimeEquals(provided, expected)
    }

    /** 授权码比较按位等长比完，避免用比较耗时去猜码。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun handleList(out: OutputStream, root: TransferRoot, query: Map<String, String>) {
        val path = parseVirtualPath(query["path"])
        if (path == null) {
            writeJsonError(out, 400, "路径不合法")
            return
        }
        if (!root.exists(path)) {
            writeJsonError(out, 404, "目录不存在")
            return
        }
        val array = JSONArray()
        root.list(path).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("path", entry.path)
                    put("dir", entry.directory)
                    put("size", entry.size)
                    put("mtime", entry.modified)
                },
            )
        }
        writeJson(
            out,
            JSONObject().apply {
                put("path", path)
                put("root", root.label)
                put("entries", array)
            },
        )
    }

    private fun handleDownload(out: OutputStream, root: TransferRoot, query: Map<String, String>) {
        val path = parseVirtualPath(query["path"])
        if (path == null) {
            writeJsonError(out, 400, "路径不合法")
            return
        }
        val size = root.size(path)
        // 个别文件（如受限目录里的特殊节点）取不到大小，此时 size 为 -1；
        // 只要文件真的存在就照常下发，用 close 定界而不是 Content-Length。
        if (size < 0 && !root.exists(path)) {
            writeJsonError(out, 404, "文件不存在")
            return
        }
        val stream = root.openRead(path)
        if (stream == null) {
            writeJsonError(out, 404, "无法读取文件")
            return
        }
        val name = segments(path).lastOrNull() ?: "download"
        stream.use {
            writeStream(
                out,
                200,
                "OK",
                mimeOf(name),
                size,
                mapOf("Content-Disposition" to contentDisposition(name)),
                it,
            )
        }
        LanShareLog.success("下载 $name（${if (size >= 0) formatSize(size) else "大小未知"}）")
    }

    private fun handleUpload(
        input: InputStream,
        out: OutputStream,
        root: TransferRoot,
        query: Map<String, String>,
        headers: Map<String, String>,
    ) {
        val dir = parseVirtualPath(query["path"])
        if (dir == null) {
            writeJsonError(out, 400, "路径不合法")
            return
        }
        val name = sanitizeFileName(query["name"])
        if (name == null) {
            writeJsonError(out, 400, "文件名不合法")
            return
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) {
            writeJsonError(out, 411, "缺少 Content-Length")
            return
        }
        val target = joinPath(dir, name)
        val sink = root.openWrite(dir, name, mimeOf(name))
        if (sink == null) {
            writeJsonError(out, 500, "无法写入文件")
            return
        }

        var written = 0L
        try {
            sink.use { fileOut ->
                val buffer = ByteArray(IO_BUFFER)
                while (written < length) {
                    val want = minOf(buffer.size.toLong(), length - written).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    fileOut.write(buffer, 0, read)
                    written += read
                }
            }
        } catch (t: Throwable) {
            runCatching { root.delete(target) }
            writeJsonError(out, 500, "上传中断：${t.message ?: "连接错误"}")
            return
        }

        if (written != length) {
            // 半截文件没有意义，直接删掉，避免用户以为传完了
            runCatching { root.delete(target) }
            writeJsonError(out, 400, "上传不完整（$written/$length 字节）")
            return
        }

        LanShareLog.success("上传 $name（${formatSize(written)}）")
        writeJson(
            out,
            JSONObject().apply {
                put("ok", true)
                put("path", target)
                put("size", written)
            },
        )
    }

    private fun handleMkdir(out: OutputStream, root: TransferRoot, query: Map<String, String>) {
        val dir = parseVirtualPath(query["path"])
        val name = sanitizeFileName(query["name"])
        if (dir == null || name == null) {
            writeJsonError(out, 400, "参数不合法")
            return
        }
        val target = joinPath(dir, name)
        if (root.exists(target)) {
            writeJsonError(out, 409, "同名文件夹已存在")
            return
        }
        if (!root.createDir(target)) {
            writeJsonError(out, 500, "创建失败，可能是当前目录不支持写入")
            return
        }
        LanShareLog.success("新建文件夹 $name")
        writeJson(
            out,
            JSONObject().apply {
                put("ok", true)
                put("path", target)
            },
        )
    }

    private fun handleDelete(out: OutputStream, root: TransferRoot, query: Map<String, String>) {
        val path = parseVirtualPath(query["path"])
        if (path == null) {
            writeJsonError(out, 400, "路径不合法")
            return
        }
        if (path == "/") {
            writeJsonError(out, 400, "不能删除根目录")
            return
        }
        if (!root.exists(path)) {
            writeJsonError(out, 404, "目标不存在")
            return
        }
        if (!root.delete(path)) {
            writeJsonError(out, 500, "删除失败")
            return
        }
        LanShareLog.success("删除 ${segments(path).lastOrNull() ?: path}")
        writeJson(out, JSONObject().apply { put("ok", true) })
    }

    // ---- HTTP 基础 ----

    private fun readLine(input: InputStream, limit: Int = 8 * 1024): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (byte == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            if (buffer.size() >= limit) return null
            buffer.write(byte)
        }
    }

    private fun writeText(out: OutputStream, status: Int, reason: String, message: String) {
        writeBytes(out, status, reason, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8))
    }

    private fun writeJson(out: OutputStream, json: JSONObject, status: Int = 200) {
        val reason = if (status == 200) "OK" else "Error"
        writeBytes(out, status, reason, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeJsonError(out: OutputStream, status: Int, message: String) {
        writeJson(out, JSONObject().apply { put("error", message) }, status)
    }

    private fun writeBytes(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String?,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        extra.forEach { (key, value) -> head.append(key).append(": ").append(value).append("\r\n") }
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun writeStream(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        length: Long,
        extra: Map<String, String>,
        body: InputStream,
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        // 长度未知时不给 Content-Length，靠 Connection: close 定界
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Accept-Ranges: none\r\n")
        extra.forEach { (key, value) -> head.append(key).append(": ").append(value).append("\r\n") }
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        body.copyTo(out, IO_BUFFER)
        out.flush()
    }

    /** 中文文件名必须同时给 filename 与 filename*，否则部分浏览器会存成乱码。 */
    private fun contentDisposition(name: String): String {
        val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = if (pair.contains('=')) {
                URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            } else {
                ""
            }
            result[key] = value
        }
        return result
    }

    private fun loadIndexHtml(context: Context): String = runCatching {
        context.resources.openRawResource(R.raw.lanshare).bufferedReader().use { it.readText() }
    }.getOrElse {
        "<!doctype html><meta charset=\"utf-8\"><title>局域网传输</title>" +
            "<p>页面资源缺失，服务仍在运行：/api/list /api/download /api/upload"
    }

    /**
     * 取当前局域网 IPv4。优先 wlan 接口：开热点/双栈时其它接口也可能是站点本地地址，
     * 但手机访问文件走的基本都是 WiFi。
     */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<String, String>>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                Collections.list(nif.inetAddresses).forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        address.hostAddress?.let { candidates += nif.name to it }
                    }
                }
            }
        }
        return candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull()?.second
    }
}
