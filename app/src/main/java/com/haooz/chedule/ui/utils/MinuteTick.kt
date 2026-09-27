package com.haooz.chedule.ui.utils

import java.time.LocalTime

/**
 * 距离下一个整分还有多少毫秒（多给 40ms 余量，保证醒来时已经跨过边界）。
 *
 * 为什么需要它：只到「分钟」粒度的倒计时文案，原来一律 `delay(1000)` 每秒轮询，
 * 一分钟里 59 次都是白算——例如助手页的 `generateSmartTip` 每次都要跑两遍
 * buildCourseTimeRanges（DateTimeFormatter 解析 + 排序），QuoteCard 的
 * `computeQuoteScene` 同理。改成睡到整分，唤醒次数降到 1/60。
 *
 * 用「本地时间算出的剩余毫秒」而不是固定 60_000：每次 tick 都会多花掉几十毫秒，
 * 固定间隔会慢慢漂移并最终错过整分边界（凌晨/长时间前台时最明显）。
 */
internal fun millisToNextMinute(now: LocalTime = LocalTime.now()): Long {
    val sinceMinuteStart = now.second * 1000L + now.nano / 1_000_000L
    return (60_000L - sinceMinuteStart + 40L).coerceIn(200L, 61_000L)
}
