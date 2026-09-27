package com.haooz.chedule.ui.utils

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * 「毛玻璃是否全量渲染」的全局开关 —— 滑动/翻页进行中降级，停下恢复。
 *
 * 为什么是全局单例而不是参数一路传下去：
 * 课表卡、今日页玻璃卡、边光描边分散在 4~5 个文件里，参数化要穿一整条调用链，
 * 而这些调用点**都已经在绘制期读状态**了（drawBackdrop 的 effects / onDrawSurface lambda、
 * EdgeLightNode.draw），所以只要提供一个稳定的读取入口，翻面就是"只失效绘制、不触发重组"。
 *
 * 用法（**务必在 lambda 里读，不要读进 composition**）：
 * ```
 * effects = remember(...) { { if (glassBlurEnabled()) { blur(...); lens(...) } } }
 * ```
 * 关键：**不要把 glassBlurEnabled() 的结果进 remember 的键** ——
 * 那会让 lambda 身份每次翻面都变，等于把 drawBackdrop 的绘制缓存整块重建
 * （v22 那个"椭圆伪影"就是这个坑的同一类：实例稳定但内容会变的东西不能当键）。
 */
object GlassPerf {
    /** true = 全量毛玻璃；false = 降级（跳过 blur / lens 与边光描边） */
    val enabled: MutableState<Boolean> = mutableStateOf(true)
}

/**
 * 绘制期读取：当前是否应该画全量毛玻璃（不要再加 inline：编译器提示收益不显著，
 * 而且内联会把快照读取点复制到每个调用点，反而不利于排查）。
 * 在 DrawModifierNode.draw / drawBackdrop 的 lambda 里调用会登记"绘制期依赖"，
 * 翻面时只失效这一层绘制，不会触发任何重组。
 */
fun glassBlurEnabled(): Boolean = GlassPerf.enabled.value
