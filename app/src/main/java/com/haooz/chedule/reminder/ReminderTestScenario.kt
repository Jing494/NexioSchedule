package com.haooz.chedule.reminder

import android.content.Context
import com.haooz.chedule.data.CourseRepository

/** 测试要直接落到哪个状态（用于单独验证某一条分支） */
enum class ReminderTestPhase { COUNTDOWN, STARTED, IN_CLASS }

/**
 * 「超级岛 / 实时动态」测试场景。
 *
 * 存在的理由：原来全项目只有一个测试按钮、只有一种时序（课前 70 秒 + 课中 2 分钟），
 * 覆盖不到「到点自动切课中」「课中距下课倒计时」「长课不中途收岛」这些真实分支 ——
 * 于是"上课倒计时正常、下课倒计时没了"这类问题只能靠碰运气发现。
 *
 * 本文件只是**场景表 + 分发**，不复制任何发送逻辑：真正发送仍然走主路径那几支函数，
 * 通道判定也与主路径完全一致（岛开着且设备支持 → 走岛，否则走原生实时动态），
 * 避免"测的那套"和"跑的那套"分叉。
 */
enum class ReminderTestScenario(
    val label: String,
    val detail: String,
    val startDelayMs: Long,
    val durationMs: Long,
    val phase: ReminderTestPhase,
) {
    /** 关键场景：一次点击就能看到「课前倒计时 → 到点自动切课中」的完整切换 */
    FULL_TIMELINE(
        label = "课前倒计时 → 到点切课中",
        detail = "70 秒倒计时，到点自动切课中卡（课时 3 分钟）",
        startDelayMs = 70_000L,
        durationMs = 180_000L,
        phase = ReminderTestPhase.COUNTDOWN,
    ),

    /** 静态「已上课」：15 秒后收起，用来验证收起时序 */
    STARTED(
        label = "已上课（静态）",
        detail = "强制发静态「已上课」，约 15 秒后自动收起",
        startDelayMs = 0L,
        durationMs = 60_000L,
        phase = ReminderTestPhase.STARTED,
    ),

    /** 课中卡：绕过「课中提醒」总开关，直接验 B 区（距下课倒计时 / 静态文案） */
    IN_CLASS(
        label = "课中 · 距下课倒计时",
        detail = "直接进课中卡（课时 3 分钟），B 区按你的设置显示倒计时或静态文案",
        startDelayMs = 0L,
        durationMs = 180_000L,
        phase = ReminderTestPhase.IN_CLASS,
    ),

    /** 长课：晚自习两节连排那种时长，验证不会在中途被系统收岛 */
    IN_CLASS_LONG(
        label = "课中 · 长课（20 分钟）",
        detail = "验证长时间不会中途收岛，长课时也不该在 60 分钟处自己消失",
        startDelayMs = 0L,
        durationMs = 1_200_000L,
        phase = ReminderTestPhase.IN_CLASS,
    );

    companion object {
        /** 当前实际会走哪条通道 —— 与主路径同一套判定 */
        fun islandActive(context: Context): Boolean =
            CourseRepository(context).getIslandNotification() &&
                IslandNotificationHelper.isIslandSupported(context)

        fun channelLabel(context: Context): String =
            if (islandActive(context)) "小米超级岛" else "原生实时动态"

        fun send(context: Context, scenario: ReminderTestScenario) {
            if (islandActive(context)) {
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
        }
    }
}
