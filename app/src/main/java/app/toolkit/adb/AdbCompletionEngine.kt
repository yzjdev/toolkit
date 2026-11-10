package app.toolkit.adb

/** 补全候选的类型。 */
enum class AdbSuggestionKind {
    /** 真实可执行的命令或参数 */
    COMMAND,

    /** 参数占位提示，例如 `<包名>`，仅用于提示用法 */
    HINT,
}

/**
 * 一条补全候选。
 */
data class AdbSuggestion(
    /** 选中后填入输入框的完整命令 */
    val text: String,
    /** 候选本身展示的名字（不带前缀） */
    val label: String,
    val description: String,
    val kind: AdbSuggestionKind,
    /**
     * 该命令是否必须带参数才能执行。
     *
     * 依据命令定义里是否存在必填占位符（`<...>`）判定：
     * 只有可选参数（`-r`、`[路径]`）或完全无参的命令为 false。
     * 用于在候选列表里提示用户「这条还得继续输入」。
     */
    val requiresArgument: Boolean = false,
)

/**
 * adb 命令补全引擎。
 *
 * 按光标位置把输入切成「已完成的分词 + 当前正在输入的分词」，
 * 沿 [AdbCompletions] 的命令树逐层下钻，再用当前分词做前缀匹配。
 *
 * 几条刻意的设计：
 * - `adb` 前缀可有可无，补全结果会保留用户已经输入的写法；
 * - 根层级同时给出顶层命令与 shell 子命令（`pm`、`am` 等），
 *   因为两者在本应用里都能直接执行；
 * - 当前分词以 `-` 开头时只匹配参数，避免把命令名混进选项列表；
 * - 命令树走到叶子时给出参数占位提示，帮助回忆用法。
 */
object AdbCompletionEngine {

    /**
     * 候选数量上限。
     *
     * 取一个足够大的值而不是直接去掉限制：命令树规模有限（百余条），
     * 这里只是给「列表无限膨胀」留个兜底，实际不会触到。
     */
    private const val MAX_SUGGESTIONS = 500

    private val WHITESPACE = Regex("\\s+")

    /**
     * 计算补全候选。
     *
     * @param input 输入框当前内容
     * @param cursor 光标位置（0 到 input.length）
     */
    fun suggest(input: String, cursor: Int = input.length): List<AdbSuggestion> {
        val upToCursor = input.take(cursor.coerceIn(0, input.length))
        // 空输入时 partial 为空串，matches 会放行全部候选，等价于列出全部命令
        val endsWithSpace = upToCursor.isNotEmpty() && upToCursor.last().isWhitespace()
        val tokens = upToCursor.trim().split(WHITESPACE).filter { it.isNotEmpty() }

        var completed = if (endsWithSpace) tokens else tokens.dropLast(1)
        var partial = if (endsWithSpace) "" else tokens.lastOrNull().orEmpty()

        // 处理可选的 adb 前缀，补全结果保持用户原本的写法
        var prefix = ""
        when {
            completed.firstOrNull()?.equals("adb", ignoreCase = true) == true -> {
                completed = completed.drop(1)
                prefix = "adb "
            }
            partial.equals("adb", ignoreCase = true) -> {
                partial = ""
                prefix = "adb "
            }
        }

        return candidates(completed, partial, prefix, MAX_SUGGESTIONS)
    }

    /**
     * 沿命令树下钻到当前层级，再生成候选。
     *
     * @param completed 已经输入完整的分词
     * @param partial 正在输入的分词
     * @param prefix 需要回填的前缀（`adb ` 或空）
     */
    private fun candidates(
        completed: List<String>,
        partial: String,
        prefix: String,
        limit: Int,
    ): List<AdbSuggestion> {
        var level = rootLevel()
        var current: AdbCommandSpec? = null

        for (token in completed) {
            val matched = level.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: return emptyList() // 已经输入的内容不在命令树里，不再猜测
            current = matched
            level = matched.children
        }

        // 叶子节点没有子命令，改为给出参数占位提示
        val source: List<AdbSuggestion> = if (level.isEmpty()) {
            (current?.placeholders ?: emptyList()).map { placeholder ->
                AdbSuggestion(
                    text = buildText(prefix, completed, placeholder),
                    label = placeholder,
                    description = current?.description.orEmpty(),
                    kind = if (placeholder.startsWith("-")) {
                        AdbSuggestionKind.COMMAND
                    } else {
                        AdbSuggestionKind.HINT
                    },
                )
            }
        } else {
            level.map { spec ->
                AdbSuggestion(
                    text = buildText(prefix, completed, spec.name),
                    label = spec.name,
                    description = spec.description,
                    kind = AdbSuggestionKind.COMMAND,
                    requiresArgument = spec.requiresArgument(),
                )
            }
        }

        return source
            .asSequence()
            .filter { matches(it.label, partial) }
            // 纯字母序，大小写不敏感；长度不参与排序，避免短命令插进字母序中间
            .sortedWith(compareBy { it.label.lowercase() })
            .take(limit)
            .toList()
    }

    /**
     * 前缀匹配。
     *
     * 当前分词以 `-` 开头时只接受参数，避免命令名混进选项列表；
     * 其余情况做大小写不敏感的前缀匹配。
     */
    private fun matches(label: String, partial: String): Boolean {
        if (partial.isEmpty()) return true
        if (partial.startsWith("-")) {
            return label.startsWith("-") && label.startsWith(partial, ignoreCase = true)
        }
        return label.startsWith(partial, ignoreCase = true)
    }

    private fun buildText(prefix: String, completed: List<String>, next: String): String {
        val head = completed.joinToString(" ")
        return buildString {
            append(prefix)
            if (head.isNotEmpty()) {
                append(head)
                append(' ')
            }
            append(next)
        }
    }

    /**
     * 根层级候选：顶层 adb 命令 + shell 子命令。
     *
     * 本应用里 `pm list packages` 与 `adb shell pm list packages` 等价，
     * 所以把 shell 子命令也提到根层级，少打一层。
     */
    private fun rootLevel(): List<AdbCommandSpec> =
        AdbCompletions.root + AdbCompletions.shellSubcommands
}
