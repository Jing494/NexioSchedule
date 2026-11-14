package com.haooz.chedule.data.platform

/**
 * 课程提醒控制器 —— 平台能力抽象（注入模式，同 [DiagnosticRecorder]）。真实实现包装 AlarmManager、
 * 权限与系统设置页跳转等 Android 专有能力，桌面/iOS 用 [NoopReminderController]。
 *
 * ⚠ companion 里的 KEY_ / IN_CLASS_TIMING_ / MODE_ 常量与 Android 侧实现**保持同值**，不要单方面改。
 */
interface ReminderController {

    // —— 提醒服务（幂等对账）——

    /** 启动/刷新课程提醒服务（内部会按当前开关状态对账，多余提醒会被清掉） */
    fun startReminderService()

    /** 停止课程提醒服务 */
    fun stopReminderService()

    // —— 课中提醒（读 course_reminder_prefs）——

    /** 课中提醒开关（超级岛 / 原生实况共用） */
    fun isInClassEnabled(): Boolean

    /** 提醒时机：[ReminderController.IN_CLASS_TIMING_FULL]=全程 / [ReminderController.IN_CLASS_TIMING_BEFORE_END]=距下课 */
    fun getInClassTimingMode(): Int

    /** 距下课分钟数（0..60） */
    fun getInClassLeadMinutes(): Int

    // —— 通知权限能力 ——

    /** 能否发布「实况通知」（API 36+ 的 promoted notifications） */
    fun canPostPromotedNotifications(): Boolean

    /** 是否已授予 POST_NOTIFICATIONS 通知权限 */
    fun hasNotificationPermission(): Boolean

    /** 是否已授予「免打扰访问权限」（自动开勿扰的前提） */
    fun isDndPermissionGranted(): Boolean

    /** 该平台能否改写系统勿扰状态 */
    fun supportsDnd(): Boolean = false

    // —— 超级岛 / Shizuku ——

    /** 设备是否支持超级岛（非 HyperOS 返回 false） */
    fun isIslandSupported(): Boolean

    /**
     * Whether this platform has a Live Activity container (iOS Dynamic Island /
     * lock screen).
     *
     * Kept separate from [isIslandSupported] on purpose: that one probes for HyperOS
     * super island, which enables Xiaomi-specific controls (Shizuku, left/right
     * slot, AOD). This method only reports whether a live container exists at all.
     * iOS 26+ is always true, but skips the Xiaomi-specific sub-options.
     *
     * The shared screen computes:
     * `showIslandCard = isIslandSupported() || supportsLiveActivity()`.
     *
     * Defaults to false so desktops never need to implement it.
     */
    fun supportsLiveActivity(): Boolean = false

    /**
     * 超级岛缩略态 B 区（课前 / 课中右侧那一块）该用哪个模式。
     *
     * 课前与课中是**同一块区域**，但历史上是两个独立下拉、课中那个默认是静态「正在上课」，
     * 于是"课前在跳秒、上课后变静态文案"被用户读成"下课倒计时飞了"。
     * 规则：用户显式选过就听用户的；没设过（-1）跟随课前 B 区。
     *
     * ★ 两边的**值空间不同**，不能把课前那个值直接透传过去：
     *   · 课前 B 区：0=课程名称 / 1=上课地点 / 2=倒计时（三个选项）
     *   · 课中 B 区：0=正在上课 / 1=距下课倒计时（这块区域只放得下这两个）
     *   所以"跟随课前"要**翻译**：课前是倒计时(2) → 课中给 1（距下课倒计时）；
     *   其余（课程名称/上课地点，课中放不下）→ 课中给 0（正在上课）。
     *   直接把 1 或 2 透传会让课中落到不存在的模式上（when 没 else 就会走空）。
     *
     * ★ 判定**只此一处**：设置页（ui-shared）与 androidApp 的 IslandNotificationHelper 都调它，
     *   谁也别再裸读 prefs 各写一套 —— 这两边过去各写一套正是那个 bug 的根因。
     * 默认实现即完整语义，平台侧不需要重写。
     *
     * @param inClassMode 课中 B 区**显式**选择；没设过必须传哨兵 -1（不是 0，0 是"正在上课"）。
     */
    fun effectiveInClassRightMode(preClassMode: Int, inClassMode: Int): Int =
        if (inClassMode >= 0) inClassMode else if (preClassMode == 2) 1 else 0

    /** Shizuku 服务是否在运行 */
    fun isShizukuRunning(): Boolean

    /** Shizuku 是否已授权本应用 */
    fun isShizukuAuthorized(): Boolean

    /** 请求 Shizuku 授权，结果回调 true=已授权 */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit)

    // —— 系统能力探测（不发起跳转）——

    /** 是否允许设置精确闹钟（API 31+ canScheduleExactAlarms；更低版本恒 true） */
    fun canScheduleExactAlarms(): Boolean

    /** 本应用是否已被电池优化白名单忽略 */
    fun isIgnoringBatteryOptimizations(): Boolean

    /** 当前系统版本是否有「实况通知」相关设置入口（Android = SDK_INT >= 36） */
    val supportsPromotedNotifications: Boolean

    /**
     * 本平台有没有「通知 / 超级岛 / 实时动态测试」页可测的场景。
     *
     * 默认 false：桌面/iOS 没有 Android 的通知通道、超级岛与实时动态，设置页据此
     * **整个不渲染**那个入口 —— 别让用户点进一个空页面（照 [supportsLiveActivity] 的先例，
     * 平台侧不需要重写；Android 实现返回 true）。
     *
     * ⚠ 接口属性不能写初始化式（`val x: Boolean = false` 在接口里编不过），默认值用默认 getter。
     */
    val supportsReminderTestPage: Boolean get() = false

    // —— 提醒体检（只读；默认实现按「查不到 = 不报故障」处理，与体检卡的兜底一致）——

    /**
     * 提醒通道的创建 / 停用状态。**列表里的 null 元素 = 该通道还没建出来** ——
     * 与「被用户停用」是两回事：全新安装时通道尚未创建（Application 启动才建），
     * 报成「N 个通道被停用」就是假警报（真机踩过）。
     *
     * 默认空列表 = 该平台没有 Android 的通知通道概念（桌面/iOS 体检按正常显示）。
     */
    fun notificationChannelStates(): List<ReminderChannelState?> = emptyList()

    /** 系统当前是否处于勿扰 / 静音（与 App 自己的「上课勿扰」无关；读不到按 false） */
    fun isSystemDndActive(): Boolean = false

    /** 系统是否限制了本应用的后台运行（自启动体检项；读不到按 false） */
    fun isBackgroundRestricted(): Boolean = false

    // —— 权限 / 系统设置页（onResult 在返回后触发）——

    /** 申请 POST_NOTIFICATIONS 通知权限（授权弹窗），回调 granted */
    fun requestNotificationPermission(onResult: (granted: Boolean) -> Unit)

    /** 打开「免打扰访问权限」设置页（ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS） */
    fun openNotificationAccessSettings(onResult: () -> Unit)

    /** 打开「实况通知」设置页（首选 MANAGE_APP_PROMOTED_NOTIFICATIONS，失败退回应用通知设置） */
    fun openPromotedNotificationSettings(onResult: () -> Unit)

    /** 打开「精确闹钟」授权页（ACTION_REQUEST_SCHEDULE_EXACT_ALARM） */
    fun openExactAlarmSettings(onResult: () -> Unit)

    /** 打开「电池优化」授权页（首选 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，失败退回白名单列表） */
    fun openBatteryOptimizationSettings(onResult: () -> Unit)

    /** 打开本应用详情设置页（ACTION_APPLICATION_DETAILS_SETTINGS，自启动检查入口） */
    fun openAppDetailsSettings(onResult: () -> Unit)

    companion object {
        // —— course_reminder_prefs 键（与 Android 侧 CourseReminderHelper.KEY_* 同值）——
        /** 课中提醒统一开关 */
        const val KEY_IN_CLASS = "in_class_reminder_enabled"
        /** 提醒时机档位（0=全程，1=距下课） */
        const val KEY_IN_CLASS_TIMING_MODE = "in_class_timing_mode"
        /** 距下课分钟数 */
        const val KEY_IN_CLASS_LEAD_MINUTES = "in_class_lead_minutes"

        // —— 提醒时机档位（与 CourseReminderHelper.IN_CLASS_TIMING_* 同值）——
        const val IN_CLASS_TIMING_FULL = 0
        const val IN_CLASS_TIMING_BEFORE_END = 1

        // —— 勿扰档位（与 Android 侧 ClassDndHelper.MODE_* 同值）——
        const val MODE_DND = 0
        const val MODE_SILENT = 1
        const val MODE_PRIORITY = 2
    }
}

/**
 * 一条通知通道的体检状态（只有 Android 有「通道」概念）。
 * 列表里用 **null** 表示「通道尚未创建」，见 [ReminderController.notificationChannelStates]。
 */
data class ReminderChannelState(
    /** 通道 id（Android = NotificationChannel.id） */
    val id: String,
    /** 用户是否把这个通道停用了（importance == NONE） */
    val disabled: Boolean,
)

/**
 * 桌面/iOS 的空实现：探测返回 false / 0，发起类方法不动作，权限类回调按「被拒绝」（granted=false），
 * 让页面走到原有的引导文案分支。
 */
object NoopReminderController : ReminderController {
    override fun startReminderService() {}
    override fun stopReminderService() {}
    override fun isInClassEnabled(): Boolean = false
    override fun getInClassTimingMode(): Int = ReminderController.IN_CLASS_TIMING_FULL
    /** 与 Android 侧默认值一致（10 分钟） */
    override fun getInClassLeadMinutes(): Int = 10
    override fun canPostPromotedNotifications(): Boolean = false
    override fun hasNotificationPermission(): Boolean = false
    override fun isDndPermissionGranted(): Boolean = false
    override fun isIslandSupported(): Boolean = false
    override fun isShizukuRunning(): Boolean = false
    override fun isShizukuAuthorized(): Boolean = false
    override fun requestShizukuPermission(onResult: (Boolean) -> Unit) = onResult(false)
    override fun canScheduleExactAlarms(): Boolean = false
    override fun isIgnoringBatteryOptimizations(): Boolean = false
    override val supportsPromotedNotifications: Boolean get() = false
    override fun requestNotificationPermission(onResult: (granted: Boolean) -> Unit) = onResult(false)
    override fun openNotificationAccessSettings(onResult: () -> Unit) {}
    override fun openPromotedNotificationSettings(onResult: () -> Unit) {}
    override fun openExactAlarmSettings(onResult: () -> Unit) {}
    override fun openBatteryOptimizationSettings(onResult: () -> Unit) {}
    override fun openAppDetailsSettings(onResult: () -> Unit) {}
}
