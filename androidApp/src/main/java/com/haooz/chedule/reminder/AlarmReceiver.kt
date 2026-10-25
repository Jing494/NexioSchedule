/** 课程提醒闹钟接收器 */
package com.haooz.chedule.reminder

import com.haooz.chedule.data.HolidayEntry

import com.haooz.chedule.data.plusDays
import com.haooz.chedule.data.todayLocalDate
import kotlinx.datetime.daysUntil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.haooz.chedule.data.NexioLog
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.HolidayCountdown
import com.haooz.chedule.data.HolidayManager
import com.haooz.chedule.data.ReturnDayReminder
import kotlinx.datetime.LocalDate

class AlarmReceiver : BroadcastReceiver() {

    /** 放假提示标题：有假期名就带上，缺失时退化成通用文案 */
    private fun holidayStartTitle(name: String): String =
        if (name.isBlank()) "明天开始放假" else "明天起${name}放假"

    /**
     * 放假提示正文：从放假日算起的剩余假期天数 + 复课日。
     * 区间取 [HolidayCountdown] 的连续假期块 —— 与今日页假期倒计时同一套口径。
     * 极端数据下查不到块时也退化为不带日期的短文案，不让它掉回「明日无课」。
     */
    private fun holidayStartMessage(
        entriesByYear: Map<Int, List<HolidayEntry>>,
        startDate: LocalDate,
    ): String {
        val block = HolidayCountdown.holidayBlockAt(entriesByYear, startDate) ?: return "明天不用上课"
        val resume = block.endDate.plusDays(1)
        val days = startDate.daysUntil(block.endDate) + 1L
        return "共${days}天，${resume.monthNumber}月${resume.dayOfMonth}日恢复上课"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getIntExtra(CourseReminderHelper.EXTRA_REMINDER_TYPE, 0)
        NexioLog.d("CourseReminder", "AlarmReceiver: type=$type ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        val repository = CourseRepository()
        val useIsland = repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)

        when (type) {
            CourseReminderHelper.TYPE_PRE_CLASS -> {
                val courseName = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_NAME) ?: "课程"
                val section = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_SECTION) ?: ""
                val startTime = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_START_TIME) ?: ""
                val courseId = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_ID) ?: ""

                // 去重 ID 以当前课表为准：回查匹配课程重取节次/时间，避免改时间后旧闹钟与 checkPending 算出不同 dedupId 双发；回查失败退化用快照的 name+section+time。
                val matchedEarly = CourseReminderHelper.getTodayCourses(context)
                    .firstOrNull { it.id == courseId }
                val dedupId = if (matchedEarly != null) {
                    val freshStart = CourseReminderHelper.getCourseStartTime(
                        matchedEarly,
                        CourseRepository()
                    ) ?: startTime
                    "${matchedEarly.name}|${matchedEarly.getTimeDisplayText()}|$freshStart"
                } else {
                    "$courseName|$section|$startTime"
                }

                // 去重检查：如果该课程最近已发送过，跳过本次（避免闹钟触发后重新调度导致双发）
                if (CourseReminderHelper.isPreClassSentRecently(context, dedupId)) {
                    NexioLog.d("AlarmReceiver", "Pre-class notification already sent recently for $courseName, skipping")
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 学期未开始（未到开学日期所在周的周一）：不发送，并重新调度清理残留闹钟
                if (!CourseReminderHelper.isSemesterStarted(repository)) {
                    NexioLog.d("AlarmReceiver", "Semester not started yet, skipping pre-class notification for $courseName")
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 关键：闹钟携带的是注册时刻的课程快照；课程可能被删/改时间教室/换课表换 ID，
                // 直接照发会弹旧数据。一律以当前课表重新解析。
                val matched = CourseReminderHelper.getTodayCourses(context)
                    .firstOrNull { it.id == courseId }
                if (matched == null) {
                    // 课表可能已变更（或闹钟没带 courseId）：全量重注册，清掉过期闹钟
                    NexioLog.d("AlarmReceiver", "Stale alarm: $courseName($startTime) no longer in today's schedule")
                    CourseReminderHelper.onAlarmProcessed(context, fullReschedule = true)
                    return
                }

                val freshStartTime = CourseReminderHelper.getCourseStartTime(matched, repository) ?: startTime
                val freshEndTime = CourseReminderHelper.getCourseEndTime(matched, repository) ?: ""
                val startMillis = CourseReminderHelper.parseTimeToTodayMillis(freshStartTime)
                // 跨零点的课（23:30–00:30 晚自习）要按次日算结束，否则课中卡/岛永远不出现
                val endMillis = CourseReminderHelper.endMillisFor(startMillis, freshEndTime)

                if (startMillis <= 0L) {
                    NexioLog.d("AlarmReceiver", "Invalid start time for ${matched.name}, skipped")
                    CourseReminderHelper.onAlarmProcessed(context, fullReschedule = true)
                    return
                }

                // 统一走 sendPreClassNotification：
                // 未开课 → 课前倒计时；已开课（连堂课间为 0、或闹钟被 Doze 延迟）→ 直接落到"已上课"态。
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                CourseReminderHelper.sendPreClassNotification(
                    context = context,
                    alarmManager = alarmManager,
                    repository = repository,
                    course = matched,
                    startTime = freshStartTime,
                    useIsland = useIsland,
                    courseStartMillis = startMillis,
                    courseEndMillis = endMillis
                )

                // 记录已发送，防止后续 startReminderService 重调度时重复发送
                CourseReminderHelper.recordPreClassSent(context, dedupId)

                // 其余今日课程闹钟已在调度时注册，无需全量重装
                CourseReminderHelper.onAlarmProcessed(context)
            }

            CourseReminderHelper.TYPE_RETURN_DAILY_BALANCE,
            CourseReminderHelper.TYPE_RETURN_DAILY_PREP -> {
                // 精确闹钟到点：直接走同一套判定（内部有"当天只发一次"去重），并顺手排下一天
                CourseReminderHelper.checkDailyReturnDayNotifications(context)
                CourseReminderHelper.onAlarmProcessed(context)
            }

            CourseReminderHelper.TYPE_NEXT_DAY -> {
                val today = todayLocalDate()
                val tomorrow = today.plusDays(1)
                val entriesByYear = HolidayManager.loadAllByYear()
                // 只加载一次假期数据，课程解析与假期判定共用，避免重复读 prefs 且口径不一致
                val resolution = CourseReminderHelper.resolveDaySchedule(
                    context, tomorrow, repository, entriesByYear
                )
                val tomorrowCourses = resolution.courses

                // 明天放假且明天没课时的两种处理：
                // - 放假前一天 → 发一条带假期长度和复课日的提示；
                // - 假期进行中 → 静默。
                if (resolution.isHolidayDate && tomorrowCourses.isEmpty()) {
                    val todayIsHoliday = HolidayManager.entriesForDate(entriesByYear, today)
                        .any { it.type == HolidayManager.TYPE_HOLIDAY }
                    if (!todayIsHoliday) {
                        val name = HolidayManager.entriesForDate(entriesByYear, tomorrow)
                            .firstOrNull { it.type == HolidayManager.TYPE_HOLIDAY }?.name.orEmpty()
                        CourseReminderHelper.showReminderNotification(
                            context,
                            type,
                            holidayStartTitle(name),
                            holidayStartMessage(entriesByYear, tomorrow),
                        )
                    } else {
                        NexioLog.d("AlarmReceiver", "Holiday in progress, skipping next-day reminder for $tomorrow")
                    }
                    CourseReminderHelper.scheduleNextDayOnly(context)
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 学期未开始：默认静默，整个假期不打扰；
                // 但明天确有课时照常发送 —— 返校/开学前一天正是这一条
                if (!CourseReminderHelper.isSemesterStarted(repository) && tomorrowCourses.isEmpty()) {
                    NexioLog.d("AlarmReceiver", "Semester not started and no courses tomorrow, skipping next-day reminder")
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 文案与测试页共用一份（CourseReminderHelper.nextDayReminderText），别在这里再写一套
                val (nextDayTitle, nextDayBody) =
                    CourseReminderHelper.nextDayReminderText(tomorrowCourses, repository)
                CourseReminderHelper.showReminderNotification(context, type, nextDayTitle, nextDayBody)

                // 明天是「返校日」（休息日且次日要上课）→ 顺带推一条超级岛 / 原生实时动态。
                // 两个通道功能必须同步：岛开走岛、岛关走原生。
                // 用带 repository 的重载：它会一并检查「节假日末期课程排除」总开关，
                // 否则用户关掉功能后仍会收到「明天返校」。
                val tomorrowDate = java.time.LocalDate.now().plusDays(1)
                if (ReturnDayReminder.isReturnDay(context, repository, tomorrowDate)) {
                    val span = ReturnDayReminder.currentHolidaySpan(context, tomorrowDate)
                    val firstReturnCourse = tomorrowCourses.firstOrNull()
                    val returnName = firstReturnCourse?.name.orEmpty()
                    val returnSection = firstReturnCourse?.getTimeDisplayText().orEmpty()
                    val returnStart = firstReturnCourse?.let {
                        CourseReminderHelper.getCourseStartTime(it, repository)
                    }.orEmpty()
                    val returnLabel = span?.name?.takeIf { it.isNotBlank() }?.let { it + "最后一天" }
                    if (useIsland) {
                        IslandNotificationHelper.sendReturnDayIslandNotification(
                            context = context,
                            courseName = returnName,
                            section = returnSection,
                            startTime = returnStart,
                            // 次日提醒这条路，正文列的就是明天的课 → 日子词自然是「明天返校」
                            targetDate = tomorrowDate,
                            holidayLabel = returnLabel,
                            progressPercent = span?.progressPercent,
                        )
                    } else {
                        CourseReminderHelper.showReturnDayLiveNotification(
                            context = context,
                            courseName = returnName,
                            section = returnSection,
                            startTime = returnStart,
                            targetDate = tomorrowDate,
                            holidayLabel = returnLabel,
                            progressPercent = span?.progressPercent,
                        )
                    }
                }

                // 只补注册下一个次日闹钟，避免 cancel+重建全部课程闹钟
                CourseReminderHelper.scheduleNextDayOnly(context)
                CourseReminderHelper.onAlarmProcessed(context)
            }
        }
    }
}
