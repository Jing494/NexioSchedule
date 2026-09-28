package com.haooz.chedule.data

import android.content.Context
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「返校」提醒侧的判定与文案（本文件**不再实现豁免本身**）。
 *
 * 假期最后一天整天算假期，课表解析为空、当天没有课前提醒；但学生通常在假期最后一天
 * 傍晚返校上晚自习。这条链路的两半现在分属两处，别再混：
 *
 * - **豁免本身（哪些课算正常课）**：上游的 `HolidayCourseExclusion` +
 *   `HolidayManager.load/saveEndCourseExclusion`，设置入口是假期设置页的
 *   「节假日末期课程排除」（开关 + 节次范围）。课表页/今日页的「假」标由它决定。
 * - **提醒本身（什么时候推、推什么词）**：本文件 + CourseReminderHelper。
 *   是否启用一律看 [isExclusionEnabled]（即上游那个开关）——
 *   本 fork 早期有一套自己的豁免偏好与设置卡，升基准后入口已删，
 *   再去读旧偏好会永远得到 false，表现为"开关开着、文案却按关着说"。
 *
 * 「返校日」定义：当天是休息日，且次日是上课日。
 * - 普通周日（次日周一上课）→ 命中；
 * - 假期最后一天（次日恢复上课）→ 命中；
 * - 周日之后仍是假期 → 周日不命中，真正的最后一天才命中；
 * - 调休补班（当天本来是周末但要上课）→ 当天是上课日，不命中。
 */
object ReturnDayReminder {

    /** 上课日：调休补班优先；其次看是否落在假期范围内；否则周一~周五。 */
    fun isSchoolDay(context: Context, date: LocalDate): Boolean {
        if (HolidayManager.workSwap(context, date) != null) return true
        if (HolidayManager.isHoliday(context, date)) return false
        return date.dayOfWeek.value <= 5
    }

    fun isRestDay(context: Context, date: LocalDate): Boolean = !isSchoolDay(context, date)

    /** 纯日期口径的返校日判定，**不含**功能总开关。做日期推算时用它。 */
    fun isReturnDay(context: Context, date: LocalDate): Boolean =
        isRestDay(context, date) && isSchoolDay(context, date.plusDays(1))

    /**
     * 「节假日末期课程排除」是否开启。
     *
     * 这是**上游设置页那个开关**（HolidayEndCourseExclusion.enabled）。本 fork 早期有一套
     * 自己的豁免偏好 + 自己的设置卡，升基准后入口已删（功能由上游接管），继续读那套偏好
     * 会永远读到 false —— 于是"开关开着、文案却按关着说"。
     */
    fun isExclusionEnabled(context: Context): Boolean =
        HolidayManager.loadEndCourseExclusion(context).enabled

    /**
     * 对外提醒口径的返校日：必须功能已开启。
     *
     * 修掉的一处逻辑漏洞：AlarmReceiver 与刷新链原来只调上面那个纯日期重载，
     * 于是用户把「节假日末期课程排除」关掉之后，「明天返校」的超级岛 / 实时动态照样会推。
     */
    fun isReturnDay(
        context: Context,
        repository: CourseRepository,
        date: LocalDate,
    ): Boolean = isExclusionEnabled(context) && isReturnDay(context, date)

    /** 今天所在的一段假期（名称 + 最后一天 + 剩余天数）。 */
    data class HolidaySpan(
        val name: String,
        val firstDate: LocalDate,
        val lastDate: LocalDate,
        val daysLeft: Int,
    ) {
        /** 这段假期一共几天。 */
        val totalDays: Int
            get() = ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1

        /** 今天是这段假期的第几天（1 起）。首日 = 1，末日 = [totalDays]。 */
        val dayIndex: Int
            get() = (totalDays - daysLeft).coerceIn(1, totalDays.coerceAtLeast(1))

        /** 假期进度（0~100），用于实时动态的进度条。单日假期给 0。 */
        val progressPercent: Int
            get() {
                val total = totalDays
                if (total <= 1) return 0
                val passed = (total - daysLeft).coerceIn(0, total)
                return (passed * 100 / total).coerceIn(0, 100)
            }
    }

    /**
     * 今天落在哪段假期里。只读一次假期数据，供「假期余额」文案使用。
     * 不在假期内（或数据缺失）返回 null。
     */
    fun currentHolidaySpan(context: Context, date: LocalDate): HolidaySpan? {
        val key = date.toString()
        var entry: HolidayManager.Entry? = null
        for (year in intArrayOf(date.year, date.year - 1)) {
            entry = HolidayManager.load(context, year).firstOrNull {
                it.type == HolidayManager.TYPE_HOLIDAY && it.matches(key)
            }
            if (entry != null) break
        }
        val hit = entry ?: return null
        val last = runCatching {
            LocalDate.parse(hit.endDate.ifBlank { hit.date })
        }.getOrNull() ?: return null
        if (last.isBefore(date)) return null
        val first = runCatching { LocalDate.parse(hit.date) }.getOrNull() ?: date
        return HolidaySpan(
            name = hit.name.ifBlank { "假期" },
            firstDate = if (first.isAfter(last)) last else first,
            lastDate = last,
            daysLeft = ChronoUnit.DAYS.between(date, last).toInt(),
        )
    }

    /** 「今天 / 明天 / 那天」——文案里写死"今天"会在次日提醒、次日小部件上直接说错日子。 */
    private fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "今天"
            1L -> "明天"
            -1L -> "昨天"
            else -> "这天"
        }

    /**
     * 今日页 / 小部件用的一句话状态。不适用时返回 null（调用方保持原样）。
     *
     * - 返校日（今天）→ "今天是中秋最后一天 · 距晚自习（18:30）还有 5 小时"
     * - 返校日（次日）→ "明天是中秋最后一天 · 晚自习 18:30 开始"
     * - 假期中（仅当天）→ "今天是中秋第 2 天 · 还剩 1 天"
     */
    fun statusText(
        context: Context,
        repository: CourseRepository,
        date: LocalDate,
        returnCourseName: String? = null,
        returnStartTime: String? = null,
    ): String? {
        val today = LocalDate.now()
        val isToday = date == today
        val span = currentHolidaySpan(context, date)
        // 返校文案：受「节假日末期课程排除」总开关管
        if (isExclusionEnabled(context) && isReturnDay(context, date)) {
            val label = dayLabel(date, today)
            // 注意：中文紧跟在 $label 后面会被当成标识符（$label是 不是 $label + 是），必须加花括号
            val where = if (span != null) "${label}是${span.name}最后一天" else "${label}是周末最后一天"
            val what = returnCourseName?.takeIf { it.isNotBlank() }
            val hasTime = what != null && !returnStartTime.isNullOrBlank()
            // 只有"今天"算倒计时才有意义（次日提醒等场景算出来是负数或几十小时）
            val remaining = if (isToday) remainingText(returnStartTime) else null
            return when {
                // 原句「距返校${课名}还有 N 小时」在没有课名时会变成「距返校还有」，
                // 有课名时又缺停顿；改成「距<课名>（18:30）还有 …」/「距返校还有 …」，用 · 分段。
                // 另外不足 1 小时原来向上取整成「1 小时」，起不到倒计时作用 —— 现在改说分钟。
                remaining != null && hasTime -> "$where · 距${what}（$returnStartTime）还有 $remaining"
                remaining != null -> "$where · 距返校还有 $remaining"
                hasTime -> "$where · $what $returnStartTime 开始"
                else -> "$where，返校啦"
            }
        }
        // 假期余额 / 假期进度：与总开关**解耦**（余额是假期信息，不是豁免功能的一部分）。
        // 但只在「当天」显示 —— 在次日小部件上按明天算余额会说成"还剩 N 天"，
        // 用户读到的是今天的信息，属于串味；非当天时返回 null 让调用方保持原样。
        //
        // 形态改成「今天是<假期名>第 N 天」，假期期间**每天**都显示，
        // 而不是只在最后一天说「今天是 X 最后一天」；剩余天数作为次要信息跟在后面。
        if (span != null && isToday) {
            val head = "今天是${span.name}第 ${span.dayIndex} 天"
            return if (span.daysLeft > 0) {
                "$head · 还剩 ${span.daysLeft} 天"
            } else {
                "$head · 最后一天"
            }
        }
        return null
    }

    /**
     * 距某个 "HH:mm" 还有多久，返回可直接拼进句子的短文案。
     * 不足 1 小时说分钟（倒计时的意义就在这里），否则给「N 小时 M 分钟」；
     * 已过或格式非法返回 null。
     */
    private fun remainingText(startTime: String?): String? {
        val minutes = minutesUntil(startTime) ?: return null
        if (minutes < 60) return "$minutes 分钟"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分钟"
    }

    /** 距某个 "HH:mm" 还有多少分钟；已过或格式非法返回 null。 */
    private fun minutesUntil(startTime: String?): Long? {
        val parts = startTime?.trim().orEmpty().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val target = runCatching {
            java.time.LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }.getOrNull() ?: return null
        val minutes = java.time.Duration.between(java.time.LocalTime.now(), target).toMinutes()
        if (minutes <= 0) return null
        return minutes
    }
}
