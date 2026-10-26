package com.vdx.kinodock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores an active sleep timer after the TV restarts. */
public final class SleepTimerBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) SleepTimer.restore(context);
    }
}
