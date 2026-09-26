package com.haooz.chedule.data

import android.content.Context
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 周末 / 节假日「最后一天」的返校节次豁免。
 *
 * 背景：假期数据会把假期最后一天整天算作假期，
 * [com.haooz.chedule.reminder.CourseReminderHelper.resolveDaySchedule] 直接返回空课表，
 * 于是当天不会有任何课前提醒。但学生通常在假期最后一天傍晚返校上晚自习。
 *
 * 做法（豁免式，而不是造课）：
 * 在「返校日」把用户指定的节次**从假期清空里豁免出来** ——
 * 取当天课表里落在这些节次的**真实课程**。这样：
 * - 是课表里真实存在的课，点进详情页正常，不会出现空白页；
 * - 走既有提醒链路（课前闹钟 / 今日页 / 小部件 / 次日提醒 / 超级岛）自动生效；
 * - 节次里没有课就什么都不显示，不会凭空造出「幽灵课」。
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
     * 对外提醒口径的返校日：必须功能已开启。
     *
     * 修掉的一处逻辑漏洞：AlarmReceiver 与刷新链原来只调上面那个纯日期重载，
     * 于是用户把「返校节次豁免」关掉之后，「明天返校」的超级岛 / 实时动态照样会推。
     */
    fun isReturnDay(
        context: Context,
        repository: CourseRepository,
        date: LocalDate,
    ): Boolean = repository.getReturnDayReminder() && isReturnDay(context, date)

    /**
     * 返校日当天不被假期清空的节次。
     * 未开启功能、或当天不是返校日时返回空集（即完全保持原有假期语义）。
     */
    fun exemptSections(
        context: Context,
        repository: CourseRepository,
        date: LocalDate,
    ): Set<Int> {
        if (!repository.getReturnDayReminder()) return emptySet()
        if (!isReturnDay(context, date)) return emptySet()
        return repository.getReturnDayReminderSections().filter { it > 0 }.toSet()
    }

    /**
     * 用户指定的「跟随星期」：0 表示不指定（用日历星期几）。
     * 用于假期那天要按"周日课表"之类取的场景。
     */
    fun followWeekday(repository: CourseRepository): Int =
        repository.getReturnDayFollowWeekday().takeIf { it in 1..7 } ?: 0

    /** 课程是否与豁免节次有交集（跨节次的课只要压到一节就算）。 */
    fun isExempt(course: Course, sections: Set<Int>): Boolean {
        if (sections.isEmpty()) return false
        if (course.startSection > course.endSection) return false
        for (section in course.startSection..course.endSection) {
            if (section in sections) return true
        }
        return false
    }

    /** 从课表里挑出落在豁免节次的课程，保持传入顺序。 */
    fun exemptCourses(courses: List<Course>, sections: Set<Int>): List<Course> =
        if (sections.isEmpty()) emptyList() else courses.filter { isExempt(it, sections) }

    /** 今天所在的一段假期（名称 + 最后一天 + 剩余天数）。 */
    data class HolidaySpan(
        val name: String,
        val firstDate: LocalDate,
        val lastDate: LocalDate,
        val daysLeft: Int,
    ) {
        /** 假期进度（0~100），用于实时动态的进度条。单日假期给 0。 */
        val progressPercent: Int
            get() {
                val total = ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1
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
     * - 返校日 → "今天/明天是中秋最后一天，返校啦"
     * - 假期中（仅当天）→ "中秋还剩 2 天"
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
        // 返校文案：受「返校节次豁免」总开关管
        if (repository.getReturnDayReminder() && isReturnDay(context, date)) {
            val label = dayLabel(date, today)
            // 注意：中文紧跟在 $label 后面会被当成标识符（$label是 不是 $label + 是），必须加花括号
            val where = if (span != null) "${label}是${span.name}最后一天" else "${label}是周末最后一天"
            val what = returnCourseName?.takeIf { it.isNotBlank() }
            // 只有"今天"算倒计时才有意义（次日提醒等场景算出来是负数或几十小时）
            val hours = if (isToday) hoursUntil(returnStartTime) else null
            return when {
                hours != null && hours > 0 -> "$where，距返校${what ?: ""}还有 $hours 小时"
                what != null && !returnStartTime.isNullOrBlank() ->
                    "$where，${what} $returnStartTime 开始"
                else -> "$where，返校啦"
            }
        }
        // 假期余额：与总开关**解耦**（余额是假期信息，不是豁免功能的一部分）。
        // 但只在「当天」显示 —— 在次日小部件上按明天算余额会说成"还剩 N 天"，
        // 用户读到的是今天的信息，属于串味；非当天时返回 null 让调用方保持原样。
        if (span != null && isToday) {
            return if (span.daysLeft > 0) {
                "${span.name}还剩 ${span.daysLeft} 天"
            } else {
                "今天是${span.name}最后一天"
            }
        }
        return null
    }

    /** 距某个 "HH:mm" 还有多少小时（向上取整）；已过或格式非法返回 null。 */
    private fun hoursUntil(startTime: String?): Int? {
        val parts = startTime?.trim().orEmpty().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val target = runCatching {
            java.time.LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }.getOrNull() ?: return null
        val minutes = java.time.Duration.between(java.time.LocalTime.now(), target).toMinutes()
        if (minutes <= 0) return null
        return ((minutes + 59) / 60).toInt()
    }
}
