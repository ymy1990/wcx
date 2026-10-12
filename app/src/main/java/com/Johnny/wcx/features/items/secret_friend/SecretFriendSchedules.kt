package com.Johnny.wcx.features.items.secret_friend

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.secret_friend.advanced.installSchedules
import com.Johnny.wcx.features.items.secret_friend.advanced.uninstallSchedules
import com.Johnny.wcx.features.items.secret_friend.advanced.showSchedulesDialog
import com.Johnny.wcx.ui.content.TextButton
import androidx.compose.material3.Text

@Feature(name = "定时显示/隐藏", categories = ["密友功能"],
    description = "按单次日期时间或每周指定时间显示、隐藏密友；支持分别启用各条规则")
object HideSchedules : SwitchFeature() {
    val hiddenContacts: Set<String> get() = SecretFriendState.getStoredWxIds()
    override fun onEnable() = installSchedules()
    override fun onDisable() = uninstallSchedules()
    fun setTemporarilyShown(shown: Boolean) {
        if (!isEnabled || !SecretFriendState.isSecretFriendMasterOn()) return
        if (shown) SecretFriendState.tempShowUntil(Long.MAX_VALUE)
        else SecretFriendState.tempOff()
    }
    fun openConfig(context: Context) = showSchedulesDialog(context)
    @Composable override fun Ui() {
        val context = LocalContext.current
        TextButton(onClick = { openConfig(context) }) { Text("配置定时显示/隐藏") }
    }
}
