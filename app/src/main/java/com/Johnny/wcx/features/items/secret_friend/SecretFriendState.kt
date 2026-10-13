package com.Johnny.wcx.features.items.secret_friend

import android.content.Context
import android.content.SharedPreferences
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.utils.HostInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 密友共享核心（MaskWechat「MaskItemBean 五字段 maskList」数据模型 + FloatingClouds 临时解除语义）。
 *
 * 本工程密友功能全部开关共用的唯一数据源：
 * - 名单：WePrefs 键 [KEY_MASK_LIST]，JSON 数组，逐条保留 MaskWechat MaskItemBean 的五字段
 *   `maskId / tagName / tipMode / tipData.mess / mapId`（org.json 手工序列化，避免引入 Gson 依赖）。
 *   旧键 [KEY_LEGACY_WXIDS]（string set）在首次读取时懒迁移，不丢数据；
 * - 临时解除：[KEY_TEMP_UNTIL] 时间戳（毫秒）。所有隐藏类 hook 在过滤前先查
 *   [isTemporarilyShown]，处于临时显示态时整段放行；
 * - 名单变动通知：[addOnListChanged]，名单保存后广播给各开关（如免打扰自动补齐）。
 *
 * 注意：本 object 不是 Feature，不注册任何 hook；hook 全部在各开关 object 内声明。
 */
object SecretFriendState {

    private const val TAG = "SecretFriendState"

    /** MaskWechat MaskItemBean 五字段 JSON 数组的 WePrefs 键。 */
    const val KEY_MASK_LIST = "maskList"

    /** 旧版名单键（string set）；首次读取名单时懒迁移到 [KEY_MASK_LIST]。 */
    const val KEY_LEGACY_WXIDS = "secret_friend_wxids"
    private const val KEY_LEGACY_MIGRATED = "secret_friend_mask_migrated"

    /** 临时解除截止时间戳（epoch 毫秒）；> 当前时间即处于临时显示态。 */
    const val KEY_TEMP_UNTIL = "secret_friend_temp_until"

    /** MaskWechat Constrant 的 tipMode 常量。 */
    const val TIP_MODE_SILENT = 0
    const val TIP_MODE_ALERT = 1

    /** MaskWechat 默认伪装映射 id（微信支付商家助手）。 */
    const val DEFAULT_MAP_ID = "gh_e087bb5b95e6"

    /** 临时解除默认时长（分钟）。 */
    const val DEFAULT_TEMP_SHOW_MINUTES = 30

    /** 临时解除时长（分钟），可在「临时解除指令」下方调整。 */
    var tempShowMinutes by prefOption("secret_friend_temp_minutes", DEFAULT_TEMP_SHOW_MINUTES)

    /**
     * MaskWechat MaskItemBean 的 Kotlin 对应（五字段全保留）。
     * [tipMess] 即 MaskItemBean.TipData.mess；[mapId] 为伪装映射 id。
     */
    data class MaskItem(
        val maskId: String,
        val tagName: String = "",
        val tipMode: Int = TIP_MODE_SILENT,
        val tipMess: String = "",
        val mapId: String = DEFAULT_MAP_ID,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("maskId", maskId)
            .put("tagName", tagName)
            .put("tipMode", tipMode)
            .put("tipData", JSONObject().put("mess", tipMess))
            .put("mapId", mapId)

        companion object {
            fun fromJson(json: JSONObject): MaskItem = MaskItem(
                maskId = json.optString("maskId", ""),
                tagName = json.optString("tagName", ""),
                tipMode = json.optInt("tipMode", TIP_MODE_SILENT),
                tipMess = json.optJSONObject("tipData")?.optString("mess", "").orEmpty(),
                mapId = json.optString("mapId", DEFAULT_MAP_ID),
            )
        }
    }

    // ─────────────────────────── 名单 ───────────────────────────

    /** 当前名单（JSON 反序列化）；首次调用触发旧键懒迁移。 */
    fun getMaskItems(): List<MaskItem> {
        migrateLegacyListIfNeeded()
        return parseMaskList(WePrefs.getStringOrDef(KEY_MASK_LIST, "[]"))
    }

    /** 保存名单并广播变动（主线程入口自行保证；DB 通知内部已 marshal 到主线程）。 */
    fun setMaskItems(items: List<MaskItem>) {
        WePrefs.putString(KEY_MASK_LIST, serializeMaskList(items))
        WeLogger.d(TAG, "mask list saved, ${items.size} item(s)")
        WeConversationApi.reloadConversations()
        notifyListChanged()
    }

    /**
     * 按纯 wxid 集合保存名单：已在名单内的条目保留原五字段（tagName/tipMode 等不丢），
     * 新增的 id 补默认条目，移除的 id 直接去掉。
     */
    fun setWxIds(wxIds: Set<String>) {
        val existing = getMaskItems().associateBy { it.maskId }
        val items = wxIds.map { id ->
            existing[id] ?: MaskItem(maskId = id)
        }
        setMaskItems(items)
    }

    /** 原始存储的密友 wxid 集合（供编辑对话框/添加菜单展示真实名单，不受主控开关影响）。 */
    fun getStoredWxIds(): Set<String> = getMaskItems().mapTo(mutableSetOf()) { it.maskId }

    /** 原始存储判断（供添加/移除菜单——主控关闭时仍可维护名单）。 */
    fun isStoredSecret(wxId: String?): Boolean =
        !wxId.isNullOrEmpty() && getMaskItems().any { it.maskId == wxId }

    /** 名单主控开关是否开启（pref 缺省开启，保持从未操作过开关的旧行为）。 */
    fun isSecretFriendMasterOn(): Boolean =
        Preferences.secretFriendMasterEnabled && WePrefs.getBoolOrDef(MASTER_ENABLED_PREF, true)

    /** 主控开关的 pref 键（与「密友名单管理」功能开关一致）。 */
    private const val MASTER_ENABLED_PREF = "密友名单管理"

    /** 生效密友 wxid 集合：主控关闭时视为空——所有隐藏/拦截功能整体失效。 */
    fun getWxIds(): Set<String> =
        if (isSecretFriendMasterOn()) getStoredWxIds() else emptySet()

    /** 判断某 wxid 是否在生效名单内。null/空一律 false，天然放行。 */
    fun isSecret(wxId: String?): Boolean =
        isSecretFriendMasterOn() && isStoredSecret(wxId)

    /** 名单是否为空（隐藏类开关据此跳过全部逻辑，零开销）。 */
    fun isEmpty(): Boolean = !isSecretFriendMasterOn() || getMaskItems().isEmpty()

    fun findMaskItem(wxId: String?): MaskItem? =
        if (wxId.isNullOrEmpty()) null else getMaskItems().firstOrNull { it.maskId == wxId }

    private fun parseMaskList(jsonText: String): List<MaskItem> = runCatching {
        val array = JSONArray(jsonText)
        (0 until array.length()).mapNotNull { idx ->
            val item = MaskItem.fromJson(array.optJSONObject(idx) ?: return@mapNotNull null)
            if (item.maskId.isNotEmpty()) item else null
        }
    }.getOrElse {
        WeLogger.e(TAG, "failed to parse maskList json", it)
        emptyList()
    }

    private fun serializeMaskList(items: List<MaskItem>): String {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /** 旧键懒迁移：maskList 缺失而旧 string set 非空时，把旧名单转成默认条目。 */
    private fun migrateLegacyListIfNeeded() {
        if (WePrefs.getBoolOrDef(KEY_LEGACY_MIGRATED, false)) return
        if (WePrefs.containsKey(KEY_MASK_LIST)) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }
        val legacy = WePrefs.getStringSetOrDef(KEY_LEGACY_WXIDS, emptySet())
        if (legacy.isEmpty()) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }
        WePrefs.putString(KEY_MASK_LIST, serializeMaskList(legacy.map { MaskItem(maskId = it) }))
        WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
        WeLogger.i(TAG, "migrated ${legacy.size} wxid(s) from legacy key $KEY_LEGACY_WXIDS")
    }

    // ─────────────────────── 名单变动通知 ───────────────────────

    private val listChangedListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 注册名单变动回调（保存名单后触发）。用于免打扰补齐等联动。 */
    fun addOnListChanged(listener: () -> Unit) {
        listChangedListeners += listener
    }

    fun removeOnListChanged(listener: () -> Unit) {
        listChangedListeners -= listener
    }

    private fun notifyListChanged() {
        listChangedListeners.forEach { listener ->
            runCatching { listener() }
                .onFailure { WeLogger.e(TAG, "onListChanged callback failed", it) }
        }
    }

    @JvmStatic
    fun reminderPrefs(): SharedPreferences = WePrefs.default

    fun isEnabledForGesture(): Boolean = getStoredWxIds().isNotEmpty()

    fun clearTemporarilyShown() {
        WePrefs.putLong(KEY_TEMP_UNTIL, 0L)
        SecretMessageUi.stateChanged()
    }

    // ─────────────────────── 临时解除状态 ───────────────────────

    /** 是否处于临时显示态（所有隐藏类 hook 过滤前先查这里）。 */
    fun isTemporarilyShown(): Boolean =
        System.currentTimeMillis() < WePrefs.getLongOrDef(KEY_TEMP_UNTIL, 0L)

    /**
     * 临时解除隐藏（时长取 [tempShowMinutes]，可用参数覆盖）。到期由 [scheduleTempExpiry]
     * 主动恢复；各隐藏钩子按 [isTemporarilyShown] 被动过滤。
     * 触发主页会话列表刷新，使 SQL 过滤立即放行密友行。
     */
    fun tempShowForMinutes(context: Context? = null, minutes: Int = tempShowMinutes.coerceIn(1, 1440)) {
        tempShowUntil(System.currentTimeMillis() + minutes * 60_000L, context)
    }

    /** A schedule's SHOW persists until its next HIDE or a manual restore. */
    fun tempShowUntil(until: Long, context: Context? = null) {
        WePrefs.putLong(KEY_TEMP_UNTIL, until)
        val refresh = Runnable {
            if (WePrefs.getLongOrDef(KEY_TEMP_UNTIL, 0L) != until) return@Runnable
            HideConversations.restoreHiddenRows()
            WeConversationApi.reloadConversations()
            SecretMessageUi.stateChanged()
            showToastIfEnabled(context, toastTempShown)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
        else mainHandler.post(refresh)
        if (until != Long.MAX_VALUE) scheduleTempExpiry(until)
    }

    /**
     * 到期主动恢复隐藏：被动过滤只在下次查询时生效，停留在主页时列表不会自己刷新
     * （「改 1 分钟到期后不恢复」的根因），到期必须主动 tempOff + reload。
     * 仅当 [KEY_TEMP_UNTIL] 未被更新（期间没有再次解除/手动恢复）时动作。
     */
    private fun scheduleTempExpiry(until: Long) {
        val delay = until - System.currentTimeMillis() + 300L
        if (delay <= 0) return
        mainHandler.postDelayed({
            if (WePrefs.getLongOrDef(KEY_TEMP_UNTIL, 0L) == until) {
                WeLogger.i(TAG, "temp-show expired, restoring hidden state")
                tempOff()
            }
        }, delay)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 立即恢复隐藏（锁屏 / 离开对话 / 离开微信 / 指令 / 到期）。 */
    fun tempOff(context: Context? = null) {
        // A non-zero expired deadline is also a transition. Zero still refreshes hiding silently.
        val previous = WePrefs.getLongOrDef(KEY_TEMP_UNTIL, 0L)
        WePrefs.putLong(KEY_TEMP_UNTIL, 0L)
        HideConversations.removeSecretRows()
        WeConversationApi.reloadConversations()
        SecretMessageUi.stateChanged()
        if (previous != 0L) showToastIfEnabled(context, toastTempOff)
    }

    // ─────────────────────── 提示自定义 ───────────────────────

    /** 操作提示总开关（默认关闭，与浮云一致）。 */
    var toastsEnabled by prefOption("secret_friend_toast_enabled", false)
    var toastTempShown by prefOption("secret_friend_toast_temp_shown", "已临时显示密友，稍后自动恢复隐藏")
    var toastTempOff by prefOption("secret_friend_toast_temp_off", "密友已恢复隐藏")
    var toastAdded by prefOption("secret_friend_toast_added", "已加入密友")
    var toastRemoved by prefOption("secret_friend_toast_removed", "已取消密友")
    var toastMomentHidden by prefOption("secret_friend_toast_moment_hidden", "该朋友圈已加入隐藏列表")

    /** 仅当「操作提示」总开关打开时弹 Toast。 */
    fun showToastIfEnabled(context: Context?, message: String) {
        if (!toastsEnabled) return
        QuickSecretToast.show(context ?: HostInfo.application, message)
    }

    // ─────────────────── 添加密友菜单文字（共用） ───────────────────

    /** 会话列表/通讯录长按菜单的「加入密友」文字（两个添加开关共用一条 pref）。 */
    var addMenuText by prefOption("secret_friend_add_menu_text", "加入密友")

    // ─────────────────────── 标题查找（共用） ───────────────────────

    /**
     * 主页标题 TextView 查找（浮云 HideMainUIList 的多策略定位 + 旧版 SecretFriendTempShow 逻辑）：
     * - 先试 HideContacts 已验证的 `android.R.id.text1`；
     * - 否则深度 ≤ [TITLE_SEARCH_MAX_DEPTH] 找**屏幕顶部 25% 区域**内、当前文本恰为
     *   「微信」的 TextView（必须排除底部导航栏同文本的 Tab，否则会挂到屏幕底部——
     *   浮云实测该误挂是多击/长按不生效的根因）。
     * 找不到返回 null（调用方下次时机重试）。
     */
    fun findHomeTitleTextView(root: View): TextView? {
        val screenH = root.resources.displayMetrics.heightPixels
        val topBandMax = (screenH * 0.25f).toInt().coerceAtLeast(120)
        val bounds = android.graphics.Rect()
        var best: TextView? = null
        var bestScore = -1
        fun walk(view: View, depth: Int) {
            if (depth > TITLE_SEARCH_MAX_DEPTH || !view.isShown) return
            if (view is TextView && view.getGlobalVisibleRect(bounds) && bounds.top < topBandMax) {
                val text = view.text?.toString().orEmpty()
                if (view.id == android.R.id.text1 || text == TITLE_TEXT || text == "WeChat" ||
                    (text.startsWith(TITLE_TEXT) && text.length <= TITLE_TEXT.length + 5)) {
                    val score = (if (view.id == android.R.id.text1) 20 else 0) +
                        (if (view.isClickable) 40 else 0)
                    if (score >= bestScore) { best = view; bestScore = score }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    private const val TITLE_SEARCH_MAX_DEPTH = 14
    private const val TITLE_TEXT = "微信"
}
