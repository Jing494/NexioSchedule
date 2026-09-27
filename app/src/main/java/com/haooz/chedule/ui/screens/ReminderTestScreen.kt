/** 通知 / 超级岛 / 实时动态 测试页面 - Screen */
package com.haooz.chedule.ui.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haooz.chedule.reminder.ClassDndHelper
import com.haooz.chedule.reminder.CourseReminderHelper
import com.haooz.chedule.reminder.IslandNotificationHelper
import com.haooz.chedule.reminder.ReminderTestKind
import com.haooz.chedule.reminder.ReminderTestScenario
import com.haooz.chedule.shizuku.ShizukuManager
import com.haooz.chedule.ui.basic.CollapsibleTopAppBarDefaults
import com.haooz.chedule.ui.basic.OverlayDropdownMenu
import com.haooz.chedule.ui.basic.SharedScrollBehavior
import com.haooz.chedule.ui.basic.collapsibleTopInset
import com.haooz.chedule.ui.utils.overScrollVertical
import com.haooz.chedule.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * 「通知 / 超级岛 / 实时动态」测试页。
 *
 * 单独拆成一个页面（原来塞在提醒设置页里，越加越长）：
 * 场景表与分发都在 [com.haooz.chedule.reminder.ReminderTestScenario] 里，
 * 本页只负责列表与展示 —— 发送一律走主路径那几支函数，不在这里拼文案。
 *
 * 通道（超级岛 / 原生实时动态）由主路径同一套判定决定，页面上直接显示出来，
 * 免得"点了没反应却不知道发去哪了"。
 */
@Composable
fun ReminderTestScreen(
    scrollBehavior: SharedScrollBehavior? = null,
    liquidGlassBackdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    // 通道是设置项的派生值：进页面读一次，并给一个「重新读取」入口
    var channel by remember { mutableStateOf(ReminderTestScenario.channelLabel(context)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {}
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .scrollEndHaptic(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
                        .collapsibleTopInset(scrollBehavior)
                        .then(
                            scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier
                        ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = paddingValues.calculateTopPadding() +
                            CollapsibleTopAppBarDefaults.CollapsedHeight + 24.dp,
                        end = 16.dp,
                        bottom = 120.dp,
                    ),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ReminderTestChannelCard(
                            channel = channel,
                            onRefresh = { channel = ReminderTestScenario.channelLabel(context) },
                        )
                    }

                    item { ReminderTestSectionTitle("课程链路（走当前通道）") }
                    item { ReminderTestScenarioCard(courseScenarios(), channel) }

                    item { ReminderTestSectionTitle("返校链路") }
                    item { ReminderTestScenarioCard(returnScenarios(), channel) }

                    item { ReminderTestSectionTitle("其他通知") }
                    item { ReminderTestScenarioCard(plainScenarios(), channel) }

                    item { ReminderTestFootnote() }
                }
            }
        }
    }
}

/** 课程链路：课前倒计时 / 已上课 / 课中 / 长课 */
private fun courseScenarios() = ReminderTestScenario.entries.filter {
    it.kind == ReminderTestKind.COURSE
}

/** 返校链路：返校当日 / 次日 + 清单 / 余额（后两条是普通通知） */
private fun returnScenarios() = ReminderTestScenario.entries.filter {
    it.kind == ReminderTestKind.RETURN_TODAY ||
        it.kind == ReminderTestKind.RETURN_TOMORROW ||
        it.kind == ReminderTestKind.PLAIN_PREP ||
        it.kind == ReminderTestKind.PLAIN_BALANCE
}

/** 其他普通通知：次日课程提醒 */
private fun plainScenarios() = ReminderTestScenario.entries.filter {
    it.kind == ReminderTestKind.PLAIN_NEXT_DAY
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
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "当前通道",
                    style = MiuixTheme.textStyles.body1.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    ),
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
        style = MiuixTheme.textStyles.body1.copy(
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun ReminderTestScenarioCard(
    scenarios: List<ReminderTestScenario>,
    channel: String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        cornerRadius = 20.dp,
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            scenarios.forEach { scenario ->
                ArrowPreference(
                    title = scenario.label,
                    summary = scenario.detail,
                    onClick = {
                        ReminderTestScenario.send(context, scenario)
                        val isIsland = scenario.kind == ReminderTestKind.COURSE ||
                            scenario.kind == ReminderTestKind.RETURN_TODAY ||
                            scenario.kind == ReminderTestKind.RETURN_TOMORROW
                        val where = if (isIsland) channel else "普通通知"
                        Toast.makeText(context, "已发送：${scenario.label}（$where）", Toast.LENGTH_SHORT)
                            .show()
                    },
                )
            }
        }
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
