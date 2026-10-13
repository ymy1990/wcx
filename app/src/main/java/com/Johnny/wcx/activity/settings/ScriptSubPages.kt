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

// ---------------------------------------------------------------------------
// 脚本自检 / 一键修复
// ---------------------------------------------------------------------------

private data class ScriptSelfCheckItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val fixable: Boolean,
)

/** 逐项检查脚本运行环境，返回每一项结果（ok=false 即问题项）。 */
private fun runScriptSelfCheck(context: Context): List<ScriptSelfCheckItem> {
    val items = mutableListOf<ScriptSelfCheckItem>()

    val scriptDir = runCatching { ScriptPluginRuntime.scriptDir(context) }.getOrNull()
    val dirOk = scriptDir != null && scriptDir.isDirectory
    items += ScriptSelfCheckItem(
        "脚本目录",
        dirOk,
        scriptDir?.absolutePath ?: "无法获取脚本目录",
        fixable = !dirOk,
    )

    val masterOn = runCatching {
        HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
    }.getOrDefault(false)
    items += ScriptSelfCheckItem(
        "插件总开关",
        masterOn,
        if (masterOn) "已开启" else "未开启：插件不会被加载",
        fixable = !masterOn,
    )

    val database = runCatching { WeChatApis.database() }.getOrNull()
    items += ScriptSelfCheckItem(
        "微信数据库 API",
        database != null,
        if (database != null) "已解析" else "未解析：DexKit 预热未完成或当前微信版本未适配",
        fixable = database == null,
    )

    val readiness = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }.getOrNull()
    items += ScriptSelfCheckItem(
        "脚本运行时就绪",
        readiness == null,
        readiness ?: "已就绪",
        fixable = readiness != null,
    )

    val dexInfo = runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("")
    val dexOk = dexInfo.isNotBlank() && !dexInfo.contains("未初始化")
    items += ScriptSelfCheckItem(
        "DexKit 解析",
        dexOk,
        dexInfo.ifBlank { "无信息" },
        fixable = !dexOk,
    )

    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    val enabledCount = plugins.count {
        runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
    }
    val noEnabled = plugins.isNotEmpty() && enabledCount == 0
    items += ScriptSelfCheckItem(
        "本地插件",
        !noEnabled,
        when {
            plugins.isEmpty() -> "尚未安装任何插件"
            noEnabled -> "已安装 " + plugins.size + " 个，但一个都没有启用"
            else -> "已安装 " + plugins.size + " 个，已启用 " + enabledCount + " 个"
        },
        fixable = false,
    )

    val server = runCatching { PluginMarketSettings.serviceUrl(context) }.getOrNull().orEmpty()
    items += ScriptSelfCheckItem(
        "市场服务器地址",
        server.isNotBlank(),
        server.ifBlank { "为空：在线插件市场不可用" },
        fixable = false,
    )

    return items
}

/** 一键修复可自动处理的问题，返回逐条执行结果。 */
private fun runScriptSelfRepair(context: Context): List<String> {
    val log = mutableListOf<String>()

    runCatching {
        val dir = ScriptPluginRuntime.scriptDir(context)
        if (!dir.isDirectory) {
            val created = dir.mkdirs() || dir.isDirectory
            log += if (created) {
                "已创建脚本目录：" + dir.absolutePath
            } else {
                "创建脚本目录失败：" + dir.absolutePath
            }
        }
    }.onFailure { log += "创建脚本目录失败：" + it.message.orEmpty() }

    val masterOn = runCatching {
        HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
    }.getOrDefault(false)
    if (!masterOn) {
        runCatching { ScriptPluginRuntime.setGlobalEnabled(context, true) }.fold(
            onSuccess = { log += "已开启插件总开关" },
            onFailure = { log += "开启插件总开关失败：" + it.message.orEmpty() },
        )
    }

    val readiness = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }.getOrNull()
    val dexInfo = runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("")
    if (readiness != null || dexInfo.contains("未初始化")) {
        runCatching { HchatScriptBootstrap.retry() }.fold(
            onSuccess = { log += "已重新初始化脚本子系统（引导链路重跑）" },
            onFailure = { log += "重新初始化失败：" + it.message.orEmpty() },
        )
    }

    val plugins = runCatching { ScriptPluginRuntime.listPlugins(context) }.getOrDefault(emptyList())
    val enabled = plugins.filter {
        runCatching { ScriptPluginRuntime.isPluginEnabled(context, it.id) }.getOrDefault(false)
    }
    if (enabled.isNotEmpty()) {
        var reloaded = 0
        for (plugin in enabled) {
            runCatching { ScriptPluginRuntime.reloadPluginAsync(context, plugin.id) }
                .onSuccess { reloaded++ }
        }
        log += "已触发重新加载：" + reloaded + "/" + enabled.size + " 个已启用插件"
    }

    if (log.isEmpty()) log += "没有需要自动修复的项目。"
    return log
}

private fun showScriptInfoDialog(context: Context, title: String, message: String) {
    runCatching {
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}

private fun showScriptFixDialog(
    context: Context,
    title: String,
    message: String,
    onFix: () -> Unit,
) {
    runCatching {
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("一键修复") { _, _ -> onFix() }
            .setNegativeButton("取消", null)
            .show()
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
                    InsetDivider()
                    ActionRow("一键自检", "逐项检查脚本运行环境，列出发现的问题") {
                        val items = runCatching { runScriptSelfCheck(context) }.getOrNull()
                        if (items == null) {
                            showScriptInfoDialog(context, "脚本自检", "自检执行失败，请重试。")
                        } else {
                            val bad = items.filter { !it.ok }
                            if (bad.isEmpty()) {
                                showScriptInfoDialog(
                                    context,
                                    "脚本自检",
                                    "全部通过（共检查 " + items.size + " 项）。",
                                )
                            } else {
                                val fixable = bad.count { it.fixable }
                                val message = StringBuilder()
                                message.append("发现 ").append(bad.size).append(" 个问题：\n\n")
                                bad.forEach { item ->
                                    message.append("· ").append(item.name).append("：").append(item.detail)
                                    if (item.fixable) {
                                        message.append("（可自动修复）")
                                    } else {
                                        message.append("（需人工处理）")
                                    }
                                    message.append("\n")
                                }
                                if (fixable > 0) {
                                    showScriptFixDialog(
                                        context,
                                        "脚本自检",
                                        message.toString() + "\n是否立即修复可自动处理的 " + fixable + " 项？",
                                    ) {
                                        val log = runCatching { runScriptSelfRepair(context) }
                                            .getOrDefault(emptyList())
                                        refreshVersion++
                                        showScriptInfoDialog(context, "修复结果", log.joinToString("\n"))
                                    }
                                } else {
                                    showScriptInfoDialog(context, "脚本自检", message.toString())
                                }
                            }
                        }
                    }
                    InsetDivider()
                    ActionRow("一键修复", "补建目录 / 开启总开关 / 重跑引导链路 / 重新加载已启用插件") {
                        val log = runCatching { runScriptSelfRepair(context) }.getOrDefault(emptyList())
                        refreshVersion++
                        showScriptInfoDialog(context, "修复结果", log.joinToString("\n"))
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
    rows += "数据库取库自检" to runCatching { dbApi?.diagnose() ?: "无（数据库 API 未解析）" }
        .getOrDefault("未知")

    // —— 各插件最近一次加载失败原因（读插件目录 log.txt 尾部）——
    val pluginLoadErrors = runCatching {
        plugins.mapNotNull { plugin ->
            val message = runCatching {
                val logFile = java.io.File(plugin.dir, "log.txt")
                if (!logFile.isFile) {
                    null
                } else {
                    val lines = logFile.readLines().takeLast(120)
                    val index = lines.indexOfLast { it.contains("ERROR") }
                    // log.txt 是追加写的：只有当最后一次「加载失败」晚于最后一次「加载成功」时才算当前错误。
                    val okIndex = lines.indexOfLast { it.contains("插件加载成功") }
                    if (index < 0 || index < okIndex) {
                        null
                    } else {
                        val head = lines[index].substringAfter("ERROR", lines[index]).trim()
                        val detail = lines.getOrNull(index + 1)?.trim().orEmpty()
                        "$head $detail".trim()
                    }
                }
            }.getOrNull()
            message?.takeIf { it.isNotBlank() }?.let { "${plugin.displayName ?: plugin.id}: $it" }
        }
    }.getOrDefault(emptyList())
    rows += "插件加载错误" to if (pluginLoadErrors.isEmpty()) "无" else pluginLoadErrors.joinToString(" ｜ ")
    rows += "DexKit 解析" to runCatching { HchatScriptBootstrap.dexParseInfo() }.getOrDefault("未知")
    rows += "脚本 Hook 注册数" to runCatching { "${HookRegistry.get().size()} 个" }
        .getOrDefault("读取失败")

    // —— 环境 ——
    val wechatVersion = runCatching { WeChatApis.version()?.current()?.displayVersion() }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    rows += "微信版本" to wechatVersion
    rows += "宿主进程" to runCatching { TargetProcesses.currentName }.getOrDefault("未知")
    rows += "模块版本" to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

    // —— 存储与服务器 ——
    rows += "脚本目录" to runCatching { ScriptPluginRuntime.scriptDir(context).absolutePath }
        .getOrDefault("未知")
    rows += "日志目录" to runCatching { KnownPaths.moduleData.resolve("logs").toString() }
        .getOrDefault("未知")
    return rows
}


// ---------------------------------------------------------------------------
// 脚本常见问题（FAQ）
// ---------------------------------------------------------------------------

private data class ScriptFaqEntry(val question: String, val answer: String)

private val SCRIPT_FAQ_SECTIONS: List<Pair<String, List<ScriptFaqEntry>>> = listOf(
    "一、插件总开关打不开" to listOf(
        ScriptFaqEntry(
            "点了没反应，或提示「微信联系人数据库尚未就绪」",
            "先打开「脚本功能诊断」看「未就绪原因」这一行，再对症处理：\n" +
                "· 显示「无（已就绪）」却仍开不了 → 重启微信后再试。\n" +
                "· 「数据库对象就绪」为 ✗ 否，或「DexKit 解析：未初始化」→ 微信刚启动、DexKit 还没解析完，等几秒或重启微信。\n" +
                "· 「微信数据库 API：未解析」→ 多为当前微信版本尚未适配，请把诊断页的「微信版本」一行反馈给开发者。"
        ),
        ScriptFaqEntry(
            "怎么确认已经就绪了？",
            "看「插件总开关：已开启」+「未就绪原因：无（已就绪）」两行同时成立即可。"
        )
    ),
    "二、插件开启后马上被关掉" to listOf(
        ScriptFaqEntry(
            "提示「加载[XX]失败，已自动关闭」",
            "这条 Toast 现在会带出真实原因（异常类型 + 说明）；「脚本功能诊断」里的「插件加载错误」一行也会显示最近一次失败的原因。\n" +
                "常见情况：\n" +
                "· 脚本本身与当前微信版本不兼容 / 脚本内部报错 → 把该行内容反馈给开发者。\n" +
                "· 插件文件不完整或被人为改坏 → 删除后从「在线插件」重新安装一次。"
        )
    ),
    "三、插件开了但没反应" to listOf(
        ScriptFaqEntry(
            "开关是开的，但功能不生效",
            "按顺序确认：\n" +
                "1. 「插件总开关」已开启（总开关关着时单个插件不会加载）。\n" +
                "2. 该插件自己的开关已开启（「本地插件」列表里是打开状态）。\n" +
                "3. 看「脚本功能诊断」的「脚本 Hook 注册数」，正常应大于 0。\n" +
                "部分插件只在特定场景生效（例如收到消息、进入群聊），也可能需要重启微信后生效。"
        )
    ),
    "四、改了脚本 / 新装了插件，要不要重启？" to listOf(
        ScriptFaqEntry(
            "脚本是实时加载、实时刷新的",
            "不需要重启：\n" +
                "· 修改插件目录里的 main.java → 约 0.5 秒后自动重新加载该插件。\n" +
                "· 新增 / 删除插件目录、修改 info.prop → 「本地插件」列表会自动刷新。\n" +
                "前提是该插件已被启用、且总开关为开启状态。"
        )
    ),
    "五、在线插件市场打不开" to listOf(
        ScriptFaqEntry(
            "列表加载不出来 / 一直转圈",
            "到「脚本功能诊断」页的「市场服务器」处切换服务器（官方 / 备用 / 自定义）。\n" +
                "某个服务器暂时不可用时，切换后重试即可；自定义地址可填你自己部署的服务端。"
        )
    ),
    "六、日志在哪里" to listOf(
        ScriptFaqEntry(
            "想自己看日志或反馈给开发者",
            "· 脚本目录：/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件\n" +
                "· 单个插件日志：上面目录下「插件文件夹 / log.txt」（含插件自身输出与加载失败原因）\n" +
                "· 模块日志目录：/storage/emulated/0/Android/data/com.tencent.mm/WCX/logs\n" +
                "· 也可以在「脚本功能诊断」页点「复制诊断信息」，一次性复制当前状态。"
        )
    )
)

/**
 * 脚本常见问题页。
 *
 * 把「开关开不了 / 插件启用失败 / 改动后要不要重启」等高频问题的处理方式集中一处，
 * 出问题时先看这里，再决定是否需要到「脚本功能诊断」取更多信息。
 */
@Composable
fun ScriptFaqScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "脚本常见问题",
        largeTitle = "脚本常见问题",
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
            SCRIPT_FAQ_SECTIONS.forEach { (sectionTitle, entries) ->
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = sectionTitle) }
                item {
                    SettingsCard {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) InsetDivider()
                            FaqItem(entry.question, entry.answer)
                        }
                    }
                }
            }
            item {
                Text(
                    text = "以上仍未解决时，请到「脚本功能诊断」页点「复制诊断信息」，把内容反馈给开发者。",
                    fontSize = 12.sp,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = question,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
        )
        Text(
            text = answer,
            fontSize = 12.sp,
            color = BasicComponentDefaults.summaryColor().color,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
