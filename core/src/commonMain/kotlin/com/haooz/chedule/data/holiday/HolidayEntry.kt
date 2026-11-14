package com.haooz.chedule.data.holiday

import com.haooz.chedule.data.model.Course
import kotlinx.datetime.LocalDate

// ════════════════════════════════════════════════════════════════════════
//  节假日条目 —— 纯数据类型
//
//  只承载数据，不碰存储或平台 API（查询逻辑见 [HolidayEntries]）。
//  ⚠ 本类型落盘的 JSON（`holiday_settings` 的 `entries_{年}`）是持久化格式，
//  键名/字段名换库时必须逐字对齐，属于数据兼容红线。
// ════════════════════════════════════════════════════════════════════════

/** 节假日与调休数据。假期跳过提醒，调休按配置的课表周次和星期调度。 */
data class HolidayEntry(
    val date: String,
    val endDate: String = "",
    val name: String,
    val type: Int,
    /**
     * 调休「上哪一天的课」的**绝对日期**（yyyy-MM-dd，空 = 未配置）。
     *
     * ⚠ 唯一可信来源。周次是相对课表学期开始时间算出的，同一 (周次,星期) 在不同课表下指向不同日期。
     */
    val followDate: String = "",
    /** 旧数据兼容：仅用于迁移/老备份还原，读取一律走 [followDate] */
    val followWeek: Int = -1,
    /** 旧数据兼容：仅用于迁移/老备份还原，读取一律走 [followDate] */
    val followWeekday: Int = -1,
    val custom: Boolean = false,
) {
    companion object {
        /** 假期 */
        const val TYPE_HOLIDAY = 0

        /** 调休工作日 */
        const val TYPE_WORKSWAP = 1
    }

    fun matches(target: String): Boolean {
        val targetDate = runCatching { LocalDate.parse(target) }.getOrNull() ?: return false
        val startDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
        val lastDate = if (endDate.isBlank()) {
            startDate
        } else {
            runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return false
        }
        return targetDate >= startDate && targetDate <= lastDate
    }

    /** 调休跟随的绝对日期；未配置/格式坏 → null */
    fun followLocalDate(): LocalDate? =
        followDate.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** 是否已配好跟随日期（老数据用 followWeek/followWeekday 也暂时算已配，等迁移） */
    fun hasFollowMapping(): Boolean =
        followLocalDate() != null || (followWeek > 0 && followWeekday in 1..7)
}

/**
 * 假期条目的**纯查询**逻辑：给定条目集合 → 查某天的条目，不碰任何存储或平台 API。
 */
object HolidayEntries {
    fun entriesForDate(entriesByYear: Map<Int, List<HolidayEntry>>, date: LocalDate): List<HolidayEntry> {
        val storageYearPriority = buildList {
            add(date.year)
            add(date.year - 1)
            addAll(entriesByYear.keys.filter { it < date.year - 1 }.sortedDescending())
        }.distinct()

        val yearRank = storageYearPriority.withIndex().associate { it.value to it.index }
        return storageYearPriority.flatMap { year ->
            entriesByYear[year].orEmpty()
                .filter { it.matches(date.toString()) }
                .map { year to it }
        }.sortedWith(
            compareByDescending<Pair<Int, HolidayEntry>> { it.second.custom }
                .thenBy { yearRank.getValue(it.first) }
        ).map { it.second }
    }

}

// ════════════════════════════════════════════════════════════════════════
//  节假日与调休 · 平台中立类型
//
//  只由 Int / Long / Boolean / String / [Course] 构成，不碰 android.* / java.time /
//  org.json / Gson，因此具备直接进 commonMain 的资格。
//
//  ⚠ 不要为了「文件归属好看」把它们塞进子包：包名仍是 com.haooz.chedule.data，
//  一旦改子包，同包调用点就要逐个加 import。
// ════════════════════════════════════════════════════════════════════════

/** Two consecutive weekday ranges from stable, original calendar weeks form one teaching week. */
data class TeachingWeekReorganizationRule(
    val firstOriginalWeek: Int,
    val firstStartWeekday: Int,
    val firstEndWeekday: Int,
    val secondOriginalWeek: Int,
    val secondStartWeekday: Int,
    val secondEndWeekday: Int,
)

/** A date in a reorganization gap keeps its surrounding teaching-week context but has no weekday. */
data class TeachingWeekPosition(
    val week: Long,
    val weekday: Int?,
    val isReorganizationPause: Boolean = false,
)

/** 假期最后一天的课程剔除：落在 [startSection]..[endSection] 节次内的课不上。 */
data class HolidayEndCourseExclusion(
    val enabled: Boolean = false,
    val startSection: Int = 1,
    val endSection: Int = 1,
) {
    fun isValid(): Boolean = startSection > 0 && endSection >= startSection
}

/** 假期前一天的课程剔除：与 [HolidayEndCourseExclusion] 同构，判定方向相反。 */
data class HolidayBeforeCourseExclusion(
    val enabled: Boolean = false,
    val startSection: Int = 1,
    val endSection: Int = 1,
) {
    fun isValid(): Boolean = startSection > 0 && endSection >= startSection
}

/** 某一天的逐日裁决结果：[courses] 是当天实际要上的课，两个 active 标志供 UI 提示用。 */
data class HolidayDayCourseResolution(
    val courses: List<Course>,
    val isHolidayDate: Boolean,
    val isHolidayEndCourseExclusionActive: Boolean,
    val isHolidayBeforeCourseExclusionActive: Boolean = false,
)

/** 被停掉的那批课里选一个代表展示，其余记为 [hidden]（用于课表卡片折叠）。 */
data class HolidayCourseDisplaySelection(
    val representative: Course?,
    val hidden: List<Course>,
)
