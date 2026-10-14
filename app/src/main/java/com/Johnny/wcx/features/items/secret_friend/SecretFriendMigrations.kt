package com.Johnny.wcx.features.items.secret_friend

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs

/** Preserve WCXLC's existing keys and migrate the former combined restoration switch once. */
object SecretFriendMigrations {
    fun migrate() {
        // The list editor is no longer a second master switch. Preserve the old effective
        // off state once, then let the category master be the sole source of truth.
        val unifiedMaster = "secret_friend_master_unified_v1"
        if (!WePrefs.getBoolOrDef(unifiedMaster, false)) {
            if (WePrefs.containsKey("密友名单管理") &&
                !WePrefs.getBoolOrDef("密友名单管理", true)) {
                Preferences.secretFriendMasterEnabled = false
            }
            WePrefs.putBool(unifiedMaster, true)
        }
        if (!WePrefs.containsKey("多击标题解除") &&
            WePrefs.getBoolOrDef("隐藏联系人", false) &&
            WePrefs.getBoolOrDef("hide_triple_click_title", false)) {
            WePrefs.putBool("多击标题解除", true)
        }
        if (!WePrefs.containsKey(SecretFriendState.KEY_MASK_LIST)) {
            val previousContacts = WePrefs.getStringSetOrDef("hidden_contacts", emptySet())
            if (previousContacts.isNotEmpty()) SecretFriendState.setWxIds(previousContacts)
        }
        val previous = WePrefs.getBoolOrDef("离开对话/离开微信隐藏", false)
        for (key in listOf("离开对话恢复隐藏", "离开主页/切走微信恢复隐藏")) {
            if (!WePrefs.containsKey(key)) WePrefs.putBool(key, previous)
        }
        if (!WePrefs.containsKey("自动拒绝音视频通话")) {
            WePrefs.putBool("自动拒绝音视频通话", WePrefs.getBoolOrDef("hide_auto_reject", false))
        }
        if (!WePrefs.containsKey("secret_friend_schedules")) {
            val legacySchedules = WePrefs.getString("hide_contacts_schedules")
            if (!legacySchedules.isNullOrBlank()) WePrefs.putString("secret_friend_schedules", legacySchedules)
        }
        if (!WePrefs.containsKey("密友名单管理")) {
            WePrefs.putBool("密友名单管理", Preferences.secretFriendMasterEnabled)
        }
        // Settings rows read the stored toggle value. Persist the new default once so their
        // initial state matches startup, while preserving an explicitly disabled setting.
        for (key in listOf(
            "密友显示状态提示", "主页会话隐藏", "通讯录隐藏", "标签内隐藏",
            "禁止进入聊天", "禁止查看资料", "朋友圈隐藏", "密友消息通知隐藏",
            "主页搜索隐藏", "隐藏我的朋友圈", "隐藏最近转发", "状态页隐藏",
            "存储空间聊天记录隐藏", "存储空间缓存隐藏",
        )) {
            if (!WePrefs.containsKey(key)) WePrefs.putBool(key, true)
        }
    }
}
