package com.Johnny.wcx.features.items.secret_friend

import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast

/**
 * 密友名单编辑入口；总开关统一使用「密友功能」分类页顶部的开关。
 *
 * 名单数据读写走 [SecretFriendState]（getStoredWxIds/setWxIds 不受主控影响），
 * 各隐藏功能消费 [SecretFriendState.getWxIds]（主控关闭时返回空 → 整体放行）。
 */
@Feature(
    name = "密友名单管理",
    categories = ["密友功能"],
    description = "编辑密友名单；总开关位于密友功能分类页顶部，关闭总开关时名单仍保留"
)
object SecretFriendManager : ClickableFeature() {

    private const val TAG = "SecretFriendManager"

    /** 默认开启：从未操作过该开关的用户，密友功能照常按各自开关工作。 */
    override val defaultEnabled: Boolean = true

    /**
     * 本行不显示开关：密友功能的唯一总开关是「密友功能」分类页顶部的总开关
     * （[com.Johnny.wcx.constants.Preferences.secretFriendMasterEnabled]）。
     * 本行只作为「编辑密友名单」入口，避免出现两个互相矛盾的总开关。
     */
    override val noSwitchWidget: Boolean = true

    override fun onClick(context: ComponentActivity) = openSelector(context)

    /** 打开密友名单选择器；任意 Context 可用（供 #my# 面板等入口复用）。 */
    fun openSelector(context: android.content.Context) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择密友",
                contacts = regularContacts,
                // 显示真实存储名单（即使主控当前关闭也能看到已有勾选）
                initialSelectedWxIds = SecretFriendState.getStoredWxIds(),
                onDismiss = onDismiss,
            ) {
                SecretFriendState.setWxIds(it)
                // 名单变化后对账：恢复被删行（移出名单的密友），仍在名单中的重新隐藏
                HideConversations.reconcileOnListChange()
                showToast("密友名单已更新（${it.size} 人）")
                onDismiss()
            }
        }
    }

    override fun onEnable() {
        // 主控重新开启：主页会话隐藏开着时，把关闭期间恢复显示的密友会话行重新隐藏
        if (HideConversations.isEnabled) {
            HideConversations.removeSecretRows()
        }
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "master enabled")
    }

    override fun onDisable() {
        // 主控关闭：不动名单存储，仅恢复被删除的会话行并放行所有过滤
        // （此时 getWxIds 已因主控关闭返回空，reconcile 内 removeSecretRows 为空操作）
        HideConversations.reconcileOnListChange()
        WeLogger.i(TAG, "master disabled: hiding disabled, rows restored, list kept")
        showToast("密友功能已关闭，密友已恢复显示；名单已保留")
    }
}
