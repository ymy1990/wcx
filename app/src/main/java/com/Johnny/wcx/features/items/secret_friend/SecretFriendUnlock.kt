package com.Johnny.wcx.features.items.secret_friend

import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import androidx.compose.runtime.Composable
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.lang.ref.WeakReference

/**
 * 临时解除与恢复组 24–27：多击标题解除、长按标题解除、锁屏隐藏、离开对话/离开微信隐藏。
 *
 * 解除统一走 [SecretFriendState.tempShowForMinutes]（默认 30 分钟，到期自动恢复）；
 * 恢复统一走 [SecretFriendState.tempOff]。标题定位采用浮云实测方案（见
 * [SecretFriendState.findHomeTitleTextView]）：LauncherUI onResume 后 decorView.post 定位
 * 顶部 25% 区域内文本恰为「微信」的 TextView，直接挂 listener（View 自身接收触摸，
 * 规避澎湃 OS 全面屏手势吞事件；重复 set 是替换语义，不叠加）。
 */
// ─────────────────────────── 24. 多击标题解除 ───────────────────────────

/**
 * 多击标题解除（浮云「多击标题解除」语义）：连续点击主页标题 N 次（可调，默认 3），
 * 每次点击间隔不超过 M 毫秒（可调，默认 1000）→ 临时解除隐藏 30 分钟。
 */
@Feature(
    name = "多击标题解除",
    categories = ["密友功能"],
    description = "连续点击微信主页标题可切换临时显示/恢复隐藏（点击次数与时间窗口可调）"
)
object MultiClickTitleUnlock : SwitchFeature() {
    var clickCount by prefOption("secret_friend_unlock_click_count", 3)
    var clickWindowMs by prefOption("secret_friend_unlock_click_window_ms", 1000)
    override fun onEnable() = SecretFriendTitleGesture.refresh()
    override fun onDisable() = SecretFriendTitleGesture.refresh()
    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "连续点击次数", value = clickCount.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> clickCount = v.coerceIn(2, 10) } },
            dialogTitle = "连续点击次数（2–10）", confirmLabel = "确定", dismissLabel = "取消")
        TextFieldDialogWidget(title = "计次窗口(毫秒)", value = clickWindowMs.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> clickWindowMs = v.coerceIn(200, 5000) } },
            dialogTitle = "计次窗口（毫秒，200–5000）", confirmLabel = "确定", dismissLabel = "取消")
    }
}

// ─────────────────────────── 25. 长按标题解除 ───────────────────────────

/**
 * 长按标题解除（旧版 SecretFriendTempShow 逻辑 / 浮云「长按标题解除」语义）：
 * 长按主页标题 [longPressMs] 毫秒（默认 800）→ 切换临时显示。
 * 用 setOnTouchListener 自计时（系统 OnLongClickListener 写死 ~500ms，无法自定义时长；
 * 且澎湃 OS 的 ACTION_CANCEL 会杀掉 View 内部的长按 postDelayed）。
 */
@Feature(
    name = "长按标题解除",
    categories = ["密友功能"],
    description = "长按微信主页标题切换临时显示/恢复隐藏（触发时长可调，默认 800 毫秒）"
)
object LongPressTitleUnlock : SwitchFeature() {

    private const val TAG = "LongPressTitleUnlock"

    /** 长按触发时长毫秒（默认 800）。 */
    var longPressMs by prefOption("secret_friend_long_press_ms", 800)

    override fun onEnable() = SecretFriendTitleGesture.refresh()
    override fun onDisable() = SecretFriendTitleGesture.refresh()

    @Composable
    override fun Ui() {
        TextFieldDialogWidget(
            title = "长按触发时长(毫秒)",
            value = longPressMs.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(5).toIntOrNull()?.let { longPressMs = it.coerceIn(300, 5000) }
            },
            dialogTitle = "长按触发时长（毫秒，300–5000）",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
    }
}

// ─────────────────────────── 26. 锁屏隐藏 ───────────────────────────

/**
 * 锁屏隐藏（浮云「锁屏隐藏」语义）：锁屏（SCREEN_OFF）广播 → 立即 tempOff()；
 * 若正处于密友对话内，同时关闭该对话窗口（finish ChattingUI）。
 * Receiver 动态注册于 onEnable，随 onDisable 注销。
 */
@Feature(
    name = "锁屏隐藏",
    categories = ["密友功能"],
    description = "锁屏后立即恢复密友隐藏；若正处在密友对话内会自动关闭对话窗口"
)
object AutoRestoreLockScreen : SwitchFeature() {

    private const val TAG = "AutoRestoreLockScreen"

    /** 当前打开的 ChattingUI（onResume 捕获，onPause 清除）。 */
    private var chattingUiRef: WeakReference<ChattingUI>? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            WeLogger.i(TAG, "screen off, restoring hidden state")
            SecretFriendState.tempOff()
            // 密友对话内锁屏 → 关闭对话窗口（返回主页时列表已恢复隐藏）
            chattingUiRef?.get()?.let { activity ->
                val wxId = activity.intent?.getStringExtra("Chat_User")
                if (SecretFriendState.isSecret(wxId)) {
                    WeLogger.i(TAG, "finishing secret chat window on screen off")
                    runCatching { activity.finish() }
                        .onFailure { WeLogger.w(TAG, "finish ChattingUI failed", it) }
                }
            }
        }
    }

    override fun onEnable() {
        runCatching {
            HostInfo.application.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }.onFailure { WeLogger.e(TAG, "register screen-off receiver failed", it) }

        // ChattingUI 不一定声明 onResume/onPause（版本相关），解析失败会整体中断 onEnable
        // 导致广播都不注册——必须逐个容错，声明找不到时回退父类查找
        val resumeMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume"; superclass(true) }
        }.getOrNull()
        if (resumeMethod != null) {
            resumeMethod.hookAfter { chattingUiRef = WeakReference(thisObject as? ChattingUI) }
        } else {
            WeLogger.w(TAG, "ChattingUI.onResume not resolvable; auto-close chat on lock screen disabled")
        }

        val pauseMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause"; superclass(true) }
        }.getOrNull()
        if (pauseMethod != null) {
            pauseMethod.hookAfter { chattingUiRef = null }
        } else {
            WeLogger.w(TAG, "ChattingUI.onPause not resolvable; stale chat window tracking possible")
        }
    }

    override fun onDisable() {
        runCatching { HostInfo.application.unregisterReceiver(screenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "unregisterReceiver failed", it) }
    }
}

// ─────────────────── 27. Independent restoration policies ───────────────────
@Feature(name = "离开对话恢复隐藏", categories = ["密友功能"],
    description = "从任意对话返回微信主页时恢复密友隐藏；进入对话和聊天详情页不触发")
object AutoRestoreOnLeave : SwitchFeature() {
    override val defaultEnabled: Boolean get() { SecretFriendMigrations.migrate(); return false }
    override fun onEnable() { ConversationReturnRecovery.bootstrap(); ConversationReturnRecovery.flagsChanged() }
    override fun onDisable() = ConversationReturnRecovery.flagsChanged()
}

@Feature(name = "离开主页/切走微信恢复隐藏", categories = ["密友功能"],
    description = "离开主页进入其他非聊天页面，或按 HOME／切换应用时恢复密友隐藏；进入对话不触发")
object AutoRestoreOnAppLeave : SwitchFeature() {
    override val defaultEnabled: Boolean get() { SecretFriendMigrations.migrate(); return false }
    override fun onEnable() { ConversationReturnRecovery.bootstrap(); ConversationReturnRecovery.flagsChanged() }
    override fun onDisable() = ConversationReturnRecovery.flagsChanged()
}
