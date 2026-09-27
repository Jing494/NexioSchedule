package com.haooz.chedule.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D

/**
 * 「毛玻璃降级」的全局系数 —— 1f = 全量渲染，0f = 全降级。
 *
 * 为什么是系数而不是布尔开关：先做成开关时，滑动一开始整屏玻璃**同一帧**从
 * "有折射的玻璃"变成"平的色块"，停下又整片弹回来。全局状态硬切换人眼一定抓得到
 * （实测反馈："感觉有点突兀"）。现在由 MainActivity 的驱动协程做
 * **150ms 淡出 / 250ms 淡入**，观感是"滑起来慢慢变糊、停下慢慢长回来"。
 *
 * 关键：稳态（系数降到 <= 0.01）时各调用点**一个 effect 都不追加** →
 * `RenderEffect` 为空 → 走 `canDirectBlit` 直采共享预模糊层，
 * 连每卡离屏录制一起省掉。收益结构没有被"柔和化"削弱。
 *
 * 用法（**务必在 effects lambda / draw 里读；不要读进 composition，也不要进 remember 的键**）：
 * ```
 * effects = remember(...) {
 *     {
 *         val f = glassPerfFactor()
 *         if (f > 0.01f) { blur(blurPx * f); lens(r * f, s * f) }
 *     }
 * }
 * ```
 * 进 remember 的键 → lambda 身份随系数每次变化，`drawBackdrop` 会判不等并整块重建
 * 绘制缓存（v22 那个"椭圆伪影"是同一类坑）；读进 composition → 四千多行的壳 body
 * 会在每个过渡帧完整重跑一遍（v28 的 ③ 刚把这类问题修掉）。
 */
object GlassPerf {
    /**
     * 过渡由驱动协程 `animateTo` 推进。放 Animatable 而不是 mutableStateOf，
     * 是为了让"谁推进动画"只有一个来源，且被取消/重建时保留当前值（不会闪回 1f）。
     */
    val anim: Animatable<Float, AnimationVector1D> = Animatable(1f)
}

/** 绘制期读取：当前毛玻璃渲染到几成。1f = 全量，0f = 全降级。 */
fun glassPerfFactor(): Float = GlassPerf.anim.value
