package app.toolkit.lanshare

import android.content.Context
import java.security.SecureRandom

/** 授权码设置：关闭时局域网内任何人都能直接访问。 */
data class AccessCode(
    val enabled: Boolean = false,
    val code: String = "",
) {
    /**
     * 真正下发给服务端做校验的码。
     *
     * 开关打开但码还没填好（不足 4 位）时返回 null，按「不校验」处理：
     * 与其让用户被自己没设完的码锁在门外，不如先放行。
     */
    fun effectiveCode(): String? =
        if (enabled && LanShareAccessCode.isValid(code)) code else null
}

/**
 * 授权码的本地存储与生成。
 *
 * 只用 SharedPreferences：这是一个开关加一个短字符串，没有查询需求，
 * 引入数据库或 DataStore 都是多余的。
 */
object LanShareAccessCode {

    private const val PREFS = "lanshare"
    private const val KEY_ENABLED = "access_code_enabled"
    private const val KEY_CODE = "access_code"

    /** 授权码固定 4 位纯数字：手机端好念、浏览器端好输，不用切输入法。 */
    const val LENGTH = 4

    private const val DIGITS = "0123456789"

    private val secureRandom = SecureRandom()

    /** 是否是合法的授权码：4 位且全是数字。 */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in '0'..'9' }

    /** 从任意输入里提取授权码：只留数字，最多 4 位。 */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }.take(LENGTH)

    fun load(context: Context): AccessCode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AccessCode(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            code = prefs.getString(KEY_CODE, "").orEmpty(),
        )
    }

    fun save(context: Context, value: AccessCode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_CODE, value.code)
            .apply()
    }

    /** 生成一个 4 位数字授权码。 */
    fun random(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(DIGITS[secureRandom.nextInt(DIGITS.length)]) }
    }
}
