package com.vdx.kinodock;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    private static final long SCREENSAVER_WALLPAPER_ROTATION_MS = 2 * 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ImageView ambient;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private final List<File> wallpaperFiles = new ArrayList<>();
    private SharedPreferences wallpaperPreferences;
    private SharedPreferences homeWallpaperPreferences;
    private String wallpaperOrder = WallpaperRotation.ORDER_SHUFFLE;
    private long wallpaperRotationMs;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** Bumped by stop(): a decode started before it finds a different number and drops its result. Main thread only. */
    private int wallpaperGeneration;

    private final Runnable wallpaperRotation = new Runnable() {
        @Override public void run() {
            if (wallpaperPreferences == null || wallpaperFiles.size() < 2 || wallpaperRotationMs <= 0) return;
            long changed = wallpaperPreferences.getLong("changed", 0L);
            long now = System.currentTimeMillis();
            if (WallpaperRotation.due(changed, now, wallpaperRotationMs)) {
                String previous = wallpaperPreferences.getString("image", wallpaperFile == null ? "" : wallpaperFile.getName());
                File next = WallpaperRotation.pick(wallpaperFiles, previous, wallpaperOrder, random);
                if (next != null && (wallpaperFile == null || !next.equals(wallpaperFile))) {
                    loadWallpaper(next, true);
                } else {
                    handler.postDelayed(this, wallpaperRotationMs);
                }
            } else {
                handler.postDelayed(this, Math.max(60_000L, changed + wallpaperRotationMs - now));
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (wallpaperFile != null) {
            wallpaperShown = false;
            wallpaperPreferences.edit().putString("image", wallpaperFile.getName())
                    .putLong("changed", System.currentTimeMillis()).apply();
            loadWallpaper(wallpaperFile, false);
        }
        scheduleWallpaperRotation();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put Home's wallpaper (the theme's single picture, or the first of its collection) behind
     * the clock, darkened by clock_wallpaper_dim, in place of the glow. Decoded off the main thread at screen size
     * in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read, the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        wallpaperFiles.clear();
        wallpaperFile = null;
        wallpaperShown = false;
        ambient.setImageDrawable(null);
        ambient.setBackground(glow);
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        wallpaperFiles.addAll(cfg.wallpapers);
        if (cfg.wallpaper != null && !wallpaperFiles.contains(cfg.wallpaper)) wallpaperFiles.add(0, cfg.wallpaper);
        homeWallpaperPreferences = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        wallpaperPreferences = ctx.getSharedPreferences("screensaver_wallpaper_rotation", Context.MODE_PRIVATE);
        wallpaperOrder = cfg.wallpaperOrder;
        wallpaperRotationMs = wallpaperFiles.size() > 1 ? SCREENSAVER_WALLPAPER_ROTATION_MS : 0L;
        String currentName = homeWallpaperPreferences.getString("image", "");
        wallpaperFile = WallpaperRotation.find(wallpaperFiles, currentName);
        if (wallpaperFile == null) wallpaperFile = cfg.wallpaper != null ? cfg.wallpaper
                : wallpaperFiles.isEmpty() ? null : wallpaperFiles.get(0);
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void scheduleWallpaperRotation() {
        handler.removeCallbacks(wallpaperRotation);
        if (wallpaperPreferences == null || wallpaperFiles.size() < 2 || wallpaperRotationMs <= 0) return;
        long changed = wallpaperPreferences.getLong("changed", 0L);
        long delay = changed == 0L ? wallpaperRotationMs
                : Math.max(60_000L, changed + wallpaperRotationMs - System.currentTimeMillis());
        handler.postDelayed(wallpaperRotation, delay);
    }

    private void loadWallpaper(File file, boolean updateRotationState) {
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        if (res == null || file == null) return;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            File loadedFile = file;
            if (bmp == null) {
                for (File fallback : wallpaperFiles) {
                    if (fallback.equals(file)) continue;
                    bmp = decode(fallback, w, h);
                    if (bmp != null) {
                        loadedFile = fallback;
                        break;
                    }
                }
            }
            if (bmp == null) return;
            Bitmap decoded = bmp;
            File displayedFile = loadedFile;
            boolean persist = updateRotationState || !displayedFile.equals(file);
            handler.post(() -> {
                if (generation != wallpaperGeneration) {
                    decoded.recycle();   // stopped meanwhile: this picture was never attached
                    return;
                }
                wallpaperShown = true;
                wallpaperFile = displayedFile;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, decoded), new ColorDrawable(shade)}));
                if (persist && wallpaperPreferences != null) {
                    wallpaperPreferences.edit().putString("image", displayedFile.getName())
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                scheduleWallpaperRotation();
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        if (wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
