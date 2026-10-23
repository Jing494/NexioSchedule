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

# ── 模块布局 ─────────────────────────────────────────────────────────
# 上游 1.6.5 起项目是 KMP 多模块：同一份逻辑按平台能力分落三处。
# 旧的单模块 "app/src/main/java/com/haooz/chedule" 已整体退役。
# ★ 在 commonMain 里出现 android.* / java.* 一律不合法（要 expect/actual 或挪到
#   androidMain），所以下面这些路径同时也是"这段代码该放哪个模块"的说明。
J_APP = "androidApp/src/main/java/com/haooz/chedule"              # Android 宿主：提醒/服务/组件/平台
J_SHARED = "ui-shared/src/commonMain/kotlin/com/haooz/chedule"    # 共享 Compose UI
J_CORE = "core/src/commonMain/kotlin/com/haooz/chedule"           # 跨平台数据层

F_GLASS_PERF = f"{J_SHARED}/ui/utils/GlassPerf.kt"
F_CARD = f"{J_SHARED}/ui/activities/classschedule/CourseCard.kt"
F_DAYCOL = f"{J_SHARED}/ui/activities/classschedule/DayColumn.kt"
F_TODAY = f"{J_SHARED}/ui/activities/today/TodayScreen.kt"
F_ISLAND = f"{J_APP}/reminder/IslandNotificationHelper.kt"
F_REMIND = f"{J_APP}/reminder/CourseReminderHelper.kt"
F_ALARM = f"{J_APP}/reminder/AlarmReceiver.kt"
F_SCEN = f"{J_APP}/reminder/ReminderTestScenario.kt"
F_MAINACT = f"{J_APP}/ui/activities/MainActivity.kt"
F_APPSHELL = f"{J_SHARED}/ui/activities/main/CourseScheduleApp.kt"
F_APPSHELL_STATE = f"{J_SHARED}/ui/activities/main/CourseScheduleAppState.kt"
F_MAINSCHED = f"{J_SHARED}/ui/activities/classschedule/MainScheduleScreen.kt"
F_SETTINGS = f"{J_SHARED}/ui/screens/SettingsScreen.kt"
F_REMSCREEN = f"{J_SHARED}/ui/activities/CourseReminderScreen.kt"
F_REMCONTROLLER = f"{J_CORE}/data/ReminderController.kt"
F_UPDCHECK = f"{J_APP}/ui/utils/UpdateChecker.kt"
F_UPDINST = f"{J_APP}/ui/utils/UpdateInstaller.kt"
WF_CANDIDATES = (
    ".github/workflows/sync-upstream-build-release.yml",
    ".github/workflows/build-release.yml",
)
# 新建仓库不注册旧文件名（GitHub 的坑），对照仓/新仓用 build-release.yml；两者都认。
WF_REL = next((p for p in WF_CANDIDATES if os.path.exists(p)), WF_CANDIDATES[0])
F_UPDSET = f"{J_SHARED}/ui/activities/UpdateSettingsScreen.kt"
F_MANIFEST = "androidApp/src/main/AndroidManifest.xml"

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
    for mod in (J_APP, J_SHARED, J_CORE):
        for base, _dirs, files in os.walk(os.path.join(ROOT, mod)):
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
        "课程卡状态轮询：两个分支都「睡到边界 + 上限 60s」"
        "（上游改造前固定 1s：一屏几十张卡每秒各醒一次，却什么都不做）",
        F_TODAY,
        [r"untilStart\.coerceIn\(1_000L, 60_000L\)", r"untilEnd\.coerceIn\(1_000L, 60_000L\)"],
        [],
    ),
    (
        "禁止倒计时孤儿引用：上游 v1.6.0beta31 把「还剩 X 分钟」搬进了今日助手，"
        "卡片里那几个局部变量随之消失；v28 的 hunk 留在原地就成了「只有使用、没有声明」，"
        "本地不编译发现不了、直到 CI 才炸。"
        "（哪天这条报红，说明上游又把倒计时加回了卡片 → 必须重新评估 v28 的分钟节拍优化）",
        F_TODAY,
        [],
        [r"\bnewMinutes\b", r"\bremainingMinutes\b", r"\bremainingSeconds\b", r"\bnewSeconds\b", r"\btotalSeconds\b"],
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
        # 判定必须放 :core 的 ReminderController（纯逻辑、可被设置页与 androidApp 同时调用）。
        # 以前钉在 androidApp 的 IslandNotificationHelper 上，但 ui-shared 无法引用那个模块
        # （依赖方向 androidApp→ui-shared 单向），设置页就只能各写一套 —— 正是出 bug 的写法。
        F_REMCONTROLLER,
        [r"fun effectiveInClassRightMode\("],
        [],
    ),
    (
        "岛 B 区判定被设置页复用（不要裸读 prefs 各写一套）",
        F_REMSCREEN,
        [r"effectiveInClassRightMode\("],
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
        # 上游又把主界面壳拆成了 activities/main/ 一组文件：落点判定（computeDropTarget）
        # 现在在 CourseScheduleAppState.kt。循环变量上游用 s，这里两种都收。
        F_APPSHELL_STATE,
        [r"geom\.sectionTopDp\[(section|s)\]"],
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
        # 新架构是「单 Activity + 路由」：测试页也走路由（`AppRoute.ReminderTest`），
        # 不再有 ReminderTestActivity。入口仍然只此一个（设置 → 特色功能）。
        "测试入口唯一（设置→特色功能），且走路由而不是新增 Activity",
        F_SETTINGS,
        [r"AppRoute\.ReminderTest"],
        [r"ReminderTestActivity"],
    ),
    (
        # 原第 17 条是「测试页要在 Manifest 注册」（= 页面必须真的挂到宿主上）。
        # 路由形态下等价于「必须注册进 route→内容映射表」，否则点了没反应/穷尽 when 编译不过；
        # 另两条原意也钉在这里：页面只从场景表取数据与派发（不许另写一套文案/发送逻辑）、
        # 不许把 Activity 形态复活。
        "测试页必须注册进导航表（route→内容映射）+ 页面只从场景表取数据/派发",
        None,  # 全树：注册点在 AppRouteContent（Android）/ IosAppEntry（iOS）
        [
            r"AppRoute\.ReminderTest ->",
            r"ReminderTestRoute\(",
            r"ReminderTestScenario\.groups\(",
            r"ReminderTestScenario\.send\(",
        ],
        [r"ReminderTestActivity"],
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
        F_REMSCREEN,
        [r"val restoreMasterDependentSwitches = \{", r"restoreMasterDependentSwitches\(\)"],
        [],
    ),
    (
        "通知通道要在 Application 启动时创建（否则体检会把「还没建」误报成「被停用」）",
        F_REMIND,
        [r"fun ensureAllNotificationChannels\(context: Context\)"],
        [],
    ),
    (
        "启动路径确实调了通道创建",
        f"{J_APP}/NexioApplication.kt",
        [r"CourseReminderHelper\.ensureAllNotificationChannels\(this\)"],
        [],
    ),
    (
        "体检必须区分「通道未创建」与「通道被停用」",
        F_REMSCREEN,
        [r"val missingCount = channelStates\.count \{ it == null \}"],
        [r"ch == null \|\| ch\.importance =="],
    ),
    (
        "跨零点的课必须走 endMillisFor()（直接用 parseTimeToTodayMillis 算结束会判无效）",
        # 真 bug（上游也有）：23:30–00:30 的晚自习，parseTimeToTodayMillis("00:30") 得到的是
        # **今天** 00:30，比开始时刻早 23 小时 → endMillis <= startMillis → 课中卡/岛永远不出现、
        # 只闪一条「已上课」（用户视角"上课提醒了却没有课中进度"）。
        F_REMIND,
        [r"fun endMillisFor\(startMillis: Long, endTime: String\?\): Long"],
        [r"endMillis\s*=\s*parseTimeToTodayMillis\(", r"val parsedEnd = CourseReminderHelper\.parseTimeToTodayMillis\("],
    ),
    (
        "备份必须覆盖除主设置外的其它用户设置文件（触感/材质/主题/课中提醒/更新源）",
        # 真实缺口：全仓 9 个 SharedPreferences，备份原先只覆盖 course_schedule_prefs 一个，
        # 于是这些文件里的用户设置换设备/重装后静默回到默认值。
        f"{J_CORE}/data/CourseRepository.kt",
        [
            r"private val BACKUP_EXTRA_PREFS = listOf\(",
            r'"app_preferences"', r'"app_theme_prefs"', r'"appearance_settings"',
            r'"course_reminder_prefs"', r'"update_settings"', r'"edu_import_prefs"',
        ],
        [r'"(countdown_state|weather_prefs|stats_prefs|webdav_config)"\s*,?\s*//'],  # 这几类不该进备份
    ),
    (
        "跨文件还原必须走白名单（备份文件不能决定写哪个 SharedPreferences）",
        f"{J_CORE}/data/CourseRepository.kt",
        [r"if \(fileName !in BACKUP_EXTRA_PREFS \|\| originalKey\.isEmpty\(\)\) return"],
        [],
    ),
    (
        "rebase 易踩：isColorOs() 的收尾右花括号（漏了会让后面所有成员嵌套错层）",
        # 真实事故：升基准解冲突时，上游那段 isColorOs() 的收尾 `}` 属于"公共区"，
        # 被排到了我方 222 行之后 → 该函数永不闭合、后续成员全被套进它内部，
        # 编译期表现为一大批 Unresolved reference。括号总数检查不可靠（字符串模板/正则字面量
        # 会造成误报），所以这里针对该模式做定点守卫。
        F_REMIND,
        [r"\}\.getOrDefault\(false\)\n    \}\n"],
        [],
    ),
    (
        "备份导出必须是黑名单式（白名单会漏掉所有不带前缀的用户设置）",
        f"{J_CORE}/data/CourseRepository.kt",
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
    (
        "更新包必须校验本 fork 签名（防替换包）",
        F_UPDCHECK,
        [r'const val FORK_CERT_SHA256 = "a7fdc7b704db284774a0124fb13495b5e6ee4a05f6430547ad8ec21aba5ac938"'],
        [],
    ),
    (
        "安装前必须做签名一致性预检（挡住另一变体的包/被替换的包）",
        F_UPDINST,
        [r"private fun signatureMatchesOwn\("],
        [],
    ),
    (
        "workflow 的 EXPECT_CERT 必须与 app 里的 FORK_CERT_SHA256 是同一把证书"
        "（改了一处忘了另一处 = 更新校验会失效）",
        WF_REL,
        [r"EXPECT_CERT: a7fdc7b704db284774a0124fb13495b5e6ee4a05f6430547ad8ec21aba5ac938"],
        [],
    ),
    (
        "提醒体检要认识「假期首末课程排除」（否则用户会以为提醒坏了）",
        F_REMSCREEN,
        [r"假期课程排除：", r"loadBeforeCourseExclusion"],
        [],
    ),
    (
        "单一 Activity：不得再新增 .ui.activities.* 的 Activity（上游已改成单 Activity + 路由）",
        # 上游 1.6.5 把十几个二级页 Activity 全并进了路由。本地若再"图省事"加一个 Activity，
        # iOS/桌面就永远到不了那个页面，Manifest 也会重新变成多宿主。
        F_MANIFEST,
        [r"<activity"],
        [r'<activity\s+android:name="\.ui\.activities\.(?!MainActivity)'],
    ),
    (
        "CI 不许写死 APK 产物名（KMP 重构后产物名会变，写死就 zipalign 报 No such file）",
        # 真踩：KMP 重构后 androidApp 的 APK 不再叫 app-release[-unsigned].apk，工作流写死名字
        # → "Unable to open ... as zip archive: No such file or directory"。
        # 必须用 find 按目录解析（注释里提到这个名字不影响，所以只钉 SRC= 赋值）。
        WF_REL,
        [r"find androidApp/build/outputs/apk/release"],
        [r"SRC=.*app-release"],
    ),
    (
        "本复刻 README 的 fork 区块/鸣谢不能被合并冲掉",
        "README.md",
        [r"<!-- fork-only:start -->", r"<!-- fork-only:end -->", r"CREDITS\.md"],
        [],
    ),
    (
        "本复刻鸣谢文件不能被合并删掉",
        "CREDITS.md",
        [r"HaoZai000", r"xiaoxun007", r"Nanawwa"],
        [],
    ),
    (
        "本复刻维护清单不能被合并删掉",
        "docs/FORK-MAINTENANCE.md",
        [r"本复刻维护清单", r"fork-only"],
        [],
    ),
    (
        "已移除的专有 SDK 不能被写回第三方说明",
        "THIRD-PARTY-NOTICES.md",
        [r"Apache-2.0"],
        [r"xms-wearable", r"XMS 手表互联 SDK"],
    ),
    (
        "手表移除记录必须保留（历史章节不能整段删）",
        "LOCAL-CHANGES.md",
        [r"已停用（2026-10-24 移除）", r"## 十九、移除手表互联"],
        [],
    ),
    (
        "本复刻合并指南不能被合并删掉",
        "LOCAL-CHANGES.md",
        [r"本地定制改动清单"],
        [],
    ),
]

def preflight():
    """门禁配置自检：路径常量指向的模块目录 / 文件必须存在。

    上游一重构（app/ → androidApp + core + ui-shared），门禁里的路径就会**集体过期**，
    而过期的表现是"文件不存在 → 该条不通过"，看上去像源码问题（本轮就踩过两条）。
    单独先报一条，让"脚本过期"和"源码缺了这条"一眼可分。
    """
    problems = []
    for mod in (J_APP, J_SHARED, J_CORE):
        if not os.path.isdir(os.path.join(ROOT, mod)):
            problems.append("模块目录不存在: %s" % mod)
    for name, rel in sorted(globals().items()):
        if name.startswith("F_") and isinstance(rel, str) and rel.endswith(".kt"):
            if not os.path.exists(os.path.join(ROOT, rel)):
                problems.append("%s → %s 不存在" % (name, rel))
    return problems


def main():
    failures = []
    print("── 源码不变量核对（%s）" % os.path.abspath(ROOT))
    pf = preflight()
    if pf:
        failures.append((0, "门禁配置自检（路径常量）", pf))
        print("  ✗ [00] 门禁配置自检（路径常量）—— 架构可能又变了，先修脚本路径")
        for p in pf:
            print("        %s" % p)
    else:
        print("  ✓ [00] 门禁配置自检（路径常量）")
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
