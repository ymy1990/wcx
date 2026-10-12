package com.Johnny.wcx.features.items.secret_friend

import androidx.compose.runtime.Composable
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull

/**
 * 消息与通知组 18–22：密友消息震动、微信团队提醒、底栏字体加粗、圆点提示、拦截扫码登录。
 *
 * 前四个开关都监听 message 表插入（IInsertListener）判密友来消息；UI 类的操作均为
 * 结构式实现（MainTabUI doOnCreate 锚点为工程已验证点，view 查找全部空安全）。
 */
// ─────────────────────────── 18. 密友消息震动 ───────────────────────────

/**
 * 密友消息震动（浮云语义）：隐藏态收到密友消息时震动提示（强度可调，支持测试）。
 * 点击本体发一次测试震动。主进程 DB hook：后台 :push 进程入库时本开关不感知
 * （与隐藏联系人通知链路同限制），微信回到前台主页时会话列表仍保持隐藏。
 */
@Feature(
    name = "密友消息震动",
    categories = ["密友功能"],
    description = "隐藏状态下收到密友消息时震动提示（强度可调）；点击本开关发送一次测试震动"
)
object VibrateOnSecretMsg : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "VibrateOnSecretMsg"

    /** 震动强度 1–5（映射震动时长）。 */
    var vibrateStrength by prefOption("secret_friend_vibrate_strength", 3)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onInsert(table: String, values: android.content.ContentValues) {
        val talker = secretIncomingTalker(table, values) ?: return
        if (SecretFriendState.isTemporarilyShown()) return
        WeLogger.d(TAG, "secret message arrived from $talker, vibrating")
        vibrateNow(vibrateStrength)
    }

    /** 点击本体 = 测试震动。 */
    override fun onClick(context: androidx.activity.ComponentActivity) {
        vibrateNow(vibrateStrength)
        WeLogger.d(TAG, "test vibration sent")
    }

    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "震动强度", value = vibrateStrength.toString(),
            onValueChange = { it.toIntOrNull()?.let { value -> vibrateStrength = value.coerceIn(1, 5) } },
            dialogTitle = "震动强度（1–5）", confirmLabel = "确定", dismissLabel = "取消")
    }

    private fun vibrateNow(strength: Int) {
        runCatching {
            val vibrator = HostInfo.application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ?: return
            if (!vibrator.hasVibrator()) return
            val clamped = strength.coerceIn(1, 5)
            val pattern = longArrayOf(0, 80L * clamped, 100, 80L * clamped)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { WeLogger.e(TAG, "vibrate failed", it) }
    }
}

// ─────────────────────────── 19. 微信团队提醒 ───────────────────────────

/**
 * 微信团队提醒（MaskWechat 提醒语义 / 浮云「微信团队提醒」）：密友来消息时以「微信团队」
 * 名义插入一条 SYSTEM 消息（type 10000），用户点进微信团队会话即可看到"谁发来了消息"。
 */
@Feature(
    name = "微信团队提醒",
    categories = ["密友功能"],
    description = "密友来消息时在「微信团队」会话插入一条提醒（不显示是谁，只提示有密友消息），点击进入微信团队会话查看"
)
object TeamNotify : SwitchFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "TeamNotify"

    private const val TEAM_TALKER = "weixin"
    private const val TYPE_SYSTEM_MSG = 10000

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onInsert(table: String, values: android.content.ContentValues) {
        val talker = secretIncomingTalker(table, values) ?: return
        if (SecretFriendState.isTemporarilyShown()) return

        val displayName = fetchDisplayName(talker)
        val content = if (displayName.isNullOrEmpty()) {
            "你的密友发来一条新消息，请查看"
        } else {
            "你的密友「$displayName」发来一条新消息，请查看"
        }
        com.Johnny.wcx.features.api.core.WeMessageApi.createSimpleMsgInfoAndInsert(
            TYPE_SYSTEM_MSG, TEAM_TALKER, content, System.currentTimeMillis() / 1000
        )
        WeLogger.d(TAG, "team notify inserted for secret message from $talker")
    }

    /** 密友的备注/昵称（查 rcontact 单行，失败返回 null，提示退化为不含名字的文案）。 */
    private fun fetchDisplayName(wxId: String): String? = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT conRemark, nickname FROM rcontact WHERE username = ?",
            arrayOf(wxId)
        ).firstOrNull()?.let { row ->
            (row["conRemark"] as? String)?.takeIf { it.isNotEmpty() }
                ?: (row["nickname"] as? String)?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}

// ─────────────────────────── 20. 底栏字体加粗 ───────────────────────────

/**
 * 底栏字体加粗（浮云语义）：有密友未读时底部导航「微信」文字加粗（强度可调）。
 * 与圆点共用按密友记录的未读状态；定位当前可见底栏，打开对应对话后清除。
 */
@Feature(
    name = "底栏字体加粗",
    categories = ["密友功能"],
    description = "隐藏状态下有密友未读消息时，底栏「微信」加粗提醒；打开对应对话后清除（强度可调）"
)
object BottomBarBold : SwitchFeature() {
    var boldStrength by prefOption("secret_friend_bold_strength", 3)
    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "字体加粗强度", value = boldStrength.toString(),
            onValueChange = { it.toIntOrNull()?.let { value -> boldStrength = value.coerceIn(1, 5) } },
            dialogTitle = "字体加粗强度（1–5）", confirmLabel = "确定", dismissLabel = "取消")
    }

    private val secretMsgListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        val talker = secretIncomingTalker(table, values) ?: return@IInsertListener
        if (!SecretFriendState.isTemporarilyShown()) SecretMessageUi.received(talker)
    }
    override fun onEnable() { SecretMessageUi.bootstrap(); WeDatabaseListenerApi.addListener(secretMsgListener) }
    override fun onDisable() { WeDatabaseListenerApi.removeListener(secretMsgListener) }
}

// ─────────────────────────── 21. 圆点提示 ───────────────────────────

/**
 * 圆点提示（浮云语义）：有密友未读时搜索框旁显示红点（大小可调）。
 * 绑定当前可见搜索入口，页面重建时重新定位；打开对应密友对话后清除。
 */
@Feature(
    name = "圆点提示",
    categories = ["密友功能"],
    description = "隐藏状态下有密友未读消息时，搜索入口旁显示红点；打开对应对话后清除（大小可调）"
)
object RedDotPrompt : SwitchFeature() {
    var dotSizeDp by prefOption("secret_friend_dot_size", 6)
    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "圆点大小(dp)", value = dotSizeDp.toString(),
            onValueChange = { it.toIntOrNull()?.let { value -> dotSizeDp = value.coerceIn(4, 12) } },
            dialogTitle = "圆点大小(dp)（4–12）", confirmLabel = "确定", dismissLabel = "取消")
    }

    private val secretMsgListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        val talker = secretIncomingTalker(table, values) ?: return@IInsertListener
        if (!SecretFriendState.isTemporarilyShown()) SecretMessageUi.received(talker)
    }
    override fun onEnable() { SecretMessageUi.bootstrap(); WeDatabaseListenerApi.addListener(secretMsgListener) }
    override fun onDisable() { WeDatabaseListenerApi.removeListener(secretMsgListener) }
}

// ─────────────────────────── 22. 拦截扫码登录 ───────────────────────────

/**
 * 拦截扫码登录（浮云 blockScanLogin 语义）：拦截「扫码登录确认」页
 * （ExtDeviceWXLoginUI，扫码把当前账号登录到 PC/网页/其他设备的确认弹窗），
 * 页面 onCreate 后立即 finish——防止密友隐藏期间他人借手机扫码确认，把密友消息
 * 同步到别的设备上查看。开关开启即全程拦截（临时显示态同样拦截，与浮云一致）。
 *
 * ExtDeviceWXLoginUI 是微信长期未混淆的稳定类名（webwx 包）；类缺失/方法漂移时
 * onEnable 安全跳过，仅日志提示。
 */
@Feature(
    name = "拦截扫码登录",
    categories = ["密友功能"],
    description = "拦截「扫码登录确认」弹窗：他人扫码无法把当前账号登录到电脑等设备，防止密友消息在别处可见"
)
object BlockScanLogin : SwitchFeature() {

    private const val TAG = "BlockScanLogin"

    /** 扫码登录确认页（微信 webwx 包稳定类名，浮云同款拦截点）。 */
    private const val EXT_DEVICE_LOGIN_UI = "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI"

    override fun onEnable() {
        val loginUi = EXT_DEVICE_LOGIN_UI.toClassOrNull() ?: run {
            WeLogger.w(TAG, "ExtDeviceWXLoginUI not found; scan-login blocking unavailable")
            return
        }
        val onCreate = loginUi.reflekt().firstMethodOrNull {
            name = "onCreate"
            parameters(Bundle::class)
        }
        if (onCreate == null) {
            WeLogger.w(TAG, "ExtDeviceWXLoginUI.onCreate not resolved; scan-login blocking unavailable")
            return
        }
        onCreate.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            WeLogger.i(TAG, "blocked scan login confirm dialog")
            runCatching { activity.finish() }
        }
    }
}
