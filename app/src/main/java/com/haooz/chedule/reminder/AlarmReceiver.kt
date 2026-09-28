/** 课程提醒闹钟接收器 */
package com.haooz.chedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.haooz.chedule.data.CourseRepository

class AlarmReceiver : BroadcastReceiver() {

    /** 把 1..23 的整数转成中文数字（如 8 -> "八"，23 -> "二十三"），用于"早八"式文案 */
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getIntExtra(CourseReminderHelper.EXTRA_REMINDER_TYPE, 0)
        // 原实现在每次闹钟触发时都新建一个 SimpleDateFormat 并格式化当前时间，
        // 纯粹为了打一条 debug 日志 —— release 包里完全是白烧 CPU。
        // 改成：只在可调试包里做，并且按需构造格式化器。
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(
                "CourseReminder",
                "AlarmReceiver: type=$type " +
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date()),
            )
        }
        val repository = CourseRepository(context)
        val useIsland = repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)

        when (type) {
            CourseReminderHelper.TYPE_PRE_CLASS -> {
                val courseName = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_NAME) ?: "课程"
                val section = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_SECTION) ?: ""
                val startTime = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_START_TIME) ?: ""
                val courseId = intent.getStringExtra(CourseReminderHelper.EXTRA_COURSE_ID) ?: ""

                // 去重 ID 先以"当前课表"为准：先回查匹配课程并重新取节次/时间，
                // 避免用户改时间后旧闹钟带着旧 startTime 算 dedupId，与 checkPending
                // 用新 startTime 算的 dedupId 双发（均落入不同 dedupId，互相不拦截）。
                // 如果回查失败再退化为闹钟里快照的 name+section+time。
                // 当前课表只解析**一次**：原来这里和下面各调一次 getTodayCourses()，
                // 也就是一次课前闹钟要把当天课表解析两遍（假期查询 + 逐课时间 + 排序）。
                val todayCourses = CourseReminderHelper.getTodayCourses(context)
                fun matchCourse(course: com.haooz.chedule.data.Course): Boolean =
                    if (courseId.isNotEmpty()) {
                        course.id == courseId
                    } else {
                        // 旧版闹钟没有 courseId，退化为按课程名+节次匹配
                        course.name == courseName && course.getTimeDisplayText() == section
                    }

                val matchedEarly = todayCourses.firstOrNull { matchCourse(it) }
                val dedupId = if (matchedEarly != null) {
                    val freshStart = CourseReminderHelper.getCourseStartTime(
                        matchedEarly,
                        repository
                    ) ?: startTime
                    "${matchedEarly.name}|${matchedEarly.getTimeDisplayText()}|$freshStart"
                } else {
                    "$courseName|$section|$startTime"
                }

                // 去重检查：如果该课程最近已发送过，跳过本次（避免闹钟触发后重新调度导致双发）
                if (CourseReminderHelper.isPreClassSentRecently(context, dedupId)) {
                    Log.d("AlarmReceiver", "Pre-class notification already sent recently for $courseName, skipping")
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 学期未开始（未到开学日期所在周的周一）：不发送，并重新调度清理残留闹钟
                if (!CourseReminderHelper.isSemesterStarted(repository)) {
                    Log.d("AlarmReceiver", "Semester not started yet, skipping pre-class notification for $courseName")
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 关键：闹钟里携带的是"注册那一刻"的课程快照。
                // 课程可能已被删除、改了时间/教室、或因换课表/云同步换了 ID，
                // 若直接照快照发送就会弹出旧数据提醒。这里一律以当前课表为准重新解析。
                val matched = todayCourses.firstOrNull { matchCourse(it) }
                if (matched == null) {
                    // 课表可能已变更：全量重注册，清掉过期闹钟
                    Log.d("AlarmReceiver", "Stale alarm: $courseName($startTime) no longer in today's schedule")
                    CourseReminderHelper.onAlarmProcessed(context, fullReschedule = true)
                    return
                }

                val freshStartTime = CourseReminderHelper.getCourseStartTime(matched, repository) ?: startTime
                val freshEndTime = CourseReminderHelper.getCourseEndTime(matched, repository) ?: ""
                val startMillis = CourseReminderHelper.parseTimeToTodayMillis(freshStartTime)
                val endMillis = CourseReminderHelper.parseTimeToTodayMillis(freshEndTime)

                if (startMillis <= 0L) {
                    Log.d("AlarmReceiver", "Invalid start time for ${matched.name}, skipped")
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
                // 学期未开始（未到开学日期所在周的周一）：不发送次日提醒
                if (!CourseReminderHelper.isSemesterStarted(repository)) {
                    CourseReminderHelper.onAlarmProcessed(context)
                    return
                }

                // 明天课表只解析一次：下面的返校岛也要用同一份
                val tomorrowCourses = CourseReminderHelper.getTomorrowCourses(context)

                // 文案与测试页共用一份（CourseReminderHelper.nextDayReminderText）
                val (nextDayTitle, nextDayBody) =
                    CourseReminderHelper.nextDayReminderText(tomorrowCourses, repository)
                CourseReminderHelper.showReminderNotification(context, type, nextDayTitle, nextDayBody)

                // 明天是「返校日」（假期/周末最后一天、次日要上课）→ 顺带推一条超级岛。
                // 复用既有岛发送路径（含 XMSF 绕白名单），这里只是多一个调用方。
                // 注意用带 repository 的重载：它会一并检查「节假日末期课程排除」总开关，
                // 否则用户关掉功能后仍会收到「明天返校」的岛 / 实时动态。
                val tomorrow = java.time.LocalDate.now().plusDays(1)
                if (com.haooz.chedule.data.ReturnDayReminder.isReturnDay(context, repository, tomorrow)) {
                    val span = com.haooz.chedule.data.ReturnDayReminder
                        .currentHolidaySpan(context, tomorrow)
                    val firstReturnCourse = tomorrowCourses.firstOrNull()
                    val returnName = firstReturnCourse?.name.orEmpty()
                    val returnSection = firstReturnCourse?.getTimeDisplayText().orEmpty()
                    val returnStart = firstReturnCourse?.let {
                        CourseReminderHelper.getCourseStartTime(it, repository)
                    }.orEmpty()
                    val returnLabel = span?.name?.takeIf { it.isNotBlank() }?.let { "${it}最后一天" }
                    if (useIsland) {
                        IslandNotificationHelper.sendReturnDayIslandNotification(
                            context = context,
                            courseName = returnName,
                            section = returnSection,
                            startTime = returnStart,
                            // 次日提醒这条路，正文列的就是明天的课 → 日子词自然是「明天返校」
                            targetDate = tomorrow,
                            holidayLabel = returnLabel,
                            progressPercent = span?.progressPercent,
                        )
                    } else {
                        // 岛关闭时走原生实时动态：两个通道功能必须同步
                        CourseReminderHelper.showReturnDayLiveNotification(
                            context = context,
                            courseName = returnName,
                            section = returnSection,
                            startTime = returnStart,
                            // 次日提醒这条路，正文列的就是明天的课 → 日子词自然是「明天返校」
                            targetDate = tomorrow,
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
