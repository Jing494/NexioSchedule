/** 小组件刷新辅助：无实例时跳过，内容未变时跳过重绘 */
package com.haooz.chedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

object WidgetUpdateCache {

    /** 存完整签名字符串，避免 hashCode 碰撞导致内容变化却被跳过 */
    private val lastSignatures = ConcurrentHashMap<String, String>()

    /** 内容签名相同则应跳过本次 updateAppWidget */
    fun shouldSkip(key: String, signature: String): Boolean {
        val prev = lastSignatures.put(key, signature)
        return prev != null && prev == signature
    }

    fun invalidate(keyPrefix: String) {
        lastSignatures.keys.filter { it.startsWith(keyPrefix) }.forEach { lastSignatures.remove(it) }
    }

    /** 组件被删除时清掉该 id 的签名，避免系统复用 id 后首帧被误跳过 */
    fun invalidateWidget(key: String) {
        lastSignatures.remove(key)
    }

    fun clear() = lastSignatures.clear()

    fun hasProviderWidgets(context: Context, provider: Class<*>): Boolean {
        return try {
            val am = AppWidgetManager.getInstance(context) ?: return true
            am.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 4 个 provider 里是否有任意一个真的被放到桌面。
     *
     * 刷新链在课中是**分钟级**的，每次都做 4 次 `getAppWidgetIds` binder 调用的话，
     * 一天下来是四位数；而"桌面上一个小组件都没放"是最常见的情况。
     * 所以这里对**否定结果**做 60 秒缓存：无实例时不重复问系统，
     * 代价是最多 60 秒后才发现"刚放了小组件"——小组件自己的 onUpdate 本来就会画首帧。
     * 任何异常一律按"有"处理，不会把真实实例漏掉。
     */
    @Volatile
    private var noWidgetsUntil = 0L

    fun anyProviderWidgets(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now < noWidgetsUntil) return false
        val any = hasProviderWidgets(context, CourseWidgetProviderStandard::class.java) ||
            hasProviderWidgets(context, TodayCourseWidgetProviderStandard::class.java) ||
            hasProviderWidgets(context, CourseWidgetProviderPad::class.java) ||
            hasProviderWidgets(context, TodayCourseWidgetProviderPad::class.java)
        if (!any) noWidgetsUntil = now + 60_000L
        return any
    }

    /** 按桌面实际放置情况刷新；未放置的 provider 完全不广播 */
    fun updateInstalledWidgets(context: Context) {
        // 一个都没放时直接走缓存早退（原来这里固定 4 次 binder 调用）
        if (System.currentTimeMillis() < noWidgetsUntil) return
        if (hasProviderWidgets(context, CourseWidgetProviderStandard::class.java)) {
            CourseWidgetProviderStandard.updateAllWidgets(context)
        }
        if (hasProviderWidgets(context, TodayCourseWidgetProviderStandard::class.java)) {
            TodayCourseWidgetProviderStandard.updateAllWidgets(context)
        }
        if (hasProviderWidgets(context, CourseWidgetProviderPad::class.java)) {
            CourseWidgetProviderPad.updateAllWidgets(context)
        }
        if (hasProviderWidgets(context, TodayCourseWidgetProviderPad::class.java)) {
            TodayCourseWidgetProviderPad.updateAllWidgets(context)
        }
    }
}
