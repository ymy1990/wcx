package com.Johnny.wcx.features.items.secret_friend;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.Johnny.wcx.features.api.core.WeDatabaseApi;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local APK repair: keep hidden-message indicators until the matching chat is opened. */
public final class SecretMessageUi {
    private static final String TAG = "WCXSecretUnread";
    private static final String PREF = "secret_friend_visual_pending_v5";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService READER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean READING = new AtomicBoolean();
    // All state below is owned by the main thread; the worker returns only immutable snapshots.
    private static final Set<String> pending = new HashSet<String>();
    private static final Map<String, Long> openedAt = new HashMap<String, Long>();
    private static final WeakHashMap<TextView, OriginalPaint> paints = new WeakHashMap<TextView, OriginalPaint>();
    private static WeakReference<Activity> home = new WeakReference<Activity>(null);
    private static Application application;
    private static boolean loaded;
    private static boolean resumed;
    private static String currentChat;
    private static long queriedAt;
    private static FrameLayout dotRoot;
    private static View dot;
    private static String lastStatus = "";
    private static String lastDatabaseStatus = "";

    private static boolean isHome(Activity a) {
        return a != null && "com.tencent.mm.ui.LauncherUI".equals(a.getClass().getName());
    }
    private static boolean isChat(Activity a) {
        return a != null && "com.tencent.mm.ui.chatting.ChattingUI".equals(a.getClass().getName());
    }
    private static void load() {
        if (loaded) return;
        Set<String> stored = SecretFriendState.reminderPrefs().getStringSet(PREF, Collections.<String>emptySet());
        if (stored != null) pending.addAll(stored);
        loaded = true;
    }
    private static void save() {
        SecretFriendState.reminderPrefs().edit().putStringSet(PREF, new HashSet<String>(pending)).apply();
    }
    private static final Runnable TICK = new Runnable() {
        public void run() {
            if (!resumed) return;
            try { render(); } catch (RuntimeException e) { Log.w(TAG, "indicator refresh failed", e); }
            MAIN.removeCallbacks(this);
            if (resumed) MAIN.postDelayed(this, 1000);
        }
    };
    private static void refresh() {
        MAIN.removeCallbacks(TICK);
        if (resumed) MAIN.post(TICK);
    }
    private static final Application.ActivityLifecycleCallbacks CALLBACKS = new Application.ActivityLifecycleCallbacks() {
        public void onActivityResumed(Activity a) {
            if (isHome(a)) { currentChat = null; watch(a); }
            else {
                resumed = false;
                MAIN.removeCallbacks(TICK);
                clearVisuals();
                currentChat = null;
                if (isChat(a)) acknowledge(a);
            }
        }
        public void onActivityPaused(Activity a) {
            if (home.get() == a) {
                resumed = false;
                MAIN.removeCallbacks(TICK);
                clearVisuals();
            }
            if (isChat(a)) currentChat = null;
        }
        public void onActivityDestroyed(Activity a) {
            if (home.get() == a) {
                resumed = false;
                home.clear();
                MAIN.removeCallbacks(TICK);
                clearVisuals();
                detachDot();
            }
        }
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) {}
        public void onActivityStopped(Activity a) {}
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    };
    public static void bootstrap() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { bootstrap(); } });
            return;
        }
        if (application != null) return;
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app != null) register(app);
        } catch (Exception e) { Log.w(TAG, "registration deferred until home is available"); }
    }
    private static void register(Application app) {
        if (application != null) return;
        app.registerActivityLifecycleCallbacks(CALLBACKS);
        application = app;
        Log.i(TAG, "v5 unread lifecycle registered; clear only the opened chat");
    }
    public static void watch(final Activity a) {
        if (!isHome(a)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { watch(a); } });
            return;
        }
        register(a.getApplication());
        if (home.get() != a) { clearVisuals(); detachDot(); home = new WeakReference<Activity>(a); }
        resumed = true;
        refresh();
    }
    public static void received(final String talker) {
        if (talker == null) return;
        MAIN.post(new Runnable() {
            public void run() {
                load();
                if (talker.equals(currentChat) || !SecretFriendState.INSTANCE.getWxIds().contains(talker)) return;
                openedAt.remove(talker);
                if (pending.add(talker)) {
                    save();
                    Log.i(TAG, "hidden incoming message; pending chats=" + pending.size());
                }
                refresh();
            }
        });
    }
    private static void acknowledge(Activity a) {
        try {
            Intent intent = a.getIntent();
            String talker = intent == null ? null : intent.getStringExtra("Chat_User");
            if (talker == null && intent != null) talker = intent.getStringExtra("Contact_User");
            currentChat = talker;
            if (talker == null || !SecretFriendState.INSTANCE.getWxIds().contains(talker)) return;
            load();
            openedAt.put(talker, SystemClock.uptimeMillis());
            boolean removed = pending.remove(talker);
            if (removed) save();
            Log.i(TAG, "matching secret chat opened; cleared=" + removed + "; remaining=" + pending.size());
        } catch (RuntimeException e) { Log.w(TAG, "chat identity unavailable; pending indicators retained", e); }
    }
    private static void queryUnread(final Set<String> secrets) {
        final long started = SystemClock.uptimeMillis();
        if (started - queriedAt < 3000 || !READING.compareAndSet(false, true)) return;
        queriedAt = started;
        READER.execute(new Runnable() {
            public void run() {
                final Set<String> unread = new HashSet<String>();
                int rows = 0;
                boolean ready = false;
                try {
                    ready = WeDatabaseApi.INSTANCE.isReady();
                    if (ready) for (String id : secrets) {
                        List<Map<String, Object>> result = WeDatabaseApi.INSTANCE.executeQuery(
                                "SELECT unReadCount FROM rconversation WHERE username = ?", new Object[] { id });
                        if (result == null || result.isEmpty()) continue;
                        Object value = result.get(0).get("unReadCount");
                        if (!(value instanceof Number)) continue;
                        rows++;
                        if (((Number) value).longValue() > 0) unread.add(id);
                    }
                } catch (RuntimeException e) { Log.w(TAG, "native unread lookup unavailable; insert tracking retained", e); }
                final String status = "ready=" + ready + "; rows=" + rows + "; unread=" + unread.size();
                MAIN.post(new Runnable() {
                    public void run() {
                        READING.set(false);
                        load();
                        Set<String> active = SecretFriendState.INSTANCE.getWxIds();
                        boolean changed = false;
                        for (String id : unread) {
                            Long opened = openedAt.get(id);
                            // Avoid an in-flight snapshot reintroducing a chat just opened locally.
                            if (active.contains(id) && !id.equals(currentChat)
                                    && (opened == null || started > opened.longValue() + 3500)) changed |= pending.add(id);
                        }
                        if (changed) { save(); Log.i(TAG, "native unread recovered; pending chats=" + pending.size()); }
                        if (!status.equals(lastDatabaseStatus)) {
                            lastDatabaseStatus = status;
                            Log.d(TAG, "native unread snapshot: " + status);
                        }
                        if (changed) refresh();
                    }
                });
            }
        });
    }
    private static final class OriginalPaint {
        final boolean fakeBold;
        final Paint.Style style;
        final float stroke;
        OriginalPaint(TextView v) {
            fakeBold = v.getPaint().isFakeBoldText();
            style = v.getPaint().getStyle();
            stroke = v.getPaint().getStrokeWidth();
        }
        void restore(TextView v) {
            v.getPaint().setFakeBoldText(fakeBold);
            v.getPaint().setStyle(style);
            v.getPaint().setStrokeWidth(stroke);
            v.invalidate();
        }
    }
    private static void clearVisuals() {
        for (Map.Entry<TextView, OriginalPaint> e : paints.entrySet()) if (e.getKey() != null) e.getValue().restore(e.getKey());
        paints.clear();
        if (dot != null) dot.setVisibility(View.INVISIBLE);
    }
    private static void detachDot() {
        if (dot != null && dot.getParent() instanceof ViewGroup) ((ViewGroup) dot.getParent()).removeView(dot);
        dot = null;
        dotRoot = null;
    }
    private static boolean visible(View v, Rect bounds) {
        return v.getVisibility() == View.VISIBLE && v.isShown() && v.getGlobalVisibleRect(bounds)
                && bounds.width() > 0 && bounds.height() > 0;
    }
    private static final class Targets {
        final ArrayList<TextView> labels = new ArrayList<TextView>();
        View search;
        int searchScore;
    }
    private static void collect(View v, Rect root, Targets targets, int depth) {
        if (v == dot || depth > 40 || v.getVisibility() != View.VISIBLE) return;
        Rect bounds = new Rect();
        if (visible(v, bounds)) {
            CharSequence desc = v.getContentDescription();
            String text = v instanceof TextView ? String.valueOf(((TextView) v).getText()) : "";
            if (v instanceof TextView && ("微信".equals(text) || "WeChat".equals(text))
                    && bounds.top > root.top + root.height() * 0.65f) targets.labels.add((TextView) v);
            if (bounds.bottom < root.top + root.height() * 0.3f) {
                int score = 0;
                if ("搜索".contentEquals(desc == null ? "" : desc) || "Search".contentEquals(desc == null ? "" : desc)) score = 3;
                else if ("搜索".equals(text) || "Search".equals(text)) score = 1;
                if (score > 0 && score >= targets.searchScore) { targets.search = v; targets.searchScore = score; }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), root, targets, depth + 1);
        }
    }
    private static boolean showDot(FrameLayout root, View search) {
        if (search == null) { if (dot != null) dot.setVisibility(View.INVISIBLE); return false; }
        if (dotRoot != root) { detachDot(); dotRoot = root; }
        int size = Math.max(1, Math.round(Math.max(4, Math.min(12, RedDotPrompt.INSTANCE.getDotSizeDp()))
                * root.getResources().getDisplayMetrics().density));
        if (dot == null) {
            dot = new View(root.getContext());
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xfffa5151);
            dot.setBackground(background);
            dot.setClickable(false);
            dot.setFocusable(false);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dot.setContentDescription("未读提示");
            root.addView(dot, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        }
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        Rect anchor = new Rect();
        if (!visible(search, anchor)) { dot.setVisibility(View.INVISIBLE); return false; }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dot.getLayoutParams();
        int x = Math.max(0, anchor.right - origin[0] - size - Math.round(7 * root.getResources().getDisplayMetrics().density));
        int y = Math.max(0, anchor.top - origin[1] + Math.round(7 * root.getResources().getDisplayMetrics().density));
        if (params.width != size || params.height != size || params.leftMargin != x || params.topMargin != y) {
            params.width = size; params.height = size; params.leftMargin = x; params.topMargin = y;
            dot.setLayoutParams(params);
        }
        dot.setVisibility(View.VISIBLE);
        dot.bringToFront();
        return true;
    }
    private static void render() {
        Activity a = home.get();
        if (a == null || a.isFinishing() || a.getWindow() == null) return;
        View decor = a.getWindow().getDecorView();
        if (!decor.hasWindowFocus() || !decor.isShown()) { clearVisuals(); return; }
        load();
        boolean boldEnabled = BottomBarBold.INSTANCE.isEnabled();
        boolean dotEnabled = RedDotPrompt.INSTANCE.isEnabled();
        Set<String> secrets = new HashSet<String>(SecretFriendState.INSTANCE.getWxIds());
        // Removing a secret or disabling the master must not leave an orphan indicator.
        if (pending.retainAll(secrets)) save();
        if ((boldEnabled || dotEnabled) && !secrets.isEmpty()) queryUnread(secrets);
        boolean hidden = !SecretFriendState.INSTANCE.isTemporarilyShown();
        boolean active = hidden && !pending.isEmpty();
        Rect bounds = new Rect();
        Targets targets = new Targets();
        if (visible(decor, bounds)) collect(decor, bounds, targets, 0);
        boolean bold = active && boldEnabled;
        Iterator<Map.Entry<TextView, OriginalPaint>> previous = paints.entrySet().iterator();
        while (previous.hasNext()) {
            Map.Entry<TextView, OriginalPaint> e = previous.next();
            if (!bold || !targets.labels.contains(e.getKey())) { if (e.getKey() != null) e.getValue().restore(e.getKey()); previous.remove(); }
        }
        if (bold) for (TextView label : targets.labels) {
            if (!paints.containsKey(label)) paints.put(label, new OriginalPaint(label));
            label.getPaint().setFakeBoldText(true);
            label.getPaint().setStyle(Paint.Style.FILL_AND_STROKE);
            label.getPaint().setStrokeWidth(Math.max(1, Math.min(5, BottomBarBold.INSTANCE.getBoldStrength())) * 0.4f);
            label.invalidate();
        }
        boolean dotted = active && dotEnabled && decor instanceof FrameLayout && showDot((FrameLayout) decor, targets.search);
        if (!dotted && dot != null) dot.setVisibility(View.INVISIBLE);
        String status = "pending=" + pending.size() + "; hidden=" + hidden + "; boldEnabled=" + boldEnabled
                + "; dotEnabled=" + dotEnabled + "; bottomLabels=" + targets.labels.size()
                + "; search=" + (targets.search != null) + "; bold=" + (bold && !targets.labels.isEmpty()) + "; dot=" + dotted;
        if (!status.equals(lastStatus)) { lastStatus = status; Log.i(TAG, status); }
    }
}
