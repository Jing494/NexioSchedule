/** 通知 / 超级岛 / 实时动态测试页 - Screen（场景表在宿主侧，本页只展示与派发） */
package com.haooz.chedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.data.platform.showToast
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.CollapsibleTopAppBarDefaults
import top.yukonga.miuix.kmp.layout.SharedScrollBehavior
import top.yukonga.miuix.kmp.layout.collapsibleTopInset
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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

/**
 * 「通知 / 超级岛 / 实时动态」测试页。
 *
 * 独立成页（不再塞在提醒设置页里）：场景表与分发都在宿主的 `ReminderTestScenario`，
 * 本页只负责列表、展示与把点击交回去 —— 发送一律走主路径那几支函数，不在这里拼通知文案。
 *
 * 通道（超级岛 / 原生实时动态）由主路径同一套判定决定，页面上直接显示出来，
 * 免得「点了没反应却不知道发去哪了」。
 *
 * @param channelLabel 当前实际生效的通道文案（宿主判定，与主路径一致）
 * @param groups 场景分组；空列表 = 该平台没有可测场景（桌面/iOS）
 * @param onRefreshChannel 重新读取通道，返回新的文案
 * @param onSend 点一条场景：宿主按 [ReminderTestEntry.id] 调主路径发送函数
 */
@Composable
fun ReminderTestScreen(
    channelLabel: String,
    groups: List<ReminderTestGroup>,
    onRefreshChannel: () -> String,
    onSend: (ReminderTestEntry) -> Unit,
    scrollBehavior: SharedScrollBehavior? = null,
) {
    // 通道是设置项的派生值：进页面读一次，另给一个「重新读取」入口
    var channel by remember(channelLabel) { mutableStateOf(channelLabel) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = {}) { paddingValues ->
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
                    top = paddingValues.calculateTopPadding() +
                        CollapsibleTopAppBarDefaults.CollapsedHeight + 24.dp,
                    end = 16.dp,
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (groups.isEmpty()) {
                    // 该平台没有可测场景（桌面/iOS）：只给一句说明，别渲染一个空通道卡片
                    item(key = "empty") { ReminderTestEmptyHint() }
                } else {
                    item(key = "channel") {
                        ReminderTestChannelCard(
                            channel = channel,
                            onRefresh = { channel = onRefreshChannel() },
                        )
                    }
                    groups.forEach { group ->
                        item(key = "title_${group.title}") { ReminderTestSectionTitle(group.title) }
                        item(key = "card_${group.title}") {
                            ReminderTestScenarioCard(
                                entries = group.entries,
                                channel = channel,
                                onSend = onSend,
                            )
                        }
                    }
                    item(key = "footnote") { ReminderTestFootnote() }
                }
            }
        }
    }
}

@Composable
private fun ReminderTestChannelCard(channel: String, onRefresh: () -> Unit) {
    Card(
        cornerRadius = 20.dp,
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "当前通道",
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = channel,
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Text(
                text = "课程链路与返校提醒都按这个通道发：开了超级岛走岛，关掉走原生实时动态。" +
                    "清单 / 余额 / 次日提醒是普通通知，不走岛。",
                style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            TextButton(
                "重新读取通道",
                onRefresh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ReminderTestSectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun ReminderTestScenarioCard(
    entries: List<ReminderTestEntry>,
    channel: String,
    onSend: (ReminderTestEntry) -> Unit,
) {
    Card(
        cornerRadius = 20.dp,
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            entries.forEach { entry ->
                ArrowPreference(
                    title = entry.label,
                    summary = entry.detail,
                    onClick = {
                        onSend(entry)
                        val where = if (entry.liveContainer) channel else "普通通知"
                        showToast("已发送：${entry.label}（$where）")
                    },
                )
            }
        }
    }
}

/** 空状态：桌面/iOS 没有 Android 的通知通道，给一句说明而不是白屏 */
@Composable
private fun ReminderTestEmptyHint() {
    Card(
        cornerRadius = 20.dp,
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        Text(
            text = "当前平台没有可用的测试场景：这些场景测的是 Android 的通知通道 / 超级岛 / 实时动态。",
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(16.dp),
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
