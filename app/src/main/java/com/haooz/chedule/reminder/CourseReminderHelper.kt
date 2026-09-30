package com.haooz.chedule.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.haooz.chedule.R
import com.haooz.chedule.data.Course
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.CourseScheduleDateBounds
import com.haooz.chedule.data.HolidayCourseExclusion
import com.haooz.chedule.data.HolidayEndCourseExclusion
import com.haooz.chedule.data.HolidayManager
import com.haooz.chedule.data.TeachingWeekPosition
import com.haooz.chedule.data.ReturnDayReminder
import com.haooz.chedule.ui.activities.MainActivity
import com.haooz.chedule.widget.WidgetUpdateCache
import java.time.LocalDate
import java.util.Calendar

object CourseReminderHelper {

    private const val TAG = "CourseReminder"

    // --- startReminderService 合并窗口所需状态（见 SERVICE_START_COALESCE_MS）---
    // 不能用 Long.MIN_VALUE：now - Long.MIN_VALUE 会溢出成负数，
    // 反而小于合并窗口 → 首次调用会被误判成「窗口内」，从此再也不真正执行。
    @Volatile
    private var lastServiceStartAt = -SERVICE_START_COALESCE_MS
    private val coalesceLock = Any()
    @Volatile
    private var coalescePending = false
    private val coalesceHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    @Volatile
    private var debugLogs: Boolean? = null

    /** 仅 debug 包打热路径日志，release 避免每分钟字符串拼接 */
    private fun debugEnabled(context: Context): Boolean {
        debugLogs?.let { return it }
        val enabled = try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
        debugLogs = enabled
        return enabled
    }

    private fun logD(context: Context, message: String) {
        if (debugEnabled(context)) android.util.Log.d(TAG, message)
    }

    /**
     * 提升（promoted ongoing）相关调用失败时**不能静默**。
     *
     * 这几个 API 一旦抛异常（例如系统不支持/权限被撤），通知照样发出去，
     * 但会**退化成普通通知**——用户看到的是"没有实时动态"，而日志里一个字都没有，
     * 无法归因。所以失败一律留 warn 痕迹。这些调用每次发通知最多两三次，不在热路径上。
     */
    private inline fun promotedCatching(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "promoted-api failed: $tag (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    const val EXTRA_REMINDER_TYPE = "reminder_type"
    const val EXTRA_COURSE_NAME = "course_name"
    const val EXTRA_COURSE_SECTION = "course_section"
    const val EXTRA_COURSE_START_TIME = "course_start_time"
    const val EXTRA_OPEN_REMINDER_SETTINGS = "open_reminder_settings"
    // 闹钟触发时用它回查课表，校验课程是否仍存在/时间是否已变更
    const val EXTRA_COURSE_ID = "course_id"

    const val TYPE_PRE_CLASS = 1
    const val TYPE_NEXT_DAY = 2

    const val WIDGET_REFRESH_REQUEST_CODE = 88888

    // 课前倒计时与"已上课"必须共用同一 ID，否则两态会同时停留在岛上
    const val ISLAND_NOTIFICATION_ID = IslandNotificationHelper.ISLAND_NOTIFICATION_ID

    // 每门课互不覆盖：RC = BASE + course.id.hashCode()
    private const val ISLAND_EXPAND_RC_BASE = 70000

    // 超过该滞后视为隔夜/重启残留，直接收起而不是补一个过期的上课态
    private const val ISLAND_LATE_TOLERANCE_MS = 5 * 60_000L

    // 开课后仍允许补发"已上课"的宽限期，超出则不再打扰
    private const val ISLAND_START_GRACE_MS = 2 * 60_000L

    // 原生实况三态固定 ID，与超级岛 1003/1004/1005 同思路，不随课程名变化
    const val LIVE_COUNTDOWN_ID = 2003

    /** 「明天返校」原生实时动态通知 ID（与小米岛 ID 分开，互不干扰） */
    const val RETURN_DAY_LIVE_ID = 2100

    /** 假期余额 / 返校准备清单：普通通知 ID 与当日去重键 */
    /** 余额/清单的精确闹钟类型与请求码（自续：到点发送后顺手排下一天） */
    const val TYPE_RETURN_DAILY_BALANCE = 30
    const val TYPE_RETURN_DAILY_PREP = 31
    private const val RC_RETURN_BALANCE_ALARM = 8101
    private const val RC_RETURN_PREP_ALARM = 8102
    private const val NOTIFY_ID_RETURN_BALANCE = 300
    private const val NOTIFY_ID_RETURN_PREP = 301

    /** 余额/清单/「明天返校」实况共用的去重状态（原来散落成字面量，这里统一） */
    private const val RETURN_DAY_STATE_PREFS = "return_day_notify_state"
    private const val KEY_RETURN_BALANCE_DATE = "balance_date"
    private const val KEY_RETURN_PREP_DATE = "prep_date"
    private const val KEY_RETURN_LIVE_PUSH = "return_live_push_at"

    /** 精确闹钟去抖状态：已排的目标时刻 + 上次真正排钟的墙钟时间 */
    private const val KEY_RETURN_BALANCE_ALARM_AT = "balance_alarm_at"
    private const val KEY_RETURN_BALANCE_ALARM_SET = "balance_alarm_set"
    private const val KEY_RETURN_PREP_ALARM_AT = "prep_alarm_at"
    private const val KEY_RETURN_PREP_ALARM_SET = "prep_alarm_set"
    const val LIVE_STARTED_ID = 2004
    const val LIVE_IN_CLASS_ID = 2005
    // 测试实时活动独立 ID，对齐超级岛 5000/5001/5002，不覆盖真实课提醒
    const val LIVE_TEST_COUNTDOWN_ID = 5100
    const val LIVE_TEST_STARTED_ID = 5101
    const val LIVE_TEST_IN_CLASS_ID = 5102

    private fun liveCountdownId(testMode: Boolean): Int =
        if (testMode) LIVE_TEST_COUNTDOWN_ID else LIVE_COUNTDOWN_ID

    private fun liveStartedId(testMode: Boolean): Int =
        if (testMode) LIVE_TEST_STARTED_ID else LIVE_STARTED_ID

    private fun liveInClassId(testMode: Boolean): Int =
        if (testMode) LIVE_TEST_IN_CLASS_ID else LIVE_IN_CLASS_ID

    // 课中提醒统一开关（超级岛 / 原生实况共用；迁移旧 island_in_class_enabled / live_in_class_enabled）
    const val KEY_IN_CLASS = "in_class_reminder_enabled"
    // 提醒时机：0=全程，1=距下课 N 分钟（N>=课程总时长时按全程，避免溢出）
    const val KEY_IN_CLASS_TIMING_MODE = "in_class_timing_mode"
    const val KEY_IN_CLASS_LEAD_MINUTES = "in_class_lead_minutes"
    const val IN_CLASS_TIMING_FULL = 0
    const val IN_CLASS_TIMING_BEFORE_END = 1

    fun isInClassEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("course_reminder_prefs", Context.MODE_PRIVATE)
        if (prefs.contains(KEY_IN_CLASS)) {
            return prefs.getBoolean(KEY_IN_CLASS, false)
        }
        // 旧键任一打开过则视为开启，避免升级后开关被静默关掉
        return prefs.getBoolean(IslandNotificationHelper.KEY_IN_CLASS_REMINDER, false) ||
            prefs.getBoolean("live_in_class_enabled", false)
    }

    fun isInClassLiveEnabled(context: Context): Boolean = isInClassEnabled(context)

    fun getInClassTimingMode(context: Context): Int {
        return context.getSharedPreferences("course_reminder_prefs", Context.MODE_PRIVATE)
            .getInt(KEY_IN_CLASS_TIMING_MODE, IN_CLASS_TIMING_FULL)
    }

    fun getInClassLeadMinutes(context: Context): Int {
        return context.getSharedPreferences("course_reminder_prefs", Context.MODE_PRIVATE)
            .getInt(KEY_IN_CLASS_LEAD_MINUTES, 10).coerceIn(0, 60)
    }

    /**
     * 当前是否应展示课中进度。
     * 距下课模式：剩余时长 <= N 分钟才展示；N 不小于本节课总时长时按全程，避免「设 60 分钟却比课还长」的溢出。
     */
    fun shouldShowInClassNow(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isInClassEnabled(context)) return false
        if (endMillis <= startMillis) return false
        if (nowMillis < startMillis || nowMillis >= endMillis) return false
        if (getInClassTimingMode(context) == IN_CLASS_TIMING_FULL) return true
        val leadMs = getInClassLeadMinutes(context) * 60_000L
        val totalMs = endMillis - startMillis
        // 课总时长不足 lead → 等价全程
        if (leadMs >= totalMs) return true
        return (endMillis - nowMillis) <= leadMs
    }

    const val CHANNEL_REMINDER_ID = "course_reminder_alert"
    const val CHANNEL_REMINDER_NAME = "课程提醒通知"
    const val CHANNEL_LIVE_ID = "course_reminder_live"

    /** 假期余额 / 返校清单专用通道：与课前提醒分开，方便在系统设置里单独控制 */
    const val CHANNEL_HOLIDAY_ID = "course_reminder_holiday"
    const val CHANNEL_LIVE_NAME = "课程提醒实况"

    // 发送去重：JSON map(courseId->时间戳)+日期，跨日失效。
    // 常规提醒 60 分钟窗口不重发；超级岛当天只发一次（原生倒计时自行跳秒，重发会弹出岛）。
    private const val PREF_SENT_HISTORY = "reminder_sent_history"
    private const val KEY_SENT_DAY = "reminder_sent_day"
    private const val KEY_SENT_MAP = "reminder_sent_map"
    private const val SENT_DEDUP_WINDOW_MS = 60 * 60 * 1000L

    private const val PREF_DAY_CHANGE = "day_change_state"
    private const val KEY_LAST_SCHEDULE_DATE = "last_schedule_date"

    // requestCode 登记表：课程删除/换课表/UUID 变化后旧闹钟不在当前列表里，
    // 只按课程取消会残留孤儿闹钟（到点弹旧数据）
    private const val PREF_ALARM_REGISTRY = "reminder_alarm_registry"
    private const val KEY_PRE_CLASS_RCS = "pre_class_rcs"
    // 一次性迁移标记：旧版按课程名 hash 派生的到点闹钟 RC 已清理完毕
    private const val KEY_LEGACY_RC_CLEANED = "legacy_rc_cleaned_v1"
    private const val KEY_EXPAND_RCS = "expand_rcs"
    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun readRcSet(context: Context, key: String): Set<Int> {
        val prefs = context.getSharedPreferences(PREF_ALARM_REGISTRY, Context.MODE_PRIVATE)
        return (prefs.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun hasCleanedLegacyAlarmRcs(context: Context): Boolean =
        context.getSharedPreferences(PREF_ALARM_REGISTRY, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_RC_CLEANED, false)

    private fun markLegacyAlarmRcsCleaned(context: Context) {
        context.getSharedPreferences(PREF_ALARM_REGISTRY, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LEGACY_RC_CLEANED, true)
        }
    }

    private fun writeRcSet(context: Context, key: String, rcs: Set<Int>) {
        context.getSharedPreferences(PREF_ALARM_REGISTRY, Context.MODE_PRIVATE).edit {
            putStringSet(key, rcs.map { it.toString() }.toSet())
        }
    }

    // 跨日后整份记录失效，保证次日可重新提醒
    fun recordPreClassSent(context: Context, courseId: String) {
        val prefs = context.getSharedPreferences(PREF_SENT_HISTORY, Context.MODE_PRIVATE)
        val today = getTodayDateString()
        val day = prefs.getString(KEY_SENT_DAY, null)
        val mapStr = prefs.getString(KEY_SENT_MAP, null)
        val json = if (day == today && !mapStr.isNullOrEmpty()) {
            try {
                org.json.JSONObject(mapStr)
            } catch (_: Exception) {
                org.json.JSONObject()
            }
        } else {
            org.json.JSONObject()
        }
        json.put(courseId, System.currentTimeMillis())
        prefs.edit {
            putString(KEY_SENT_DAY, today)
            putString(KEY_SENT_MAP, json.toString())
        }
    }

    // 按 courseId 精确匹配 60 分钟窗口；跨日整份重置以便次日重发
    fun isPreClassSentRecently(context: Context, courseId: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_SENT_HISTORY, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT_DAY, null) != getTodayDateString()) return false
        val mapStr = prefs.getString(KEY_SENT_MAP, null) ?: return false
        val lastTime = try {
            org.json.JSONObject(mapStr).optLong(courseId, 0L)
        } catch (_: Exception) {
            0L
        }
        if (lastTime <= 0L) return false
        return System.currentTimeMillis() - lastTime < SENT_DEDUP_WINDOW_MS
    }

    // 岛倒计时是原生 ChronometerCountDown，当天发一次即可；重发会触发岛重新弹出
    fun hasIslandPreClassSentToday(context: Context, courseId: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_SENT_HISTORY, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT_DAY, null) != getTodayDateString()) return false
        val mapStr = prefs.getString(KEY_SENT_MAP, null) ?: return false
        return try {
            org.json.JSONObject(mapStr).has(courseId)
        } catch (_: Exception) {
            false
        }
    }

    // 日期变化时重调度：原调度仅覆盖今天，跨日后需为新一天重新注册闹钟
    fun checkAndRescheduleOnDayChange(context: Context) {
        val today = getTodayDateString()
        val prefs = context.getSharedPreferences(PREF_DAY_CHANGE, Context.MODE_PRIVATE)
        val lastDate = prefs.getString(KEY_LAST_SCHEDULE_DATE, null)

        if (lastDate == today) return

        prefs.edit { putString(KEY_LAST_SCHEDULE_DATE, today) }
        startReminderService(context)
    }

    private fun getTodayDateString(): String {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    // 课程开始/结束时间戳：通知、闹钟、岛倒计时一律用下面两个函数，禁止另算一套

    fun parseTimeToTodayMillis(time: String?): Long {
        if (time.isNullOrBlank()) return -1L
        val parts = time.split(":")
        if (parts.size < 2) return -1L
        val hour = parts[0].trim().toIntOrNull() ?: return -1L
        val minute = parts[1].trim().toIntOrNull() ?: return -1L
        if (hour !in 0..23 || minute !in 0..59) return -1L
        return todayMillis(hour, minute)
    }

    internal fun hasSameLocalMinute(time: String?, timestampMillis: Long): Boolean {
        if (time.isNullOrBlank()) return false
        val parts = time.trim().split(":")
        if (parts.size != 2) return false
        val hour = parts[0].trim().toIntOrNull() ?: return false
        val minute = parts[1].trim().toIntOrNull() ?: return false
        if (hour !in 0..23 || minute !in 0..59) return false
        val expected = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        return hour == expected.get(Calendar.HOUR_OF_DAY) && minute == expected.get(Calendar.MINUTE)
    }

    // 向上取整，保证文案与系统倒计时剩余秒数一致
    private fun ceilMinutesUntil(startMillis: Long, now: Long = System.currentTimeMillis()): Int {
        val remain = startMillis - now
        if (remain <= 0) return 0
        return ((remain + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    private fun formatClock(millis: Long): String {
        if (millis <= 0L) return ""
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }

    /** 课前实况正文：第一行「N分钟后上课 · 上课时间 - 下课时间」，第二行上课地点。 */
    private fun buildPreClassBigText(
        minutesUntilStart: Int,
        startTime: String,
        endTime: String,
        classroom: String
    ): String = buildString {
        val timeRange = when {
            startTime.isNotEmpty() && endTime.isNotEmpty() -> "$startTime - $endTime"
            startTime.isNotEmpty() -> startTime
            endTime.isNotEmpty() -> endTime
            else -> ""
        }
        if (timeRange.isEmpty()) {
            append("${minutesUntilStart}分钟后上课")
        } else {
            append("${minutesUntilStart}分钟后上课 · $timeRange")
        }
        if (classroom.isNotEmpty()) {
            append('\n')
            append(classroom)
        }
    }

    fun todayMillis(hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * **指定日期**上 "HH:mm" 的绝对时间戳。
     * 与 [parseTimeToTodayMillis] 的区别是它不会把"明天"解析成"今天"；格式非法返回 -1。
     */
    fun millisAtDate(date: java.time.LocalDate, time: String?): Long {
        if (time.isNullOrBlank()) return -1L
        val parts = time.split(":")
        if (parts.size < 2) return -1L
        val hour = parts[0].trim().toIntOrNull() ?: return -1L
        val minute = parts[1].trim().toIntOrNull() ?: return -1L
        if (hour !in 0..23 || minute !in 0..59) return -1L
        return runCatching {
            date.atTime(hour, minute)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(-1L)
    }

    /**
     * 课表边界闹钟专用：课前提醒点、上课瞬间、下课瞬间。
     *
     * 用 setAlarmClock 而非 setExactAndAllowWhileIdle——后者受 Doze 的
     *「每应用每 9 分钟一次」配额限制，会被高频的对账刷新抢占而迟到最多约 8 分钟；
     * 而 setAlarmClock 不受该配额限制，系统会提前退出 Doze 保证准点。
     *
     * 代价：状态栏会显示闹钟图标，下一个闹钟时间会暴露给锁屏与其他应用。
     *
     * 只用于课表边界这类一次性关键时刻。**每分钟的对账刷新链绝不能用**，
     * 否则状态栏闹钟图标会一直亮着（刷新链走 scheduleNextWidgetRefresh）。
     */
    fun setCourseBoundaryAlarm(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, null),
                pendingIntent
            )
        } catch (_: SecurityException) {
            // 精确闹钟权限被撤销时退回，至少不让闹钟静默丢失
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } catch (_: SecurityException) { }
        }
    }

    /**
     * 合并窗口。一次「切换课表」会同时惊动 CourseViewModel / ScheduleViewModel /
     * SettingsViewModel，各自调一次 startReminderService，实测 1ms 内触发 4 次，
     * 而每次都是 cancelAllAlarms + scheduleAllAlarms（全量重排）+ 写 SP。
     * 这些调用读的是同一份最新状态，重复执行毫无意义，所以窗口内只真正跑一次。
     */
    private const val SERVICE_START_COALESCE_MS = 400L

    fun startReminderService(context: Context) {
        startReminderService(context, CourseRepository(context))
    }

    fun startReminderService(context: Context, repository: CourseRepository) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(coalesceLock) {
            if (now - lastServiceStartAt < SERVICE_START_COALESCE_MS) {
                // 命中窗口：本次不立即执行，只在窗口末尾补跑一次，保证最终状态一定生效
                scheduleCoalescedStartLocked(context)
                com.haooz.chedule.ui.utils.FeatureLog.reminderFlow("service_start_coalesced") {
                    "sinceLast=${now - lastServiceStartAt}ms from=${callSite()}"
                }
                return
            }
            // 立即占位：检查与更新必须原子，否则两条并发线程会同时通过判断、各全量重排一次
            lastServiceStartAt = now
        }
        // 启动路径自愈：上次给超级岛"绕白名单"时把小米推送服务断网，若进程在恢复前被杀，
        // 网络会一直是关的。这里补一次恢复。
        //
        // **必须避开正在进行的绕白名单窗口**：自愈会调 setXmsfNetworkingEnabled(true)，
        // 若恰好落在"关网 → notify → 恢复"的窗口里就把窗口掀了 ——
        // 那条通知是在"网已恢复"的状态下发出去的，不被当成焦点通知处理，
        // 用户看到的就是**普通通知**而不是超级岛（连发多条时尤其容易踩上）。
        if (!IslandNotificationHelper.isXmsfBypassInFlight()) {
            runCatching { com.haooz.chedule.shizuku.ShizukuManager.healPendingXmsfRestore(context) }
        }
        doStartReminderService(context, repository)
    }

    /**
     * 调用来源：跳过自身栈帧，回溯到第一个外部调用点。
     * 只在录制时求值（走 lambda 惰性），正式版未录制零成本。
     */
    private fun callSite(): String {
        val st = Thread.currentThread().stackTrace
        for (i in st.indices) {
            val e = st[i]
            val cn = e.className
            // 跳过自身与抓栈本身产生的内部帧（VMStack / Thread.getStackTrace）
            if (cn.contains("CourseReminderHelper") || cn.contains("VMStack") ||
                cn == "java.lang.Thread"
            ) continue
            return "${e.fileName}:${e.lineNumber} ${e.methodName}"
        }
        return "unknown"
    }

    private fun scheduleCoalescedStartLocked(context: Context) {
        val app = context.applicationContext
        if (coalescePending) return
        // 必须置位：补发回调靠它判断要不要真的跑，漏了这行补发会被永久跳过
        coalescePending = true
        coalesceHandler.postDelayed({
            synchronized(coalesceLock) {
                if (!coalescePending) return@postDelayed
                coalescePending = false
            }
            // 全量重排 + 写 SP，放后台线程，别占主线程
            Thread {
                runCatching { doStartReminderService(app, CourseRepository(app), fromCoalesced = true) }
            }.apply {
                isDaemon = true
                name = "reminder-coalesced"
            }.start()
        }, SERVICE_START_COALESCE_MS)
    }

    /** 关闭提醒时取消待补发，否则延迟的那次会把刚关掉的闹钟又排上 */
    private fun cancelCoalescedStart() {
        synchronized(coalesceLock) {
            coalescePending = false
        }
    }

    private fun doStartReminderService(
        context: Context,
        repository: CourseRepository,
        fromCoalesced: Boolean = false
    ) {
        lastServiceStartAt = android.os.SystemClock.elapsedRealtime()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pre = repository.getPreClassReminder()
        val next = repository.getNextDayReminder()
        // detail 惰性求值：未录制时连 dnd 这次 SP 读、这次抓栈都不做
        com.haooz.chedule.ui.utils.FeatureLog.reminderFlow("service_start") {
            "pre=$pre next=$next dnd=${repository.getClassDndEnabled()} " +
                "coalesced=$fromCoalesced from=${callSite()}"
        }
        if (!pre && !next) {
            // 提醒关闭：取消本应用闹钟/通知，但 widget 刷新闹钟必须保留（只用小组件也要刷新）
            com.haooz.chedule.ui.utils.FeatureLog.reminderFlow("service_start_path", "all_off_cleanup")
            cancelAllAlarms(context, alarmManager)
            cancelIslandExpandAlarms(context, alarmManager)
            cancelCourseStartAlarms(context, alarmManager)
            cancelAllReminderNotifications(context)
            ClassDndHelper.cancelClassDndAlarms(context, alarmManager)
            ClassDndHelper.applyCurrentState(context)
            scheduleNextWidgetRefresh(context, alarmManager)
            return
        }
        com.haooz.chedule.ui.utils.FeatureLog.reminderFlow("service_start_path", "schedule_all")
        reconcileActiveHolidayCourse(context)
        scheduleAllAlarms(context, repository, alarmManager)
        scheduleWidgetRefresh(context, alarmManager)
        // 开关切换后立即对账，不必等闹钟
        ClassDndHelper.applyCurrentState(context)
    }

    /**
     * 闹钟触发后的轻量收尾：只保 widget 刷新链与勿扰对账。
     * 今日课程闹钟在调度时已一次性注册，无需每次触发都 cancel+全量重装。
     * 课表变更仍由设置页 / ViewModel / 开机 / 跨日路径调用 startReminderService。
     */
    fun onAlarmProcessed(context: Context, fullReschedule: Boolean = false) {
        if (fullReschedule) {
            startReminderService(context)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleNextWidgetRefresh(context, alarmManager)
        ClassDndHelper.applyCurrentState(context)
    }

    /** 次日提醒触发后只注册下一个次日闹钟，避免全量重调度 */
    fun scheduleNextDayOnly(context: Context) {
        val repository = CourseRepository(context)
        if (!repository.getNextDayReminder() || !isSemesterStarted(repository)) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleNextDayAlarm(context, repository, alarmManager)
    }

    // 开学日期所在周的周一之后才算已开始；解析失败时保守放行，避免误屏蔽
    fun isSemesterStarted(repository: CourseRepository): Boolean {
        return try {
            val start = LocalDate.parse(repository.getClassStartTime().replace("/", "-"))
            val startMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
            !LocalDate.now().isBefore(startMonday)
        } catch (_: Exception) {
            true
        }
    }

    fun stopReminderService(context: Context) {
        com.haooz.chedule.ui.utils.FeatureLog.reminderFlow("service_stop")
        cancelCoalescedStart()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAllAlarms(context, alarmManager)
        cancelIslandExpandAlarms(context, alarmManager)
        cancelCourseStartAlarms(context, alarmManager)
        ClassDndHelper.cancelClassDndAlarms(context, alarmManager)
        ClassDndHelper.applyCurrentState(context)
        cancelAllReminderNotifications(context)
        // 不取消 widget 刷新闹钟，避免桌面小部件停止刷新
    }

    private fun scheduleAllAlarms(context: Context, repository: CourseRepository, alarmManager: AlarmManager) {
        cancelAllAlarms(context, alarmManager)
        if (repository.getPreClassReminder()) {
            schedulePreClassAlarms(context, repository, alarmManager)
        }
        if (repository.getNextDayReminder()) {
            scheduleNextDayAlarm(context, repository, alarmManager)
        }
        ClassDndHelper.scheduleClassDndAlarms(context, alarmManager)
    }

    private fun cancelAllAlarms(context: Context, alarmManager: AlarmManager) {
        // 先按登记表取消，覆盖已删除/已换 ID 的孤儿闹钟
        for (rc in readRcSet(context, KEY_PRE_CLASS_RCS)) {
            val pendingIntent = PendingIntent.getBroadcast(
                context, rc, Intent(context, AlarmReceiver::class.java), PI_FLAGS
            )
            alarmManager.cancel(pendingIntent)
        }
        writeRcSet(context, KEY_PRE_CLASS_RCS, emptySet())

        val allCourses = CourseRepository(context).getAllCourses()
        for (course in allCourses) {
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                course.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
        val nextDayIntent = Intent(context, AlarmReceiver::class.java)
        val nextDayPendingIntent = PendingIntent.getBroadcast(
            context,
            99999,
            nextDayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(nextDayPendingIntent)
    }

    private fun cancelIslandExpandAlarms(context: Context, alarmManager: AlarmManager) {
        for (rc in readRcSet(context, KEY_EXPAND_RCS)) {
            val pendingIntent = PendingIntent.getBroadcast(
                context, rc, Intent(context, IslandExpandReceiver::class.java), PI_FLAGS
            )
            alarmManager.cancel(pendingIntent)
        }
        writeRcSet(context, KEY_EXPAND_RCS, emptySet())

        // 历史版本固定 ID 兜底清理
        val knownIds = listOf(1001, 1002, 1003, 5000)
        for (id in knownIds) {
            val intent = Intent(context, IslandExpandReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun cancelCourseStartAlarms(context: Context, alarmManager: AlarmManager) {
        val intent = Intent(context, CourseStartReceiver::class.java)
        // 新固定 ID 的到点闹钟（真实 + 测试）
        for (rc in intArrayOf(LIVE_COUNTDOWN_ID, LIVE_TEST_COUNTDOWN_ID)) {
            val pending = PendingIntent.getBroadcast(
                context,
                rc,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pending)
        }
        // 旧版按课程名 hash 派生 RC 的残留。
        //
        // 这是一次性迁移，不是常规维护：现版本到点闹钟用的是固定 ID
        // （LIVE_COUNTDOWN_ID / LIVE_TEST_COUNTDOWN_ID，见 sendCourseStartNotification）。
        // 而这里的每个 RC 都要 getBroadcast(FLAG_UPDATE_CURRENT) + cancel ——
        // 后者在没命中已有闹钟时会「先建再删」，一门课 2 次 binder 往返；
        // 60 门课就是 120 次，而且每次全量重排（启动 App / 改设置 / 开机 / 跨日）都要重走一遍。
        // 迁移完成后置位标记，之后就再也不用为已经不存在的旧 RC 付这笔开销。
        if (!hasCleanedLegacyAlarmRcs(context)) {
            val allCourses = CourseRepository(context).getAllCourses()
            for (course in allCourses) {
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    10000 + course.name.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
            markLegacyAlarmRcsCleaned(context)
        }
    }

    private fun schedulePreClassAlarms(
        context: Context,
        repository: CourseRepository,
        alarmManager: AlarmManager
    ) {
        if (!isSemesterStarted(repository)) {
            writeRcSet(context, KEY_PRE_CLASS_RCS, emptySet())
            return
        }

        val minutesBefore = repository.getPreClassReminderMinutes()

        logD(context, "schedulePre: semesterStarted=true minuteBefore=$minutesBefore")

        // 复用 getTodayCourses，勿再内联 workSwap/周次/节假日过滤
        val todayCourses = getTodayCourses(context)
        if (todayCourses.isEmpty()) {
            logD(context, "schedulePre: noCoursesForToday")
            writeRcSet(context, KEY_PRE_CLASS_RCS, emptySet())
            return
        }
        logD(context, "schedulePre: todayCourses=${todayCourses.size}")

        // 登记本次注册的 RC，供 cancelAllAlarms 清理孤儿闹钟
        val scheduledRcs = mutableSetOf<Int>()

        val useIsland = repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)

        for ((index, course) in todayCourses.withIndex()) {
            val startTime = getCourseStartTime(course, repository) ?: continue
            val startParts = startTime.split(":")
            if (startParts.size != 2) continue
            val startHour = startParts[0].toIntOrNull() ?: continue
            val startMinute = startParts[1].toIntOrNull() ?: continue
            val startTotalMinutes = startHour * 60 + startMinute

            // 找紧邻前一节课：同一时段可能多门课，不能直接用 index-1
            val prevCourse = todayCourses.take(index).lastOrNull { prev ->
                isConsecutiveCourse(prev, course)
            }

            val triggerMinutes = if (prevCourse != null) {
                val prevEndTime = getCourseEndTime(prevCourse, repository) ?: continue
                val prevEndParts = prevEndTime.split(":")
                if (prevEndParts.size != 2) continue
                val prevEndHour = prevEndParts[0].toIntOrNull() ?: continue
                val prevEndMinute = prevEndParts[1].toIntOrNull() ?: continue
                val prevEndTotalMinutes = prevEndHour * 60 + prevEndMinute

                val breakMinutes = startTotalMinutes - prevEndTotalMinutes
                if (breakMinutes < minutesBefore) {
                    // 课间短于提前提醒：改为上一节下课时立即提醒，否则会落进上一节课堂时间
                    maxOf(0, prevEndTotalMinutes)
                } else {
                    startTotalMinutes - minutesBefore
                }
            } else {
                startTotalMinutes - minutesBefore
            }

            val now = Calendar.getInstance()
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val alarmTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, triggerMinutes / 60)
                set(Calendar.MINUTE, triggerMinutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (currentMinutes >= triggerMinutes) {
                // 已过触发时间且课未开始：立即补发，用统一 dedupId 避免重复/漏发
                logD(context, "schedulePre: immediate ${course.name} cur=$currentMinutes trigger=$triggerMinutes")
                if (currentMinutes < startTotalMinutes) {
                    val dedupId = "${course.name}|${course.getTimeDisplayText()}|$startTime"
                    val alreadySent = isPreClassSentRecently(context, dedupId) ||
                        (useIsland && hasIslandPreClassSentToday(context, dedupId))
                    if (!alreadySent) {
                        val startMillis = todayMillis(startHour, startMinute)
                        val endMillis = parseTimeToTodayMillis(getCourseEndTime(course, repository))
                        sendPreClassNotification(
                            context, alarmManager, repository, course, startTime,
                            useIsland, startMillis, endMillis
                        )
                        logD(context, "schedulePre: immediate-SENT ${course.name}")
                    }
                }
                continue
            }
            logD(context, "schedulePre: alarm ${course.name} trigger=$triggerMinutes")

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_REMINDER_TYPE, TYPE_PRE_CLASS)
                putExtra(EXTRA_COURSE_NAME, course.name)
                putExtra(EXTRA_COURSE_SECTION, course.getTimeDisplayText())
                // START_TIME 作 fallback（旧闹钟无 EXTRA_COURSE_ID 时按 name+section+time 匹配）
                putExtra(EXTRA_COURSE_START_TIME, startTime)
                putExtra(EXTRA_COURSE_ID, course.id)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                course.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            setCourseBoundaryAlarm(alarmManager, alarmTime.timeInMillis, pendingIntent)
            scheduledRcs.add(course.id.hashCode())
        }

        writeRcSet(context, KEY_PRE_CLASS_RCS, scheduledRcs)
    }

    private fun scheduleNextDayAlarm(
        context: Context,
        repository: CourseRepository,
        alarmManager: AlarmManager
    ) {
        if (!isSemesterStarted(repository)) return

        val currentWeek = repository.getLiveTeachingWeek()
        val totalWeeks = repository.getTotalWeeks()
        val lastWeekWithCourses = repository.getLastWeekWithCourses()
        if (currentWeek < 1 || currentWeek > totalWeeks || currentWeek > lastWeekWithCourses) return

        val hour = repository.getNextDayReminderHour()
        val minute = repository.getNextDayReminderMinute()

        val now = Calendar.getInstance()
        val alarmTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DATE, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_TYPE, TYPE_NEXT_DAY)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            99999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmTime.timeInMillis,
                pendingIntent
            )
        } catch (_: SecurityException) { }
    }

    private fun scheduleWidgetRefresh(context: Context, alarmManager: AlarmManager) {
        // 链式调度：每次触发后由 WidgetRefreshReceiver 重新注册下一次
        scheduleNextWidgetRefresh(context, alarmManager)
    }

    internal fun capRefreshAtMidnight(result: Long, now: Long, nextMidnight: Long): Long =
        maxOf(minOf(result, nextMidnight), now + 1_000L)

    // 有课进行中/临近课：下一分钟整点（并对齐上课瞬间）；否则下次课程前或 30 分钟
    fun computeNextWidgetRefreshTime(context: Context): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val repository = CourseRepository(context)
        val todayCourses = getTodayCourses(context)

        // 倒计时/岛激活时需每分钟刷新，便于更新文案与对账补切
        val countdownPrefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        val hasActiveCountdown = countdownPrefs.getBoolean("active", false) ||
            IslandNotificationHelper.IslandState.isActiveAny(context)

        // 课中提醒：剩余分钟与进度都按整分钟量化，只有跨分钟才会变，真实课与测试课同理。
        // 所以取活跃的那份快照对齐分钟边界即可——再密的轮询也只是在重复算同一个值、白白多唤醒。
        val inClassEndMillis = if (IslandNotificationHelper.isInClassReminderEnabled(context)) {
            // 真实岛与测试岛互斥，命中任一处于课中态的快照
            IslandNotificationHelper.IslandState.snapshot(context, testMode = false)
                ?.takeIf { it.switched && it.endMillis > now }?.endMillis
                ?: IslandNotificationHelper.IslandState.snapshot(context, testMode = true)
                    ?.takeIf { it.switched && it.endMillis > now }?.endMillis
        } else null

        // 课前提醒窗口内也保持每分钟刷新，保证补发与倒计时都被驱动
        val minutesBefore = repository.getPreClassReminderMinutes()
        logD(context, "computeNext: courses=${todayCourses.size} countdown=$hasActiveCountdown inClassEnd=$inClassEndMillis")
        var hasActiveCourse = hasActiveCountdown
        var nextEventTime: Long? = null
        // 用于把刷新对齐到上课瞬间，避免倒计时归零后等一整分钟才切"已上课"
        var imminentStartMillis: Long? = null

        /*
         * 「分钟级消费者」：只有它们才需要"课中每分钟唤醒一次"。
         *
         * 原来只要有一节课正在上就置 hasActiveCourse=true —— 与任何开关、任何小组件都无关，
         * 于是即便所有提醒都关掉、桌面也没放小组件，仍旧是**每分钟一次精确唤醒**，
         * 一学期下来是每天数百次纯空唤醒（每次还要跑一遍 receiver 的整条对账链）。
         *
         * 注意：这里只是**降低轮询密度**，刷新链本身仍然是 setExactAndAllowWhileIdle 精确闹钟，
         * 不能改成非精确（见 scheduleNextWidgetRefresh 上方的注释：链式调度一旦漂移会累积，
         * 课前补发与跨日重调度会整个失效）。
         */
        val minuteLevelConsumer = hasActiveCountdown ||
            inClassEndMillis != null ||
            WidgetUpdateCache.anyProviderWidgets(context)

        for (course in todayCourses) {
            val startTime = getCourseStartTime(course, repository)
            val endTime = getCourseEndTime(course, repository)
            if (startTime == null || endTime == null) continue

            val startParts = startTime.split(":")
            val endParts = endTime.split(":")
            if (startParts.size != 2 || endParts.size != 2) continue

            val startMin = (startParts[0].toIntOrNull() ?: 0) * 60 + (startParts[1].toIntOrNull() ?: 0)
            val endMin = (endParts[0].toIntOrNull() ?: 0) * 60 + (endParts[1].toIntOrNull() ?: 0)

            if (currentMinutes in startMin until endMin) {
                if (minuteLevelConsumer) {
                    hasActiveCourse = true
                    break
                }
                // 没有分钟级消费者：不为"这节课正在上"每分钟唤醒，
                // 但要把下课本身上升为一个事件点（否则会退化到 30 分钟兜底心跳）
                val endCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, endParts[0].toInt())
                    set(Calendar.MINUTE, endParts[1].toInt())
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (endCal.timeInMillis > now &&
                    (nextEventTime == null || endCal.timeInMillis < nextEventTime!!)
                ) {
                    nextEventTime = endCal.timeInMillis
                }
                continue
            }

            val minutesToStart = startMin - currentMinutes
            if (minutesToStart >= 0 && minutesToStart <= minutesBefore) {
                hasActiveCourse = true
                imminentStartMillis = parseTimeToTodayMillis(startTime).takeIf { it > 0 }
                break
            }

            if (minutesToStart > 0) {
                val startCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, startParts[0].toInt())
                    set(Calendar.MINUTE, startParts[1].toInt())
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (startCal.timeInMillis > now) {
                    if (nextEventTime == null || startCal.timeInMillis < nextEventTime) {
                        nextEventTime = startCal.timeInMillis
                    }
                }
            }
        }

        val result = if (inClassEndMillis != null) {
            // 真实课与测试课统一对齐分钟边界；跨分钟时自然会重算剩余时间与进度
            now + IslandNotificationHelper.msUntilNextMinuteBoundary(inClassEndMillis - now)
        } else if (hasActiveCourse) {
            val nextMinute = Calendar.getInstance().apply {
                add(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val start = imminentStartMillis
            if (start != null && start > now) minOf(nextMinute, start + 1_000L) else nextMinute
        } else if (nextEventTime != null) {
            // 提前 5 分钟进入倒计时，最迟 30 分钟避免跨日检测延迟
            val earlyRefresh = nextEventTime - 5 * 60 * 1000L
            val maxRefresh = now + 30 * 60 * 1000L
            minOf(earlyRefresh, maxRefresh)
        } else {
            now + 30 * 60 * 1000L
        }
        // clamp 到 now+1s，否则过去时间会让 setExactAndAllowWhileIdle 立即触发连刷
        val nextMidnight = Calendar.getInstance().apply {
            add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val safe = capRefreshAtMidnight(result, now, nextMidnight)
        logD(context, "computeNext: active=$hasActiveCourse ret=$safe")
        return safe
    }

    // 这里曾按「关键 / 非关键时段」分流，非关键时段降级为 setAndAllowWhileIdle 以省电。
    // 已移除，勿再引入：刷新链是链式的（每次触发后才注册下一次），
    // 一旦掺入非精确闹钟，触发点漂移会逐次累积，Doze 深处延迟可达数小时，
    // 跨日重调度与课前补发会整个失效——用户反馈的「提醒不准时」正源于此。
    // 刷新链现在一律走 setExactAndAllowWhileIdle，见 scheduleNextWidgetRefresh。

    /**
     * 刷新链必须始终精确唤醒，不能用 setAndAllowWhileIdle 降级。
     * 它是链式调度：每次触发后才注册下一次。非精确闹钟会让触发点漂移，
     * 且漂移逐次累积——Doze 深处可能延迟数小时，跨日重调度与课前补发会整个失效。
     * 省下的那点电远不抵「提醒不准时」的代价。
     */
    fun scheduleNextWidgetRefresh(context: Context, alarmManager: AlarmManager) {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = WidgetRefreshReceiver.ACTION_REFRESH_WIDGET
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WIDGET_REFRESH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        val triggerAt = computeNextWidgetRefreshTime(context)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } catch (_: SecurityException) { }
    }

    // 只按节次相邻判断；不限上午/下午分段，否则跨段连堂识别不出、提醒会落进上一节课堂
    private fun isConsecutiveCourse(prev: Course, current: Course): Boolean {
        return prev.endSection + 1 == current.startSection
    }

    fun getTodayOfWeek(): Int {
        val calendar = Calendar.getInstance()
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    // 新代码直接用 CourseTimeResolver
    fun getCourseStartTime(course: Course, repository: CourseRepository): String? =
        com.haooz.chedule.data.CourseTimeResolver.getStartTime(course, repository)

    fun getCourseEndTime(course: Course, repository: CourseRepository): String? =
        com.haooz.chedule.data.CourseTimeResolver.getEndTime(course, repository)

    fun getLatestCourseEndTime(courses: List<Course>, repository: CourseRepository): String? =
        latestCourseEndTime(courses) { getCourseEndTime(it, repository) }

    internal fun latestCourseEndTime(
        courses: List<Course>,
        endTimeForCourse: (Course) -> String?,
    ): String? = courses.mapNotNull { course ->
        val rawTime = endTimeForCourse(course) ?: return@mapNotNull null
        val parts = rawTime.split(":")
        if (parts.size != 2) return@mapNotNull null
        val hour = parts[0].toIntOrNull() ?: return@mapNotNull null
        val minute = parts[1].toIntOrNull() ?: return@mapNotNull null
        if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
        (hour * 60 + minute) to rawTime
    }.maxByOrNull { it.first }?.second

    /**
     * 小组件/ContentProvider/今日页/次日提醒共用的某日课表解析。
     * 节假日默认空课，末日例外只返回命中课程；调休按 followWeekday/followWeek 映射。
     */
    data class DayScheduleResolution(
        val courses: List<Course>,
        /** 实际用于查课的星期（调休映射后） */
        val displayDayOfWeek: Int,
        /** 实际用于查课的周次 */
        val displayWeek: Int,
        /** 日历上的星期（未映射） */
        val calendarDayOfWeek: Int,
        /** 目标日是否为节假日 */
        val isHolidayDate: Boolean,
        /** 目标日是否配置了调休映射 */
        val isWorkSwap: Boolean,
        /** 目标日是节假日末日，且配置启用了节次课程排除 */
        val isHolidayEndCourseExclusionActive: Boolean = false,
    )

    /** 由开学日推目标日期所在日历课表周；仅作相对偏移，不直接当「当前周」 */
    fun calendarWeekForDate(repository: CourseRepository, date: LocalDate): Int =
        calendarWeekLongForDate(repository, date)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()

    private fun calendarWeekLongForDate(repository: CourseRepository, date: LocalDate): Long {
        return try {
            val start = LocalDate.parse(repository.getClassStartTime().replace("/", "-"))
            CourseScheduleDateBounds.calendarWeekForDate(start, date)
        } catch (_: Exception) {
            repository.getCurrentWeek().toLong()
        }
    }

    /**
     * 与主课表「当前周」对齐的目标日周次。
     * The semester start encodes a manual week adjustment, while a saved current_week
     * does not advance when the app remains closed across a week boundary.
     * 调休 followWeek 仍由调用方优先覆盖。
     */
    fun alignedStoredWeekForDate(repository: CourseRepository, date: LocalDate): Int {
        return repository.getLiveTeachingWeek(date)
    }

    internal fun liveTeachingWeek(
        semesterStartDate: LocalDate,
        date: LocalDate,
        rules: List<com.haooz.chedule.data.TeachingWeekReorganizationRule>,
    ): Int = com.haooz.chedule.data.TeachingWeekReorganization.mapDate(semesterStartDate, date, rules)
        .week.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    internal fun effectiveWeekendDays(
        semesterStartDate: LocalDate,
        week: Int,
        rules: List<com.haooz.chedule.data.TeachingWeekReorganizationRule>,
        hasCoursesOnDate: (LocalDate) -> Boolean,
    ): Set<Int> = (6..7).filterTo(mutableSetOf()) { weekday ->
        com.haooz.chedule.data.TeachingWeekReorganization
            .dateForPosition(semesterStartDate, week, weekday, rules)
            ?.let(hasCoursesOnDate) == true
    }

    internal fun canResolveCourseCandidates(
        datePosition: TeachingWeekPosition,
        workSwapEntry: HolidayManager.Entry?,
        displayWeek: Int,
        totalWeeks: Int,
        lastWeekWithCourses: Int,
    ): Boolean {
        val hasTeachingPosition = !datePosition.isReorganizationPause && datePosition.weekday != null
        val explicitlyMappedPause = workSwapEntry?.let {
            it.type == HolidayManager.TYPE_WORKSWAP && it.followWeek > 0 && it.followWeekday in 1..7
        } == true
        return (hasTeachingPosition || explicitlyMappedPause) &&
            displayWeek in 1..totalWeeks && displayWeek <= lastWeekWithCourses
    }

    fun resolveDaySchedule(context: Context, forTomorrow: Boolean): DayScheduleResolution {
        val today = LocalDate.now()
        return resolveDaySchedule(context, if (forTomorrow) today.plusDays(1) else today)
    }

    fun resolveDaySchedule(context: Context, date: LocalDate): DayScheduleResolution =
        resolveDaySchedule(context, date, CourseRepository(context))

    /** Same day resolution with a reusable repository for multi-date calculations. */
    fun resolveDaySchedule(
        context: Context,
        date: LocalDate,
        repository: CourseRepository,
    ): DayScheduleResolution = resolveDaySchedule(context, date, repository, null)

    /** Same day resolution with cached entries indexed by year for multi-date calculations. */
    fun resolveDaySchedule(
        context: Context,
        date: LocalDate,
        repository: CourseRepository,
        holidayEntriesByYear: Map<Int, List<HolidayManager.Entry>>?,
    ): DayScheduleResolution {
        val calendarDay = date.dayOfWeek.value
        val entriesByYear = holidayEntriesByYear ?: HolidayManager.loadAllByYear(context)
        val holidayEntries = HolidayManager.entriesForDate(entriesByYear, date)
        val targetEntry = holidayEntries.firstOrNull { it.type == HolidayManager.TYPE_WORKSWAP }
        val datePosition = repository.teachingWeekPositionForDate(date)
        val isWorkSwap = targetEntry?.followWeekday?.let { it in 1..7 } == true
        val displayDay = targetEntry?.followWeekday?.takeIf { it in 1..7 }
            ?: datePosition.weekday ?: calendarDay
        // 未配置调休覆盖时，用教学周映射相对今天的偏移，并避免今日调休 followWeek 污染。
        val displayWeek = targetEntry?.followWeek?.takeIf { it > 0 }
            ?: alignedStoredWeekForDate(repository, date)
        val exclusion = HolidayManager.loadEndCourseExclusion(context)
        return resolveDaySchedule(
            date = date,
            entriesByYear = entriesByYear,
            exclusion = exclusion,
            displayDayOfWeek = displayDay,
            displayWeek = displayWeek,
            calendarDayOfWeek = calendarDay,
            isWorkSwap = isWorkSwap,
            candidates = {
                val totalWeeks = repository.getTotalWeeks()
                val lastWeekWithCourses = repository.getLastWeekWithCourses()
                if (!canResolveCourseCandidates(
                        datePosition = datePosition,
                        workSwapEntry = targetEntry,
                        displayWeek = displayWeek,
                        totalWeeks = totalWeeks,
                        lastWeekWithCourses = lastWeekWithCourses,
                    )
                ) {
                    emptyList()
                } else {
                    repository.getAllCourses()
                        .filter { it.dayOfWeek == displayDay && it.isActiveInWeek(displayWeek) }
                        .sortedBy { getCourseStartTime(it, repository).toMinutes() }
                }
            },
            sectionTimes = repository::getCurrentSectionTimes,
            sectionCount = {
                repository.getMorningSections() +
                    repository.getAfternoonSections() + repository.getEveningSections()
            },
        )
    }

    internal fun resolveDaySchedule(
        date: LocalDate,
        entriesByYear: Map<Int, List<HolidayManager.Entry>>,
        exclusion: HolidayEndCourseExclusion,
        displayDayOfWeek: Int,
        displayWeek: Int,
        calendarDayOfWeek: Int,
        isWorkSwap: Boolean,
        candidates: () -> List<Course>,
        sectionTimes: () -> Map<Int, String>,
        sectionCount: () -> Int,
    ): DayScheduleResolution {
        val dayResolution = HolidayCourseExclusion.resolveDayCourses(
            entriesByYear = entriesByYear,
            date = date,
            exclusion = exclusion,
            candidates = candidates,
            sectionTimes = sectionTimes,
            sectionCount = sectionCount,
        )
        return DayScheduleResolution(
            courses = dayResolution.courses,
            displayDayOfWeek = displayDayOfWeek,
            displayWeek = displayWeek,
            calendarDayOfWeek = calendarDayOfWeek,
            isHolidayDate = dayResolution.isHolidayDate,
            isWorkSwap = isWorkSwap,
            isHolidayEndCourseExclusionActive = dayResolution.isHolidayEndCourseExclusionActive,
        )
    }

    /** 节假日/调休数据变更后：重排提醒并立即刷新已放置的小部件 */
    fun onHolidayDataChanged(context: Context) {
        // 顺手 bump 假期版本号：今日页状态卡、课表页豁免标记、小部件都靠它做 remember 键。
        // 节假日末期课程排除 / 余额 / 清单这些开关不走 HolidayManager.save，不 bump 的话
        // 关掉开关后今日页还会一直显示旧的「返校啦」卡片。
        HolidayManager.notifyConfigChanged(context)
        startReminderService(context)
        reconcileActiveHolidayCourse(context)
        ClassDndHelper.applyCurrentState(context)
        WidgetUpdateCache.updateInstalledWidgets(context)
        // 开关变化立刻对账一次精确闹钟（开→排上，关→取消），不必等下一次刷新链
        runCatching { checkDailyReturnDayNotifications(context) }
    }

    /** Remove a real ongoing course notification when holiday changes no longer resolve it for today. */
    private fun reconcileActiveHolidayCourse(context: Context) {
        val now = System.currentTimeMillis()
        val repository = CourseRepository(context)
        val countdownPrefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        val countdownStart = countdownPrefs.getLong("startMillis", 0L)
        val countdownEnd = countdownPrefs.getLong("endMillis", 0L)
        val countdownIsReal = !countdownPrefs.getBoolean("test_mode", false)
        if (countdownIsReal && countdownEnd > now && (countdownStart > 0L)) {
            val stillScheduled = isResolvedCourse(
                context = context,
                repository = repository,
                startMillis = countdownStart,
                endMillis = countdownEnd,
                courseName = countdownPrefs.getString("courseName", "") ?: "",
                classroom = countdownPrefs.getString("classroom", "") ?: "",
                section = countdownPrefs.getString("section", "") ?: "",
            )
            if (!stillScheduled) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(LIVE_COUNTDOWN_ID)
                manager.cancel(LIVE_STARTED_ID)
                manager.cancel(LIVE_IN_CLASS_ID)
                countdownPrefs.edit {
                    putBoolean("active", false)
                        .putBoolean("in_class_active", false)
                        .remove("last_displayed_minutes")
                        .remove("last_in_class_minutes")
                        .remove("last_in_class_progress")
                }
            }
        }

        val islandState = IslandNotificationHelper.IslandState.snapshot(context, testMode = false)
        if (islandState != null && islandState.endMillis > now && !isResolvedCourse(
                context = context,
                repository = repository,
                startMillis = islandState.startMillis,
                endMillis = islandState.endMillis,
                courseName = islandState.courseName,
                classroom = islandState.classroom,
                section = islandState.section,
            )
        ) {
            IslandNotificationHelper.cancelIslandState(context, islandState.notificationId)
            IslandNotificationHelper.IslandState.clear(context, testMode = false)
        }
    }

    private fun isResolvedCourse(
        context: Context,
        repository: CourseRepository,
        startMillis: Long,
        endMillis: Long,
        courseName: String,
        classroom: String,
        section: String,
    ): Boolean {
        val date = runCatching {
            java.time.Instant.ofEpochMilli(startMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
        }.getOrNull() ?: return false
        val resolution = resolveDaySchedule(context, date, repository)
        return resolution.courses.any { course ->
            course.name == courseName &&
                course.classroom == classroom &&
                course.getTimeDisplayText() == section &&
                hasSameLocalMinute(getCourseStartTime(course, repository), startMillis) &&
                hasSameLocalMinute(getCourseEndTime(course, repository), endMillis)
        }
    }

    /**
     * 次日提醒的文案 —— **主路径（AlarmReceiver）与测试页共用这一份**。
     *
     * 原来这段文本写死在 AlarmReceiver 的 onReceive 里，结果是：
     * 测试页没法单独验这条通知（"测试范围不全"里的一处），而且以后改文案得记得改一处不存在的地方。
     * 抽出来后两边都调它，文案只有一份。
     */
    fun nextDayReminderText(context: Context, repository: CourseRepository): Pair<String, String> =
        nextDayReminderText(getTomorrowCourses(context), repository)

    /**
     * 同上，但课表已由调用方取好 —— AlarmReceiver 里同一份"明天课表"还要给返校岛用，
     * 传进来可以避免一次闹钟把当天课表解析两遍（那正是这个项目已经优化过的点）。
     */
    fun nextDayReminderText(
        tomorrowCourses: List<Course>,
        repository: CourseRepository,
    ): Pair<String, String> {
        if (tomorrowCourses.isEmpty()) return "明日无课" to "明天没有课程安排"
        val firstCourse = tomorrowCourses.first()
        val firstStart = getCourseStartTime(firstCourse, repository)
        val firstHour = firstStart?.split(":")?.firstOrNull()?.toIntOrNull() ?: 9
        val details = when {
            firstHour < 9 -> "明早有早${chineseNumberHour(firstHour)}，${firstCourse.name}"
            firstHour < 12 -> "明早有课，${firstCourse.name} $firstStart"
            firstHour < 18 -> "下午有课，${firstCourse.name} $firstStart"
            else -> "晚上有课，${firstCourse.name} $firstStart"
        }
        return "明天共${tomorrowCourses.size}节课" to details
    }

    /** 星期几/钟点用的中文数字（原来在 AlarmReceiver 里，随文案一起搬过来） */
    private fun chineseNumberHour(n: Int): String {
        if (n <= 0 || n > 23) return n.toString()
        val digit = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        return when {
            n < 10 -> digit[n]
            n < 20 -> "十" + digit[n - 10]
            else -> digit[n / 10] + "十" + digit[n % 10]
        }
    }

    /** 测试用：立刻发一条次日提醒。不写任何"已发"状态，不影响正式提醒 */
    fun sendNextDayReminderNow(context: Context) {
        val repository = CourseRepository(context)
        val (title, body) = nextDayReminderText(context, repository)
        showReminderNotification(context, TYPE_NEXT_DAY, title, body)
    }

    fun getTomorrowCourses(context: Context): List<Course> =
        resolveDaySchedule(context, forTomorrow = true).courses

    fun getTodayCourses(context: Context): List<Course> =
        resolveDaySchedule(context, forTomorrow = false).courses

    fun findNextCourseToday(context: Context): Course? {
        val repository = CourseRepository(context)
        val courses = getTodayCourses(context)
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val upcoming = courses.firstOrNull { course ->
            val startTime = getCourseStartTime(course, repository) ?: return@firstOrNull false
            val parts = startTime.split(":")
            if (parts.size == 2) {
                val courseMinutes = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                courseMinutes > currentMinutes
            } else false
        }
        if (upcoming != null) return upcoming
        for (course in courses.reversed()) {
            val endTime = getCourseEndTime(course, repository) ?: continue
            val parts = endTime.split(":")
            if (parts.size == 2) {
                val endMinutes = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                if (currentMinutes < endMinutes) return course
            }
        }
        return null
    }

    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canPostPromotedNotifications()
    }

    // 与超级岛同口径：剩余分钟向上取整；进度按已上课比例（0→100）
    private fun inClassRemainMinutes(endMillis: Long, now: Long): Int {
        val remainMs = endMillis - now
        if (remainMs <= 0L) return 0
        return ((remainMs + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    }

    private fun inClassProgressPercent(startMillis: Long, endMillis: Long, remainMinutes: Int): Int {
        val totalMs = endMillis - startMillis
        if (totalMs <= 0L) return 0
        val remainMs = remainMinutes * 60_000L
        val elapsedMs = (totalMs - remainMs).coerceIn(0L, totalMs)
        return ((elapsedMs * 100L) / totalMs).toInt().coerceIn(0, 100)
    }

    /**
     * ColorOS（OPPO/realme/OnePlus）会优化进度条标头导致偏移，课中进度用系统默认 tracker。
     */
    private fun isColorOs(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val brand = android.os.Build.BRAND.lowercase(java.util.Locale.ROOT)
        val colorOsBrands = setOf("oppo", "realme", "oneplus")
        if (manufacturer in colorOsBrands || brand in colorOsBrands) return true
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            val rom = (get.invoke(null, "ro.build.version.oplusrom") as? String).orEmpty()
            rom.isNotBlank()
        }.getOrDefault(false)
    }

    /**
     * 假期余额 / 返校准备清单的每日提醒。
     *
     * 刻意搭在既有「小部件刷新链」上调用（[WidgetRefreshReceiver]），
     * **不新增任何闹钟类型**，用 SharedPreferences 做"当天只发一次"去重。
     * 两者都只用**普通通知**：不常驻、不提升、不占超级岛/实时动态的位置，
     * 避免与课前倒计时、课中进度争同一个岛位/通知位（那是最容易出暗病的地方）。
     */
    private fun ensureHolidayChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_HOLIDAY_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HOLIDAY_ID,
                "假期与返校",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "假期余额、返校准备清单"
            }
        )
    }

    fun checkDailyReturnDayNotifications(context: Context) {
        val repository = CourseRepository(context)
        val today = java.time.LocalDate.now()
        val todayKey = today.toString()
        val prefs = context.getSharedPreferences(RETURN_DAY_STATE_PREFS, Context.MODE_PRIVATE)

        // 假期余额：假期中每天一条（返校日当天不发，那天由返校提醒负责）
        val span = ReturnDayReminder.currentHolidaySpan(context, today)
        val balanceTarget = java.time.LocalTime.of(
            repository.getReturnDayBalanceHour(),
            repository.getReturnDayBalanceMinute(),
        )
        // 「假期余额」= 假期期间**每天**一条，**含最后一天**。
        //
        // 原来有两个排除条件：`daysLeft > 0` 与 `!isReturnDay(today)`。
        // 而假期的最后一天必然同时命中这两条（剩 0 天 + 次日要上课），
        // 于是整个假期里最该提醒的那天一条都不发 —— 用户实测「余额提醒设了 10:00 却没收到」。
        // 今日页早就在最后一天显示「今天是X第 N 天 · 最后一天」了，通知却漏掉同一天，两边口径不一致。
        // 现在统一为：只要在假期内就发；最后一天换成「最后一天」的文案。
        if (span != null &&
            repository.getReturnDayBalanceEnabled() &&
            balanceLeadDaysAllow(repository, span) &&
            !java.time.LocalTime.now().isBefore(balanceTarget) &&
            prefs.getString(KEY_RETURN_BALANCE_DATE, null) != todayKey
        ) {
            val (title, body) = balanceNotificationText(context, repository, span)
            showReminderNotification(
                context,
                NOTIFY_ID_RETURN_BALANCE,
                title,
                body,
                channelId = CHANNEL_HOLIDAY_ID,
            )
            prefs.edit { putString(KEY_RETURN_BALANCE_DATE, todayKey) }
        }

        // 返校准备清单：返校日到点推一条（默认关，文案可自定义）
        if (repository.getReturnDayPrepEnabled() && ReturnDayReminder.isReturnDay(context, today)) {
            val target = java.time.LocalTime.of(
                repository.getReturnDayPrepHour(),
                repository.getReturnDayPrepMinute(),
            )
            if (!java.time.LocalTime.now().isBefore(target) &&
                prefs.getString(KEY_RETURN_PREP_DATE, null) != todayKey
            ) {
                showReminderNotification(
                    context,
                    NOTIFY_ID_RETURN_PREP,
                    prepNotificationTitle(),
                    prepNotificationBody(repository),
                    channelId = CHANNEL_HOLIDAY_ID,
                )
                prefs.edit { putString(KEY_RETURN_PREP_DATE, todayKey) }
            }
        }

        maybeUpdateReturnDayLiveNotification(context, repository)

        // 精确闹钟（自续）：到点即发，不再依赖刷新链的下一跳（空闲时最坏晚约 30 分钟）。
        // 刷新链那套保留作兜底 —— 闹钟被系统清掉/Doze 延迟时仍能补发。
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (repository.getReturnDayBalanceEnabled()) {
            scheduleReturnDayExactAlarm(
                context, alarmManager, RC_RETURN_BALANCE_ALARM, TYPE_RETURN_DAILY_BALANCE, balanceTarget,
                KEY_RETURN_BALANCE_ALARM_AT, KEY_RETURN_BALANCE_ALARM_SET,
            )
        } else {
            cancelReturnDayExactAlarm(
                context, alarmManager, RC_RETURN_BALANCE_ALARM, TYPE_RETURN_DAILY_BALANCE,
                KEY_RETURN_BALANCE_ALARM_AT, KEY_RETURN_BALANCE_ALARM_SET,
            )
        }
        if (repository.getReturnDayPrepEnabled()) {
            scheduleReturnDayExactAlarm(
                context,
                alarmManager,
                RC_RETURN_PREP_ALARM,
                TYPE_RETURN_DAILY_PREP,
                java.time.LocalTime.of(
                    repository.getReturnDayPrepHour(),
                    repository.getReturnDayPrepMinute(),
                ),
                KEY_RETURN_PREP_ALARM_AT,
                KEY_RETURN_PREP_ALARM_SET,
            )
        } else {
            cancelReturnDayExactAlarm(
                context, alarmManager, RC_RETURN_PREP_ALARM, TYPE_RETURN_DAILY_PREP,
                KEY_RETURN_PREP_ALARM_AT, KEY_RETURN_PREP_ALARM_SET,
            )
        }
    }

    /**
     * 余额通知的标题/正文。**主路径与"立即测试"按钮共用这一份**，
     * 否则两处文案迟早会分叉（这正是本项目已经踩过的坑）。
     *
     * 「节假日末期课程排除」开着时，最后一天说"收拾一下准备返校吧"；
     * 关着时说明用户当天并没有返校课，就只说"好好休息"。
     *
     * ★ 判据必须是**上游那个开关**（[ReturnDayReminder.isExclusionEnabled]）：
     * 本 fork 早期那套自有豁免偏好已经没有设置入口了，读它会永远得到 false，
     * 于是出现"开关明明开着、最后一天却只说好好休息"。
     */
    private fun balanceNotificationText(
        context: Context,
        repository: CourseRepository,
        span: ReturnDayReminder.HolidaySpan,
    ): Pair<String, String> {
        val lastDay = span.daysLeft <= 0
        val title = if (lastDay) "今天是${span.name}最后一天" else "${span.name}还剩 ${span.daysLeft} 天"
        val body = when {
            !lastDay -> "好好休息，返校前我会再提醒你"
            ReturnDayReminder.isExclusionEnabled(context) -> "最后一天了，收拾一下准备返校吧"
            else -> "最后一天了，好好休息吧"
        }
        return title to body
    }

    /** 「只在假期最后 N 天开始提醒」：N=0（默认）表示不限制，假期期间每天都提醒。 */
    private fun balanceLeadDaysAllow(
        repository: CourseRepository,
        span: ReturnDayReminder.HolidaySpan,
    ): Boolean {
        val lead = repository.getReturnDayBalanceLeadDays()
        return lead <= 0 || span.daysLeft < lead
    }

    /** 清单通知标题：按实际时刻选措辞（写死"今晚"在中午会明显说错话）。 */
    private fun prepNotificationTitle(): String =
        if (java.time.LocalTime.now().hour >= 16) "今晚返校，别忘了带" else "返校别忘了带这些"

    /** 清单正文：逐行显示（BigTextStyle 展开后按行排）；用换行分隔就照用，否则按常见分隔符拆开。 */
    private fun prepNotificationBody(repository: CourseRepository): String {
        val raw = repository.getReturnDayPrepText()
        if (raw.contains('\n')) return raw
        return raw.split('/', '、', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { raw }
    }

    /**
     * 「立即发一条」——仅供设置页的测试按钮使用。
     *
     * 与正式提醒**共用同一套文案函数**，但**不写"当天只发一次"去重键**，
     * 所以随便点、点几次都行，也不会把当天的正式提醒顶掉。
     * 不在假期里时给一条演示文案，方便先看样式。
     */
    fun sendReturnDayReminderNow(context: Context, prep: Boolean) {
        val repository = CourseRepository(context)
        if (prep) {
            showReminderNotification(
                context,
                NOTIFY_ID_RETURN_PREP,
                prepNotificationTitle(),
                prepNotificationBody(repository),
                channelId = CHANNEL_HOLIDAY_ID,
            )
            return
        }
        val span = ReturnDayReminder.currentHolidaySpan(context, java.time.LocalDate.now())
        val (title, body) = if (span != null) {
            balanceNotificationText(context, repository, span)
        } else {
            // 不在假期里：正式提醒不会发，这里给演示
            "假期余额提醒（测试）" to "当前不在假期内，正式提醒会在假期中按设定时间发出"
        }
        showReminderNotification(context, NOTIFY_ID_RETURN_BALANCE, title, body, channelId = CHANNEL_HOLIDAY_ID)
    }

    /**
     * 把余额/清单的精确闹钟排到「今天该时刻」；已过则排到明天（自续链的锚点）。
     *
     * **去抖**：目标触发时刻没变就不重排。这个函数挂在刷新链上，而刷新链在倒计时/岛活跃时
     * 是**分钟级**的；原实现每次都 `AlarmManager.setAlarmClock` —— 每次都是跨进程调用 +
     * 系统侧闹钟图标刷新。为兼容"闹钟被系统清掉、进程被杀后 SP 仍在"的情况，保留 6 小时心跳：
     * 超过 6 小时没真正排过就强制重排一次。
     */
    private fun scheduleReturnDayExactAlarm(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        type: Int,
        target: java.time.LocalTime,
        triggerKey: String,
        heartbeatKey: String,
    ) {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, target.hour)
            set(Calendar.MINUTE, target.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DATE, 1)
        }
        val trigger = at.timeInMillis
        val now = System.currentTimeMillis()
        val state = context.getSharedPreferences(RETURN_DAY_STATE_PREFS, Context.MODE_PRIVATE)
        val sameTrigger = state.getLong(triggerKey, -1L) == trigger
        val recentEnough = now - state.getLong(heartbeatKey, 0L) < 6 * 60 * 60 * 1000L
        if (sameTrigger && recentEnough) return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_TYPE, type)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setCourseBoundaryAlarm(alarmManager, trigger, pendingIntent)
        state.edit {
            putLong(triggerKey, trigger)
            putLong(heartbeatKey, now)
        }
    }

    private fun cancelReturnDayExactAlarm(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        type: Int,
        triggerKey: String,
        heartbeatKey: String,
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_TYPE, type)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        // 清掉去抖状态：开关重新打开时必须能立刻排上
        context.getSharedPreferences(RETURN_DAY_STATE_PREFS, Context.MODE_PRIVATE)
            .edit { remove(triggerKey); remove(heartbeatKey) }
    }

    /**
     * 「明天返校」实时动态：返校日前一晚起，在开始前窗口内**定时重推**，让进度条随时间推进。
     *
     * 同样搭在既有刷新链上（不新增闹钟类型），并用 SP 做 15 分钟节流，
     * 避免每次刷新都重推（刷新链在课中会是分钟级）。
     * 岛开启时不走这里 —— 两个通道是互斥的。
     */
    private fun maybeUpdateReturnDayLiveNotification(
        context: Context,
        repository: CourseRepository,
    ) {
        if (repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)) return
        // 功能总开关关掉就不再推（原来只判 isReturnDay，关掉开关照样会推）。
        // 判据同上：上游「节假日末期课程排除」的开关。
        if (!ReturnDayReminder.isExclusionEnabled(context)) return
        val today = java.time.LocalDate.now()
        val tomorrow = today.plusDays(1)
        val todayIsReturn = ReturnDayReminder.isReturnDay(context, today)
        val tomorrowIsReturn = ReturnDayReminder.isReturnDay(context, tomorrow)
        if (!todayIsReturn && !tomorrowIsReturn) return
        val targetDate = if (todayIsReturn) today else tomorrow
        val resolution = resolveDaySchedule(context, targetDate)
        val first = resolution.courses.firstOrNull() ?: return
        val startTime = getCourseStartTime(first, repository) ?: return
        // 按**目标日期**算起始时刻。原来用 parseTimeToTodayMillis（按"今天"解析），
        // 于是 targetDate=明天 时算出来的时间戳已经过去，这一路永远提前 return ——
        // "返校日前一晚定时重推进度"在「明天返校」这个主要场景下根本没跑过。
        val startMillis = millisAtDate(targetDate, startTime)
        if (startMillis <= 0L) return
        val now = System.currentTimeMillis()
        // 窗口放到 24 小时：返校提醒是**前一晚**推的，此时距次日傍晚开课可能超过 12 小时；
        // 12 小时的窗口会让"前晚"这个主要时机被挡掉。已开始则不再重推。
        if (now >= startMillis || startMillis - now > 24 * 60 * 60 * 1000L) return
        val prefs = context.getSharedPreferences(RETURN_DAY_STATE_PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_RETURN_LIVE_PUSH, 0L) < 15 * 60 * 1000L) return
        val span = ReturnDayReminder.currentHolidaySpan(context, targetDate)
        showReturnDayLiveNotification(
            context = context,
            courseName = first.name,
            section = first.getTimeDisplayText(),
            startTime = startTime,
            targetDate = targetDate,
            holidayLabel = span?.name?.takeIf { it.isNotBlank() }?.let { "${it}最后一天" },
            progressPercent = span?.progressPercent,
        )
        prefs.edit { putLong(KEY_RETURN_LIVE_PUSH, now) }
    }

    /**
     * 「返校」提醒里的那个日子词 —— **原生实时动态与超级岛共用这一份**。
     *
     * 为什么不能写死「明天返校」：目标日（正文所依据的那天）可能是**今天**。
     * `ReturnDayReminder.isReturnDay(d) = isRestDay(d) && isSchoolDay(d+1)`，
     * 所以「返校日」就是假期最后一天：被「节假日末期课程排除」放出来的课（例如第11节 18:30 晚自习）
     * 是**今晚**的课。此时标题说「明天返校」、正文列今晚的课，自相矛盾
     * （真机实测：标题「明天返校 · 中秋节最后一天」，正文「道法｜第11节 18:30」）。
     * 而清单通知那边（prepNotificationTitle）一直说的是「今晚返校」 ——
     * 同一天两条提醒说不同的话，本质就是这个写死造成的。
     *
     * 规则（16:00 口径与 prepNotificationTitle 保持一致）：
     * - 目标日在今天之后 → 「明天返校」（次日提醒走这条，正文列的也正是明天的课）
     * - 目标日就是今天、首节在 16:00 之后 → 「今晚返校」
     * - 目标日就是今天、但首节更早 → 「今天返校」（人其实已经回校了，说「今晚」会晚一天）
     * - 其它（理论不可达）→ 「返校」
     */
    internal fun returnDayVerb(
        targetDate: java.time.LocalDate,
        firstStartTime: String?,
        today: java.time.LocalDate = java.time.LocalDate.now(),
    ): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, targetDate)
        if (days > 0L) return "明天返校"
        if (days < 0L) return "返校"
        val hour = firstStartTime?.substringBefore(':')?.trim()?.toIntOrNull()
        return if (hour != null && hour < 16) "今天返校" else "今晚返校"
    }

    /**
     * 「明天返校」原生实时动态通知。
     *
     * 与小米超级岛那条是同一功能的两个通道（提醒设置页的开关是互斥的：
     * 岛开走岛、岛关走原生），所以两边必须同步 —— 岛那边加了返校提醒，这里也要有。
     */
    @android.annotation.SuppressLint("NewApi")
    fun showReturnDayLiveNotification(
        context: Context,
        courseName: String,
        section: String,
        startTime: String,
        /** 正文字段所依据的日期（可能今天、可能明天）—— 日子词由它决定，不能写死 */
        targetDate: java.time.LocalDate,
        holidayLabel: String?,
        /** 非空且 API 36+ 时用 ProgressStyle 显示假期进度（复用课中那套实时动态规范） */
        progressPercent: Int? = null,
    ) {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 日子词必须跟着「正文列的是哪天的课」走：目标日就是今天时说「明天返校」会与正文矛盾
        val verb = returnDayVerb(targetDate, startTime)
        val title = if (holidayLabel.isNullOrBlank()) verb else "$verb · $holidayLabel"
        val content = buildString {
            if (courseName.isNotEmpty()) append(courseName)
            if (section.isNotEmpty()) append("｜").append(section)
            if (startTime.isNotEmpty()) append(" ").append(startTime)
            // 兜底文案不能说"明天要上课"：明天可能只是"要上学但没有课"，那样是假话
            if (isEmpty()) append("$verb，别忘了收拾东西")
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            RETURN_DAY_LIVE_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val timeoutMs = 12 * 60 * 60 * 1000L
        // 与课中同源：开关开且 API 36+ 走 ProgressStyle，否则退回 NotificationCompat
        val useProgressStyle =
            progressPercent != null && Build.VERSION.SDK_INT >= 36 &&
                CourseRepository(context).getLiveProgressStyle()
        if (useProgressStyle) {
            val styleBuilder = Notification.Builder(context, CHANNEL_LIVE_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setTimeoutAfter(timeoutMs)
            runCatching {
                styleBuilder.setStyle(
                    Notification.ProgressStyle()
                        .setProgressTrackerIcon(
                            android.graphics.drawable.Icon.createWithResource(
                                context,
                                R.drawable.ic_live_dot,
                            )
                        )
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
                        .setProgress(progressPercent.coerceIn(0, 100))
                )
            }
            promotedCatching("returnDay.setRequestPromotedOngoing") { styleBuilder.setRequestPromotedOngoing(true) }
            promotedCatching("returnDay.setShortCriticalText") { styleBuilder.setShortCriticalText(verb) }
            manager.notify(
                RETURN_DAY_LIVE_ID,
                styleBuilder.build().apply {
                    flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
                },
            )
            return
        }
        val baseBuilder = NotificationCompat.Builder(context, CHANNEL_LIVE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(
                if (progressPercent != null) {
                    Notification.CATEGORY_PROGRESS
                } else {
                    Notification.CATEGORY_REMINDER
                }
            )
            .setShortCriticalText(verb)
            .setRequestPromotedOngoing(true)
            .setTimeoutAfter(timeoutMs)
        if (progressPercent != null) {
            baseBuilder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        }
        val notification = baseBuilder.build().apply {
            flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
        }
        manager.notify(RETURN_DAY_LIVE_ID, notification)
    }

    /**
     * 原生实况课中进度：API 36+ 用 ProgressStyle + 提升 ongoing，与 SleepDown 同思路。
     * 每分钟只在剩余分钟/进度变化时重推，挂到下课自动结束。
     */
    @SuppressLint("NewApi")
    fun showOrUpdateInClassLiveNotification(
        context: Context,
        courseName: String,
        classroom: String,
        endTime: String,
        startMillis: Long,
        endMillis: Long,
        countdownNotificationId: Int,
        testMode: Boolean = false
    ) {
        ensureNotificationChannels(context)
        val now = System.currentTimeMillis()
        if (endMillis <= now) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val inClassId = liveInClassId(testMode)
        val startedId = liveStartedId(testMode)
        val countdownId = liveCountdownId(testMode)

        val prefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        val wasInClass = prefs.getBoolean("in_class_active", false)
        val remainMin = inClassRemainMinutes(endMillis, now)
        val progress = inClassProgressPercent(startMillis, endMillis, remainMin)
        // 进度条改为「倒计时式」：总量 = 这节课总分钟数，进度 = 剩余分钟。
        // 原 elapsed/total 在 45 分钟课里每分钟只进 2.2%，肉眼像静止（反馈"像假的"）。
        val liveTotalMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
        val liveProgress = remainMin.coerceIn(0, liveTotalMinutes)
        val lastMin = prefs.getInt("last_in_class_minutes", -1)
        val lastProgress = prefs.getInt("last_in_class_progress", -1)

        // 首次切入课中：收起倒计时/已上课，避免三态并存
        if (!wasInClass) {
            manager.cancel(countdownId)
            manager.cancel(startedId)
            // 清掉另一套（真实/测试）残留，避免双通道并存
            if (testMode) {
                manager.cancel(LIVE_COUNTDOWN_ID)
                manager.cancel(LIVE_STARTED_ID)
            } else {
                manager.cancel(LIVE_TEST_COUNTDOWN_ID)
                manager.cancel(LIVE_TEST_STARTED_ID)
            }
        } else if (remainMin == lastMin && progress == lastProgress) {
            // 同分钟无变化，不重推，避免岛/胶囊闪烁
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context, courseName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endTimeLabel = if (endTime.isNotEmpty()) "${endTime}下课" else "下课"
        val infoLine = "$endTimeLabel · 还剩${remainMin}分钟"
        val expandedText = if (classroom.isBlank()) infoLine else "$infoLine\n$classroom"
        val shortCriticalText = "${remainMin}分钟"

        // 课中只保留进度卡片，不挂动作按钮（点整卡打开课表即可）
        // 两条路：① ProgressStyle（带 tracker 图标，观感最好，但部分机型不激活）
        //         ② NotificationCompat + setProgress（课前在用的那套，确保能激活）
        // 用 getLiveProgressStyle() 开关让用户自己选；默认走 ①。
        val useProgressStyle =
            Build.VERSION.SDK_INT >= 36 && CourseRepository(context).getLiveProgressStyle()
        val notification = if (useProgressStyle) {
            val styleBuilder = Notification.Builder(context, CHANNEL_LIVE_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(courseName)
                .setContentText(infoLine)
                // BigTextStyle 随后会被 ProgressStyle 覆盖，expandedText（含教室）会整个丢掉 ——
                // 降级路径（NotificationCompat + BigTextStyle）是带教室的，两条路观感不一致。
                // subText 正是"展开视图第三行"的官方位置，用它把教室补回来。
                .setStyle(Notification.BigTextStyle().bigText(expandedText))
                .apply { if (classroom.isNotBlank()) setSubText(classroom) }
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setTimeoutAfter(endMillis - now)
            runCatching {
                // 进度条用「倒计时式」：总量 = 总分钟，进度 = 剩余分钟（elapsed/total 每分钟只进 2.2%，肉眼像静止）
                val progressStyle = Notification.ProgressStyle()
                    .setProgressSegments(
                        listOf(Notification.ProgressStyle.Segment(liveTotalMinutes))
                    )
                    .setProgress(liveProgress)
                // ColorOS（OPPO/realme/OnePlus）会优化进度条标头导致偏移，这种情况走系统默认 tracker
                if (!isColorOs()) {
                    progressStyle.setProgressTrackerIcon(
                        android.graphics.drawable.Icon.createWithResource(
                            context,
                            R.drawable.ic_live_dot,
                        )
                    )
                }
                styleBuilder.setStyle(progressStyle)
            }
            promotedCatching("inClass.setRequestPromotedOngoing") { styleBuilder.setRequestPromotedOngoing(true) }
            promotedCatching("inClass.setShortCriticalText") { styleBuilder.setShortCriticalText(shortCriticalText) }
            styleBuilder.build().apply { flags = flags or Notification.FLAG_ONLY_ALERT_ONCE }
        } else {
            NotificationCompat.Builder(context, CHANNEL_LIVE_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(courseName)
                .setContentText(expandedText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setProgress(liveTotalMinutes, liveProgress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setTimeoutAfter(endMillis - now)
                .setShortCriticalText(shortCriticalText)
                .setRequestPromotedOngoing(true)
                .build()
                .apply {
                    flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
                }
        }
        manager.notify(inClassId, notification)
        prefs.edit {
            putBoolean("in_class_active", true)
                .putInt("in_class_notification_id", inClassId)
                .putInt("last_in_class_minutes", remainMin)
                .putInt("last_in_class_progress", progress)
        }
    }

    private fun cancelInClassLiveNotification(
        context: Context,
        testMode: Boolean = false
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(liveInClassId(testMode))
        val prefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("in_class_active", false)
                .remove("last_in_class_minutes")
                .remove("last_in_class_progress")
        }
    }

    /** 15 秒「已上课」实况；独立 ID，与倒计时/课中错开。 */
    private fun showStartedLiveNotification(
        context: Context,
        courseName: String,
        startTime: String,
        testMode: Boolean = false
    ) {
        ensureNotificationChannels(context)
        val startedIntent = PendingIntent.getActivity(
            context, courseName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dndIntent = Intent(context, ClassDndReceiver::class.java).apply {
            // 测试课已并入 isInClass，与真实课共用同一条按钮链路
            action = ClassDndReceiver.ACTION_TOGGLE
        }
        val dndPendingIntent = PendingIntent.getBroadcast(
            context,
            courseName.hashCode() + 100,
            dndIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val bigText = buildString {
            if (startTime.isNotEmpty()) {
                append(startTime)
                append(" · ")
            }
            append("现在上课")
        }
        val startedNotification = NotificationCompat.Builder(context, CHANNEL_LIVE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(courseName)
            .setShortCriticalText(courseName)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
            )
            .setOngoing(true)
            .setContentIntent(startedIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setRequestPromotedOngoing(true)
            .addAction(R.drawable.ic_notification_calendar, "查看课表", startedIntent)
            .addAction(R.drawable.ic_notification_mute, "上课勿扰", dndPendingIntent)
            .setTimeoutAfter(15_000L)
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
            }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val startedNotificationId = liveStartedId(testMode)
        context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
            .edit { putInt("started_notification_id", startedNotificationId) }
        manager.notify(startedNotificationId, startedNotification)
    }

    /**
     * 通道是**进程级**系统状态，建一次就够。
     *
     * 原实现每次发通知都 `createNotificationChannel` ×2 —— 那就是 2 次 binder 往返 +
     * 2 个 NotificationChannel 分配。课中提醒是**每分钟**发一次的，
     * 等于每分钟白付两次跨进程调用去设置同一个通道。
     *
     * 但**不能只用一个布尔量永久跳过**：用户可以在系统设置里把通道删掉，
     * 而 `createNotificationChannel` 正好是"被删了会重建"的那条路径；
     * 永久跳过会让之后的通知静默丢弃。所以做成 10 分钟心跳复核：
     * 每分钟那条热路径照样零开销，通道被删也能在 10 分钟内自愈。
     */
    private const val CHANNEL_ENSURE_INTERVAL_MS = 10 * 60 * 1000L

    @Volatile
    private var notificationChannelsEnsuredAt = 0L

    private fun ensureNotificationChannels(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - notificationChannelsEnsuredAt < CHANNEL_ENSURE_INTERVAL_MS) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val alertChannel = NotificationChannel(
            CHANNEL_REMINDER_ID,
            CHANNEL_REMINDER_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "课程提醒通知"
            setShowBadge(true)
        }
        val liveChannel = NotificationChannel(
            CHANNEL_LIVE_ID,
            CHANNEL_LIVE_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "课程提醒实时进度"
            setShowBadge(true)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // 绕过勿扰：本频道承载倒计时/已上课/课中进度，若不放行，
            // 开「上课勿扰」后用户特意打开的课中提醒就永远看不见了。
            // 注意：仅对 PRIORITY 档生效；完全勿扰（INTERRUPTION_FILTER_NONE）
            // 属系统级全静音，bypassDnd 无法放行。
            setBypassDnd(true)
        }
        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(liveChannel)
        notificationChannelsEnsuredAt = android.os.SystemClock.elapsedRealtime()
    }

    fun showReminderNotification(
        context: Context,
        id: Int,
        title: String,
        message: String,
        channelId: String = CHANNEL_REMINDER_ID,
    ) {
        ensureNotificationChannels(context)
        if (channelId == CHANNEL_HOLIDAY_ID) ensureHolidayChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message.replace("\n", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    fun showPreClassCountdownNotification(
        context: Context,
        courseName: String,
        classroom: String,
        section: String,
        startTime: String,
        startMillis: Long,
        endMillis: Long,
        testMode: Boolean = false
    ) {
        ensureNotificationChannels(context)

        val contentIntent = PendingIntent.getActivity(
            context, courseName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dndIntent = Intent(context, ClassDndReceiver::class.java).apply {
            // 测试课已并入 isInClass，与真实课共用同一条按钮链路
            action = ClassDndReceiver.ACTION_TOGGLE
        }
        val dndPendingIntent = PendingIntent.getBroadcast(
            context,
            courseName.hashCode() + 100,
            dndIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = liveCountdownId(testMode)
        // 向上取整，与系统倒计时剩余秒数一致
        val minutesUntilStart = ceilMinutesUntil(startMillis)

        // 右侧显示模式：0=课程名称，1=上课地点，2=倒计时
        val reminderPrefs = context.getSharedPreferences("course_reminder_prefs", Context.MODE_PRIVATE)
        val collapsedMode = reminderPrefs.getInt("live_right_mode", 0)
        val shortCriticalText = when (collapsedMode) {
            0 -> courseName
            1 -> classroom.ifEmpty { courseName }
            2 -> "${minutesUntilStart}分钟"
            else -> courseName
        }

        val bigText = buildPreClassBigText(
            minutesUntilStart = minutesUntilStart,
            startTime = startTime,
            endTime = formatClock(endMillis),
            classroom = classroom
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(courseName)
            .setShortCriticalText(shortCriticalText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
            )
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setRequestPromotedOngoing(true)
            .addAction(R.drawable.ic_notification_calendar, "查看课表", contentIntent)
            .addAction(R.drawable.ic_notification_mute, "上课勿扰", dndPendingIntent)
            .apply {
                val timeout = endMillis - System.currentTimeMillis()
                if (timeout > 0) setTimeoutAfter(timeout)
            }
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
            }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 互斥：发真实课时清测试，发测试时清真实，避免双通道并存
        if (testMode) {
            manager.cancel(LIVE_COUNTDOWN_ID)
            manager.cancel(LIVE_STARTED_ID)
            manager.cancel(LIVE_IN_CLASS_ID)
        } else {
            manager.cancel(LIVE_TEST_COUNTDOWN_ID)
            manager.cancel(LIVE_TEST_STARTED_ID)
            manager.cancel(LIVE_TEST_IN_CLASS_ID)
        }
        manager.notify(notificationId, notification)

        // 倒计时状态供 WidgetRefreshReceiver 每分钟更新
        val prefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("active", true)
                .putBoolean("test_mode", testMode)
                .putString("courseName", courseName)
                .putString("classroom", classroom)
                .putString("section", section)
                .putString("startTime", startTime)
                .putLong("startMillis", startMillis)
                .putLong("endMillis", endMillis)
                .putInt("notificationId", notificationId)
                .putBoolean("in_class_active", false)
                .remove("last_displayed_minutes")
                .remove("last_in_class_minutes")
                .remove("last_in_class_progress")
                .remove("started_shown")
        }

        // 精确闹钟保证到点可靠更新
            val delay = startMillis - System.currentTimeMillis()
        if (delay > 0) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = startMillis + 100L
            val alarmIntent = Intent(context, CourseStartReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setCourseBoundaryAlarm(alarmManager, triggerAt, pendingIntent)
        }

        // 启动 widget 刷新链，确保倒计时每分钟更新
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleNextWidgetRefresh(context, alarmManager)
    }

    /**
     * 测试实时活动：与超级岛测试同一套固定课程，不依赖真实课表。
     * 课前 70 秒倒计时 + 课中 120 秒，便于验证倒计时 / 已上课 / 课中进度全链路。
     */
    fun sendTestLiveNotification(
        context: Context,
        /** 距"上课"还有多久（0 = 已经上课了） */
        startDelayMs: Long = 70_000L,
        /** 课时长度 */
        durationMs: Long = 120_000L,
        /** 直接落到哪个状态；COUNTDOWN 走完整时序 */
        phase: ReminderTestPhase = ReminderTestPhase.COUNTDOWN,
    ) {
        val courseName = "大学英语Ⅱ"
        val classroom = "博A201"
        val section = "第3~4节"
        val startMillis = System.currentTimeMillis() + startDelayMs
        val endMillis = startMillis + durationMs
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val startTime = fmt.format(java.util.Date(startMillis))
        val endTime = fmt.format(java.util.Date(endMillis))

        if (phase == ReminderTestPhase.COUNTDOWN) {
            showPreClassCountdownNotification(
                context = context,
                courseName = courseName,
                classroom = classroom,
                section = section,
                startTime = startTime,
                startMillis = startMillis,
                endMillis = endMillis,
                testMode = true
            )
            return
        }

        // 「已上课 / 课中」两态都读 countdown_state（对账、下课收起、进度都靠它），
        // 所以先落一份与主路径同构的状态，再直接进对应状态（绕过用户开关）。
        writeTestCountdownState(
            context = context,
            courseName = courseName,
            classroom = classroom,
            section = section,
            startTime = startTime,
            startMillis = startMillis,
            endMillis = endMillis,
        )
        // 测试课也要走「上课自动开启、下课自动关闭」，否则勿扰链路在测试里跑不到
        ClassDndHelper.syncTestClassDndAlarms(context)
        if (phase == ReminderTestPhase.STARTED) {
            // 上游的 showStartedLiveNotification 已不带 classroom（真实路径也不传，正文改为
            // 由函数内部拼；测试路径保持一致，否则两边的观感就不一样了）
            showStartedLiveNotification(
                context = context,
                courseName = courseName,
                startTime = startTime,
                testMode = true
            )
        } else {
            showOrUpdateInClassLiveNotification(
                context = context,
                courseName = courseName,
                classroom = classroom,
                endTime = endTime,
                startMillis = startMillis,
                endMillis = endMillis,
                countdownNotificationId = liveCountdownId(true),
                testMode = true,
            )
        }
    }

    /** 测试用：写与 showPreClassCountdownNotification 同构的倒计时状态（键名必须一致） */
    private fun writeTestCountdownState(
        context: Context,
        courseName: String,
        classroom: String,
        section: String,
        startTime: String,
        startMillis: Long,
        endMillis: Long,
    ) {
        context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE).edit {
            putBoolean("active", true)
                .putBoolean("test_mode", true)
                .putBoolean("in_class_active", false)
                .putString("courseName", courseName)
                .putString("classroom", classroom)
                .putString("section", section)
                .putString("startTime", startTime)
                .putLong("startMillis", startMillis)
                .putLong("endMillis", endMillis)
                .putInt("notificationId", liveCountdownId(true))
                .remove("last_displayed_minutes")
                .remove("last_in_class_minutes")
                .remove("last_in_class_progress")
                .remove("started_shown")
        }
    }

    // 每分钟由 WidgetRefreshReceiver 驱动；同 notifyId 重复 notify 无痕更新
    fun updateActiveCountdown(context: Context) {
        val prefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return

        // 岛模式下不显示实况通知：收起残留实况，改由岛状态对账接管
        val repository = CourseRepository(context)
        if (repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)) {
            if (prefs.getBoolean("active", false)) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(LIVE_COUNTDOWN_ID)
                manager.cancel(LIVE_STARTED_ID)
                manager.cancel(LIVE_IN_CLASS_ID)
                manager.cancel(LIVE_TEST_COUNTDOWN_ID)
                manager.cancel(LIVE_TEST_STARTED_ID)
                manager.cancel(LIVE_TEST_IN_CLASS_ID)
                prefs.edit {
                    putBoolean("active", false)
                        .putBoolean("in_class_active", false)
                }
            }
            reconcileIslandCountdown(context)
            return
        }

        val testMode = prefs.getBoolean("test_mode", false)
        val startMillis = prefs.getLong("startMillis", 0L)
        val endMillis = prefs.getLong("endMillis", 0L)
        val notificationId = liveCountdownId(testMode)
        val courseName = prefs.getString("courseName", "") ?: ""
        val classroom = prefs.getString("classroom", "") ?: ""
        val startTime = prefs.getString("startTime", "") ?: ""

        val now = System.currentTimeMillis()

        // 课程结束：取消通知并清状态
        if (endMillis in 1..now) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(liveCountdownId(testMode))
            manager.cancel(liveStartedId(testMode))
            cancelInClassLiveNotification(context, testMode)
            IslandNotificationHelper.cancelIslandState(context, ISLAND_NOTIFICATION_ID)
            prefs.edit { putBoolean("active", false) }
            return
        }

        // 到点：优先课中进度，否则发 15 秒「已上课」
        if (now >= startMillis) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val inClassOn = isInClassLiveEnabled(context) && endMillis > now
            val inClassNow = inClassOn && shouldShowInClassNow(context, startMillis, endMillis, now)

            if (inClassNow) {
                // 课中：保持 countdown_state active，挂到下课由每分钟刷新驱动
                if (!prefs.getBoolean("in_class_active", false)) {
                    manager.cancel(liveCountdownId(testMode))
                    IslandNotificationHelper.cancelIslandState(context, ISLAND_NOTIFICATION_ID)
                }
                val endTimeText = if (endMillis > 0L) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(endMillis))
                } else {
                    ""
                }
                showOrUpdateInClassLiveNotification(
                    context = context,
                    courseName = courseName,
                    classroom = classroom,
                    endTime = endTimeText,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    countdownNotificationId = liveCountdownId(testMode),
                    testMode = testMode
                )
                return
            }

            if (inClassOn && !inClassNow) {
                // 课中已开但「距下课」未进窗：不发「已上课」，保持 active 等进窗再切课中。
                // 但上课时刻已过，倒计时卡失去意义，必须收起，否则会一直停在 00:00。
                manager.cancel(liveCountdownId(testMode))
                IslandNotificationHelper.cancelIslandState(context, ISLAND_NOTIFICATION_ID)
                return
            }

            // 课中未开：到点闪一次「已上课」
            manager.cancel(liveCountdownId(testMode))
            IslandNotificationHelper.cancelIslandState(context, ISLAND_NOTIFICATION_ID)
            cancelInClassLiveNotification(context, testMode)
            prefs.edit { putBoolean("active", false) }
            showStartedLiveNotification(
                context = context,
                courseName = courseName,
                startTime = startTime,
                testMode = testMode
            )
            return
        }

        val collapsedPrefs = context.getSharedPreferences("course_reminder_prefs", Context.MODE_PRIVATE)
        val collapsedMode = collapsedPrefs.getInt("live_right_mode", 0)

        val minutesUntilStart = ceilMinutesUntil(startMillis, now)

        // 只在分钟数变化时重建（正文含手动倒计时，各模式都要刷）
        val lastDisplayedMinutes = prefs.getInt("last_displayed_minutes", -1)
        if (minutesUntilStart == lastDisplayedMinutes) return
        val shortCriticalText = when (collapsedMode) {
            0 -> courseName
            1 -> classroom.ifEmpty { courseName }
            2 -> "${minutesUntilStart}分钟"
            else -> courseName
        }

        val bigText = buildPreClassBigText(
            minutesUntilStart = minutesUntilStart,
            startTime = startTime,
            endTime = formatClock(endMillis),
            classroom = classroom
        )

        val contentIntent = PendingIntent.getActivity(
            context, courseName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dndIntent = Intent(context, ClassDndReceiver::class.java).apply {
            // 测试课已并入 isInClass，与真实课共用同一条按钮链路
            action = ClassDndReceiver.ACTION_TOGGLE
        }
        val dndPendingIntent = PendingIntent.getBroadcast(
            context,
            courseName.hashCode() + 100,
            dndIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(courseName)
            .setShortCriticalText(shortCriticalText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
            )
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setRequestPromotedOngoing(true)
            .addAction(R.drawable.ic_notification_calendar, "查看课表", contentIntent)
            .addAction(R.drawable.ic_notification_mute, "上课勿扰", dndPendingIntent)
            .apply {
                val timeout = endMillis - now
                if (timeout > 0) setTimeoutAfter(timeout)
            }
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
            }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)

        prefs.edit { putInt("last_displayed_minutes", minutesUntilStart) }
    }

    // 立即补发课前提醒（含去重）。调用方负责判断是否处于提醒窗口内。
    // courseStartMillis 是岛倒计时/文案/切换闹钟的唯一真源
    fun sendPreClassNotification(
        context: Context,
        alarmManager: AlarmManager,
        repository: CourseRepository,
        course: Course,
        startTime: String,
        useIsland: Boolean,
        courseStartMillis: Long,
        courseEndMillis: Long = 0L
    ) {
        // 校验通过后再记录去重，避免无效时间污染 60 分钟窗口拦截后续合法发送
        if (courseStartMillis <= 0L) return
        val dedupId = "${course.name}|${course.getTimeDisplayText()}|$startTime"
        recordPreClassSent(context, dedupId)

        val safeEndMillis = courseEndMillis.coerceAtLeast(0L)
        val nowMs = System.currentTimeMillis()

        if (courseStartMillis <= nowMs) {
            // 连堂课间为 0 时触发点可能已过上课时刻：落"已上课"；超宽限期则不再打扰
            val elapsed = nowMs - courseStartMillis
            if (elapsed > ISLAND_START_GRACE_MS) {
                logD(context, "sendPreClass: ${course.name} started ${elapsed}ms ago, too late, skip")
                return
            }
            logD(context, "sendPreClass: ${course.name} just started, show started state")
            if (useIsland) {
                IslandNotificationHelper.sendPreClassIslandNotification(
                    context = context,
                    courseName = course.name,
                    classroom = course.classroom,
                    section = course.getTimeDisplayText(),
                    startTime = startTime,
                    endTime = getCourseEndTime(course, repository),
                    teacher = course.teacher,
                    courseStartMillis = courseStartMillis,
                    courseEndMillis = safeEndMillis,
                    notificationId = ISLAND_NOTIFICATION_ID
                )
            } else {
                showPreClassCountdownNotification(
                    context, course.name, course.classroom, course.getTimeDisplayText(),
                    startTime, courseStartMillis, safeEndMillis
                )
                updateActiveCountdown(context)
            }
            return
        }

        if (useIsland) {
            val endTime = getCourseEndTime(course, repository)
            IslandNotificationHelper.sendPreClassIslandNotification(
                context = context,
                courseName = course.name,
                classroom = course.classroom,
                section = course.getTimeDisplayText(),
                startTime = startTime,
                endTime = endTime,
                teacher = course.teacher,
                courseStartMillis = courseStartMillis,
                courseEndMillis = safeEndMillis,
                notificationId = ISLAND_NOTIFICATION_ID
            )
            scheduleIslandExpandAlarm(
                context, alarmManager, course.name, course.classroom,
                course.getTimeDisplayText(), startTime, endTime, courseStartMillis
            )
        } else {
            showPreClassCountdownNotification(
                context, course.name, course.classroom, course.getTimeDisplayText(),
                startTime, courseStartMillis, safeEndMillis
            )
        }
    }

    // RC 按自纪元起的分钟数 mod 100000 派生：同一天不会撞号
    private fun expandRequestCode(courseStartMillis: Long): Int =
        ISLAND_EXPAND_RC_BASE +
            kotlin.math.abs((courseStartMillis / 60_000L % 100_000L).toInt())

    fun scheduleIslandExpandAlarm(
        context: Context,
        alarmManager: AlarmManager,
        courseName: String,
        classroom: String,
        section: String,
        startTime: String,
        endTime: String?,
        courseStartMillis: Long,
        notificationId: Int = ISLAND_NOTIFICATION_ID
    ) {
        val expandIntent = Intent(context, IslandExpandReceiver::class.java).apply {
            putExtra(IslandExpandReceiver.EXTRA_COURSE_NAME, courseName)
            putExtra(IslandExpandReceiver.EXTRA_CLASSROOM, classroom)
            putExtra(IslandExpandReceiver.EXTRA_SECTION, section)
            putExtra(IslandExpandReceiver.EXTRA_START_TIME, startTime)
            putExtra(IslandExpandReceiver.EXTRA_END_TIME, endTime ?: "")
            // 必须与倒计时岛同 ID，"已上课"才能原地替换而非另起一个岛
            putExtra(IslandExpandReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(IslandExpandReceiver.EXTRA_COURSE_START_MILLIS, courseStartMillis)
        }
        val rc = expandRequestCode(courseStartMillis)
        val expandPending = PendingIntent.getBroadcast(
            context, rc, expandIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setCourseBoundaryAlarm(alarmManager, courseStartMillis, expandPending)
        writeRcSet(context, KEY_EXPAND_RCS, readRcSet(context, KEY_EXPAND_RCS) + rc)
    }

    // 每分钟对账：兜底切换闹钟丢失/Doze 延迟、岛残留、开关关闭后的清理
    // 同时扫真实岛与测试岛，否则「测试小米超级岛」课中进度永远不更新
    fun reconcileIslandCountdown(context: Context) {
        val repository = CourseRepository(context)
        val islandEnabled = repository.getIslandNotification() &&
            IslandNotificationHelper.isIslandSupported(context)

        if (!islandEnabled) {
            if (IslandNotificationHelper.IslandState.isActiveAny(context)) {
                IslandNotificationHelper.cancelIslandNotifications(context)
                IslandNotificationHelper.IslandState.clear(context)
                IslandNotificationHelper.IslandState.clear(context, testMode = true)
                logD(context, "reconcileIsland: island disabled, dismissed")
            }
            return
        }

        reconcileIslandState(context, testMode = false)
        reconcileIslandState(context, testMode = true)
    }

    private fun reconcileIslandState(context: Context, testMode: Boolean) {
        val state = IslandNotificationHelper.IslandState.snapshot(context, testMode) ?: return
        val now = System.currentTimeMillis()

        val inClassEnabled = IslandNotificationHelper.isInClassReminderEnabled(context)
        // 课中开启：不按 15 秒「已上课」收起；对账只负责进课中/刷进度/下课收起
        val keepForInClass = inClassEnabled && state.endMillis > now
        val shouldShowInClass = keepForInClass &&
            shouldShowInClassNow(context, state.startMillis, state.endMillis, now)

        val expiredByEnd = state.endMillis > 0 && now >= state.endMillis
        val expiredByShow = !inClassEnabled && state.switched &&
            now - state.switchedAt >= IslandNotificationHelper.ISLAND_STARTED_VISIBLE_MS
        if (expiredByEnd || expiredByShow) {
            // 连带收起另一半（倒计时/已上课），避免残留岛一直停在 00:00
            IslandNotificationHelper.cancelIslandState(context, state.notificationId)
            IslandNotificationHelper.IslandState.clear(context, testMode)
            logD(context, "reconcileIsland: dismissed test=$testMode end=$expiredByEnd show=$expiredByShow")
            return
        }

        // 到点但还没切换（闹钟丢失/延迟）→ 立即补切，最多滞后一个刷新周期
        if (!state.switched && now >= state.startMillis) {
            if (now - state.startMillis > ISLAND_LATE_TOLERANCE_MS) {
                // 隔夜或重启后残留的状态：不要再补一个过期的"已上课"，直接收起
                IslandNotificationHelper.cancelIslandState(context, state.notificationId)
                IslandNotificationHelper.IslandState.clear(context, testMode)
                logD(context, "reconcileIsland: stale state dismissed for ${state.courseName}")
                return
            }
            logD(context, "reconcileIsland: late switch for ${state.courseName}")
            IslandNotificationHelper.onClassStart(
                context = context,
                courseName = state.courseName,
                classroom = state.classroom,
                section = state.section,
                startTime = state.startTime,
                endTime = state.endTime.ifEmpty { null },
                notificationId = state.notificationId,
                testMode = testMode
            )
            return
        }

        // 课中提醒：仅 shouldShowInClassNow 为真（全程，或距下课已进窗）才切课中卡
        // 已在课中则无需重推（系统 timer 自刷距下课）；课中开启时对账不补「已上课」
        if (inClassEnabled && now >= state.startMillis) {
            val alreadyInClass = IslandNotificationHelper.isInClassNotificationId(state.notificationId)
            val canShow = state.endMillis > now
            if (canShow) {
                if (alreadyInClass) {
                    IslandNotificationHelper.updateInClassIslandIfNeeded(context, state, testMode)
                } else if (shouldShowInClass) {
                    // 仅进窗后切课中；不可 || end>start，否则「距下课 N 分钟」被短路、上课即挂卡
                    IslandNotificationHelper.sendInClassIslandNotification(
                        context = context,
                        courseName = state.courseName,
                        classroom = state.classroom,
                        section = state.section,
                        startTime = state.startTime,
                        endTime = state.endTime,
                        notificationId = state.notificationId,
                        testMode = testMode
                    )
                }
            }
            return
        }

        if (shouldShowInClass) {
            // 理论上 inClassEnabled 已在上面 return；此处兜底
            IslandNotificationHelper.sendInClassIslandNotification(
                context = context,
                courseName = state.courseName,
                classroom = state.classroom,
                section = state.section,
                startTime = state.startTime,
                endTime = state.endTime,
                notificationId = state.notificationId,
                testMode = testMode
            )
        }
    }

    // 关闭提醒时清理实况/岛残留
    fun cancelAllReminderNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val countdownPrefs = context.getSharedPreferences("countdown_state", Context.MODE_PRIVATE)
        manager.cancel(LIVE_COUNTDOWN_ID)
        manager.cancel(LIVE_STARTED_ID)
        manager.cancel(LIVE_IN_CLASS_ID)
        manager.cancel(LIVE_TEST_COUNTDOWN_ID)
        manager.cancel(LIVE_TEST_STARTED_ID)
        manager.cancel(LIVE_TEST_IN_CLASS_ID)
        // 旧版按课程名 hash 的残留 ID
        val legacyId = countdownPrefs.getInt("notificationId", 0)
        if (legacyId != 0 && legacyId != LIVE_COUNTDOWN_ID && legacyId != LIVE_TEST_COUNTDOWN_ID) {
            manager.cancel(legacyId)
            manager.cancel(legacyId + 1)
            manager.cancel(legacyId + 2)
        }
        countdownPrefs.edit {
            putBoolean("active", false)
                .putBoolean("in_class_active", false)
                .remove("test_mode")
                .remove("last_in_class_minutes")
                .remove("last_in_class_progress")
        }
        IslandNotificationHelper.cancelIslandNotifications(context)
        IslandNotificationHelper.IslandState.clear(context)
    }

    // 每分钟兜底补发：窗口内且未发送的课立即补发
    fun checkPendingPreClassReminders(context: Context) {
        val repository = CourseRepository(context)
        if (!repository.getPreClassReminder()) {
            return
        }
        if (!isSemesterStarted(repository)) {
            return
        }

        // 复用 getTodayCourses，勿再内联过滤逻辑
        val courses = getTodayCourses(context)
        if (courses.isEmpty()) {
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val minutesBefore = repository.getPreClassReminderMinutes()
        val useIsland = repository.getIslandNotification() && IslandNotificationHelper.isIslandSupported(context)
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for ((index, course) in courses.withIndex()) {
            val startTime = getCourseStartTime(course, repository) ?: continue
            val startTotal = startTime.toMinutes()
            if (startTotal == Int.MAX_VALUE) continue

            // 连堂课窗口起点后移到上一节下课，与 schedulePreClassAlarms 一致
            var windowStart = startTotal - minutesBefore
            val prevCourse = courses.take(index).lastOrNull { isConsecutiveCourse(it, course) }
            if (prevCourse != null) {
                val prevEnd = getCourseEndTime(prevCourse, repository)?.toMinutes()
                if (prevEnd != null && prevEnd != Int.MAX_VALUE && startTotal - prevEnd < minutesBefore) {
                    windowStart = prevEnd
                }
            }
            // 先判窗口再算去重：去重键要读 SharedPreferences 里的 JSON 映射，
            // 原来对**每一节课、每一次刷新**都解析一遍（一天上千次），
            // 而真正可能补发的只有落在 [窗口起点, 上课时刻) 里的那一节。
            val inWindow = currentMinutes in windowStart until startTotal
            if (!inWindow) continue
            val dedupId11 = "${course.name}|${course.getTimeDisplayText()}|$startTime"
            val dedupHit = isPreClassSentRecently(context, dedupId11)
            // 岛当天已发过则不重发（重发会触发岛重新弹出）
            val islandHit = useIsland && hasIslandPreClassSentToday(context, dedupId11)
            if (dedupHit || islandHit) continue
            val startMillis = parseTimeToTodayMillis(startTime)
            val endMillis = parseTimeToTodayMillis(getCourseEndTime(course, repository))
            sendPreClassNotification(
                context, alarmManager, repository, course, startTime,
                useIsland, startMillis, endMillis
            )
            logD(context, "checkPending: SENT ${course.name}")
        }
    }

    // 非法返回 Int.MAX_VALUE 排到末尾
    private fun String?.toMinutes(): Int {
        if (this.isNullOrBlank()) return Int.MAX_VALUE
        val parts = this.split(":")
        if (parts.size != 2) return Int.MAX_VALUE
        val h = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
        val m = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
        return h * 60 + m
    }
}
