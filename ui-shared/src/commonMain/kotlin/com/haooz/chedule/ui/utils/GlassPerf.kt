package com.haooz.chedule.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D

/**
 * 「毛玻璃降级」的全局系数 —— 1f = 全量渲染，0f = 全降级。
 *
 * 为什么是系数而不是布尔开关：先做成开关时，滑动一开始整屏玻璃**同一帧**从
 * "有折射的玻璃"变成"平的色块"，停下又整片弹回来。全局状态硬切换人眼一定抓得到
 * （实测反馈："感觉有点突兀"）。现在由壳层（CourseScheduleApp）的驱动协程做
 * **150ms 淡出 / 停滑 250ms 防抖后 250ms 淡入**，观感是"滑起来慢慢变糊、停下慢慢长回来"。
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
 *         if (f > 0.01f) { blur(blurPx * f); lens(r, s * f) }
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


/**
 * 已降级的判定口径：到这个档位就当「没有玻璃」，直接走最省的那条路。
 *
 * 0.01 不是随手取的：淡出动画最后那几帧乘出来的模糊半径已经小于 1px（肉眼与 0 无差），
 * 继续走 shader 只是白烧 GPU。
 */
internal const val GLASS_PERF_OFF_THRESHOLD = 0.01f

/** 当前是否已降到「无玻璃」档。 */
internal fun glassPerfOff(): Boolean = glassPerfFactor() <= GLASS_PERF_OFF_THRESHOLD

/**
 * 按降级系数折算一份柔光样式。**必须在绘制期调用**（见本文件顶部两条禁忌）。
 *
 * 折算口径与 v22–v45 的老实现保持一致，不要改：
 * - 模糊：按系数整体缩小（模糊是「有多糊」，淡出就是变清楚）。
 * - 折射：只缩 refractionScale（强度），不动边缘厚度 / 描边宽度 ——
 *   宽度跟着缩会让高光环向卡片边缘收拢、观感像「蹭一下冒出来」，而且层几何每帧都在变。
 */
internal fun com.haooz.softglass.glass.SoftGlassStyle.withGlassPerf(): com.haooz.softglass.glass.SoftGlassStyle {
    val f = glassPerfFactor()
    if (f >= 1f) return this
    if (f <= GLASS_PERF_OFF_THRESHOLD) {
        // 全降级：模糊与折射都归零，只剩一层平色（卡片自带的 background）
        return copy(smallBlurRadius = 0f, refractionScale = 0f)
    }
    return copy(smallBlurRadius = smallBlurRadius * f, refractionScale = refractionScale * f)
}
