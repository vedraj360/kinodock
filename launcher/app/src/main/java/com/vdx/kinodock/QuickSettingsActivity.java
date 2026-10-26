package com.vdx.kinodock;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    private TextView sleepTimerStatus;
    private Dialog sleepDialog;
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_screen_saver, 0,
                SystemSettings.intent(this, Settings.ACTION_DREAM_SETTINGS));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addSleepTimerRow(rows);
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    private void addSleepTimerRow(LinearLayout rows) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_sleep_timer);
        ((TextView) row.findViewById(R.id.title)).setText("Sleep timer");
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        sleepTimerStatus = row.findViewById(R.id.status);
        sleepTimerStatus.setText(SleepTimer.remainingLabel(this));
        row.setOnClickListener(v -> showSleepTimerChoices());
        rows.addView(row);
    }

    private void showSleepTimerChoices() {
        // This selection replaces the quick menu with the timer choices; don't leave the menu sitting behind it.
        findViewById(R.id.panel).setVisibility(View.GONE);
        String activeLabel = SleepTimer.remainingLabel(this);
        boolean active = SleepTimer.remainingMs(this) > 0;
        Dialog dialog = new Dialog(this);
        sleepDialog = dialog;
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(22), dp(26), dp(22));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xFF15171B);
        panel.setCornerRadius(dp(22));
        panel.setStroke(dp(1), 0x2AFFFFFF);
        content.setBackground(panel);

        TextView title = new TextView(this);
        title.setText("Sleep timer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText(active
                ? "Currently " + activeLabel + ". Choose a new duration or cancel the timer."
                : "Choose when the TV starts its built-in screen saver/sleep action.");
        subtitle.setTextColor(0xBFFFFFFF);
        subtitle.setTextSize(14);
        subtitle.setTypeface(UiFont.get(this, R.string.font_regular));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(6);
        subtitleParams.bottomMargin = dp(14);
        content.addView(subtitle, subtitleParams);

        if (active) {
            addTimerChoice(content, "Cancel active timer", activeLabel, true, () -> {
                SleepTimer.cancel(this);
                if (sleepTimerStatus != null) sleepTimerStatus.setText(SleepTimer.remainingLabel(this));
                Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                finish();
            });
        }
        String[] labels = {"30 minutes", "45 minutes", "1 hour", "90 minutes", "2 hours"};
        long[] minutes = {30, 45, 60, 90, 120};
        for (int i = 0; i < labels.length; i++) {
            final long selectedMinutes = minutes[i];
            addTimerChoice(content, labels[i], "Start timer", false, () -> {
                try {
                    SleepTimer.schedule(this, selectedMinutes * 60_000L);
                    if (sleepTimerStatus != null) sleepTimerStatus.setText(SleepTimer.remainingLabel(this));
                    Toast.makeText(this, "Sleep timer set for " + labelsFor(selectedMinutes), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    finish();
                } catch (RuntimeException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
        }

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
        dialog.setOnDismissListener(ignored -> {
            if (sleepDialog == dialog) sleepDialog = null;
            finish();
        });
        dialog.show();
        if (window != null) {
            window.setLayout(dp(480), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        View firstChoice = content.getChildAt(2);
        if (firstChoice != null) firstChoice.requestFocus();
    }

    private void addTimerChoice(LinearLayout content, String title, String detail, boolean cancel, Runnable action) {
        TextView choice = new TextView(this);
        choice.setText(title + "     " + detail);
        choice.setTextColor(cancel ? 0xFFFFA49C : Color.WHITE);
        choice.setTextSize(16);
        choice.setTypeface(UiFont.get(this, R.string.font_medium));
        choice.setGravity(Gravity.CENTER_VERTICAL);
        choice.setPadding(dp(14), 0, dp(14), 0);
        choice.setFocusable(true);
        choice.setClickable(true);
        choice.setBackground(timerChoiceBackground(false, cancel));
        choice.setOnFocusChangeListener((view, focused) -> {
            view.setScaleX(focused ? 1.025f : 1f);
            view.setScaleY(focused ? 1.025f : 1f);
            view.setBackground(timerChoiceBackground(focused, cancel));
        });
        choice.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(5);
        content.addView(choice, params);
    }

    private GradientDrawable timerChoiceBackground(boolean focused, boolean cancel) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? (cancel ? 0x553F2424 : 0x35FFFFFF) : 0x14FFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), focused ? 0x70FFFFFF : 0x18FFFFFF);
        return bg;
    }

    private String labelsFor(long minutes) {
        return minutes == 60 ? "1 hour" : minutes == 90 ? "90 minutes"
                : minutes == 120 ? "2 hours" : minutes + " minutes";
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        if (sleepDialog == null || !sleepDialog.isShowing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}
