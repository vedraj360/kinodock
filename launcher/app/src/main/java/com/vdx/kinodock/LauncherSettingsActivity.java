package com.vdx.kinodock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.vdx.kinodock.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView quoteChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        quoteChoice = findViewById(R.id.quoteChoice);
        quoteChoice.setOnClickListener(v -> changeQuoteNow());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY)
                    .remove(UnsplashWallpaperApi.ENABLED_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean unsplashCurrent = UnsplashWallpaperApi.enabled(this);
        boolean localAutoRotate = choices.getBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false);
        UnsplashWallpaperApi.Photo unsplashPhoto = UnsplashWallpaperApi.current(this);
        Dialog dialog = new Dialog(this);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(26), dp(22), dp(26), dp(20));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xFF11161D);
        panelBg.setCornerRadius(dp(24));
        panelBg.setStroke(dp(1), 0x2EFFFFFF);
        panel.setBackground(panelBg);

        TextView title = new TextView(this);
        title.setText(R.string.wallpaper_label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(27);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText(unsplashCurrent ? getString(R.string.wallpaper_unsplash_active)
                : localAutoRotate ? "Local wallpapers rotate every 30 minutes"
                : current == null ? getString(R.string.wallpaper_theme_default) : displayWallpaperName(current));
        subtitle.setTextColor(0x99FFFFFF);
        subtitle.setTextSize(14);
        subtitle.setTypeface(UiFont.get(this, R.string.font_regular));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        subtitleParams.bottomMargin = dp(16);
        panel.addView(subtitle, subtitleParams);

        TextView localRotationToggle = new TextView(this);
        localRotationToggle.setText(localAutoRotate
                ? "Local auto-rotate · ON · every 30 minutes"
                : "Local auto-rotate · OFF · tap to enable");
        localRotationToggle.setTextColor(localAutoRotate ? 0xFFB9E7C7 : Color.WHITE);
        localRotationToggle.setTextSize(15);
        localRotationToggle.setTypeface(UiFont.get(this, R.string.font_medium));
        localRotationToggle.setGravity(Gravity.CENTER_VERTICAL);
        localRotationToggle.setPadding(dp(16), 0, dp(16), 0);
        localRotationToggle.setBackgroundResource(R.drawable.settings_choice_bg);
        localRotationToggle.setFocusable(true);
        localRotationToggle.setClickable(true);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, dp(52));
        toggleParams.bottomMargin = dp(12);
        panel.addView(localRotationToggle, toggleParams);
        localRotationToggle.setOnClickListener(v -> {
            boolean enable = !choices.getBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false);
            android.content.SharedPreferences.Editor editor = choices.edit()
                    .putBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, enable);
            if (enable) {
                editor.remove(Config.WALLPAPER_CHOICE_KEY)
                        .putBoolean(UnsplashWallpaperApi.ENABLED_KEY, false);
            }
            editor.apply();
            changed();
            refresh();
            dialog.dismiss();
            showWallpaperPicker();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout choicesGrid = new LinearLayout(this);
        choicesGrid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choicesGrid, new ScrollView.LayoutParams(-1, -2));
        ExecutorService previews = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "wallpaper-previews");
            thread.setDaemon(true);
            return thread;
        });

        View firstChoice = null;
        LinearLayout optionRow = null;
        int unsplashOffset = unsplashPhoto == null ? 0 : 1;
        for (int i = 0; i <= images.size() + unsplashOffset; i++) {
            if (i % 2 == 0) {
                optionRow = new LinearLayout(this);
                optionRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rowParams.topMargin = dp(10);
                choicesGrid.addView(optionRow, rowParams);
            }
            boolean photoTile = unsplashPhoto != null && i == 0;
            int imageIndex = i - unsplashOffset;
            final File file = imageIndex >= 0 && imageIndex < images.size() ? images.get(imageIndex) : null;
            boolean selected = photoTile ? unsplashCurrent : file == null ? "none".equals(current)
                    : !unsplashCurrent && file.getName().equals(current);
            View option = photoTile ? wallpaperOptionUnsplash(unsplashPhoto, selected, dialog, previews)
                    : wallpaperOption(file, selected, dialog, previews);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0, dp(100), 1f);
            if (i % 2 == 1) optionParams.setMarginStart(dp(10));
            optionRow.addView(option, optionParams);
            if (selected || (firstChoice == null && i == 0)) firstChoice = option;
        }
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        panel.addView(scroll, scrollParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(56));
        actionsParams.topMargin = dp(16);
        panel.addView(actions, actionsParams);
        addWallpaperAction(actions, getString(R.string.wallpaper_theme_default), false, () -> {
            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                    .putBoolean(UnsplashWallpaperApi.ENABLED_KEY, false).apply();
            changed();
            refresh();
            dialog.dismiss();
        });
        addWallpaperAction(actions, getString(android.R.string.cancel), false, dialog::dismiss);
        addWallpaperAction(actions, "Update from Unsplash", true, () -> {
            if (!UnsplashWallpaperApi.configured()) {
                Toast.makeText(this, R.string.wallpaper_api_not_configured, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.wallpaper_updating, Toast.LENGTH_SHORT).show();
            UnsplashWallpaperApi.updateNow(this, updated -> {
                if (updated) {
                    changed();
                    refresh();
                    Toast.makeText(this, R.string.wallpaper_updated_unsplash, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    showWallpaperPicker();
                } else {
                    Toast.makeText(this, R.string.wallpaper_update_failed, Toast.LENGTH_LONG).show();
                }
            });
        });

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.62f;
            window.setAttributes(params);
        }
        dialog.setOnDismissListener(ignored -> previews.shutdownNow());
        dialog.show();
        if (window != null) {
            int maxWidth = dp(1220);
            int maxHeight = dp(820);
            window.setLayout(Math.min(maxWidth, Math.round(getResources().getDisplayMetrics().widthPixels * 0.92f)),
                    Math.min(maxHeight, Math.round(getResources().getDisplayMetrics().heightPixels * 0.90f)));
            window.setGravity(Gravity.CENTER);
        }
        View initialFocus = firstChoice;
        if (initialFocus != null) initialFocus.post(() -> {
            if (dialog.isShowing()) initialFocus.requestFocus();
        });
    }

    private View wallpaperOption(File file, boolean selected, Dialog dialog, ExecutorService previews) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(10), dp(8), dp(12), dp(8));
        card.setBackgroundResource(R.drawable.settings_choice_bg);
        card.setSelected(selected);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((view, focused) -> {
            view.setScaleX(focused ? 1.025f : 1f);
            view.setScaleY(focused ? 1.025f : 1f);
        });

        if (file != null) {
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackgroundColor(0xFF202833);
            preview.setTag(file.getAbsolutePath());
            card.addView(preview, new LinearLayout.LayoutParams(dp(138), dp(78)));
            String path = file.getAbsolutePath();
            previews.execute(() -> {
                Bitmap bitmap = decodeWallpaperPreview(file);
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || !path.equals(preview.getTag())) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        return;
                    }
                    if (bitmap != null) preview.setImageBitmap(bitmap);
                });
            });
        } else {
            FrameLayout nonePreview = new FrameLayout(this);
            GradientDrawable previewBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF293340, 0xFF151A21});
            previewBg.setCornerRadius(dp(12));
            nonePreview.setBackground(previewBg);
            TextView mark = new TextView(this);
            mark.setText("×");
            mark.setTextColor(0xBBFFFFFF);
            mark.setTextSize(32);
            mark.setGravity(Gravity.CENTER);
            nonePreview.addView(mark, new FrameLayout.LayoutParams(-1, -1));
            card.addView(nonePreview, new LinearLayout.LayoutParams(dp(138), dp(78)));
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(file == null ? getString(R.string.wallpaper_none) : displayWallpaperName(file.getName()));
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(UiFont.get(this, R.string.font_medium));
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (selected) {
            TextView current = new TextView(this);
            current.setText("CURRENT");
            current.setTextColor(0xFFB9E7C7);
            current.setTextSize(10);
            current.setLetterSpacing(0.1f);
            current.setTypeface(UiFont.get(this, R.string.font_medium));
            LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(-1, -2);
            currentParams.topMargin = dp(3);
            labels.addView(current, currentParams);
        }
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        card.setOnClickListener(v -> {
            if (file == null) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
            else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, file.getName()).apply();
            choices.edit().putBoolean(UnsplashWallpaperApi.ENABLED_KEY, false)
                    .putBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false).apply();
            changed();
            refresh();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        return card;
    }

    private View wallpaperOptionUnsplash(UnsplashWallpaperApi.Photo photo, boolean selected,
                                         Dialog dialog, ExecutorService previews) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(10), dp(8), dp(12), dp(8));
        card.setBackgroundResource(R.drawable.settings_choice_bg);
        card.setSelected(selected);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((view, focused) -> {
            view.setScaleX(focused ? 1.025f : 1f);
            view.setScaleY(focused ? 1.025f : 1f);
        });
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFF202833);
        preview.setTag(photo.imageUrl);
        card.addView(preview, new LinearLayout.LayoutParams(dp(138), dp(78)));
        previews.execute(() -> {
            Bitmap bitmap = decodeRemoteWallpaperPreview(photo.imageUrl);
            runOnUiThread(() -> {
                if (!dialog.isShowing() || !photo.imageUrl.equals(preview.getTag())) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                if (bitmap != null) preview.setImageBitmap(bitmap);
            });
        });
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText("Unsplash wallpaper");
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(UiFont.get(this, R.string.font_medium));
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = new TextView(this);
        detail.setText(selected ? "CURRENT · " + photo.credit() : photo.credit());
        detail.setTextColor(0x99FFFFFF);
        detail.setTextSize(11);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        labels.addView(detail, detailParams);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        card.setOnClickListener(v -> {
            choices.edit().putBoolean(UnsplashWallpaperApi.ENABLED_KEY, true)
                    .putBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false).apply();
            changed();
            refresh();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        return card;
    }

    private Bitmap decodeRemoteWallpaperPreview(String imageUrl) {
        java.net.HttpURLConnection connection = null;
        try {
            String previewUrl = imageUrl + (imageUrl.contains("?") ? "&" : "?") + "w=420&q=65";
            connection = (java.net.HttpURLConnection) new java.net.URL(previewUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            return BitmapFactory.decodeStream(connection.getInputStream());
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void addWallpaperAction(LinearLayout actions, String label, boolean emphasized, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(emphasized ? 0xFFB9E7C7 : Color.WHITE);
        button.setTextSize(13);
        button.setTypeface(UiFont.get(this, R.string.font_medium));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundResource(R.drawable.settings_choice_bg);
        button.setOnFocusChangeListener((view, focused) -> {
            view.setScaleX(focused ? 1.03f : 1f);
            view.setScaleY(focused ? 1.03f : 1f);
        });
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMarginEnd(dp(8));
        actions.addView(button, params);
    }

    private Bitmap decodeWallpaperPreview(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > 420 || options.outHeight / sample > 240) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException ignored) {
            return null;
        }
    }

    private String displayWallpaperName(String filename) {
        String name = filename.replaceFirst("(?i)\\.(png|jpe?g|webp)$", "").replace('_', ' ').replace('-', ' ');
        StringBuilder pretty = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void changeQuoteNow() {
        if (!quoteChoice.isEnabled()) return;
        quoteChoice.setEnabled(false);
        quoteChoice.setText(R.string.quote_updating);
        Thread worker = new Thread(() -> {
            DailyQuoteApi.Quote quote = null;
            List<DailyQuoteApi.Quote> quotes = null;
            int selectedIndex = 0;
            try {
                quotes = DailyQuoteApi.fetchBatch();
                if (quotes != null && !quotes.isEmpty()) {
                    selectedIndex = (int) (System.nanoTime() & 0x7fffffff) % quotes.size();
                    quote = quotes.get(selectedIndex);
                }
            } catch (Exception ignored) { }
            if (quote != null && quotes != null) {
                getSharedPreferences(DailyQuoteApi.PREFS, MODE_PRIVATE).edit()
                        .putLong("fetched_at", System.currentTimeMillis())
                        .putInt("quote_index", selectedIndex)
                        .putString("quotes", DailyQuoteApi.serialize(quotes))
                        .putString("line", quote.line)
                        .putString("credit", quote.credit)
                        .apply();
            }
            DailyQuoteApi.Quote result = quote;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                quoteChoice.setEnabled(true);
                quoteChoice.setText(R.string.change_quote_now);
                Toast.makeText(this, result == null ? R.string.quote_update_failed : R.string.quote_updated,
                        result == null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            });
        }, "manual-quote-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                choices.getBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false)
                        ? "Local auto-rotate · 30 min"
                        : UnsplashWallpaperApi.enabled(this) ? getString(R.string.wallpaper_unsplash_active)
                        : "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY)
                || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(UnsplashWallpaperApi.ENABLED_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
