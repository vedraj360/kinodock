package com.vdx.kinodock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Schedules the Android TV's built-in sleep action without keeping the launcher process alive. */
final class SleepTimer {
    private static final String PREFS = "your_tv_sleep_timer";
    private static final String END_AT = "end_at";
    private static final int REQUEST_CODE = 9173;
    private static final String SLEEP_ACTION = "com.google.android.pano.action.SLEEP";

    private SleepTimer() { }

    static void schedule(Context context, long durationMs) {
        long endAt = System.currentTimeMillis() + durationMs;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) throw new IllegalStateException("Alarm service unavailable");
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(context));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(END_AT, endAt).apply();
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent action = pendingIntent(context);
        if (alarms != null) alarms.cancel(action);
        action.cancel();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(END_AT).apply();
    }

    static void restore(Context context) {
        long endAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(END_AT, 0L);
        if (endAt <= System.currentTimeMillis()) {
            cancel(context);
            return;
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(context));
    }

    static long remainingMs(Context context) {
        long endAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(END_AT, 0L);
        long remaining = endAt - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    static String remainingLabel(Context context) {
        long minutes = (remainingMs(context) + 59_999L) / 60_000L;
        if (minutes <= 0) return "Off";
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours == 0 ? minutes + " min remaining"
                : remainder == 0 ? hours + " hr remaining"
                : hours + " hr " + remainder + " min remaining";
    }

    static String remainingShortLabel(Context context) {
        long minutes = (remainingMs(context) + 59_999L) / 60_000L;
        if (minutes <= 0) return "";
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours == 0 ? minutes + "m" : remainder == 0 ? hours + "h" : hours + "h " + remainder + "m";
    }

    static String sleepAction() { return SLEEP_ACTION; }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(SLEEP_ACTION)
                .setPackage("com.android.tv.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags);
    }
}
