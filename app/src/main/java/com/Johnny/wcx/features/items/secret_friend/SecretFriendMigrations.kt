package com.Johnny.wcx.features.items.secret_friend

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs

/** Preserve WCXLC's existing keys and migrate the former combined restoration switch once. */
object SecretFriendMigrations {
    fun migrate() {
        if (!WePrefs.containsKey(SecretFriendState.KEY_MASK_LIST)) {
            val previousContacts = WePrefs.getStringSetOrDef("hidden_contacts", emptySet())
            if (previousContacts.isNotEmpty()) SecretFriendState.setWxIds(previousContacts)
        }
        if (!WePrefs.containsKey("隐藏联系人")) WePrefs.putBool("隐藏联系人", true)
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
        // Both existing master preferences must remain effective during an upgrade.
        Preferences.secretFriendMasterEnabled = Preferences.secretFriendMasterEnabled &&
            WePrefs.getBoolOrDef("密友名单管理", true)
    }
}
