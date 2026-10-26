package com.vdx.kinodock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small foreground-only Unsplash client. It keeps metadata, hotlinks the returned image URL, and has no disk cache. */
final class UnsplashWallpaperApi {
    static final String ENABLED_KEY = "unsplash_wallpaper_enabled";
    private static final String TAG = "TvHome.Unsplash";
    private static final String PREFS = "unsplash_wallpaper";
    private static final long ROTATION_MS = 6L * 60L * 60L * 1000L;
    private static final String[] DARK_QUERIES = {
            "dark cinematic landscape night",
            "dark cinematic superhero city night",
            "dark Batman cinematic night",
            "dark Iron Man cinematic night",
            "dark Spider-Man cinematic city night",
            "dark Avengers superhero cinematic scene",
            "dark superhero movie scene cinematic",
            "dark futuristic city cinematic",
            "dark abstract cinematic background",
            "dark forest cinematic night",
            "dark dramatic architecture night",
            "dark space cinematic wallpaper"
    };
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "unsplash-wallpaper");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    interface Callback { void complete(boolean updated); }

    static final class Photo {
        final String id;
        final String imageUrl;
        final String photographer;
        final String profileUrl;
        final String description;
        final String downloadLocation;
        final long updatedAt;

        Photo(String id, String imageUrl, String photographer, String profileUrl,
              String description, String downloadLocation, long updatedAt) {
            this.id = id;
            this.imageUrl = imageUrl;
            this.photographer = photographer;
            this.profileUrl = profileUrl;
            this.description = description;
            this.downloadLocation = downloadLocation;
            this.updatedAt = updatedAt;
        }

        String credit() {
            return photographer.isEmpty() ? "Unsplash" : "Photo by " + photographer + " · Unsplash";
        }
    }

    private UnsplashWallpaperApi() { }

    static boolean configured() { return !BuildConfig.UNSPLASH_ACCESS_KEY.trim().isEmpty(); }

    static boolean enabled(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_KEY, false);
    }

    static Photo current(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("id", "");
        String url = p.getString("url", "");
        if (id.isEmpty() || url.isEmpty()) return null;
        return new Photo(id, url, p.getString("photographer", ""),
                p.getString("profile", ""), p.getString("description", ""), "",
                p.getLong("updated", 0L));
    }

    /** Manual refresh. Search is one API request; the returned image URL is hotlinked by WallpaperPlayer. */
    static void updateNow(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        if (!configured() || !RUNNING.compareAndSet(false, true)) {
            if (callback != null) MAIN.post(() -> callback.complete(false));
            return;
        }
        IO.execute(() -> {
            boolean success = false;
            try {
                Photo old = current(app);
                List<Photo> photos = search();
                if (photos.isEmpty()) throw new IllegalStateException("Unsplash returned no usable photos");
                Photo chosen = choose(photos, old == null ? "" : old.id);
                // Required by Unsplash when a photo is set as a wallpaper. Failure to record this event
                // must not prevent the photo from being used or disturb the current wallpaper.
                trackSelection(chosen);
                save(app, chosen);
                app.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                        .putBoolean(ENABLED_KEY, true)
                        .putBoolean(WallpaperRotation.LOCAL_AUTO_ROTATE_KEY, false).apply();
                success = true;
            } catch (Exception | OutOfMemoryError failure) {
                Log.w(TAG, "Wallpaper refresh failed; keeping the current wallpaper", failure);
            } finally {
                RUNNING.set(false);
            }
            if (callback != null) {
                boolean result = success;
                MAIN.post(() -> callback.complete(result));
            }
        });
    }

    /** Called by Home while visible; it never starts more than one fetch and never polls more than every six hours. */
    static void updateIfDue(Context context, Callback callback) {
        if (!enabled(context)) return;
        Photo photo = current(context);
        if (photo == null || System.currentTimeMillis() - photo.updatedAt >= ROTATION_MS) {
            updateNow(context, callback);
        }
    }

    private static List<Photo> search() throws Exception {
        String queryText = DARK_QUERIES[(int) (System.nanoTime() & 0x7fffffff) % DARK_QUERIES.length];
        String query = URLEncoder.encode(queryText, "UTF-8");
        String address = "https://api.unsplash.com/search/photos?query=" + query
                + "&orientation=landscape&color=black&order_by=relevant&content_filter=high&per_page=30";
        JSONObject response = new JSONObject(request(address));
        JSONArray results = response.optJSONArray("results");
        List<Photo> photos = new ArrayList<>();
        if (results == null) return photos;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            if (looksLikeMountainPhoto(item)) continue;
            JSONObject urls = item.optJSONObject("urls");
            JSONObject user = item.optJSONObject("user");
            JSONObject links = item.optJSONObject("links");
            if (urls == null || user == null || links == null) continue;
            String image = urls.optString("regular", "").trim();
            String id = item.optString("id", "").trim();
            if (image.isEmpty() || id.isEmpty()) continue;
            JSONObject userLinks = user.optJSONObject("links");
            photos.add(new Photo(id, image, user.optString("name", "Unsplash photographer"),
                    userLinks == null ? "" : userLinks.optString("html", ""),
                    item.optString("alt_description", "Dark cinematic landscape"),
                    links.optString("download_location", ""), 0L));
        }
        return photos;
    }

    private static boolean looksLikeMountainPhoto(JSONObject photo) {
        StringBuilder text = new StringBuilder(photo.optString("description", ""))
                .append(' ').append(photo.optString("alt_description", ""));
        JSONArray tags = photo.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONObject tag = tags.optJSONObject(i);
                if (tag != null) text.append(' ').append(tag.optString("title", ""));
            }
        }
        String searchable = text.toString().toLowerCase(java.util.Locale.ROOT);
        String[] unwanted = {"mountain", "mountains", "mountainous", "alpine", "mountain range", "mountain peak"};
        for (String word : unwanted) if (searchable.contains(word)) return true;
        return false;
    }

    private static Photo choose(List<Photo> photos, String previousId) {
        List<Photo> alternatives = new ArrayList<>();
        for (Photo photo : photos) if (!photo.id.equals(previousId)) alternatives.add(photo);
        List<Photo> pool = alternatives.isEmpty() ? photos : alternatives;
        return pool.get((int) (System.nanoTime() % pool.size()));
    }

    private static void trackSelection(Photo photo) {
        try {
            if (photo.downloadLocation.isEmpty()) return;
            JSONObject response = new JSONObject(request(photo.downloadLocation));
            // Unsplash may return a different URL for the final image size; use it when present.
            String trackedUrl = response.optString("url", "").trim();
            if (!trackedUrl.isEmpty()) {
                // The API's `urls.regular` remains the URL used for displaying; the response URL is not used as an image source.
            }
        } catch (Exception failure) {
            Log.w(TAG, "Could not report wallpaper selection to Unsplash", failure);
        }
    }

    private static void save(Context context, Photo photo) {
        long now = System.currentTimeMillis();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("id", photo.id).putString("url", photo.imageUrl)
                .putString("photographer", photo.photographer).putString("profile", photo.profileUrl)
                .putString("description", photo.description).putLong("updated", now).apply();
    }

    private static String request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Version", "v1");
        connection.setRequestProperty("Authorization", "Client-ID " + BuildConfig.UNSPLASH_ACCESS_KEY);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Unsplash HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }
}
