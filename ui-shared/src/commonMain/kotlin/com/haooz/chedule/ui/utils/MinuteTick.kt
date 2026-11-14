package com.haooz.chedule.ui.utils

import com.haooz.chedule.data.util.nowLocalTime
import kotlinx.datetime.LocalTime

/**
 * 距离下一个整分还有多少毫秒（多给 40ms 余量，保证醒来时已经跨过边界）。
 *
 * 为什么需要它：只到「分钟」粒度的文案，原来一律 `delay(1000)` 每秒轮询，
 * 一分钟里 59 次都是白算——例如助手卡片的 `generateSmartTip` 每次都要跑两遍
 * buildCourseTimeRanges（解析 + 排序），格言卡片的 `computeQuoteScene` 同理。
 * 改成睡到整分，唤醒次数降到 1/60。
 *
 * 用「本地时间算出的剩余毫秒」而不是固定 60_000：每次 tick 都会多花掉几十毫秒，
 * 固定间隔会慢慢漂移并最终错过整分边界（凌晨/长时间前台时最明显）。
 *
 * 口径是 **ceil**（显示「还剩 N 分钟」时 N 向上取整，值只在墙上时钟的整分边界跳变）——
 * 与下面课程倒计时行的 [millisToNextCountdownTick] **不能统一**。
 */
internal fun millisToNextMinute(now: LocalTime = nowLocalTime()): Long {
    val sinceMinuteStart = now.second * 1000L + now.nanosecond / 1_000_000L
    return (60_000L - sinceMinuteStart + 40L).coerceIn(200L, 61_000L)
}

/**
 * 「课程倒计时行」的下一次唤醒间隔（毫秒）：**从当前剩余秒数推**，不要固定 1s / 60s。
 *
 * 为什么不能与 [millisToNextMinute] 统一：这里显示的是**剩余**时间，而且最后一分钟
 * 切成秒级（`formatRemainingUntil` 的 `remainMillis < 60_000`）—— 若整体睡到下一个整分，
 * 那 60 秒里秒数会冻住，用户看到的是「60 秒后」直接跳到「已开始」。
 * 所以：最后一分钟每秒醒一次；其余是分钟口径，只在整分边界跳变。
 *
 * 跳变点用 floor 语义的 `剩余秒数 % 60 + 1`（旧实现在课程卡里就是这么推的）：
 * 分钟值 v 的窗口是 (end-60(v+1), end-60v]，下一个跳变点在 end-60v，
 * 即距现在 `剩余秒数 % 60` 秒；+1 秒是为了跨过跳变那一瞬本身 ——
 * 正好压在跳变点上会取到退化值并把那一分钟整段跳过，落在退化点时下一轮会自愈。
 */
internal fun millisToNextCountdownTick(remainMillis: Long): Long =
    if (remainMillis < 60_000L) 1_000L
    else ((remainMillis / 1000L) % 60L + 1L) * 1_000L
