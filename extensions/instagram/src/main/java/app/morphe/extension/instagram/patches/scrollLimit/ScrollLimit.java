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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Daily foreground budget. Once the configured number of minutes is spent, the existing
 * "Disable Reels scrolling" preference is switched on and held on until midnight, then restored
 * if this timer was the one that switched it on.
 */
@SuppressWarnings("unused")
public final class ScrollLimit {

    private static final String PREFS_NAME = "piko_scroll_limit";
    private static final String KEY_DATE = "usage_date";
    private static final String KEY_USED_MS = "used_ms";
    /** Date the budget ran out, so enforcement survives a restart and ends at midnight. */
    private static final String KEY_ENFORCED_DATE = "enforced_date";
    /** Whether this timer, rather than the user, switched the Reels preference on. */
    private static final String KEY_SELF_ENABLED = "self_enabled";

    private static final long TICK_MS = 15_000L;

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

        restoreIfNewDay();
        applyLimit();
    }

    /**
     * Enforcement is recorded against a date rather than recomputed from the setting, so raising
     * the limit (or resetting and importing preferences) cannot buy more time today.
     */
    private static synchronized void applyLimit() {
        SharedPreferences sp = prefs(null);
        if (sp == null) return;

        boolean enforced = usageDate.equals(sp.getString(KEY_ENFORCED_DATE, ""));
        if (!enforced) {
            int limit = Pref.dailyScrollLimitMinutes();
            if (limit <= 0 || usedMs < limit * 60_000L) return;

            boolean alreadyOn = SharedPref.getBooleanPref(Settings.DISABLE_REELS_SCROLLING);
            sp.edit()
                    .putString(KEY_ENFORCED_DATE, usageDate)
                    .putBoolean(KEY_SELF_ENABLED, !alreadyOn)
                    .apply();
            Utils.showToastShort(str("piko_daily_scroll_limit_reached"));
        }

        // Re-asserted on every tick, so flipping the switch off elsewhere does not stick.
        if (!SharedPref.getBooleanPref(Settings.DISABLE_REELS_SCROLLING)) {
            SharedPref.setBooleanPref(Settings.DISABLE_REELS_SCROLLING.key, true);
        }
    }

    /** Hands the preference back at midnight, but only if this timer switched it on. */
    private static synchronized void restoreIfNewDay() {
        SharedPreferences sp = prefs(null);
        if (sp == null) return;

        String enforcedDate = sp.getString(KEY_ENFORCED_DATE, "");
        if (enforcedDate.isEmpty() || enforcedDate.equals(usageDate)) return;

        if (sp.getBoolean(KEY_SELF_ENABLED, false)) {
            SharedPref.setBooleanPref(Settings.DISABLE_REELS_SCROLLING.key, false);
        }
        sp.edit().remove(KEY_ENFORCED_DATE).remove(KEY_SELF_ENABLED).apply();
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

    /** True while today's budget is spent and the Reels preference is being held on. */
    public static boolean isEnforcedToday() {
        try {
            SharedPreferences sp = prefs(null);
            return sp != null && today().equals(sp.getString(KEY_ENFORCED_DATE, ""));
        } catch (Exception ex) {
            return false;
        }
    }

    /** Rejects switching Reels scrolling back on before midnight. */
    public static boolean canEnableReelsScrolling() {
        return !isEnforcedToday();
    }

    /** Summary for the preference row, for both the settings screen and the edit dialog. */
    public static String settingsSummary(int limitMinutes) {
        if (limitMinutes <= 0) return str("piko_daily_scroll_limit_desc");
        String summary = str("piko_daily_scroll_limit_usage", usedMinutesToday(), limitMinutes);
        if (isEnforcedToday()) {
            summary = summary + "\n" + str("piko_daily_scroll_limit_locked");
        }
        return summary;
    }

    public static String settingsSummary() {
        return settingsSummary(Pref.dailyScrollLimitMinutes());
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
}
