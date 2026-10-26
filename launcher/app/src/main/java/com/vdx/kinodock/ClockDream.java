package com.vdx.kinodock;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private static final long DEFAULT_SLEEP_AFTER_DREAM_MS = 30L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishForSleep = this::finish;
    private ClockScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.useHomeWallpaper(this);
        scene.start();
        scheduleSleep();
    }

    /** OnePlus firmware can leave a Dream visible after its normal idle sleep timeout. End it at that timeout. */
    private void scheduleSleep() {
        long screenOff = Settings.System.getLong(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                5L * 60L * 1000L);
        long sleep = Settings.Secure.getLong(getContentResolver(), "sleep_timeout", -1L);
        long remaining = sleep > 0 ? Math.max(60_000L, sleep - screenOff) : DEFAULT_SLEEP_AFTER_DREAM_MS;
        handler.removeCallbacks(finishForSleep);
        handler.postDelayed(finishForSleep, remaining);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(finishForSleep);
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(finishForSleep);
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
