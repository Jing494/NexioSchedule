package com.haooz.chedule.reminder

import android.content.Context
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.ReturnDayReminder
import java.time.LocalDate

/** 测试要直接落到哪个状态（用于单独验证某一条分支） */
enum class ReminderTestPhase { COUNTDOWN, STARTED, IN_CLASS }

/** 场景属于哪条链路 —— 决定发送时调哪支函数 */
enum class ReminderTestKind {
    /** 课前倒计时 / 已上课 / 课中：按当前通道（岛或实时动态）发 */
    COURSE,

    /** 返校（当日）→ 日子词应为「今晚返校」 */
    RETURN_TODAY,

    /** 返校（次日）→ 日子词应为「明天返校」 */
    RETURN_TOMORROW,

    /** 返校准备清单（普通通知） */
    PLAIN_PREP,

    /** 假期余额（普通通知） */
    PLAIN_BALANCE,

    /** 次日课程提醒（普通通知） */
    PLAIN_NEXT_DAY,
}

/**
 * 「通知 / 超级岛 / 实时动态」测试场景表。
 *
 * 存在的理由：原来全项目只有一个测试按钮、只有一种时序（课前 70 秒 + 课中 2 分钟），
 * 覆盖不到「到点自动切课中」「课中距下课倒计时」「长课不中途收岛」「返校岛」这些真实分支 ——
 * 于是"上课倒计时正常、下课倒计时没了"这类问题只能靠碰。
 *
 * 本文件只是**场景表 + 分发**，不复制任何发送逻辑：真正发送仍然走主路径那几支函数，
 * 通道判定也与主路径完全一致（岛开着且设备支持 → 走岛，否则走原生实时动态），
 * 避免"测的那套"和"跑的那套"分叉。
 */
enum class ReminderTestScenario(
    val label: String,
    val detail: String,
    val kind: ReminderTestKind,
    /** 仅 COURSE 用：距"上课"还有多久 / 课时长度 */
    val startDelayMs: Long = 0L,
    val durationMs: Long = 0L,
    val phase: ReminderTestPhase = ReminderTestPhase.COUNTDOWN,
) {
    // ---------- 课程链路（走当前通道）----------
    FULL_TIMELINE(
        label = "课前倒计时 → 到点切课中",
        detail = "70 秒倒计时，到点自动切课中卡（课时 3 分钟）",
        kind = ReminderTestKind.COURSE,
        startDelayMs = 70_000L,
        durationMs = 180_000L,
        phase = ReminderTestPhase.COUNTDOWN,
    ),
    STARTED(
        label = "已上课（静态）",
        detail = "强制发静态「已上课」，约 15 秒后自动收起",
        kind = ReminderTestKind.COURSE,
        durationMs = 60_000L,
        phase = ReminderTestPhase.STARTED,
    ),
    IN_CLASS(
        label = "课中 · 距下课倒计时",
        detail = "直接进课中卡（课时 3 分钟），B 区按你的设置显示倒计时或静态文案",
        kind = ReminderTestKind.COURSE,
        durationMs = 180_000L,
        phase = ReminderTestPhase.IN_CLASS,
    ),
    IN_CLASS_LONG(
        label = "课中 · 长课（20 分钟）",
        detail = "验证长时间不会中途收岛，长课时也不该在 60 分钟处自己消失",
        kind = ReminderTestKind.COURSE,
        durationMs = 1_200_000L,
        phase = ReminderTestPhase.IN_CLASS,
    ),

    // ---------- 返校链路 ----------
    RETURN_TODAY(
        label = "返校 · 当日（今晚返校）",
        detail = "走当前通道发返校提醒；目标日是今天，日子词应为「今晚返校」",
        kind = ReminderTestKind.RETURN_TODAY,
    ),
    RETURN_TOMORROW(
        label = "返校 · 次日（明天返校）",
        detail = "走当前通道发返校提醒；目标日是明天，日子词应为「明天返校」",
        kind = ReminderTestKind.RETURN_TOMORROW,
    ),
    PLAIN_PREP(
        label = "返校准备清单（普通通知）",
        detail = "与「立即发一条测试」同一条实现，不写当天去重键",
        kind = ReminderTestKind.PLAIN_PREP,
    ),
    PLAIN_BALANCE(
        label = "假期余额（普通通知）",
        detail = "文案跟随「返校节次豁免」开关；不在假期内会给演示文案",
        kind = ReminderTestKind.PLAIN_BALANCE,
    ),

    // ---------- 其他普通通知 ----------
    PLAIN_NEXT_DAY(
        label = "次日课程提醒（普通通知）",
        detail = "汇总明天节数 + 第一节；文案与正式提醒共用一份实现",
        kind = ReminderTestKind.PLAIN_NEXT_DAY,
    );

    companion object {
        /** 当前实际会走哪条通道 —— 与主路径同一套判定 */
        fun islandActive(context: Context): Boolean =
            CourseRepository(context).getIslandNotification() &&
                IslandNotificationHelper.isIslandSupported(context)

        fun channelLabel(context: Context): String =
            if (islandActive(context)) "小米超级岛" else "原生实时动态"

        fun send(context: Context, scenario: ReminderTestScenario) {
            when (scenario.kind) {
                ReminderTestKind.COURSE -> if (islandActive(context)) {
                    IslandNotificationHelper.sendTestIslandNotification(
                        context = context,
                        startDelayMs = scenario.startDelayMs,
                        durationMs = scenario.durationMs,
                        phase = scenario.phase,
                    )
                } else {
                    CourseReminderHelper.sendTestLiveNotification(
                        context = context,
                        startDelayMs = scenario.startDelayMs,
                        durationMs = scenario.durationMs,
                        phase = scenario.phase,
                    )
                }

                ReminderTestKind.RETURN_TODAY -> sendReturnDay(context, today = true)
                ReminderTestKind.RETURN_TOMORROW -> sendReturnDay(context, today = false)
                ReminderTestKind.PLAIN_PREP ->
                    CourseReminderHelper.sendReturnDayReminderNow(context, prep = true)
                ReminderTestKind.PLAIN_BALANCE ->
                    CourseReminderHelper.sendReturnDayReminderNow(context, prep = false)
                ReminderTestKind.PLAIN_NEXT_DAY ->
                    CourseReminderHelper.sendNextDayReminderNow(context)
            }
        }

        /**
         * 返校提醒测试：固定用「第11节 18:30」这个晚自习样例 ——
         * 只有晚间课才能稳定验证「今晚返校」那条日子词（16:00 口径）；
         * 假期标签取真实 span，取不到就留空（两条分支都能验到）。
         */
        private fun sendReturnDay(context: Context, today: Boolean) {
            val targetDate = if (today) LocalDate.now() else LocalDate.now().plusDays(1)
            val span = ReturnDayReminder.currentHolidaySpan(context, targetDate)
            val holidayLabel = span?.name?.takeIf { it.isNotBlank() }?.let { "${it}最后一天" }
            if (islandActive(context)) {
                IslandNotificationHelper.sendReturnDayIslandNotification(
                    context = context,
                    courseName = "大学英语Ⅱ",
                    section = "第11节",
                    startTime = "18:30",
                    targetDate = targetDate,
                    holidayLabel = holidayLabel,
                    progressPercent = span?.progressPercent,
                )
            } else {
                CourseReminderHelper.showReturnDayLiveNotification(
                    context = context,
                    courseName = "大学英语Ⅱ",
                    section = "第11节",
                    startTime = "18:30",
                    targetDate = targetDate,
                    holidayLabel = holidayLabel,
                    progressPercent = span?.progressPercent,
                )
            }
        }
    }
}
