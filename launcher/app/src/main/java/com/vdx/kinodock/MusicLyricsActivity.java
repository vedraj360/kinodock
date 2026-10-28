package com.vdx.kinodock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen, remote-friendly Now Playing view with exact-track LRCLIB lyrics. */
public final class MusicLyricsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private String currentKey = "";
    private LyricsApi.TrackLyrics lyrics;
    private final ArrayList<TextView> lyricRows = new ArrayList<>();
    private LinearLayout lyricList;
    private ScrollView lyricScroll;
    private TextView status;
    private TextView title;
    private TextView artist;
    private TextView progressText;
    private ProgressBar progress;
    private TextView playPause;
    private ImageView albumArt;
    private int activeLine = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUi();
        sessions = getSystemService(MediaSessionManager.class);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF080B10);
        View shade = new View(this);
        GradientDrawable shadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC101820, 0xF20A0D12, 0xFF07090C});
        shade.setBackground(shadeBg);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(58), dp(34), dp(58), dp(30));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView kicker = label("NOW PLAYING", 12, 0xBFFFFFFF);
        kicker.setLetterSpacing(0.18f);
        header.addView(kicker, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = control("Back", 14);
        close.setContentDescription("Close Now Playing");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(44)));
        close.post(close::requestFocus);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = dp(24);
        page.addView(body, bodyParams);

        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setGravity(Gravity.CENTER_HORIZONTAL);
        player.setPadding(0, dp(14), dp(36), dp(12));
        body.addView(player, new LinearLayout.LayoutParams(dp(350), -1));

        albumArt = new ImageView(this);
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setBackground(makeRounded(0xFF1B222B, 18, 0x30FFFFFF));
        albumArt.setClipToOutline(true);
        player.addView(albumArt, new LinearLayout.LayoutParams(dp(252), dp(252)));

        title = label("Play music in an app", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(22);
        player.addView(title, titleParams);
        artist = label("Synced lyrics will appear here", 16, 0xBFFFFFFF);
        artist.setGravity(Gravity.CENTER);
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(-1, -2);
        artistParams.topMargin = dp(5);
        player.addView(artist, artistParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x44FFFFFF));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.topMargin = dp(22);
        player.addView(progress, progressParams);
        progressText = label("--:--  /  --:--", 13, 0xBFFFFFFF);
        progressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-1, -2);
        timeParams.topMargin = dp(7);
        player.addView(progressText, timeParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
        controlsParams.topMargin = dp(16);
        player.addView(controls, controlsParams);
        TextView previous = control("|◀", 17);
        previous.setContentDescription("Previous track");
        previous.setOnClickListener(v -> transport(0));
        controls.addView(previous, controlParams());
        playPause = control("▶", 19);
        playPause.setContentDescription("Play or pause");
        playPause.setOnClickListener(v -> transport(1));
        controls.addView(playPause, controlParams());
        TextView next = control("▶|", 17);
        next.setContentDescription("Next track");
        next.setOnClickListener(v -> transport(2));
        controls.addView(next, controlParams());

        LinearLayout lyricsPanel = new LinearLayout(this);
        lyricsPanel.setOrientation(LinearLayout.VERTICAL);
        lyricsPanel.setPadding(dp(30), dp(22), dp(24), dp(12));
        GradientDrawable panelBg = makeRounded(0x1AFFFFFF, 22, 0x20FFFFFF);
        lyricsPanel.setBackground(panelBg);
        body.addView(lyricsPanel, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView lyricsTitle = label("LYRICS", 12, 0x99FFFFFF);
        lyricsTitle.setLetterSpacing(0.18f);
        lyricsPanel.addView(lyricsTitle, new LinearLayout.LayoutParams(-1, -2));
        status = label("Waiting for an active music session…", 17, 0xCCFFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setFocusable(true);
        status.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(10);
        lyricsPanel.addView(status, statusParams);
        status.setOnClickListener(v -> openNotificationSettings());
        lyricScroll = new ScrollView(this);
        lyricScroll.setFillViewport(true);
        lyricScroll.setClipToPadding(false);
        lyricScroll.setPadding(0, dp(8), dp(8), dp(12));
        lyricsPanel.addView(lyricScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        lyricList = new LinearLayout(this);
        lyricList.setOrientation(LinearLayout.VERTICAL);
        lyricList.setGravity(Gravity.CENTER_VERTICAL);
        lyricScroll.addView(lyricList, new ScrollView.LayoutParams(-1, -2));

        setContentView(root);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            readActiveSession();
            updateProgressAndHighlight();
            handler.postDelayed(this, 500);
        }
    };

    private void readActiveSession() {
        if (sessions == null) return;
        List<MediaController> controllers;
        try {
            controllers = sessions.getActiveSessions(new ComponentName(this, GuardRevival.class));
        } catch (SecurityException noAccess) {
            showPermissionState();
            return;
        } catch (RuntimeException unavailable) {
            showStatus("Media information is unavailable on this TV.", false);
            return;
        }
        MediaController chosen = null;
        for (MediaController candidate : controllers) {
            MediaMetadata metadata = candidate.getMetadata();
            if (metadata == null || metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
            PlaybackState state = candidate.getPlaybackState();
            if (chosen == null || (state != null && state.getState() == PlaybackState.STATE_PLAYING)) chosen = candidate;
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) break;
        }
        if (chosen == null) {
            controller = null;
            if (currentKey.isEmpty()) showStatus("Start music in a player app, then return here.", false);
            return;
        }
        controller = chosen;
        MediaMetadata metadata = chosen.getMetadata();
        String track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String performer = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (performer == null || performer.trim().isEmpty()) performer = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (performer == null) performer = "";
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = track.trim().toLowerCase(Locale.ROOT) + "|" + performer.trim().toLowerCase(Locale.ROOT) + "|" + duration / 1000;
        title.setText(track);
        artist.setText(performer.isEmpty() ? (album == null ? "" : album) : performer);
        PlaybackState state = chosen.getPlaybackState();
        playPause.setText(state != null && state.getState() == PlaybackState.STATE_PLAYING ? "Ⅱ" : "▶");
        if (key.equals(currentKey)) return;
        Bitmap art = null;
        try {
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        } catch (RuntimeException | OutOfMemoryError ignored) { }
        if (art != null) albumArt.setImageBitmap(art);
        currentKey = key;
        activeLine = -1;
        lyrics = null;
        lyricRows.clear();
        lyricList.removeAllViews();
        showStatus("Finding a close match for this track…", false);
        loadLyrics(track, performer, album == null ? "" : album, duration, key);
    }

    private void loadLyrics(String track, String performer, String album, long duration, String key) {
        String cacheKey = "track_" + digest(key);
        android.content.SharedPreferences cache = getSharedPreferences("music_lyrics", MODE_PRIVATE);
        String cached = cache.getString(cacheKey, "");
        if (!cached.isEmpty()) {
            try {
                JSONObject value = new JSONObject(cached);
                applyLyrics(new LyricsApi.TrackLyrics(value.optString("track"), value.optString("artist"),
                        value.optString("plain"), value.optString("synced")), key, true);
                return;
            } catch (Exception ignored) { }
        }
        Thread worker = new Thread(() -> {
            LyricsApi.TrackLyrics found = null;
            String error = null;
            try { found = LyricsApi.fetch(track, performer, album, duration); }
            catch (Exception | OutOfMemoryError failure) { error = "Lyrics service is temporarily unavailable."; }
            LyricsApi.TrackLyrics result = found;
            String failureMessage = error;
            handler.post(() -> {
                if (!key.equals(currentKey) || isFinishing()) return;
                if (result == null) {
                    showStatus(failureMessage == null ? "No lyrics matched this version of the track." : failureMessage, true);
                    return;
                }
                try {
                    cache.edit().putString(cacheKey, new JSONObject().put("track", result.track)
                            .put("artist", result.artist).put("plain", result.plain)
                            .put("synced", result.synced).toString()).apply();
                } catch (Exception ignored) { }
                applyLyrics(result, key, false);
            });
        }, "lrclib-track-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyLyrics(LyricsApi.TrackLyrics result, String key, boolean fromCache) {
        if (!key.equals(currentKey)) return;
        lyrics = result;
        status.setVisibility(View.GONE);
        lyricList.removeAllViews();
        lyricRows.clear();
        activeLine = -1;
        for (LyricsApi.Line line : result.lines) {
            TextView row = label(line.text, line.atMs < 0 ? 19 : 21, 0x80FFFFFF);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMaxLines(3);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(3);
            lyricList.addView(row, rowParams);
            lyricRows.add(row);
        }
        String label = result.timed() ? "SYNCED LYRICS" : "LYRICS · NOT TIMED";
        status.setText(label + (fromCache ? " · saved on this TV" : ""));
        status.setVisibility(View.VISIBLE);
        status.setGravity(Gravity.START);
        status.setTextSize(11);
        status.setTextColor(0x99FFFFFF);
        status.setPadding(0, dp(8), 0, dp(4));
        status.setOnClickListener(null);
        if (!result.timed()) {
            TextView note = label("These lyrics are available, but this track has no timing data.", 13, 0x99FFFFFF);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.topMargin = dp(5);
            lyricList.addView(note, noteParams);
        }
    }

    private void showPermissionState() {
        lyricList.removeAllViews();
        lyricRows.clear();
        showStatus("Allow Notification access so KinoDock can read the active music session. It uses track metadata and playback position only.", true);
        status.setOnClickListener(v -> openNotificationSettings());
        status.setContentDescription("Open Notification access settings");
    }

    private void showStatus(String message, boolean action) {
        status.setText(message + (action ? "\n\nSelect to open access settings" : ""));
        status.setTextSize(17);
        status.setTextColor(0xCCFFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(24), dp(24), dp(24));
        status.setVisibility(View.VISIBLE);
        status.setOnClickListener(action ? v -> openNotificationSettings() : null);
        status.setContentDescription(action ? message + ". Press select to open access settings." : message);
    }

    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception ignored) { Toast.makeText(this, "Open TV Settings → Apps → Special app access → Notification access", Toast.LENGTH_LONG).show(); }
    }

    private void updateProgressAndHighlight() {
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return;
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? 0 : Math.max(0, state.getPosition());
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += Math.max(0, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed();
        }
        if (duration > 0) position = Math.min(duration, position);
        progress.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration));
        progressText.setText(time(position) + "  /  " + time(duration));
        if (lyrics == null || !lyrics.timed()) return;
        int active = -1;
        for (int i = 0; i < lyrics.lines.size(); i++) {
            if (lyrics.lines.get(i).atMs >= 0 && lyrics.lines.get(i).atMs <= position) active = i;
            else if (lyrics.lines.get(i).atMs > position) break;
        }
        if (active == activeLine) return;
        activeLine = active;
        for (int i = 0; i < lyricRows.size(); i++) {
            TextView row = lyricRows.get(i);
            boolean selected = i == active;
            row.setTextColor(selected ? Color.WHITE : 0x80FFFFFF);
            row.animate().scaleX(selected ? 1.025f : 1f).scaleY(selected ? 1.025f : 1f)
                    .setDuration(220).start();
            if (selected) {
                row.setBackground(makeRounded(0x18FFFFFF, 12, 0x00FFFFFF));
                lyricScroll.smoothScrollTo(0, Math.max(0, row.getTop() - lyricScroll.getHeight() / 2 + row.getHeight() / 2));
            } else row.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void transport(int action) {
        if (controller == null) return;
        try {
            MediaController.TransportControls controls = controller.getTransportControls();
            if (action == 0) controls.skipToPrevious();
            else if (action == 2) controls.skipToNext();
            else {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
                else controls.play();
            }
        } catch (RuntimeException ignored) { }
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(48));
        params.setMarginStart(dp(7));
        params.setMarginEnd(dp(7));
        return params;
    }

    private TextView control(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(makeRounded(0x22FFFFFF, 24, 0x40FFFFFF));
        view.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.12f : 1f)
                .scaleY(focused ? 1.12f : 1f).setDuration(150).start());
        return view;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(this, R.string.font_regular));
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable makeRounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private String time(long ms) {
        if (ms <= 0) return "--:--";
        long seconds = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
