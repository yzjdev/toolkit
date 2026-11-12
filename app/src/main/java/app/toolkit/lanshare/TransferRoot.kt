package app.toolkit.lanshare

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 浏览器看到的一个目录项。路径是相对根目录的虚拟绝对路径，例如 /照片/1.jpg */
data class TransferEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * HTTP 服务可访问的根目录。
 *
 * 所有路径都是相对根的虚拟绝对路径，实现方负责拦截越界访问。
 */
interface TransferRoot {

    /** 展示给用户看的位置描述 */
    val label: String

    fun list(path: String): List<TransferEntry>

    fun size(path: String): Long

    fun exists(path: String): Boolean

    fun openRead(path: String): InputStream?

    fun openWrite(parent: String, name: String, mime: String): OutputStream?

    fun createDir(path: String): Boolean

    fun delete(path: String): Boolean
}

/**
 * 直接文件系统访问，根目录是外部存储（/storage/emulated/0）。
 *
 * 依赖「所有文件访问权限」；Android 11+ 的 Android/data、Android/obb
 * 即使有这个权限也不可读写，列目录时会被系统挡掉，按空目录处理即可。
 */
class FileTransferRoot(private val base: File) : TransferRoot {

    override val label: String get() = base.absolutePath

    override fun list(path: String): List<TransferEntry> {
        val dir = resolve(path)?.takeIf { it.isDirectory } ?: return emptyList()
        // 受限目录 listFiles 会返回 null，直接当空目录
        val children = dir.listFiles() ?: return emptyList()
        return children.map { file ->
            TransferEntry(
                name = file.name,
                path = joinPath(path, file.name),
                directory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                modified = file.lastModified(),
            )
        }.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
    }

    override fun size(path: String): Long = resolve(path)?.takeIf { it.isFile }?.length() ?: -1L

    override fun exists(path: String): Boolean = resolve(path)?.exists() == true

    override fun openRead(path: String): InputStream? =
        runCatching { resolve(path)?.takeIf { it.isFile }?.inputStream() }.getOrNull()

    override fun openWrite(parent: String, name: String, mime: String): OutputStream? = runCatching {
        val dir = resolve(parent) ?: return null
        if (!dir.isDirectory) return null
        // 同名文件直接覆盖，避免浏览器端出现 "文件 (1).txt"
        File(dir, name).outputStream()
    }.getOrNull()

    override fun createDir(path: String): Boolean = runCatching {
        val dir = resolve(path) ?: return false
        dir.isDirectory || dir.mkdirs()
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching {
        val target = resolve(path) ?: return false
        if (target == base.canonicalFile) return false
        target.deleteRecursively()
    }.getOrDefault(false)

    /** 把虚拟路径解析成真实文件，越界（.. 或软链逃逸）一律返回 null。 */
    private fun resolve(path: String): File? {
        val relative = path.trim('/')
        val target = if (relative.isEmpty()) base else File(base, relative)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { base.canonicalFile }.getOrNull() ?: return null
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) return null
        return canonical
    }
}

/** 路径规范化：折叠重复斜杠，拒绝 .. 越界；不合法返回 null。 */
fun parseVirtualPath(raw: String?): String? {
    val text = (raw ?: "/").trim()
    if (text.any { it == '\u0000' }) return null
    val segments = segments(text)
    if (segments.any { it == ".." }) return null
    return "/" + segments.joinToString("/")
}

fun segments(path: String): List<String> =
    path.split('/').filter { it.isNotEmpty() && it != "." }

fun joinPath(parent: String, name: String): String {
    val base = parent.trimEnd('/')
    return "$base/$name"
}

/** 上传文件名只保留最后一段，挡掉路径注入。 */
fun sanitizeFileName(raw: String?): String? {
    val name = raw?.trim()?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    if (name.isEmpty() || name == "." || name == ".." || name.any { it == '\u0000' }) return null
    return name
}

/** 字节数转人类可读，日志与界面共用。 */
fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}

/** 下载/上传用的 MIME 推断，够用即可。 */
fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "log", "md" -> "text/plain"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "text/javascript"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "application/octet-stream"
}
