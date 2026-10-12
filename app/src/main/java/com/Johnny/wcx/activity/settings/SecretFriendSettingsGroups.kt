package com.Johnny.wcx.activity.settings

import com.Johnny.wcx.features.core.BaseFeature

/** WCXLC's section names and row order, with the repaired independent controls. */
internal object SecretFriendSettingsGroups {
    private val definitions = linkedMapOf(
        "名单与总控" to listOf("会话列表长按添加", "通讯录长按添加", "密友名单管理", "隐藏联系人"),
        "隐藏：会话与通讯录" to listOf("主页会话隐藏", "标签内隐藏", "禁止查看资料", "禁止进入聊天", "通讯录隐藏", "锁屏隐藏"),
        "隐藏：朋友圈" to listOf("分组图标隐藏", "朋友圈互动隐藏", "朋友圈隐藏", "隐藏我的朋友圈", "已隐藏朋友圈管理"),
        "隐藏：其他界面" to listOf("主页搜索隐藏", "发现页入口隐藏", "发现页朋友圈入口隐藏", "存储空间缓存隐藏", "存储空间聊天记录隐藏", "状态页隐藏", "隐藏最近转发"),
        "显示与恢复" to listOf("临时解除指令", "多击标题解除", "离开对话恢复隐藏", "离开主页/切走微信恢复隐藏", "长按标题解除", "定时显示/隐藏"),
        "提醒与提示" to listOf("圆点提示", "密友消息通知隐藏", "底栏字体加粗", "微信团队提醒", "拦截扫码登录", "操作提示", "密友消息震动", "菜单显示文字", "自动拒绝音视频通话")
    )
    fun group(items: List<BaseFeature>): List<Pair<String, List<BaseFeature>>> {
        val byName = items.associateBy { it.name }
        val result = definitions.map { (title, names) -> title to names.mapNotNull(byName::get) }
            .filter { it.second.isNotEmpty() }
        val known = definitions.values.flatten().toSet()
        val remaining = items.filter { it.name !in known }
        return if (remaining.isEmpty()) result else result + ("其他密友功能" to remaining)
    }
}
