/** 通知 / 超级岛 / 实时动态测试页 - Screen（场景表在宿主侧，本页只展示与派发） */
package com.haooz.chedule.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.data.platform.showToast
import com.haooz.chedule.ui.glass.ScheduleSoftGlassStyle
import com.haooz.chedule.ui.components.text.material.Text
import com.haooz.chedule.ui.utils.AppMaterialSettings
import com.haooz.chedule.ui.utils.isAppDarkTheme
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.chedule.ui.components.softGlassBackdrop
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.layout.CollapsibleTopAppBarDefaults
import top.yukonga.miuix.kmp.layout.SharedScrollBehavior
import top.yukonga.miuix.kmp.layout.collapsibleTopInset
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 超级岛通道色（与胶囊顶栏选中态同一族）。 */
private val IslandAccent = Color(0xFF5B5BD6)
/** 普通通知通道色。 */
private val NeutralAccent = Color(0xFF8A8A8E)

/**
 * 测试页的一个场景。
 *
 * ⚠ 文案只由场景表（宿主侧 `ReminderTestScenario`）提供，本页**不复刻**任何场景文案与发送逻辑 ——
 * 本项目已经两次踩到「两处各写一套」（页面里再写一份文案/再拼一次通知）。
 * 本页也不知道「超级岛/实时动态怎么发」：点一下只把 [id] 交回宿主。
 */
data class ReminderTestEntry(
    /** 稳定 id（= 场景枚举名）：派发时用它回查场景表，commonMain 不认识宿主侧枚举 */
    val id: String,
    val label: String,
    val detail: String,
    /** 走「超级岛 / 实时动态」通道（普通通知为 false）；只用于结果提示文案 */
    val liveContainer: Boolean = false,
)

/** 场景分组（课程链路 / 返校链路 / 其他通知）。 */
data class ReminderTestGroup(
    val title: String,
    val entries: List<ReminderTestEntry>,
)

/** 卡片圆角：与课表卡片同一档（20dp），视觉上一眼「同一套材质」。 */
private val CardCorner = 20.dp
/** 行内左侧色条宽度。 */
private val AccentBarWidth = 3.dp

/**
 * 「通知 / 超级岛 / 实时动态」测试页。
 *
 * 独立成页（不再塞在提醒设置页里）：场景表与分发都在宿主的 `ReminderTestScenario`，
 * 本页只负责列表、展示与把点击交回去 —— 发送一律走主路径那几支函数，不在这里拼通知文案。
 *
 * 通道（超级岛 / 原生实时动态）由主路径同一套判定决定，页面上直接显示出来，
 * 免得「点了没反应却不知道发去哪了」。
 *
 * ## 视觉（1.7.x 重做）
 * 上游这一轮把玻璃抽成了 :softglass 库，本页跟着换成**同一套材质语言**：
 * - 卡片走 `softGlassBackdrop`（实时录制采样），不再是纯色 `Card`；
 * - 每个场景带一条**通道色条**（岛=靛蓝 / 普通通知=灰），扫一眼就知道发去哪；
 * - 按压有明确的缩放与高光，点了到底有没有反应不再靠猜；
 * - 无材质档 / 无 shader 时自动退回实色（与 `AppMaterialSettings` 同一套判定）。
 *
 * @param channelLabel 当前实际生效的通道文案（宿主判定，与主路径一致）
 * @param groups 场景分组；空列表 = 该平台没有可测场景（桌面/iOS）
 * @param onRefreshChannel 重新读取通道，返回新的文案
 * @param onSend 点一条场景：宿主按 [ReminderTestEntry.id] 调主路径发送函数
 * @param liquidGlassBackdrop 玻璃采样源（宿主提供）；null = 无玻璃，卡片退回实色
 */
@Composable
fun ReminderTestScreen(
    channelLabel: String,
    groups: List<ReminderTestGroup>,
    onRefreshChannel: () -> String,
    onSend: (ReminderTestEntry) -> Unit,
    scrollBehavior: SharedScrollBehavior? = null,
    liquidGlassBackdrop: GlassBackdrop? = null,
) {
    // 通道是设置项的派生值：进页面读一次，另给一个「重新读取」入口
    var channel by remember(channelLabel) { mutableStateOf(channelLabel) }

    Box(modifier = Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
                .collapsibleTopInset(scrollBehavior)
                .then(
                    scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier
                ),
            contentPadding = PaddingValues(
                start = 16.dp,
                // 顶栏折叠高度 + 呼吸：既不被胶囊顶栏盖住，也不让第一张卡贴边
                top = CollapsibleTopAppBarDefaults.CollapsedHeight + 20.dp,
                end = 16.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (groups.isEmpty()) {
                // 该平台没有可测场景（桌面/iOS）：只给一句说明，别渲染一个空通道卡片
                item(key = "empty") { ReminderTestEmptyHint(liquidGlassBackdrop) }
            } else {
                item(key = "channel") {
                    ReminderTestChannelCard(
                        channel = channel,
                        backdrop = liquidGlassBackdrop,
                        onRefresh = { channel = onRefreshChannel() },
                    )
                }
                groups.forEach { group ->
                    item(key = "title_" + group.title) { ReminderTestSectionTitle(group.title) }
                    item(key = "card_" + group.title) {
                        ReminderTestScenarioCard(
                            entries = group.entries,
                            channel = channel,
                            backdrop = liquidGlassBackdrop,
                            onSend = onSend,
                        )
                    }
                }
                item(key = "footnote") { ReminderTestFootnote() }
            }
        }
    }
}

/**
 * 玻璃卡片壳：本页所有卡片共用。
 *
 * 两条硬约束（都是踩过的坑，与课表卡片同一套口径）：
 * 1. `shape` / `style` 必须 **remember 出稳定引用** —— drawBackdrop 按引用比较，
 *    每次重组新建会让采样层整块重录；
 * 2. 无材质档 / 没给采样源 → 直接画实色，不挂玻璃节点。
 *
 * 滑动降级系数**不用在这里读**：`SoftGlassSurface` 内部已在绘制期把它乘进样式
 * （`withGlassPerf()`，静态与实时两条路径共用），本页作为消费者不必重复一遍 ——
 * 重复读反而多一处"忘了进 remember 键"的风险。
 */
@Composable
private fun GlassCard(
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val materialsOn = AppMaterialSettings.glassEffectsEnabled()
    val isDark = isAppDarkTheme()
    val shape = remember { ContinuousRoundedRectangle(CardCorner) }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { CardCorner.toPx() }
    val flatColor = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.72f)
    }
    val glassStyle = remember { ScheduleSoftGlassStyle }

    val decorated = modifier
        .fillMaxWidth()
        .clip(shape)
        .then(
            if (materialsOn && backdrop != null) {
                Modifier.softGlassBackdrop(
                    backdrop = backdrop,
                    shape = shape,
                    style = glassStyle,
                    radiusPx = radiusPx,
                    blurSigmaPx = glassStyle.smallBlurRadius,
                )
            } else {
                Modifier.background(flatColor, shape)
            }
        )

    Box(modifier = decorated) { content() }
}

@Composable
private fun ReminderTestChannelCard(
    channel: String,
    backdrop: GlassBackdrop?,
    onRefresh: () -> Unit,
) {
    GlassCard(backdrop) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态点：走岛=靛蓝实心，普通实时动态=中空灰
                val islandOn = channel.contains("岛")
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(ContinuousRoundedRectangle(5.dp))
                        .background(
                            if (islandOn) IslandAccent
                            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                        )
                )
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前通道",
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = channel,
                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                PressablePill(text = "重新读取", onClick = onRefresh)
            }
            Text(
                text = "课程链路与返校提醒都按这个通道发：开了超级岛走岛，关掉走原生实时动态。" +
                    "清单 / 余额 / 次日提醒是普通通知，不走岛。",
                style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            )
        }
    }
}

/** 小胶囊按钮：按压缩放 + 触感反馈。 */
@Composable
private fun PressablePill(
    text: String,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "pillScale",
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ContinuousRoundedRectangle(14.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = if (pressed) 0.22f else 0.14f))
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReminderTestSectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/**
 * 一组场景。
 *
 * 做成一张连续卡片、条目之间用极细分隔线（不是每行一张卡）：
 * 场景是「同一件事的几个变体」，切开反而显得零散。
 */
@Composable
private fun ReminderTestScenarioCard(
    entries: List<ReminderTestEntry>,
    channel: String,
    backdrop: GlassBackdrop?,
    onSend: (ReminderTestEntry) -> Unit,
) {
    GlassCard(backdrop) {
        Column(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                ScenarioRow(
                    entry = entry,
                    channel = channel,
                    onClick = {
                        onSend(entry)
                        val where = if (entry.liveContainer) channel else "普通通知"
                        showToast("已发送：" + entry.label + "（" + where + "）")
                    },
                )
                if (index != entries.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    )
                }
            }
        }
    }
}

/** 单条场景：色条 + 标题 + 说明 + 「发送」，整行按压缩放。 */
@Composable
private fun ScenarioRow(
    entry: ReminderTestEntry,
    channel: String,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "rowScale",
    )
    // 通道色：岛用靛蓝，普通通知用中性灰 —— 与顶部状态点同一套语义
    val accent = if (entry.liveContainer) IslandAccent else NeutralAccent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = AccentBarWidth, height = 26.dp)
                .clip(ContinuousRoundedRectangle(2.dp))
                .background(accent.copy(alpha = 0.85f))
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.detail,
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "发送",
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = if (entry.liveContainer) accent else MiuixTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 空状态：桌面/iOS 没有 Android 的通知通道，给一句说明而不是白屏 */
@Composable
private fun ReminderTestEmptyHint(backdrop: GlassBackdrop?) {
    GlassCard(backdrop) {
        Text(
            text = "当前平台没有可用的测试场景：这些场景测的是 Android 的通知通道 / 超级岛 / 实时动态。",
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@Composable
private fun ReminderTestFootnote() {
    Text(
        text = "说明：\n" +
            "• 课程场景的「到点自动切换」靠刷新链对账，最多滞后一个刷新周期，属正常；\n" +
            "• 课中卡到下课点会自动收岛，那是时序走到了，不是掉线；\n" +
            "• 「课前提醒」的普通通知没有单独入口：它和岛 / 实时动态共用\n" +
            "  sendPreClassNotification 的同一条时间线，由上面的课程场景覆盖。",
        style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
