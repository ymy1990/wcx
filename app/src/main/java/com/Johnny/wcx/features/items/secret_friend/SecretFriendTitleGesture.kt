package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.tencent.mm.ui.LauncherUI
import java.lang.ref.WeakReference
import kotlin.math.abs

/** One touch listener coordinates title taps and long presses, and is recovered after UI rebuilds. */
object SecretFriendTitleGesture {
    private val main = Handler(Looper.getMainLooper())
    private var activeActivity = WeakReference<Activity>(null)
    private var legacyEnabled: (() -> Boolean)? = null
    private var legacyTrigger: ((Context) -> Unit)? = null
    private var secretTaps = 0
    private var legacyTaps = 0
    private var secretLastTap = 0L
    private var legacyLastTap = 0L
    private var longTask: Runnable? = null

    fun registerLegacy(enabled: () -> Boolean, trigger: (Context) -> Unit) {
        legacyEnabled = enabled
        legacyTrigger = trigger
        refresh()
    }

    fun unregisterLegacy() { legacyEnabled = null; legacyTrigger = null; refresh() }

    fun refresh() {
        main.post {
            if (hasSources()) {
                TitleGestureRecovery.bootstrap()
                val current = activeActivity.get() ?: runCatching { LauncherUI.getInstance() }.getOrNull()
                if (current != null) watch(current)
            } else {
                cancelLongPress()
                secretTaps = 0
                legacyTaps = 0
                TitleGestureRecovery.stop()
            }
        }
    }

    private fun secretClickEnabled() = MultiClickTitleUnlock.isEnabled && !SecretFriendState.isEmpty()
    private fun secretLongEnabled() = LongPressTitleUnlock.isEnabled && !SecretFriendState.isEmpty()
    private fun hasSources() = secretClickEnabled() || secretLongEnabled() || legacyEnabled?.invoke() == true

    fun watch(activity: Activity) {
        activeActivity = WeakReference(activity)
        TitleGestureRecovery.watch(activity)
        activity.window?.decorView?.post { attach(activity) }
    }

    private fun toggle(context: Context) {
        if (SecretFriendState.isTemporarilyShown()) SecretFriendState.tempOff(context)
        else SecretFriendState.tempShowForMinutes(context)
    }

    private fun cancelLongPress() { longTask?.let(main::removeCallbacks); longTask = null }

    fun attach(activity: Activity) {
        if (!hasSources()) return
        activeActivity = WeakReference(activity)
        val root = activity.window?.decorView ?: return
        val title = SecretFriendState.findHomeTitleTextView(root) ?: return
        if (TitleGestureRecovery.healthy(title)) return
        cancelLongPress()
        title.isClickable = true
        title.isLongClickable = true
        val slop = ViewConfiguration.get(title.context).scaledTouchSlop
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var cancelled = false
        var longFired = false
        TitleGestureRecovery.track(title) { view, event ->
            if (!hasSources()) return@track false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelLongPress()
                    downAt = SystemClock.uptimeMillis()
                    downX = event.x; downY = event.y
                    cancelled = false; longFired = false
                    if (secretLongEnabled()) {
                        val task = Runnable {
                            if (!cancelled && secretLongEnabled()) {
                                longFired = true; secretTaps = 0; legacyTaps = 0
                                toggle(view.context)
                            }
                        }
                        longTask = task
                        main.postDelayed(task, LongPressTitleUnlock.longPressMs.coerceIn(300, 5000).toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        cancelled = true; cancelLongPress(); secretTaps = 0; legacyTaps = 0
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    val now = SystemClock.uptimeMillis()
                    if (!cancelled && !longFired && now - downAt < 750) {
                        if (secretClickEnabled()) {
                            secretTaps = if (now - secretLastTap > MultiClickTitleUnlock.clickWindowMs.coerceIn(200, 5000)) 1 else secretTaps + 1
                            secretLastTap = now
                            if (secretTaps >= MultiClickTitleUnlock.clickCount.coerceIn(2, 10)) {
                                secretTaps = 0; toggle(view.context)
                            }
                        }
                        if (legacyEnabled?.invoke() == true) {
                            legacyTaps = if (now - legacyLastTap > 500) 1 else legacyTaps + 1
                            legacyLastTap = now
                            if (legacyTaps >= 3) { legacyTaps = 0; legacyTrigger?.invoke(view.context) }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { cancelled = true; cancelLongPress(); secretTaps = 0; legacyTaps = 0 }
            }
            true
        }
    }
}
