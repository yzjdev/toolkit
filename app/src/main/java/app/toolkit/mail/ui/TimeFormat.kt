package app.toolkit.mail.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 列表里的时间：今天显示时分，今年显示月日，更早显示年月日。 */
fun formatListTime(millis: Long): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val locale = Locale.getDefault()

    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", locale).format(Date(millis))

        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", locale).format(Date(millis))

        else -> SimpleDateFormat("yyyy年M月d日", locale).format(Date(millis))
    }
}

/** 详情页与账号页用的完整时间。 */
fun formatFullTime(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
}
