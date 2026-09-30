# 本地定制改动清单 · 与「跟上上游」合并指南

> 这份文档是**本地自用**加的，不推上游。
> 目的只有一个：**上游发新版本时，能把下面这些定制机械地叠上去**，而不用重新摸一遍代码。
> 每次本地改动都请追加一节，并把 commit SHA 记上。

---

## 一、基线与身份

| 项 | 值 |
|---|---|
| **上游基线**（本定制栈的起点） | `291e8b9` — `v1.6.0.2-0928`（2026-09-28 升；历史基线 `f2c4ab6`=`v1.6.0.1`、`1f46721`=`v1.5.6beta13`） |
| **本地分支** | `local/audit-v22`（rebase 后；`tmp-pub3` 是等内容的发布栈，只少了 docs 提交） |
| **栈深度** | 本地 35 笔 / 发布栈 27 个补丁 |
| 包名 / versionCode | `com.haooz.chedule` / 上游 `158`；发版码 = `上游码×100 + gh序号` |
| versionName | **跟随上游**（`1.6.0.2-0928`），CI 发版时覆写为 `1.6.0.2.N-ghN`（序号全局计数，跨基准也单调） |

远端（都只是**只读参考**，本地定制一律**不推上游**）：

- `origin` → gitee `com_haooz_account/hyper_schedule`（本 worktree 的源）
- `github` → `HaoZai000/NexioSchedule`（已加为只读远端；另外 `_src/github/` 是它的独立 clone）
- 两边版本并不总是同步：本机当前 gitee `master` 在 `16ea289`，github 在 `8e596fc`

---

## 二、本地提交栈（从旧到新）

| # | 提交 | 日期 | 主题 | 文件数 |
|---|---|---|---|---|
| 1 | `0b47862` | 2026-09-26 | 本地修复:返校节次豁免 + 底栏避让导航栏 + 横屏导航判定 + 实况/岛同步 | 21 |
| 2 | `3ba856b` | 2026-09-26 | 余额/清单改为精确闹钟（自续） | 2 |
| 3 | `99990be` | 2026-09-26 | 审计修复 + 性能优化（v21） | 16 |
| 4 | `48e434f` | 2026-09-27 | v22：修椭圆伪影（回滚 EdgeLight 值比较）+ 文案 + 按小米岛/Android 实时更新规范修正 | 5 |
| 5 | `c628397` | 2026-09-27 | v23：修超级岛「下课后回落成普通通知」——取消没走绕白名单窗口 + 连续通知各自开窗 + 自愈掀窗 | 2 |
| 6 | `f2ddcd1` | 2026-09-27 | v24：修课表拖拽落点错位（+ 三项低风险审计项） | 5 |
| 7 | `5deac7b` | 2026-09-27 | v25：拖拽第二阶段 —— 落点格子对了，卡片仍吸附到偏高 1.5 格 | 1 |
| 8 | `b39a0c4` | 2026-09-27 | v26：修「假期余额提醒」在假期最后一天永远不发 | 1 |
| 9 | `2246fd2` | 2026-09-27 | v27：假期余额独立成卡 + 跟随豁免开关 + 只在最后N天 + 测试按钮 + 提醒体检 | 4 |
| 10 | `8c9d098` | 2026-09-27 | docs: 加 LOCAL-CHANGES.md（本地定制清单 + 跟上上游的合并指南） | 1 |
| 11 | `856261f` | 2026-09-27 | v28：流畅度 —— 预合成减半 / 整页重执行消除 / 轮询降到分钟节拍 / 滑动期毛玻璃降级 | 11 |
| 12 | `11c88e1` | 2026-09-27 | docs: LOCAL-CHANGES 补 v28（栈深度、主题索引、复算脚本、3 条新取舍） | 1 |
| 13 | `714c3cd` | 2026-09-27 | v29：毛玻璃降级从布尔硬切改为系数淡入淡出（修“突兀”） | 6 |
| 14 | （docs 提交，SHA 见 `git log -1`） | 2026-09-27 | docs: LOCAL-CHANGES 补 v28/v29（栈深度 14、取舍 8 升级为“必须系数+淡入淡出”） | 1 |
| 15 | `9b63b8b` | 2026-09-27 | v30：修毛玻璃过渡的「扫过」感 —— 折射宽度恒定、只淡强度 | 3 |
| 16 | （docs 提交，SHA 见 `git log -1`） | 2026-09-27 | docs: LOCAL-CHANGES 补 v30（栈深度 16、折射宽度恒定约束入清单） | 1 |
| 17 | `f33dfb4` | 2026-09-27 | v31：修「返校」提醒的日子词（标题写死“明天返校”而正文是今晚的课） | 3 |
| 18 | （docs 提交，SHA 见 `git log -1`） | 2026-09-27 | docs: LOCAL-CHANGES 补 v31（栈深度 18、取舍 11 日子词必须由目标日推导） | 1 |
| 19 | `54d528e` | 2026-09-27 | v32：修课中倒计时“飞了”的根因（B 区开关不联动）+ 测试场景板块 | 5 |
| 20 | （docs 提交，SHA 见 `git log -1`） | 2026-09-27 | docs: LOCAL-CHANGES 补 v32（栈深度 20、取舍 12 岛 B 区两开关必须联动） | 1 |
| 21 | `8fc45f2` | 2026-09-27 | v33：测试独立成页 + 覆盖补齐（返校岛/实时动态）+ 体检卡折叠下移 | 9 |
| 22 | （docs 提交，SHA 见 `git log -1`） | 2026-09-27 | docs: LOCAL-CHANGES 补 v33（栈深度 22、取舍 13 测试入口与体检卡位置） | 1 |

> 一整条栈是**线性**的，直接 `git rebase --onto <新上游> 1f46721 local/audit-v21` 就能整体搬过去。

---

## 三、按主题索引（冲突时按这里定位）

上游如果改了同一个文件，先看这张表知道**本地为什么动它**，再决定怎么合。

| 主题 | 涉及提交 | 关键文件 |
|---|---|---|
| 底栏避让系统导航栏 / 横屏导航判定 | 1 | `ScheduleBottomBar.kt` `MainActivity.kt` `MainScheduleScreen.kt` |
| 返校节次豁免（新功能：假期最后一天放出指定节次） | 1 | `data/ReturnDayReminder.kt`(新) `CourseReminderHelper.kt` `CourseRepository.kt` `DayColumn.kt` `MainScheduleScreen.kt` |
| 假期余额 / 返校清单（通知+精确闹钟） | 1, 8, 9 | `CourseReminderHelper.kt` `CourseRepository.kt` `HolidaySettingsScreen.kt` |
| 超级岛 / 实时动态 同步与规范修正 | 1, 4, 5 | `IslandNotificationHelper.kt` `CourseReminderHelper.kt` |
| **XMSF 绕白名单改成批量窗口**（取消也走窗口、按提交顺序串行、自愈不掀窗） | 5 | `IslandNotificationHelper.kt` `ShizukuManager.kt` |
| 性能（假期 JSON 缓存 / 刷新链唤醒门控 / Compose 重组热点） | 3 | `HolidayManager.kt` `CourseReminderHelper.kt` `MainActivity.kt` `TodayScreen.kt` `WidgetUpdateCache.kt` 等 |
| **节次几何单一真源**（拖拽落点 / 吸附位置 / 卡片高度都要用 `sectionTop`） | 6, 7 | `MainActivity.kt`（`computeDropTarget` / `sectionTopPx`）`MainScheduleScreen.kt`（`ScheduleGridGeometry`） |
| `drawBackdrop` lambda 记忆化（避免每帧重建毛玻璃） | 3 | `TodayScreen.kt` `ShortcutMenu.kt` `CustomizeScheduleScreen.kt` |
| 提醒设置页「提醒体检」自检 | 9 | `CourseReminderScreen.kt` |
| **滑动/翻页期毛玻璃降级**（卡片 blur+lens / 边光；**全局系数**只在绘制期读；v29 起 150/250ms 淡入淡出而非硬切；v30 起**折射宽度恒定、只淡强度**） | 11, 13, 15 | `ui/utils/GlassPerf.kt`(新) `MainActivity.kt`(driver) `CourseCard.kt` `DayColumn.kt` `TodayScreen.kt` `EdgeLightModifier.kt` |
| **轮询节拍**（助手/格言/课程行倒计时：分钟节拍；课程行按剩余秒数推跳变点） | 11 | `ui/utils/MinuteTick.kt`(新) `TodayAssistant.kt` `TodayScreen.kt` |
| 预合成页数 / 页作用域内的 state 读取 / 壳 pager key lambda / 跨页 LazyListState | 11 | `MainScheduleScreen.kt` `MainActivity.kt` `TodayScreen.kt` |
| 旧版残留闹钟 RC 的一次性清理标记 | 11 | `CourseReminderHelper.kt` |
| **「返校」日子词**（今天/今晚/明天必须与正文所依据的日期一致；原生实时动态与超级岛共用一份） | 17 | `CourseReminderHelper.kt` `IslandNotificationHelper.kt` `AlarmReceiver.kt` |
| **超级岛缩略态 B 区**（课前/课中同一块区域，两个下拉必须联动；未设置时跟随课前） | 19 | `IslandNotificationHelper.kt`(effectiveInClassRightMode) `CourseReminderScreen.kt` |
| **通知 / 超级岛 / 实时动态 测试页**（9 个场景，独立页面；只调主路径发送函数） | 19, 21 | `reminder/ReminderTestScenario.kt`(新) `ui/screens/ReminderTestScreen.kt`(新) `ui/activities/ReminderTestActivity.kt`(新) `SettingsScreen.kt` `CourseReminderHelper.kt` |
| 提醒体检卡（默认粗略状态、点击展开；位置在总开关下方） | 21 | `CourseReminderScreen.kt` |

---

## 四、上游发新版了，怎么合

### 方案 A（推荐）：`rebase --onto`

```bash
cd projects/nexio-schedule/src
git fetch origin                 # 或 git fetch github
git branch backup/v27 HEAD       # 先留个后路

# 把 1f46721 之后的本地 22 个提交整体搬到新上游上
git rebase --onto <新上游sha> 1f46721 local/audit-v21
```

冲突处理原则：**先看第三节的主题索引**，确认本地为什么改那几行，再决定保留哪边。
一般规律：本地改的是"新增分支/新增函数/修几何口径"，优先保留本地的语义、把上游的新结构套进来。

### 方案 B：只挑一部分

```bash
git cherry-pick 99990be 48e434f ...      # 按第二节的 SHA
```

### 方案 C：没有 git 对象时（离线）

- 逐提交补丁：`patches/series/0001-.patch … 0009-….patch`（`git format-patch` 产物，可直接 `git am`）
- 整体补丁：`patches/local-fixes.patch`（旧的）、`patches/audit-v21…v26.patch`
- 完整仓库快照（含全部提交与上游历史）：`_archive/nexio-local-fix-v27.bundle`
  ```bash
  git clone _archive/nexio-local-fix-v27.bundle nexio-restored
  ```

---

## 五、构建与签名

- 构建工作副本在 **app 私有目录**：`$HOME/build/nexio`（与 `src/` 内容一致，`src/` 是 git worktree）
- 构建脚本：`$HOME/build-nexio.sh`（含 JDK21 / SDK / aapt2 override 等坑的规避，注释见 `FINAL-REPORT.md`）
- 建议加资源限制，免得吃满手机：`nice -n 19 ./build-nexio.sh :app:assembleRelease --max-workers=2`
- 签名：`$HOME/nexio-local.jks`，alias `nexio`。**口令不写在这里**（工作区禁止落密钥），见 `REVISION-v2.md`
- 产物目录：`dist-v21/ … dist-v33/`，每次都是"同签名可直接覆盖安装"

---

## 六、验证脚本（可复跑，不依赖真机）

| 脚本 | 覆盖 |
|---|---|
| `dist-v30/verify_glass_v30.py` | 毛玻璃降级的**源码约束**核对（29 项，替代 v29 那份）：布尔开关无残留 / 四个调用点都乘系数且有 `<=0.01` 归零守卫 / 系数不进 `remember` 键 / 驱动是 150-250-250 / 边光乘 `alpha` 未改 `intensity` / **折射宽度未被乘系数（几何稳定性，钉死 v29 的「扫过」根因）** |
| `dist-v29/verify_glass_v29.py`（已废弃，见该目录 README） | 毛玻璃降级的**源码约束**核对（26 项）：旧布尔开关无残留 / 四个 effects 调用点都乘系数且有 `<=0.01` 归零守卫 / 系数没有任何一处进 `remember` 键 / 驱动是 150-250-250 而非写 0f-1f / 边光乘 `alpha` 且未改 `intensity` |
| `dist-v33/verify_testpage_v33.py` | 测试覆盖矩阵（真实通知类型/岛状态逐条对场景表）+ 独立成页（Manifest/入口/旧板块已移除）+ 体检卡（默认折叠/展开重查/位置在总开关之后）+ 次日文案只有一份实现 |
| `dist-v32/verify_islandtest_v32.py` | 岛 B 区模式联动（5 组判定，含"没设过不惊扰老用户"）+ 只有一份判定 + 14 项测试场景板约束（走主路径发送函数 / 通道判定一致 / 状态键同构 / UI 独立板块 / 老按钮与默认参数未改） |
| `dist-v31/verify_returnday_v31.py` | 「返校」日子词：7 组日子/时刻逻辑复算 + 11 项源码约束（只有一份实现 / 写死字面量只剩 `returnDayVerb` 内部一处 / 三个调用点都传 `targetDate` / 16:00 口径与清单通知互补）+ `isReturnDay` 公式核对 |
| `dist-v28/verify_perf_v28.py` | 性能改动的离线复算：分钟节拍（24h 唤醒数/漂移/值域）+ 课程行取值窗口覆盖（15 相位×3 作息，skip=0/无跳档/末分钟秒级/唤醒 3300→115）+ 唤醒间隔值域 |
| `dist-v27/verify_balance_v27.py` | 假期余额/清单：发不发 6 种条件 × 提前天数边界 × 文案矩阵（含豁免开关）× 25 项源码正则核对 |
| `dist-v25/verify_droptarget.py` | 拖拽几何：落点在哪一格 + 那一格画在哪个 Y（旧模型 ±1.5 格） |
| `dist-v24/verify_droptarget.py`、`dist-v22/`、`dist-v22/verify_logic_v22.py` | 早期版本的同名复算 |

> 这批脚本的价值在于：**新功能算的是不是源码里那套公式**（正则比对源码），
> 所以能挡住"测了个替身"。新加逻辑时请照这个模式补一份。

---

## 七、合并时要小心的几个"故意取舍"

这些是**有意为之**，别在上游合并时顺手改回去（详细理由见 `AUDIT-v22.md` 第 3.3 节）：

1. **岛的 XMSF 绕白名单必须是"批量窗口"**：发送与取消共用同一个断网窗口、按提交顺序串行、
   自愈要避开在飞窗口。改回"每条各开一次"会重现"下课后回落成普通通知"。
2. **节的 Y 坐标只有一套真源**：`ScheduleGridGeometry.sectionTopDp`。
   `sectionTopPx()` / `computeDropTarget()` 都必须优先用它（含特殊块偏移）。
   若上游改了 `computeSpecialGridLayout` 的 `sectionOffset`，这两个函数要一起复核。
3. **`EdgeLightElement.equals` 不能按值比较**：动态 shape（`rememberDynamicCornerRadiusShape`）
   实例不变、轮廓随动画变，按值比会让 `update()` 不再被调用、轮廓冻结成椭圆。
4. **假期余额在假期最后一天必须发**（原来 `daysLeft > 0 && !isReturnDay` 两个条件把最后一天排除掉了）。
5. **余额/清单的文案只有一份实现**（`balanceNotificationText` / `prepNotification*`），
   主路径与"立即测试"共用；别写第二份。
6. **测试按钮不能写"当天只发一次"去重键**，否则点一下就顶掉当天的正式提醒。
7. 性能那批改动里有几处是"看起来能省、其实会破坏可靠性"的边界，见 `AUDIT-v21.md`
   第 3.1/3.2 节与 `AUDIT-v22.md` 第 4 节（例如刷新链**不能**降级成非精确闹钟）。
8. **毛玻璃降级必须是「全局系数 + 淡入淡出」，不能改回布尔硬切**：
   - 硬切会让整屏玻璃在同一帧变平/弹回，实测反馈"突兀"；
   - 系数必须**只在绘制期读**（`effects` lambda / `DrawModifierNode.draw`）：
     进 `remember` 的键 → `drawBackdrop` 的 `effects` 身份每次变化即变、整块重建绘制缓存
     （v22 椭圆伪影同类）；读进 composition → 四千多行的壳 body 每个过渡帧重跑一遍；
   - 系数 **≤ 0.01 时必须一个 effect 都不追加**：否则 `RenderEffect` 非空，
     `canDirectBlit`（直采共享预模糊层、跳过每卡离屏录制）那条快路径就没了，收益全丢；
   - 边光淡出只能乘 `layer.alpha`，**不能改 `intensity`**：`recordedIntensity` 一失配就会每帧重录；
   - 停滑后的 250ms 防抖别去掉（惯性尾部会连续开关）；
   - **折射的「宽度」(`lens()` 的 `refractionHeight`) 必须恒定，只淡「强度」(`refractionAmount`)**：
     宽度乘系数会让那圈高光向卡片边缘收拢/扫过（观感"蹭的一下冒出来"），
     而且库内 `padding = padding - refractionHeight` 会让层几何每帧抖。
9. **课程行倒计时的唤醒点必须从"当前剩余秒数"推**（`totalSeconds % 60 + 1`），
   **不能**写成"睡到下一个整分"：显示用 floor 语义，进循环那一瞬若压在跳变点上
   （"未开始"分支会睡到 `start`，正好是整分），会取到退化值并把那一分钟的文案整段跳过
   （肉眼 45 → 43）。相反，助手/格言的文案是 ceil 语义，睡到整分才对 —— 两者**不能统一**。
13. **测试入口只有一个（设置 → 特色功能 →「通知与超级岛测试」），别再塞回提醒设置页**：
   场景表在 `reminder/ReminderTestScenario.kt`，页面只做列表与展示，发送一律走主路径函数 ——
   千万别在页面里另写一套文案（本项目已两次踩到"两处各写一套"）。新增通知类型时，
   请同时往场景表补一条，否则又会出现"测不到"的盲区（本轮就是这样发现次日提醒文案
   写死在 AlarmReceiver 里、因此无法测试的）。

12. **超级岛缩略态 B 区：课前/课中两个设置必须联动**：
   它们是**同一块区域**，但历史上是两个独立下拉，课中那个默认是静态「正在上课」。
   于是"课前在跳秒、上课后变静态文案"——用户读作"下课倒计时飞了"。
   `effectiveInClassRightMode()` 是唯一判定：显式选过听用户的，没设过（-1）跟随课前 B 区。
   改动时**不要**再把两边各写一套读取逻辑。另外：测试课的 2 分钟课时会让岛到点自动收起，
   那是正常时序，不是掉线。

11. **「返校」提醒的日子词必须由「正文所依据的那一天」推导，且原生实时动态与超级岛共用一份**：
   `isReturnDay(d) = isRestDay(d) && isSchoolDay(d+1)` → 返校日就是假期最后一天，
   正文列的可能是**今天**被豁免放出来的晚自习，此时标题写死「明天返校」就自相矛盾
   （真机实测即是此例）。规则：目标日在今天之后→「明天返校」；目标是今天且首节 ≥16:00→「今晚返校」；
   目标是今天但首节更早→「今天返校」。16:00 口径与 `prepNotificationTitle` 必须一致。
   这次两个通道同时中招，正因为当初各写了一套 —— 别再分叉。

10. **`beyondViewportPageCount` 现在是 1**：调回 2 等于同时多养两页的组合/测量/布局，
    是本轮最大的一笔白干。若上游为了别的原因改大，要连带评估课表页开销。


---

## 八、升基准记录（1f46721 → f2c4ab6，2026-09-27）

上游在 3 天里推了 62 笔提交、68 个文件、+8947/−1817；与本地 26 笔定制提交**重叠 22 个文件**。
真正的工作量不在"解冲突"，而在**上游自己实现了同一批功能**，需要判断谁留谁走：

| 功能 | 上游的实现 | 处置 |
|---|---|---|
| 假期末日课程例外 | `data/HolidayCourseExclusion.kt` + `HolidayManager.load/saveEndCourseExclusion` + `HolidayDayCourseResolution`，贯通 resolveDaySchedule / 今日页 / 课表页 / 格子；设置入口在假期设置页的「**节假日末期课程排除**」（开关 + 节次范围） | **采用上游**。我原来的「返校节次豁免」（`ReturnDayReminder` 的 exempt 那套 + 自有偏好 + 自有卡片）变成 dead code，设置入口已删；提醒层的「已开启」门槛改为认上游那个开关（任一开着都算开） |
| 底栏避让导航栏 | `ScheduleBottomBar` 里 `maxOf(24.dp, navBarBottomInset + 8.dp)` | **删我的**（写法几乎相同） |
| 横滑手势 | `HorizontalPagerGesture` 大改 279 行（加 overscroll / 橡皮筋） | **取上游**；我的"同一帧合并 scrollBy"性能补丁未落（待在新结构上重新评估） |
| 教学周重组 | `TeachingWeekReorganization`（+ 假期设置页入口与规则列表） | 上游独有，保留 |
| 课表页预合成 | 未动 | 我的 `beyondViewportPageCount = 1` 保留 |

**仍然保留的本地定制**：假期余额提醒、返校准备清单、返校日子词（`returnDayVerb`）、
超级岛/实时动态的规范修正与 XMSF 绕白名单、毛玻璃系数降级与折射宽度恒定、
分钟节拍轮询、通知与超级岛测试独立页、下载源只认带 APK 的版本、应用内跳本 fork Releases。

### 两个操作教训（别再踩）

1. **`git rebase` 里冲突标记是反的**：`<<<<<<< HEAD` 段是**上游**，`=======` 之后才是我的提交。
   第一次全取反了；本机 `~/.pick.py <file> up|mine [块号]` 就是为此写的。
2. **`concurrency.cancel-in-progress` 会在下一轮 dispatch 时取消正在跑的那轮**：
   gh6 就是在 Gitee 附件上传中途被取消，留下一条"有 release 没有 APK"的记录
   （App 侧会跳过它；workflow 已加 `cancelled() || failure()` 的收尾清理步骤）。
   发版期间**不要连发两次 dispatch**。


---

## 九、升基准记录（f2c4ab6 → 291e8b9 = 1.6.0.2-0928，2026-09-28）

上游 10 笔提交、37 文件、+747/−287 —— 量比上一次小得多，**冲突只有 4 处**。
这次的特点是：**上游自己在做性能优化，而且和我 v21/v28 撞车**。

| 我做过、上游也做了 | 我的做法 | 处置 |
|---|---|---|
| "effects/onDrawSurface 必须 remember 稳定，否则 `DrawBackdropElement.equals` 判不等→重建 RenderEffect→壁纸玻璃无意义重绘" | v21/v28 给 TodayScreen 的玻璃卡/分组标题各加一层 remember 包装 | **取上游**（同源优化，且他们还把 `blurPx` 多 remember 了一层） |
| "半径为 0 不挂 blur 图层" | v21 在 MainActivity 里用 `graphicsLayer { renderEffect = … }` 自己判 | **取上游**（他们的 `mainContentBlurModifier` 用同样两个半径来源与 `>0.01f` 阈值） |
| `beyondViewportPageCount = 1`（预合成减半） | v28 改的 | 上游也采纳了 → **只保留我的说明注释** |
| 毛玻璃系数淡入淡出（`glassPerfFactor`，折射宽度恒定只淡强度） | v29/v30 | **我的独有，保留**（上游没有系数，只有固定强度） |
| 轮询降到分钟节拍（`MinuteTick`） | v28 | **我的独有，保留** |

### 本轮 dry-run 抓到的两个真问题（都已修）
1. **`isColorOs()` 的收尾 `}` 被挤走**：上游那段函数的收尾括号在冲突里属于"公共区"，
   被我方 222 行推到了后面 → 该函数永不闭合、后续 29 个成员全被套进它内部，
   编译期报一大片 `Unresolved reference`。已 amend 进引入它的那笔提交，
   门禁加第 27 条定点守卫（"`}.getOrDefault(false)` 后面必须紧跟 `}`"）。
   **教训：合并"两边各插一段"的冲突时，公共区的收尾括号属于哪一边要单独确认。**
2. **上游改了 `showStartedLiveNotification` 的签名**（去掉 `classroom`），
   而我的测试路径还在传 → 编译不过。测试路径跟着改成与真实路径一致。

### 审计发现并修掉的缺口（v42）
`exportAllPreferences()` 只导出 `course_schedule_prefs`，而全仓有 **9 个 SharedPreferences**：
`app_preferences`（触感反馈 / 应用材质等级 / 预测返回 / 隐藏背景）、`app_theme_prefs`（主题）、
`course_reminder_prefs`（课中提醒 / 岛 B 区模式 / 展开光效）、`update_settings`（更新通道 / 下载源）、
`edu_import_prefs`（教育导入仓库）里的用户设置**都不进备份**。
现在用命名空间前缀 `prefs@<文件>@<键>` 一并导出/还原（白名单分派 + 按目标类型落库），
明确排除 `countdown_state`（瞬时）、`weather_prefs`（缓存）、`stats_prefs`（计数）、
`webdav_config`（含凭据）。门禁加第 28/29 条不变量。

> **本轮的固定流程**（以后照做）：rebase → 静态体检（门禁 + 结构）→ CI **dry_run** 验编译 →
> 绿了再正式发版 → 归档 dist → **审计**（文案入口名 / 开关联动口径 / 读取已删配置 / 死代码 /
> 备份覆盖面 / get-set 配对）→ 审计发现**直接修** → 再审计，直到干净。
