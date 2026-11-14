package com.haooz.chedule.data

import com.haooz.chedule.data.backup.encodeStringList
import com.haooz.chedule.data.repo.CourseRepository
import com.haooz.chedule.data.storage.AppStorage
import com.haooz.chedule.data.storage.CourseStorage
import com.haooz.chedule.data.storage.InMemoryKeyValueStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 备份范围：**本 fork 与上游的故意分歧**（见 LOCAL-CHANGES §三）。
 *
 * 上游把「全量备份」定义成"课表数据 + 节假日/调休"，有意把提醒 / 勿扰 / 外观
 * 这些应用功能设置挡在外面；本 fork 选择**带上全部用户设置** —— 换设备或重装后，
 * 课前提醒 / 超级岛开关 / 勿扰 / 假期余额 / 返校准备清单 / 小部件档位 / 搭配外观
 * 不该静默回到默认值（当初就是"恢复了但设置全变了、还不报错"才改的）。
 *
 * ⚠ 上游同名测试断言的正是**相反**的语义（"设备本地设置不该进备份"）。
 * 每次跟上游都要**保住这个文件**，否则行为会被测试倒逼回去。
 * 口径仍然成立的两条硬约束（也是上游那版测试想保护的）：
 * - 瞬时/缓存/凭据类**必须**排除（见 [excludedKeys]）；
 * - 导入备份**不丢本机设置**、也不该把凭据类写回去。
 */
class BackupScopeTest {

    private lateinit var repository: CourseRepository

    @BeforeTest
    fun setUp() {
        AppStorage.init { InMemoryKeyValueStore() }
        CourseStorage.install { null }
        repository = CourseRepository.getInstance()
        repository.invalidateAllCaches()
        freshState("备份范围")
        // ⚠ 断言用的键必须**真的被写过**：KeyValueStore 里没有这个键时导出结果里也不会有它。
        // 上一版测试忘了写后四个，断言一直在测"空状态"，看着像产品缺陷、实际是测试自己的疏漏。
        repository.setPreClassReminder(true)
        repository.setPreClassReminderMinutes(15)
        repository.setNextDayReminder(true)
        repository.setNextDayReminderHour(21)
        repository.setNextDayReminderMinute(30)
        repository.setIslandNotification(true)
        repository.setClassDndEnabled(true)
        repository.setClassDndMode(2)
        repository.setWidgetPaddingMode(1)
        repository.setDefaultHomepage("今日")
        repository.setShiftModeEnabled(true)
        repository.setShiftSelectedSchedules(listOf("备份范围"))
    }

    private fun freshState(name: String) {
        val cleaned = repository.exportAllPreferences()
            .filterKeys { !it.startsWith("schedule_") && !it.startsWith("time_config_") }
            .toMutableMap()
        cleaned["schedule_names"] = encodeStringList(listOf(name))
        cleaned["schedule_folders"] = "[]"
        cleaned["current_schedule_id"] = name
        cleaned["schedule_${name}_courses"] = "[]"
        repository.importAllPreferences(cleaned)
    }

    @Test
    fun `导出含课表内容与全部用户设置`() {
        val backup = repository.exportAllPreferences()
        assertTrue("schedule_备份范围_courses" in backup, "课程必须进备份")
        assertTrue("schedule_names" in backup, "课表名必须进备份")
        // ★ fork 语义：这些**要**进备份（上游那版断言的是 assertFalse）
        userSettingKeys.forEach { key ->
            assertTrue(key in backup, "$key 是用户设置，本 fork 要把它们带走")
        }
    }

    @Test
    fun `瞬时状态缓存与凭据不进备份`() {
        val backup = repository.exportAllPreferences()
        excludedKeys.forEach { key ->
            assertFalse(key in backup, "$key 不该进备份（瞬时 / 缓存 / 凭据）")
        }
    }

    @Test
    fun `导入备份不丢本机设备设置`() {
        val backup = repository.exportAllPreferences()
        repository.importAllPreferences(backup)
        assertTrue(repository.getPreClassReminder(), "课前提醒应原样留在本机")
        assertEquals(15, repository.getPreClassReminderMinutes())
        assertTrue(repository.getIslandNotification(), "超级岛开关应原样留在本机")
        assertTrue(repository.getClassDndEnabled(), "勿扰开关应原样留在本机")
        assertEquals(2, repository.getClassDndMode())
    }

    private companion object {
        /** 本 fork 认为**必须**跟着备份走的用户设置。 */
        val userSettingKeys = listOf(
            "pre_class_reminder", "pre_class_reminder_minutes",
            "next_day_reminder", "next_day_reminder_hour", "next_day_reminder_minute",
            "island_notification", "class_dnd_enabled", "class_dnd_mode",
            "widget_padding_mode", "default_homepage", "shift_mode_enabled",
            "shift_selected_schedules",
        )

        /** 瞬时 / 缓存 / 凭据：两端都同意**不**进备份。 */
        val excludedKeys = listOf(
            "default_folder_migrated",
        )
    }
}
