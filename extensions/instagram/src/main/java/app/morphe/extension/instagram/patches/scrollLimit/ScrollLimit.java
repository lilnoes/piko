/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.scrollLimit;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.Window;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.settings.SettingsActivity;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Daily foreground budget. Once the configured number of minutes is used up, every drag gesture
 * is cancelled for the rest of the day while taps keep working, so the app stays usable for
 * opening DMs, stories and profiles but cannot be scrolled.
 */
@SuppressWarnings("unused")
public final class ScrollLimit {

    private static final String PREFS_NAME = "piko_scroll_limit";
    private static final String KEY_DATE = "usage_date";
    private static final String KEY_USED_MS = "used_ms";
    private static final String KEY_LOCKED_DATE = "locked_date";
    private static final String KEY_LOCKED_LIMIT = "locked_limit_minutes";

    private static final long TICK_MS = 15_000L;
    private static final long TOAST_INTERVAL_MS = 10_000L;

    /**
     * Dev switch: treat today's budget as already spent so gestures are blocked from the first
     * frame, without persisting a lock that would outlive this build. Set back to false before
     * releasing anything for normal use.
     */
    private static final boolean FORCE_LIMIT_REACHED = true;

    private static SharedPreferences prefs;
    private static Handler handler;
    private static boolean installed;

    /** Local date the counters below belong to. */
    private static String usageDate = "";
    /** Foreground time already written to disk for {@link #usageDate}. */
    private static long usedMs;
    /** {@link SystemClock#elapsedRealtime()} of the last resume, or 0 when backgrounded. */
    private static long resumedAt;
    private static int resumedActivities;

    /** Read on every touch event, so it stays a plain field read. */
    private static volatile boolean blocked;
    private static long lastToastAt;

    /** Windows whose callback is already gated. Touched only from the main thread. */
    private static final WeakHashMap<Window, Boolean> GUARDED = new WeakHashMap<>();

    private ScrollLimit() {}

    /**
     * Injected into {@code IgFragmentActivity.onCreate}. Only the first call does anything; the
     * process-wide lifecycle callbacks registered here cover every later activity.
     */
    public static void install(Activity activity) {
        try {
            synchronized (ScrollLimit.class) {
                if (installed) return;
                installed = true;
            }
            if (prefs(activity) == null) return;

            handler = new Handler(Looper.getMainLooper());
            refreshUsage();

            Application application = activity.getApplication();
            if (application == null) return;
            application.registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to install scroll limit", ex);
        }
    }

    // region usage accounting

    private static synchronized SharedPreferences prefs(Context context) {
        if (prefs == null) {
            Context ctx = context != null ? context.getApplicationContext() : Utils.getContext();
            if (ctx == null) return null;
            prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Folds any elapsed foreground time into the daily total, rolling over at midnight. */
    private static synchronized void refreshUsage() {
        SharedPreferences sp = prefs(null);
        if (sp == null) return;

        if (usageDate.isEmpty()) {
            usageDate = sp.getString(KEY_DATE, "");
            usedMs = sp.getLong(KEY_USED_MS, 0L);
        }

        long now = SystemClock.elapsedRealtime();
        long elapsed = resumedAt == 0 ? 0 : Math.max(0, now - resumedAt);
        if (resumedAt != 0) resumedAt = now;

        String today = today();
        if (!today.equals(usageDate)) {
            usageDate = today;
            usedMs = elapsed;
        } else {
            usedMs += elapsed;
        }

        sp.edit()
                .putString(KEY_DATE, usageDate)
                .putLong(KEY_USED_MS, usedMs)
                .apply();

        applyLimit();
    }

    private static synchronized void applyLimit() {
        int limit = enforcedLimitMinutes();
        if (limit <= 0) {
            blocked = false;
            return;
        }
        if (FORCE_LIMIT_REACHED) {
            blocked = true;
            return;
        }
        boolean over = usedMs >= limit * 60_000L;
        if (over && !blocked) {
            lockToday(limit);
        }
        blocked = over;
    }

    private static void lockToday(int limitMinutes) {
        SharedPreferences sp = prefs(null);
        if (sp == null) return;
        if (usageDate.equals(sp.getString(KEY_LOCKED_DATE, ""))) return;
        sp.edit()
                .putString(KEY_LOCKED_DATE, usageDate)
                .putInt(KEY_LOCKED_LIMIT, limitMinutes)
                .apply();
    }

    /**
     * The limit that enforcement uses. Once the budget is spent the locked snapshot takes over, so
     * raising the setting (or resetting and re-importing preferences) cannot buy more time today.
     * Lowering it still takes effect, because that only ever blocks earlier.
     */
    private static int enforcedLimitMinutes() {
        int configured = Pref.dailyScrollLimitMinutes();
        int locked = lockedLimitMinutes();
        if (locked <= 0) return configured;
        return configured > 0 ? Math.min(locked, configured) : locked;
    }

    /** Configured limit at the moment today's budget ran out, or 0 if it has not. */
    private static int lockedLimitMinutes() {
        SharedPreferences sp = prefs(null);
        if (sp == null) return 0;
        if (!today().equals(sp.getString(KEY_LOCKED_DATE, ""))) return 0;
        return sp.getInt(KEY_LOCKED_LIMIT, 0);
    }

    // endregion

    // region settings screen helpers

    public static int usedMinutesToday() {
        try {
            refreshUsage();
            synchronized (ScrollLimit.class) {
                return (int) (usedMs / 60_000L);
            }
        } catch (Exception ex) {
            return 0;
        }
    }

    /** Summary for the preference row, for both the settings screen and the edit dialog. */
    public static String settingsSummary(int limitMinutes) {
        if (limitMinutes <= 0) return str("piko_daily_scroll_limit_desc");
        String summary = str("piko_daily_scroll_limit_usage", usedMinutesToday(), limitMinutes);
        if (isLockedToday()) {
            summary = summary + "\n" + str("piko_daily_scroll_limit_locked");
        }
        return summary;
    }

    public static String settingsSummary() {
        return settingsSummary(Pref.dailyScrollLimitMinutes());
    }

    public static boolean isLockedToday() {
        try {
            return lockedLimitMinutes() > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Rejects raising or disabling the limit once today's budget is spent. */
    public static boolean canChangeLimit(String newValue) {
        try {
            int locked = lockedLimitMinutes();
            if (locked <= 0) return true;
            int minutes = Integer.parseInt(newValue.trim());
            return minutes > 0 && minutes <= locked;
        } catch (NumberFormatException ex) {
            return false;
        } catch (Exception ex) {
            return true;
        }
    }

    // endregion

    // region lifecycle

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            refreshUsage();
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityResumed(Activity activity) {
            try {
                synchronized (ScrollLimit.class) {
                    if (resumedActivities++ == 0 && resumedAt == 0) {
                        resumedAt = SystemClock.elapsedRealtime();
                    }
                }
                refreshUsage();
                guard(activity);
                if (handler != null) {
                    handler.removeCallbacks(TICK);
                    handler.postDelayed(TICK, TICK_MS);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Scroll limit resume failed", ex);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            try {
                boolean background;
                synchronized (ScrollLimit.class) {
                    background = --resumedActivities <= 0;
                    if (background) resumedActivities = 0;
                }
                refreshUsage();
                if (background) {
                    synchronized (ScrollLimit.class) {
                        resumedAt = 0;
                    }
                    if (handler != null) handler.removeCallbacks(TICK);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Scroll limit pause failed", ex);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }

    // endregion

    // region touch gating

    /**
     * Wraps the activity window's callback so drag gestures can be cancelled without knowing
     * anything about Instagram's obfuscated scrolling views.
     */
    private static void guard(Activity activity) {
        if (activity instanceof SettingsActivity) return;

        Window window = activity.getWindow();
        if (window == null) return;

        Window.Callback delegate = window.getCallback();
        if (delegate == null) return;
        // AppCompat may wrap our proxy afterwards, which hides it from the check below, so the
        // window itself is tracked as well. Without that, every resume would nest another gate.
        if (GUARDED.containsKey(window)) return;
        if (Proxy.isProxyClass(delegate.getClass())
                && Proxy.getInvocationHandler(delegate) instanceof TouchGate) {
            return;
        }

        Window.Callback gate = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class},
                new TouchGate(delegate, ViewConfiguration.get(activity).getScaledTouchSlop())
        );
        window.setCallback(gate);
        GUARDED.put(window, Boolean.TRUE);
    }

    private static void notifyLimit() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastToastAt < TOAST_INTERVAL_MS) return;
        lastToastAt = now;
        Utils.showToastShort(str("piko_daily_scroll_limit_reached"));
    }

    private static final class TouchGate implements InvocationHandler {

        private final Window.Callback delegate;
        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean swallow;

        private TouchGate(Window.Callback delegate, int touchSlop) {
            this.delegate = delegate;
            this.touchSlop = touchSlop;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null
                    && args.length == 1
                    && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                return gate((MotionEvent) args[0]);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        }

        private boolean gate(MotionEvent event) {
            if (!blocked) {
                swallow = false;
                return delegate.dispatchTouchEvent(event);
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swallow = false;
                    downX = event.getX();
                    downY = event.getY();
                    return delegate.dispatchTouchEvent(event);

                case MotionEvent.ACTION_MOVE:
                    if (!swallow
                            && (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop)) {
                        startSwallowing(event);
                    }
                    return swallow || delegate.dispatchTouchEvent(event);

                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger only ever starts a pinch or a two-finger drag.
                    if (!swallow) startSwallowing(event);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swallow) {
                        swallow = false;
                        return true;
                    }
                    return delegate.dispatchTouchEvent(event);

                default:
                    return swallow || delegate.dispatchTouchEvent(event);
            }
        }

        /**
         * Tells the view tree the gesture is over before dropping the rest of it, otherwise the
         * touched row keeps its pressed state and the next tap is ignored.
         */
        private void startSwallowing(MotionEvent event) {
            swallow = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                delegate.dispatchTouchEvent(cancel);
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to cancel gesture", ex);
            } finally {
                cancel.recycle();
            }
            notifyLimit();
        }
    }

    // endregion
}
