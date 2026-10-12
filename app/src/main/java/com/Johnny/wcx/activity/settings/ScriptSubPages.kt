package com.Johnny.wcx.activity.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.Johnny.wcx.features.hchat.HchatScriptBootstrap
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.PluginMarketUi
import h.Hchat.ui.miuix.MarketServerSettingCard
import h.Hchat.ui.miuix.ScriptPluginManagerTabPage
import h.Hchat.ui.miuix.SettingsCard
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import com.Johnny.wcx.utils.fs.KnownPaths

/** 在线插件市场（Hchat 原版 UI），整页跳转。 */
@Composable
fun ScriptMarketScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        PluginMarketUi.ScriptPluginMarketPage(context = context, onBack = onBack)
    }
}

/** 本地插件管理，整页跳转。 */
@Composable
fun ScriptManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        ScriptPluginManagerTabPage(context = context, onBack = onBack)
    }
}

/**
 * 脚本功能诊断页。
 *
 * 目标：脚本开关开不起来 / 脚本不工作时，一眼能看出卡在引导链路的哪一步、微信 API 是否解析。
 */
@Composable
fun ScriptDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshVersion by remember { mutableStateOf(0) }
    val rows = remember(refreshVersion) { collectScriptDiagnostics(context) }
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = "脚本功能诊断",
        largeTitle = "脚本功能诊断",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp,
            ),
        ) {
            item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "运行时状态") }
            item {
                SettingsCard {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) InsetDivider()
                        DiagnosticRow(row.first, row.second, row.second.startsWith("✗") || row.second.contains("未"))
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "市场服务器") }
            item { MarketServerSettingCard(context) }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
            item {
                SettingsCard {
                    ActionRow("重新初始化脚本子系统", "重跑引导链路：WechatApiFeature + ScriptPluginFeature") {
                        HchatScriptBootstrap.retry()
                        refreshVersion++
                        showToast("已重新初始化，请稍候再试开关")
                    }
                    InsetDivider()
                    ActionRow("刷新诊断信息", "重新读取当前运行时状态") { refreshVersion++ }
                    InsetDivider()
                    ActionRow("复制诊断信息", "把上面的内容复制到剪贴板，便于反馈") {
                        val text = rows.joinToString("\n") { "${it.first}：${it.second}" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 脚本诊断", text))
                        showToast("诊断信息已复制")
                    }
                }
            }
            item {
                Text(
                    text = "提示：脚本依赖微信公共 API（DexKit 解析数据库/账号/消息等）。" +
                        "若「微信数据库 API」为未解析，通常是 DexKit 预热未完成或当前微信版本未适配。",
                    fontSize = 12.sp,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, warn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = if (warn) androidx.compose.ui.graphics.Color(0xFFD93025)
            else BasicComponentDefaults.summaryColor().color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 采集脚本运行时诊断信息（label -> value）。 */
private fun collectScriptDiagnostics(context: Context): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()

    // —— 总开关与插件 ——
    val enabled = runCatching {
        HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
    }.getOrDefault(false)
    rows += "插件总开关" to if (enabled) "已开启" else "已关闭"

    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    val enabledPlugins = plugins.count {
        runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
    }
    rows += "已安装插件" to "${plugins.size} 个（已启用 $enabledPlugins 个）"

    // —— 微信公共 API（脚本运行的前提）——
    val database = runCatching { WeChatApis.database() }.getOrNull()
    val account = runCatching { WeChatApis.account() }.getOrNull()
    val contacts = runCatching { WeChatApis.contacts() }.getOrNull()
    rows += "微信数据库 API" to if (database != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"
    rows += "微信账号 API" to if (account != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"
    rows += "微信联系人 API" to if (contacts != null) "已解析" else "✗ 未解析（DexKit 未完成或未适配）"

    // —— 引导链路 ——
    val bootstrapStatus = runCatching { HchatScriptBootstrap.status }.getOrDefault("未知")
    rows += "引导链路" to bootstrapStatus
    // 与「插件总开关」使用完全相同的就绪判定，避免诊断页与开关结论不一致。
    val realReadiness = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }.getOrNull()
    rows += "未就绪原因" to (realReadiness ?: "无（已就绪）")

    // —— 数据库对象深入状态（开关真正的卡点）——
    val dbApi = database as? WeChatDatabaseApi
    rows += "数据库API可用" to runCatching {
        if (dbApi?.isAvailable() == true) "是（已定位 CoreStorage/SqliteDB 类）"
        else "✗ 否（未解析出 CoreStorage 或 SqliteDB 类）"
    }.getOrDefault("未知")
    rows += "数据库对象就绪" to runCatching {
        if (dbApi?.isReady() == true) "是（已取得 SqliteDB 对象）"
        else "✗ 否（CoreStorage 里找不到 SqliteDB 对象）"
    }.getOrDefault("未知")
    rows += "DexKit 解析" to runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("未知")
    rows += "脚本 Hook 注册数" to runCatching { "${HookRegistry.get().size()} 个" }
        .getOrDefault("读取失败")

    // —— 环境 ——
    val wechatVersion = runCatching { WeChatApis.version()?.current()?.displayVersion() }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    rows += "微信版本" to wechatVersion
    rows += "宿主进程" to runCatching { TargetProcesses.currentName }.getOrDefault("未知")
    rows += "模块版本" to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
    val lspApi = runCatching { WePrefs.getIntOrDef(Preferences.CACHED_LSP_API_VERSION, 0) }.getOrDefault(0)
    rows += "LSPosed API" to if (lspApi > 0) "$lspApi" else "未知（尚未缓存）"

    // —— 存储与服务器 ——
    rows += "脚本目录" to runCatching { ScriptPluginRuntime.scriptDir(context).absolutePath }
        .getOrDefault("未知")
    rows += "日志目录" to runCatching { KnownPaths.moduleData.resolve("logs").toString() }
        .getOrDefault("未知")
    return rows
}
