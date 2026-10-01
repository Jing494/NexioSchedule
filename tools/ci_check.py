#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建前的源码不变量核对（质量门禁）。

为什么会有这个脚本：下面每一条都是本地真机实测 / 离线复算踩出来的坑
（岛 B 区联动、折射宽度恒定、日子词唯一、次日文案共享、假期余额最后一天、
节 Y 坐标单一真源、下载源只认带包的版本 …）。它们没有单元测试覆盖，
只能把"改回去就会重现故障"的约束用正则钉在源码上。

用法：  python3 tools/ci_check.py <源码根目录>
退出码：任一条不通过 → 1

⚠️ 在 CI 里**不要**写成 `python3 ci_check.py . | tee log`：
   bash 默认没有 pipefail，管道返回的是 tee 的退出码，失败会被吞掉、步骤照样绿。
   这个脚本本身就是因为那个坑才补上的（此前那条"质量门禁"一直是空转）。
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
J = "app/src/main/java/com/haooz/chedule"

F_GLASS_PERF = f"{J}/ui/utils/GlassPerf.kt"
F_CARD = f"{J}/ui/components/CourseCard.kt"
F_DAYCOL = f"{J}/ui/components/DayColumn.kt"
F_TODAY = f"{J}/ui/screens/TodayScreen.kt"
F_ISLAND = f"{J}/reminder/IslandNotificationHelper.kt"
F_REMIND = f"{J}/reminder/CourseReminderHelper.kt"
F_ALARM = f"{J}/reminder/AlarmReceiver.kt"
F_SCEN = f"{J}/reminder/ReminderTestScenario.kt"
F_MAINACT = f"{J}/ui/activities/MainActivity.kt"
F_MAINSCHED = f"{J}/ui/screens/MainScheduleScreen.kt"
F_SETTINGS = f"{J}/ui/screens/SettingsScreen.kt"
F_REMSCREEN = f"{J}/ui/activities/CourseReminderScreen.kt"
F_UPDCHECK = f"{J}/ui/utils/UpdateChecker.kt"
F_UPDSET = f"{J}/ui/activities/UpdateSettingsScreen.kt"
F_MANIFEST = "app/src/main/AndroidManifest.xml"

_cache = {}


def read(rel):
    """读源码文本；文件不存在返回 None（缺失本身也算失败）"""
    if rel not in _cache:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            _cache[rel] = None
        else:
            with open(path, encoding="utf-8", errors="replace") as fh:
                _cache[rel] = fh.read()
    return _cache[rel]


def walk_kt():
    """遍历所有 .kt（用于"全树只有一处实现"这类检查）"""
    for base, _dirs, files in os.walk(os.path.join(ROOT, "app", "src", "main", "java")):
        for name in files:
            if name.endswith(".kt"):
                full = os.path.join(base, name)
                with open(full, encoding="utf-8", errors="replace") as fh:
                    yield os.path.relpath(full, ROOT), fh.read()


# (说明, 文件, 必须匹配的正则, 禁止匹配的正则)
CHECKS = [
    (
        "毛玻璃降级是「全局系数 + 只淡强度」",
        F_GLASS_PERF,
        [r"fun glassPerfFactor\(\): Float = GlassPerf\.anim\.value"],
        [],
    ),
    (
        "四个绘制降级点都乘系数、且 <=0.01 一个 effect 都不追加",
        None,  # 全树统计
        [r"glassFactor > 0\.01f"],
        [],
    ),
    (
        "系数只在绘制期读，不进 remember 键",
        None,
        [],
        [r"remember\([^)]*glassPerfFactor"],
    ),
    (
        "折射宽度恒定、只淡强度（宽度乘系数会「扫过」+ 层几何每帧抖）",
        F_CARD,
        [r"lens\(lensRadiusPx, lensStrengthPx \* glassFactor\)"],
        [r"lens\([^,\n]*\*"],
    ),
    (
        "同上（课表格子）",
        F_DAYCOL,
        [r"lens\(lensRadiusPx, lensStrengthPx \* glassFactor\)"],
        [r"lens\([^,\n]*\*"],
    ),
    (
        "课程行倒计时唤醒点从「当前剩余秒数」推（写整分会跳过一分钟文案）",
        F_TODAY,
        [r"\(totalSeconds % 60 \+ 1\) \* 1_000L"],
        [],
    ),
    (
        "「返校」日子词只有一份实现（两通道分叉过一次）→ 见下方唯一性检查",
        F_REMIND,
        [r"internal fun returnDayVerb\("],
        [],
    ),
    (
        "次日提醒文案只有一份实现（AlarmReceiver 不能再写一套）",
        F_ALARM,
        [r"CourseReminderHelper\.nextDayReminderText\("],
        [],
    ),
    (
        "岛 B 区（课前/课中）模式判定只有一处真源",
        F_ISLAND,
        [r"fun effectiveInClassRightMode\("],
        [],
    ),
    (
        "岛 B 区判定被设置页复用（不要裸读 prefs 各写一套）",
        F_REMSCREEN,
        [r"IslandNotificationHelper\.effectiveInClassRightMode\("],
        [],
    ),
    (
        "岛通知的 XMSF 绕白名单是批量窗口：发送与取消共用同一个入口",
        F_ISLAND,
        [r"private suspend fun <T> withXmsfBypass\(", r"cancelIdsWithBypassRetry\("],
        [],
    ),
    (
        "节的 Y 坐标单一真源：落点判定优先用渲染实际用的 sectionTopDp",
        F_MAINACT,
        [r"geom\.sectionTopDp\[section\]"],
        [],
    ),
    (
        "同上：课表页把 specialGrid.sectionTop 传下去",
        F_MAINSCHED,
        [r"sectionTopDp = specialGrid\.sectionTop"],
        [],
    ),
    (
        "假期余额在假期最后一天也要发（回归过：最后一天永远不发）",
        F_REMIND,
        [r"val lastDay = span\.daysLeft <= 0"],
        [r"daysLeft > 0 && !isReturnDay"],
    ),
    (
        "测试入口唯一（设置→特色功能）+ 场景表在 ReminderTestScenario",
        F_SETTINGS,
        [r"ReminderTestActivity::class\.java"],
        [],
    ),
    (
        "测试页要在 Manifest 注册",
        F_MANIFEST,
        [r"ReminderTestActivity"],
        [],
    ),
    (
        "场景表不许自己持久化（写「当天只发一次」去重键会顶掉正式提醒）",
        F_SCEN,
        [],
        [r"prefs", r"\.edit\s*\{"],
    ),
    (
        "课表周页只预合成紧邻 1 页（调回 2 会多养两页的组合/测量/布局）",
        F_MAINSCHED,
        [r"beyondViewportPageCount = 1"],
        [],
    ),
    (
        "下载检查只认带 .apk 附件的版本（Gitee 建 release 时只有源码 zip）",
        F_UPDCHECK,
        [r"private fun apkUrlOf\(", r"if \(apk\.isBlank\(\)\) continue"],
        [],
    ),
    (
        "总开关联动必须可逆（联动手动关掉的子开关，总开关再打开时要能恢复）",
        # 真实反馈：用户没动过「课中提醒」，却发现自己被静默关掉了 ——
        # 因为总开关关闭时会强制置 false 且**没有反向恢复**。
        f"{J}/ui/activities/CourseReminderScreen.kt",
        [r"val restoreMasterDependentSwitches = \{", r"restoreMasterDependentSwitches\(\)"],
        [],
    ),
    (
        "通知通道要在 Application 启动时创建（否则体检会把「还没建」误报成「被停用」）",
        f"{J}/reminder/CourseReminderHelper.kt",
        [r"fun ensureAllNotificationChannels\(context: Context\)"],
        [],
    ),
    (
        "启动路径确实调了通道创建",
        "app/src/main/java/com/haooz/chedule/NexioApplication.kt",
        [r"CourseReminderHelper\.ensureAllNotificationChannels\(this\)"],
        [],
    ),
    (
        "体检必须区分「通道未创建」与「通道被停用」",
        f"{J}/ui/activities/CourseReminderScreen.kt",
        [r"val missingCount = channelStates\.count \{ it == null \}"],
        [r"ch == null \|\| ch\.importance =="],
    ),
    (
        "跨零点的课必须走 endMillisFor()（直接用 parseTimeToTodayMillis 算结束会判无效）",
        # 真 bug（上游也有）：23:30–00:30 的晚自习，parseTimeToTodayMillis("00:30") 得到的是
        # **今天** 00:30，比开始时刻早 23 小时 → endMillis <= startMillis → 课中卡/岛永远不出现、
        # 只闪一条「已上课」（用户视角"上课提醒了却没有课中进度"）。
        f"{J}/reminder/CourseReminderHelper.kt",
        [r"fun endMillisFor\(startMillis: Long, endTime: String\?\): Long"],
        [r"endMillis\s*=\s*parseTimeToTodayMillis\(", r"val parsedEnd = CourseReminderHelper\.parseTimeToTodayMillis\("],
    ),
    (
        "备份必须覆盖除主设置外的其它用户设置文件（触感/材质/主题/课中提醒/更新源）",
        # 真实缺口：全仓 9 个 SharedPreferences，备份原先只覆盖 course_schedule_prefs 一个，
        # 于是这些文件里的用户设置换设备/重装后静默回到默认值。
        f"{J}/data/CourseRepository.kt",
        [
            r"private val BACKUP_EXTRA_PREFS = listOf\(",
            r'"app_preferences"', r'"app_theme_prefs"', r'"course_reminder_prefs"',
            r'"update_settings"', r'"edu_import_prefs"',
        ],
        [r'"(countdown_state|weather_prefs|stats_prefs|webdav_config)"\s*,?\s*//'],  # 这几类不该进备份
    ),
    (
        "跨文件还原必须走白名单（备份文件不能决定写哪个 SharedPreferences）",
        f"{J}/data/CourseRepository.kt",
        [r"if \(fileName !in BACKUP_EXTRA_PREFS \|\| originalKey\.isEmpty\(\)\) return"],
        [],
    ),
    (
        "rebase 易踩：isColorOs() 的收尾右花括号（漏了会让后面所有成员嵌套错层）",
        # 真实事故：升基准解冲突时，上游那段 isColorOs() 的收尾 `}` 属于"公共区"，
        # 被排到了我方 222 行之后 → 该函数永不闭合、后续成员全被套进它内部，
        # 编译期表现为一大批 Unresolved reference。括号总数检查不可靠（字符串模板/正则字面量
        # 会造成误报），所以这里针对该模式做定点守卫。
        f"{J}/reminder/CourseReminderHelper.kt",
        [r"\}\.getOrDefault\(false\)\n    \}\n"],
        [],
    ),
    (
        "备份导出必须是黑名单式（白名单会漏掉所有不带前缀的用户设置）",
        f"{J}/data/CourseRepository.kt",
        [r"val excludedKeys = setOf\(KEY_DEFAULT_FOLDER_MIGRATED\)"],
        [r"val relevantKeys = listOf\("],
    ),
    (
        "切换下载源要失效缓存重查",
        F_UPDSET,
        [r"LaunchedEffect\(effectiveDownloadSource, updateChannel\)"],
        [],
    ),
    (
        "检查更新在缓存里没有下载地址时强制重查（否则只弹「未找到下载链接」）",
        F_UPDSET,
        [r"latestRelease!!\.apkUrl\.isNotBlank\(\)"],
        [],
    ),
]


def main():
    failures = []
    print("── 源码不变量核对（%s）" % os.path.abspath(ROOT))
    for idx, (desc, rel, must, must_not) in enumerate(CHECKS, 1):
        problems = []
        if rel is None:
            # 全树统计：把每个 .kt 的文本拼起来看（正则不跨文件）
            texts = [t for _p, t in walk_kt()]
        else:
            text = read(rel)
            if text is None:
                problems.append("文件不存在: %s" % rel)
                texts = []
            else:
                texts = [text]
        blob = "\n".join(texts)
        for pat in must:
            if not re.search(pat, blob):
                problems.append("缺少: %s" % pat)
        for pat in must_not:
            if re.search(pat, blob):
                problems.append("不该出现: %s" % pat)
        if rel is None and not texts:
            problems.append("没有扫到任何 Kotlin 源码（根目录传错了？）")

        if problems:
            failures.append((idx, desc, problems))
            print("  ✗ [%02d] %s" % (idx, desc))
            for p in problems:
                print("        %s" % p)
        else:
            print("  ✓ [%02d] %s" % (idx, desc))

    # 「出现次数 / 全树只有一处实现」类：单独做，避免正则跨行不可靠
    # (符号, 最少, 最多, 说明)
    counts = [
        ("fun returnDayVerb(", 1, 1, "「返校」日子词实现"),
        ("fun nextDayReminderText(", 2, 2, "次日文案实现（恰好 1 个重载）"),
        ("glassFactor > 0.01f", 4, 99, "毛玻璃降级守卫（四个绘制点）"),
        ("glassPerfFactor()", 5, 99, "毛玻璃系数读取点"),
    ]
    for symbol, lo, hi, label in counts:
        files = []
        for name, text in walk_kt():
            n = text.count(symbol)
            if n:
                files.append((name, n))
        total = sum(n for _f, n in files)
        if not (lo <= total <= hi):
            failures.append((0, label, ["%s 出现 %d 次（期望 %d~%d）：%s" % (symbol, total, lo, hi, files)]))
            print("  ✗ %s：%s 出现 %d 次（期望 %d~%d）" % (label, symbol, total, lo, hi))
        else:
            print("  ✓ %s：%s 出现 %d 次（期望 %d~%d）" % (label, symbol, total, lo, hi))

    print()
    if failures:
        print("❌ 不变量核对未通过：%d 条。这些约束对应的都是真机踩过的坑，请先修源码再发版。" % len(failures))
        return 1
    print("✅ 全部 %d 条不变量通过" % (len(CHECKS) + len(counts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
