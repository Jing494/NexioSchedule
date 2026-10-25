# 本地定制改动清单 · 与「跟上上游」合并指南

> 这份文档是**本地自用**加的，不推上游。
> 目的只有一个：**上游发新版本时，能把下面这些定制机械地叠上去**，而不用重新摸一遍代码。
> 每次本地改动都请追加一节，并把 commit SHA 记上。

---

## 一、基线与身份

| 项 | 值 |
|---|---|
| **上游基线**（本定制栈的起点） | `fbb16aa3` — `v1.6.0.2-0928`（beta33，2026-09-29 升；历史基线 `291e8b9`=`1.6.0.2-0928`、`f2c4ab6`=`v1.6.0.1`、`1f46721`=`v1.5.6beta13`） |
| **本地分支** | `local/audit-v22`（rebase 后；`tmp-pub4` 是等内容的发布栈，只少了 docs 提交） |
| **栈深度** | 本地 40 笔 / 发布栈 30 个补丁 |
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
| beyondViewportPageCount=1（预合成减半） | v28 改的 | 2026-10-25 上游改为滑动期 1 / 停稳 2/3；门禁 #19 已跟随 |
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

> **2026-10-17 重写**：上游已把单模块 `app/` 拆成 KMP 多模块，下表按**新布局**给。
> 路径速记：`androidApp/`（Android 宿主：reminder / widget / 平台实现）、
> `ui-shared/src/commonMain/`（共享 Compose UI，**不许 android./java.**）、
> `core/src/commonMain/`（数据层）、`core/src/androidMain/`（数据层的 Android 实现）。

| 主题 | 落在哪 | 关键点 |
|---|---|---|
| 提醒口径 / 精确闹钟 / 跨零点 | `androidApp/…/reminder/CourseReminderHelper.kt` | `endMillisFor()`、通道启动即建、余额最后一天 |
| 次日提醒文案（唯一实现） | 同上 + `…/reminder/AlarmReceiver.kt` | `nextDayReminderText` 恰好 2 个重载 |
| 超级岛 / 实时动态 | `androidApp/…/reminder/IslandNotificationHelper.kt` | XMSF 绕白名单必须是**批量窗口** |
| 岛 B 区判定（唯一真源） | `core/src/commonMain/…/data/ReminderController.kt` | 课前 0/1/2 → 课中 0/1 要**翻译**；哨兵 -1 |
| 假期余额 / 返校清单（数据） | `core/…/data/CourseRepository.kt`（getter/setter）+ `core/src/androidMain/…/data/ReturnDayReminder.kt` | 这些键不带前缀，只有备份黑名单带得走 |
| 假期余额 / 清单（设置 UI） | `ui-shared/…/ui/activities/HolidaySettingsScreen.kt` | 独立成卡，不挂在豁免卡的 `if(enabled)` 里 |
| 提醒体检 / 测试页 | `ui-shared/…/ui/activities/CourseReminderScreen.kt`、`…/ui/screens/ReminderTestScreen.kt`、`androidApp/…/reminder/ReminderTestScenario.kt` | 测试页走**路由**，绝不新增 Activity；场景表零持久化 |
| 毛玻璃系数降级 | `ui-shared/…/ui/utils/GlassPerf.kt` + 4 个绘制点（CourseCard / DayColumn / TodayScreen / `backdrop/…/edgelight/EdgeLightModifier.kt`） | 折射宽度恒定、只淡强度、**只在绘制期读** |
| 分钟节拍 | `ui-shared/…/ui/utils/MinuteTick.kt` + TodayScreen / TodayAssistantCard | 用 kotlinx.datetime，不要 java.time |
| 节次几何单一真源 | `ui-shared/…/ui/screens/MainScheduleScreen.kt`（ScheduleGridGeometry）+ `…/ui/activities/CourseScheduleApp.kt`（computeDropTarget） | `sectionTopDp` **和** `specialBandRangesDp` 都要 |
| 备份语义（故意分歧） | `core/…/data/CourseRepository.kt` | 黑名单导出 + `prefs@文件@键` + `appearance_settings` |
| 更新链路（fork 源 + 签名预检） | `androidApp/…/ui/utils/UpdateChecker.kt` / `UpdateInstaller.kt` + `ui-shared/…/ui/components/UpdateDialog.kt`、`activities/UpdateSettingsScreen.kt` | 只认带包版本 / 切源重查 / 安装前签名预检 |
| 单 Activity + 路由 | `androidApp/…/ui/navigation/AppRouteContent.kt` + `ui-shared/…/ui/navigation/*` | 新页面加路由，不动 Manifest |
| CI / 门禁 / 版本号 | `.github/workflows/build-release.yml` + `tools/` | 门禁 47 条 +「配置自检」 |

**fork 独有的新文件**（不会与上游正文冲突，但模块搬家时 git 可能把它们放错位置）：

- `core/src/androidMain/kotlin/com/haooz/chedule/data/ReturnDayReminder.kt`
- `ui-shared/src/commonMain/kotlin/com/haooz/chedule/ui/utils/GlassPerf.kt`
- `ui-shared/src/commonMain/kotlin/com/haooz/chedule/ui/utils/MinuteTick.kt`
- `ui-shared/src/commonMain/kotlin/com/haooz/chedule/ui/screens/ReminderTestScreen.kt`
- `androidApp/src/main/java/com/haooz/chedule/reminder/ReminderTestScenario.kt`

**合并时的三条判断口径**：

1. 两边都改 → 先查 §七「故意取舍」，再决定；能"新增分支 / 新增函数"就不要改上游那几行。
2. 上游用默认值重构 helper 时（如 `buildGridGeometry()`），**fork 加的字段必须显式补上** —— 有默认值就照样编译，语义静默丢失。
3. 合并后必须 `python3 tools/ci_check.py .`：47 条全绿 + `[00] 配置自检` 通过才算完；路径报"文件不存在"先怀疑脚本过期。

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

- 构建在**应用私有目录**里做一份独立副本（与工作区内容一致的拷贝；早年的 worktree 方案已退役）
- 构建脚本、JDK/SDK/aapt2 的坑规避都放构建机本地，**不入库**
- 构建与服务都在远端 CI 上跑；本机只改文件（见 §十七「验证方式」）
- 签名用本 fork 的 keystore：**文件与口令只在构建机与 GitHub Secrets 里**，仓库内一律不写、也不指路
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

10. **课表周页预合成**：滑动期 beyondViewportPageCount=1，停稳后由 WeekPagerHost 扩到 2（边缘驻留连续翻周 3）。不要无条件常驻多页；改大要连带评估课表页开销。


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
| 课表页预合成 | 上游改为 WeekPagerHost 动态 1/2/3 | 2026-10-25 合并后跟随上游，不再固定 1 |

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
| beyondViewportPageCount=1（预合成减半） | v28 改的 | 2026-10-25 上游改为滑动期 1 / 停稳 2/3；门禁 #19 已跟随 |
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

### 第三类问题：补丁守卫的**假警报**（CI 连续三轮红）
现象：守卫报 **27 个补丁全部"内容不一致"**，而本地 `check_patches.py` 对比却是"一致"。
根因：`git format-patch` 的**缩写哈希长度是自动伸缩的**（随仓库对象数变化）——
本机 8 位（`index df24e5b7..5cf4dc77`）、GitHub runner 7 位（`index df24e5b..5cf4dc7`），
而守卫只归一化了 `-- <git版本>` 尾巴和 `[PATCH NN/total]` 计数。
前几轮能过，只是因为那会儿两边恰好都是 7 位 —— 属于运气。
修法：`check_patches.py` 的 `normalize()` 增加 `index <hash>..<hash>` 归一化；
并让它在报"不一致"时**打印首个不同的行**（原来只报文件名，排查只能靠猜）。
反向对照：真实内容改动仍会被判不一致 ✓。

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


---

## 十、升基准记录（291e8b9 → fbb16aa3 = 上游 beta31~33，2026-09-29）

上游 4 笔提交、15 文件、+1082/−125，**没改版本号**（仍是 `1.6.0.2-0928` / code 158）。

### 上游这轮加了什么
| 新增 | 内容 |
|---|---|
| `ui/screens/ClassEndEffects.kt` | **下课庆祝特效**：烟花粒子 + 随爆炸的短促震动（并加了 `VIBRATE` 权限） |
| `wearable/WatchPayload.kt` + `WearableScheduleSync.kt` + `app/libs/xms-wearable-lib_1.4_release.aar` | **小米手表同步**（AAR 是 102 KB 二进制，随上游基准进来 —— 我们的补丁不需要携带它，所以不会踩到「二进制补丁」问题；2026-10-24 已移除） |
| CourseRepository / 两屏备份 / 偏好设置 / 设置页 / 今日助手 / 今日页 / ShareScheduleData | 接入上面两个功能 |

### 冲突只有 3 处
1. **TodayScreen（v28）**：上游把课中倒计时**改回朴素的 `delay(1000L)` 秒级轮询** ——
   我的 v28 是「降到分钟节拍」（离线复算验证过的优化）→ **取我的**。
2. **CourseRepository（v40/v42）**：⚠️ **语义分歧，见下**。
3. **LocalBackupScreen**：两边各加了一个 import → 都保留。

### ⚠️ 与上游的**故意分歧**：全量备份带不带用户设置
上游把「全量备份」重新定义成 **"课表数据 + 节假日/调休"**，新增
`isCombinationBackupKey()` / `isAppFeatureBackupKey()`，**导出侧不写、恢复侧还跳过**这些键
（搭配 / 提醒 / 勿扰 / 小部件档位 / 主题）。
**本 fork 选择相反**：备份**带上全部用户设置**（用户 2026-09-29 拍板选 A），
因为诉求就是"换设备/重装后别静默丢开关"。实现见 `exportAllPreferences()` 的 `excludedKeys`
（只排除 `KEY_DEFAULT_FOLDER_MIGRATED`）+ `BACKUP_EXTRA_PREFS`（跨文件命名空间 `prefs@<文件>@<键>`）。
**每次 rebase 都会在这里冲突**，要改回上游语义：删 `excludedKeys`、改用那两个 helper、
并在恢复侧加回它的跳过守卫。

### 本轮同时发布的审计修复（v43/v44）
- 跨零点的课（23:30–00:30 晚自习）没有课中卡/岛 → 新增 `endMillisFor()` 统一 4 处；
- 「提醒体检」把"通道尚未创建"误报成"3 个通道被停用" → 启动即建通道 + 体检区分未创建/被停用，
  另补一项「系统勿扰」；
- 总开关联动不可逆（关总开关会静默关掉「课中提醒 / 上课勿扰」且不恢复）→ 改为可逆。

### 本轮还踩/记下的两条
1. **`git commit -m "…"` 里带半角双引号会提前闭合 shell 引号** → 消息被当成 pathspec、
   提交根本没生成（推送显示 Everything up-to-date）→ 之后一律写**消息文件**（`-F`）。
2. **上游 Gitee 镜像是滞后的**：判断"上游有没有新提交"要以 **GitHub 上游**为准
   （这轮 Gitee 还停在 beta30，GitHub 已经 beta33）。

---

## 十一、CI 改为「合并式 fork」（B+C 方案）

**改了什么**：`.github/workflows/build-release.yml` 不再"钉死上游基准 + 逐个 `git am` 重放补丁"，
改成 `master` 就是发布分支（= 上游 + 本 fork 的定制提交），CI 直接构建它：

- 定时（每 6h）/ 手动触发 → 上游有新提交就先 `git merge upstream/master`；
- 合并干净且未命中「分歧清单」→ 构建 → 门禁 35 条（当时） + APK 契约核验全绿 → **才**推 master、才发版；
- 合并冲突 → `merge --abort`，**远端一个字节都不动**，另开 Issue 等人工。

**为什么**：31 个补丁每次升基准都要整体重放，上游一动就可能"半合半不合"地留下孤儿引用。
本次 beta33 正是这么炸的：上游删掉卡片倒计时，v28 的 hunk 却还在原地引用那几个已不存在的局部变量
（`newMinutes` 等），本地不编译 → 一路到 CI 才报 11 条 `Unresolved reference`。
合并式只在两边真的改到同一处时才需要人工介入。

**安全设计**（都不依赖人的记性）：

1. **推 master 放在最后**：构建、门禁、契约、签名核验全过了才推 —— 远端 master 永远不会指向"构建不过"的树。
2. **分歧清单** `tools/divergence-watch.txt`：列的是**故意**与上游不同的文件（备份语义、毛玻璃系数、
   分钟节拍、岛 B 区联动 …）。合并碰到就**只验证不发版**并开 Issue 请人工过一眼 ——
   "能编译 + 门禁能过"并不等于语义没被翻回去。
3. **冲突即停**：abort 后远端不变；另有"失败时开 Issue"兜底。
4. **dry_run 预演**：可以拿任意分支 ref 先跑整条流水线（`{"ref":"<分支>","inputs":{"dry_run":"true"}}`）。

**验证方式**：

- 离线：在被测仓库里**原样执行** workflow 的 `check` / `merge` / `watch` 三个 `run` 块
  （只替换 `${{ }}` 表达式与本机不可写的 `/tmp`），四条路径全绿：
  无更新→不合并；干净→合并；命中清单→`blocked=true`；冲突→abort 且 HEAD 不动、无 `MERGE_HEAD` 残留。
- 真跑：先推 `ci-merge-test` 分支，用 `workflow_dispatch(ref=ci-merge-test, dry_run=true)` 跑通整条链路，再落 master。

**回滚**：旧形态的 master（`4e61976f`：钉基准 + 补丁）仍在历史里，
`git push --force origin 4e61976f:master` 即回到旧流水线（旧的 workflow 文件就在该提交内）。

**留档**：`patches/series/*.patch`（31 个）与 `tools/check_patches.py` 保留但不再参与构建；
`local-audit-v21` 分支是"发布栈"（与 `patches/` 精确对应）。

### 十一·附：落地与验证结果（同一天完成）

- **beta33 升基准的编译问题**：先修 v45，再跑 `dry_run` —— 门禁 35 条（当时）全过、`BUILD SUCCESSFUL`、
  算出 `1.6.0.2.12-gh12`；随后正式发版 **gh12**：GitHub + Gitee 双平台都挂上了 APK + `.sha256`，
  签名指纹 `a7fdc7b7…ac938` 一致、`versionCode 15812` 单调（序号 12 > 历史 11）。
- **新（合并式）CI 的真实验证**：先把它推成 `ci-merge-test` 分支，用
  `workflow_dispatch(ref=ci-merge-test, dry_run=true)` 跑通整条链路（合并判定、门禁、签名、版本核验全绿，
  dry_run 故不发版），**确认无误后才落到 master**。
- **预演抓到的一个真事故**：我把原来 `env:`（只有一个 `UPSTREAM_BASE`）整段退役时，只剩注释没删键 ——
  PyYAML 认为 `env: null` 合法，**GitHub 直接判 `Invalid workflow file: (Line: 37, Col: 5): Unexpected value ''`**：
  `workflow_dispatch` 返回 422，而且推分支还会因此产生一条 `startup_failure` 运行（像是"CI 挂了"）。
  现已新增 `tools/check_workflow.py` 做本地预检（空值键 / 触发条件 / 步骤完整性 / run 语法 /
  `steps.<id>` 引用是否存在），并做过反向对照：拿"没修的那版"跑它会精准复现 GitHub 那条报错。

### 十一·附2：合并式 CI 的第一次真实自动跟（它挡住的两件事）

上游 PR #57（`9ea4e54d`）进来后，定时任务自动合并 → 门禁 35 条（当时）通过 → **构建失败**，
流水线按设计**没推 master** 并开了 Issue。原因不是我们的代码（Kotlin 编译全过）：

```
Execution failed for task ':app:validateSigningRelease'
> Keystore file '.../keystore/nexio-release.jks' not found for signing config 'release'
```

上游这次给 `app/build.gradle.kts` 加了 `signingConfigs.release`（读仓库根的可选
`keystore.properties`，storeFile 缺省 `../keystore/nexio-release.jks`），并挂到 debug/release
两个 buildType 上 —— **构建期**就要求 keystore 存在；而本流水线原本是"先构建、后写 keystore 签名"。

改法：新增「准备签名材料」步骤放在构建**之前**（解 `secrets.KEYSTORE_BASE64` → `signing/fork.jks`，
写 `keystore.properties`，值里的反斜杠转义；该文件与 `*.jks` 上游已加进 `.gitignore`）；
原签名步骤改为「整理产物」：优先用构建期已签名的 `app-release.apk`，上游哪天撤掉 signingConfig
则自动回退旧的 `zipalign + apksigner`；对齐只做 `-c` 检查（签完再 zipalign 会破坏 v2/v3 签名）。
验证：在 `ci-fix-keystore` 分支先跑 dry_run（真合并 `9ea4e54` + 真构建）通过后才落 master。

**凭据纪律（新增，长期有效）**：`tools/check_secrets.py` 成为**每次都跑**的闸 ——
扫工作区 / 跟踪文件 / 提交信息（本地可 `--objects` 扫全历史对象），判 GitHub PAT、
`access_token=`/`oauth2:` 形态、云厂商 key、私钥头，以及"仓库跟踪了 keystore/.env/
keystore.properties"这类结构问题；输出**只给类型与位置、命中一律打码**，绝不回显凭据本体。
已做反向对照（种一个假 token 能被精准抓到且打码）。特意**不**把 `*.pem/*.key` 当文件名命中：
公开证书是合法入库的，真正的私钥由内容头规则抓 —— 否则会误拦发布（已用真实合并树本地预跑验证）。
另外失败 Issue 改为**去重**（同类未关就追加评论），不再按天堆一屏。

### 十二、wear 变体（与手表 rpk 同证书的第二签名）—— **已停用（2026-10-24 移除）**

> 以下为历史记录，保留用于追溯。手表互联需求已取消，相关代码/CI/secrets 均已移除。

**需求**：小米穿戴的 interconnect 在商店签名那一套里要求「手表 rpk 与手机 APK 同包名且同签名」
（`WearableScheduleSync` 的注释），而本 fork 普通包用的是自己的 keystore（为了覆盖安装）。
于是同一个 Release 里再放一个 `…-wear.apk`，用**与手表 rpk 同一把** keystore 重签 ——
内容是同一次构建、版本号完全相同，只换签名。

**实现**：
- CI 新增「可选｜wear 变体签名」：读 `WEAR_KEYSTORE_BASE64/…PASSWORD/…ALIAS`，
  先 `zipalign -f 4`（必须，它会重写 zip 并去掉 AGP 的 v2/v3 签名块）再用 apksigner 三方案重签，
  产出 `…-wear.apk` + `.sha256`；**没配 secrets 就跳过**，流水线照常出普通包。
- 契约核验追加：wear 变体必须**与普通包同版本号**、**与普通包不同证书**（同证书 = 白做，直接红）。
- Gitee 附件改为上传 `final/` 下**全部** APK，并按本地数量核验（不再只挂一个）。
- 更新逻辑（App 侧，两处）：
  1. `UpdateChecker`：新增 `FORK_CERT_SHA256` 常量与 `ownSignerSha256()`/`isWearVariant()`；
     挑附件时按变体过滤 —— 普通变体不认 `-wear`，wear 变体只认 `-wear`；
     没有**本变体**包的版本直接跳过（原来只判断"有没有 .apk"，两个变体进来就会挑错）。
  2. `UpdateInstaller`：本地缓存名带变体后缀；`installApk` 之前**校验下载包的签名 == 本机自己的签名**，
     不一致就删掉并提示（挡住另一变体的包 / 被替换的包）。
- 门禁新增 4 条：变体常量存在、挑包按变体过滤、安装前签名预检、
  **workflow 的 EXPECT_CERT 与 app 里的 FORK_CERT_SHA256 必须同一把证书**（改一处忘另一处会认错变体）。
- 分歧清单新增 `app/build.gradle.kts`：上游上次就是在这里加了"构建期要求 keystore"。

**关于 rpk 的实测**：用户提供的 `Nexio 课程表（小米手环10Pro）.rpk`（v1.0.0/21，包名 `com.haooz.chedule`，
声明了 `system.interconnect`）里 **`META-INF/CERT` 只是一份 SHA-256 摘要清单，没有任何证书**，
`build.txt` 也是 `originType=undefined` / `component=true` 的开发者工具组件包 —— 也就是这份 rpk
**没有可"同签名"的对象**，普通包很可能直接就能连。wear 变体作为**备好的一道门**存在：
一旦确认手表期望的是哪把证书（商店签名 rpk / 或自己重签 rpk），把对应 keystore 填进 4 个 secrets 即可。

### 十三、上游 v1.6.1beta2 合并 + 两个"只有真跑才暴露"的坑

**1) 冲突不是最危险的，静默丢语义才是。** 上游把课表网格几何的内联构造重构成了
`buildGridGeometry()`，而本 fork 给 `ScheduleGridGeometry` 加过两个字段
（`sectionTopDp` / `specialBandRangesDp`，修"拖拽落点整体偏下"）。这两个字段**有默认值**，
所以上游那份只传 6 个参数的构造**照样能编译** —— 直接"取上游那边"就会静默抹掉这个修复。
解法是两边都要：采用上游的重构，同时把两个字段补进 helper。
门禁 ci_check.py 的「节的 Y 坐标单一真源：落点判定优先用渲染实际用的 sectionTopDp」
正是为这种"能编译但语义变错"准备的。

**2) `cp X X` 在 `bash -e` 下会直接判失败（真踩）。** 上游这轮把"强制正式签名"撤掉了
（`a6d2d2d0` / `6bc863e0`），于是产物变成未签名，我们第一次真正走「整理产物」的回退路径：
`SRC` 已被设为 `$OUT`，最后又 `cp -f "$SRC" "$OUT"` → `cp: ... are the same file` → 退出码 1 →
GitHub 默认 shell `bash -e` 让整步失败（日志红在"整理产物"）。修法是只在两者不同时才复制。

**教训（写给我的下一次）**：用桩工具复现 workflow 步骤时，**必须用 `bash -e`**
（GitHub 的默认 shell 是 `bash -e {0}`）。我先前那版桩测试用的是 `bash`（没有 `-e`），
所以 `cp` 的非零退出被吞掉、测试全绿，却在上游真跑时红 —— 这也是本次 dry_run 的价值：
master 一个字节没动，问题在预演分支上就被抓住。

### 十四、上游 PR#60/#61 的同步审查（我们定制要不要跟着改？）

上游新增「**假期前日课程排除**」（新键 `before_course_exclusion_enabled` / `_start_section` /
`_end_section`，UI 在假期设置里）与「手表 version=4 全学期推送」，并且动了我们分歧清单里的 5 个文件
（CourseRepository / CourseReminderHelper / CourseCard / DayColumn / TodayScreen）。

**逐条结论：功能联动不需要改，都有依据**

| 我们的定制 | 是否受影响 | 依据 |
|---|---|---|
| 课前提醒 / 上课勿扰 / 岛 / 实况 / 课中卡 | 不受影响（自动跟随） | 都通过 `getTodayCourses()` → `resolveDaySchedule().courses` 取课，新排除就发生在这一层 |
| 假期余额 / 返校提醒 | 不受影响 | 看的是「末期豁免」开关与假期首末日期，与"前日排除"是两件事 |
| 备份 / 云同步（全量用户设置） | 不受影响 | 导出走 `HolidayManager.exportBackupData()`，上游这轮给它加了 `BACKUP_BEFORE_EXCLUSION_KEY` → 新键已自动进备份 |
| 课表拖拽几何（`sectionTopDp`/`specialBandRangesDp`） | 不受影响 | 上游这轮只加了"被排除课程"的展示传参，没碰几何；门禁 40 条全过 |
| 毛玻璃降级（GlassPerf 系数） | 不受影响 | 新徽章 `CourseCardBadge` 是纯数据 + 文案，不参与绘制 |
| 自续精确闹钟（余额 / 清单） | 不受影响 | 由 HolidayManager 状态驱动，与课程排除无关 |

**主动补的一处（也就是"监听那个按钮"）**：提醒体检新增一条**说明项** —— 当「假期首末课程排除」开着时显示
「假期前一天：第 X~Y 节；假期最后一天：第 X~Y 节 —— 这些课在这些天不展示也不提醒（不是提醒坏了）」。
它用 `isInfo` 与"通过/不通过"区分（ℹ + 中性色），不计入"待处理"计数；门禁新增第 41 条把它钉住，
防止以后合并时被静默丢掉。

**文案**：已跟随上游把该段从「节假日末期课程排除」改名为「节假日首末课程排除」（本 fork 余额卡片描述里也同步）。
**未做（可选）**：测试页加一个「假期前日排除」场景，不必等真假期就能验证联动。

### 十五、上游 v1.6.1beta8→v1.6.2beta4（7 提交 / 73 文件）的合并与同步审查（历史记录；wear 已移除）

> 本节为历史审查记录；其中手表相关代码已于 2026-10-24 移除。

**冲突 9 文件 14 段**，处置见合并提交 `69d5f765`。两条最关键的：
- `UpdateChecker.kt`：上游新增「记录附件 sha256(digest) + 体积」与「安装包完整性校验」
  （体积/ZIP 魔数/中央目录/PM 解析/包名与 versionCode 递增）。做法是**合体**：
  采集摘要时用**本变体的 -wear 过滤**（否则 wear 用户会拿普通包的摘要去校验 wear 包，永远失败），
  并把上游的校验块与我们的 `apkTagOrNull`（剥 `-wear`）并存。
  上游的 `verifyApk`/`rememberApkDigest` 已接进安装链路（`UpdateInstaller` 42/106 行调用），
  我们的 `signatureMatchesOwn` 预检仍在 `installApk` 之前 —— 两道闸叠加，互不冲突。
- `WatchPayload.kt`：整文件取上游（手表协议 v4 由上游重写，本 fork 无定制）。

**新增的上游数据流向（需要你知情/拍板）**：上游这轮加了首次启动的**隐私同意弹窗**
（未同意不申请权限、不发起网络副作用），并新增 `StatsReporter` / `NoticeFetcher` /
`AppreciationFetcher` —— **同意后**会在启动时把设备/运行信息发往
`http://182.92.193.223`（**明文 HTTP**）与 `nexioschedule.icu`（安装量/活跃/公告）。
我们 fork 的额外流向只有：更新检查走**我们自己的** GitHub/Gitee Release、WebDAV 备份走你自己填的服务器。
`CrashLogHelper` 经核查**完全不联网**（本地录屏/日志留痕）。
→ 选项：① 保持上游行为（你可在弹窗里拒绝）；
  **决定（用户 2026-10-04）：不动**，保持上游行为 —— 不关 phone-home、不改策略文案；
  弹窗里拒绝即可不上报。以后不必再翻这条。
  原选项：① 保持上游行为（你可在弹窗里拒绝）；② 本 fork 关掉 phone-home（小改动、需新增分歧清单项）；
③ 保持行为但把本 fork 的策略文案补一句"更新检查走本 fork 仓库"。

**顺带**：把 `UpdateChecker.kt` / `UpdateInstaller.kt` 加入「故意分歧清单」——
这两处我们的定制最深、上游又改得最勤，以后一撞就该人工过一眼（这次就是撞了才发现要合体）。

### 十六、Gitee 自愈 job（补齐「GitHub 有、Gitee 缺」的版本）

**事故**：gh17 在 Gitee 上整版缺失。日志里的真因是 `curl exit 35`（Gitee 与 runner 之间 TLS 抖动），
而**创建 release 的那句 curl 没加保护** —— GitHub 默认 shell 是 `bash -e {0}`，一次抖动就掐断整步，
后面写的「重试 / 按 tag 回查 / 附件重试」根本没机会跑；随后按设计把「没有安装包的半成品 release」删掉，
于是 Gitee 上什么都没有（GitHub 侧正常，master 全程可用）。

**两处修法**：
1. 现有 Gitee 发布步骤加固：创建 release 的 curl 包进 3 次重试 + `|| true`；按 tag 回查加保护；
   附件上传重试 3→5 次、退避 5s→10s（跨国上传会抖，抖一次就得回退重发一整版）。
2. **新增 `gitee-backfill` job（自愈）**：不依赖构建、跑得很快，手动 dispatch 与每 6 小时的定时都会跑。
   对最近 3 个 GitHub Release 逐个检查 Gitee 侧：缺 release 就补建、缺安装包就从 GitHub Release
   拉同名 APK 挂上去并核验。用桩把三条控制流都跑过（齐全→跳过 / 缺 release→补建+挂包 / 有壳无包→只挂包），
   并用 `bash -e` 复现 GitHub 的默认 shell（这个事故的根因就是 `-e` 下未保护的 curl）。
---

## 十七、升基准到上游 v1.6.5（4754e6c0）—— 上游重构成 KMP，本地定制要**重做**而不是"解冲突"

**这一轮的性质变了**：上游把单模块 `app/` 拆成了 KMP 多模块，并且从"多 Activity"改成
"单 Activity + Compose 导航"。所以本地那 41 个定制文件不是"改到同一处需要手工选边"，
而是**所在模块变了**：

| 上游落位 | 放什么 | 我们的东西怎么办 |
|---|---|---|
| `androidApp/` | Android 宿主：reminder / widget / 平台实现 | 我们改得最深的提醒层（CourseReminderHelper / IslandNotificationHelper / AlarmReceiver）在这里，**android.* 合法**，可原样保留 |
| `ui-shared/commonMain/` | 共享 Compose UI（screens / components / activities） | 这里**不能出现 android.***：我们过去在 MainActivity/Screen 里直接读 Context、起 Intent 的写法全都要改成上游的抽象 |
| `core/commonMain/` | 跨平台数据层 | Context + SharedPreferences → `AppStorage.store(name)`（`KeyValueStore`）；`java.time` → `kotlinx.datetime` |

新抽象（重做时照这些走，别再引 Context）：
- 存储：`AppStorage.store("<文件名>")` → `KeyValueStore`（`all()` / `edit { putX }` / `registerListener`）；
- 导航：`LocalHostNavigation`；路由见 `ui/navigation/AppRoute.kt`（**不要再新增 Activity**）；
- 平台能力：`PlatformToast` / `PlatformLog` / `PlatformInfo`（core 的 expect/actual）。

### 第一阶段（已完成，提交 `739695ac`）
把 master 拉回**结构干净、可构建**的上游基线：冲突一律先取上游（含 AndroidManifest 里
已不存在的那些 Activity）、`app/` 与 `mtzwidget/` 跟随上游删除（前者是重构产物、后者上游已弃，
都还在 git 历史里）、本地独有的 6 个新文件先摘除待重做。
保留的本地基建：`.github/workflows/`、`tools/`（门禁/守卫）、`patches/`（留档）、`LOCAL-CHANGES.md`。

回滚点：`pre-merge-upstream-1.6.5`（= `0fe5ab9f`，与远端 `Jing494/NexioSchedule` master 同一个提交）。

### 第二阶段（进行中）：按新架构逐项重做
**`tools/ci_check.py` 就是这份清单** —— 它现在按新模块路径解析，缺哪项就报哪项。
第一阶段结束时它报 **30 条**；重做一项就少一条。已完成的：

- ✅ **备份覆盖全部用户设置**（`core/CourseRepository.kt`）：导出改黑名单 + `prefs@文件@键` 命名空间，
  白名单新增 `appearance_settings`（上游把搭配外观拆到了这个新文件，不带上就丢外观）。
  门禁 26/27/29 通过。

待重做（按价值排序，共 27 条门禁）：
1. 提醒层：跨零点 `endMillisFor`、通知通道启动即建、体检区分「未创建/被停用」、总开关可逆联动、
   假期余额最后一天、返校日子词、次日文案单份实现（`androidApp/reminder`）
2. 超级岛：B 区课前/课中联动单一真源、XMSF 绕白名单批量窗口（`androidApp/reminder`）
3. 测试页与体检：场景表 + 独立页（**做成路由，不要新增 Activity**）+ 体检卡 + 「假期首末课程排除」说明项
4. 流畅度：毛玻璃全局系数降级（4 个绘制点）、分钟节拍（`MinuteTick` 要用 kotlinx.datetime）
5. 更新链路：下载源只认带包版本、切源失效重查、缓存无地址强制重查（`ui-shared` 的
   UpdateDialog / UpdateSettingsScreen + `androidApp` 的 UpdateChecker/UpdateInstaller）
6. 节次几何单一真源 `sectionTopDp`（拖拽落点）

### 验证方式（本地编不了）
构建机上没有可用的 JDK/Gradle（工作区所在卷不可执行），所以**编译只有 CI 能验**：
推预演分支 → `workflow_dispatch(ref=<分支>, dry_run=true)`。
注意门禁在 dispatch 时也会跑，所以预演分支要么先把清单修到全绿，要么临时放宽门禁步骤。
**发版期间别连发两次 dispatch**（`concurrency.cancel-in-progress` 会取消在跑的那轮）。


---

## 十八、第二阶段完成（2026-10-17）· 合并式 CI 全链路验证通过

**结果**：上游 **v1.6.5（4754e6c0）** 已合入 master；本地定制按 KMP 新架构**全部重做**；
CI 门禁 41/41、KMP 平台纯净度 360 文件零违规、凭据扫描干净；GitHub Release 已发
**`v1.6.5.19-gh19`**（Nexio.v1.6.5.19-gh19.apk + .sha256；wear 变体因未配 WEAR_* secrets 未出）。

**分支状态**：master = `e231280e`（合并 `0fe5ab9f` + 上游 `4754e6c0`，9 个提交）；
回滚点 `pre-merge-upstream-1.6.5` = `0fe5ab9f`（仍是旧的多 Activity 定制栈）。

**本轮真正的工作量在"重做"而不是"解冲突"**：上游把 `app/` 拆成 KMP
（`androidApp` / `core` / `ui-shared` / `miuix` / `backdrop`）并改成单 Activity 导航，
所以本地 41 个定制文件按模块重新落位：提醒层留 `androidApp`（`android.*` 合法）、
共享 UI 进 `ui-shared/commonMain`（不许 `android.*`，走 `AppStorage` / `ReminderController` 注入）、
数据层进 `core/commonMain`（`java.time` → `kotlinx.datetime`）。

**本轮踩到并记下的三条**：
1. **跨模块的"唯一真源"要先看依赖方向**：岛 B 区判定原本在 `androidApp`，而设置页在 `ui-shared`
   —— 依赖是单向的，设置页**不可能**引用它，所以过去只能各写一套（正是"课前跳秒/上课变静态"的根因）。
   判定挪进 `:core` 的 `ReminderController` 才成立；门禁也跟着从 androidApp 改指 core。
2. **两个值空间不同要翻译，不能透传**：课前 B 区 `0/1/2`（课程名/地点/倒计时）、课中只有 `0/1`
   （正在上课/距下课）。"没设过跟随课前"必须写成 `if (课前==2) 1 else 0`，哨兵是 **-1** 不是 0。
3. **"已收口的符号"是合并后编译失败的高频来源**：本地代码搬进新模块后仍用裸
   `android.util.Log` / `Toast`，而新架构该位置走 `NexioLog` / `showToast`。CI 首次 `dry_run`
   只报 7 个错误，全是这一类 —— 本地编不了时，**早跑 CI 最省事**（比人肉核对快得多）。

另外：产物路径别再写死 `app-release[-unsigned].apk` —— KMP 重构后 `androidApp` 的产物名变了，
工作流已改成按目录 `find` 解析（写死就报 `Unable to open … No such file`）。

---

## 十九、移除手表互联与 wear 变体（2026-10-24）

**决定**：手表互联需求取消。旧仓库 26 个 Release 从未发布过 -wear.apk（WEAR_* secrets 一直未配置），
gh27 的 wear 包下载量为 0，没有需要迁移的真实用户。

**删除**：
- androidApp/…/wearable/（WatchPayload / WearableScheduleSync）与相关测试
- 小米 XMS AAR 依赖、androidApp/libs/ 说明
- App 内「导出到手环」入口与 onExportToWearable 注入链
- ScheduleEffects.syncWearable 及 iOS/Android 实现
- UpdateChecker / UpdateInstaller 的 wear 变体分流（保留签名预检）
- CI 的 wear 签名/契约步骤、AAR 还原步骤、WEAR_* / XMS_AAR_* secrets

**保留**：§十二、§十五 的历史记录已标「已停用」；发布流程改为单变体普通包。
gh28 起只发普通包，gh27 的 wear 附件从 GitHub/Gitee 删除。

---

## 二十、跟上游 1.6.7-1025（21919ead，2026-10-25）

- 22 笔；冲突 6 处；保留本 fork 的手表移除与分钟节拍。
- TodayAssistantCard 的 tick 循环补回 now/current/next（上游把判定收进 statusAt）。
- ci_check #19 跟随上游 WeekPagerHost：滑动期 beyond=1，停稳 2/3。
- 发布 v1.6.7.29-gh29（versionCode 16729），Gitee 同步 ffca20e6。
