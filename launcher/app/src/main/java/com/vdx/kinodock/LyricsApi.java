package com.vdx.kinodock;

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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact-track lyric lookups. No key is needed; failures are returned to the caller, never thrown into Home. */
final class LyricsApi {
    static final class Line {
        final long atMs;
        final String text;
        Line(long atMs, String text) { this.atMs = atMs; this.text = text; }
    }

    static final class TrackLyrics {
        final String track;
        final String artist;
        final String plain;
        final String synced;
        final List<Line> lines;
        TrackLyrics(String track, String artist, String plain, String synced) {
            this.track = track;
            this.artist = artist;
            this.plain = plain;
            this.synced = synced;
            this.lines = parse(synced == null || synced.trim().isEmpty() ? plain : synced);
        }
        boolean timed() { return !synced.isEmpty() && !lines.isEmpty() && lines.get(0).atMs >= 0; }
    }

    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private LyricsApi() { }

    static TrackLyrics fetch(String track, String artist, String album, long durationMs) throws Exception {
        StringBuilder address = new StringBuilder("https://lrclib.net/api/get?track_name=")
                .append(encode(track)).append("&artist_name=").append(encode(artist));
        if (album != null && !album.trim().isEmpty()) address.append("&album_name=").append(encode(album));
        long seconds = Math.round(durationMs / 1000.0);
        if (seconds > 0 && seconds <= 3600) address.append("&duration=").append(seconds);
        JSONObject exact = request(address.toString());
        if (usable(exact)) return from(exact);

        // Some players report an edited duration. Only use a fallback when title, artist, and duration still match.
        String search = "https://lrclib.net/api/search?track_name=" + encode(track)
                + "&artist_name=" + encode(artist);
        JSONArray matches = requestArray(search);
        JSONObject best = null;
        long bestDelta = Long.MAX_VALUE;
        String wantedTrack = normalize(track), wantedArtist = normalize(artist);
        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);
            if (item == null || !usable(item)
                    || !wantedTrack.equals(normalize(item.optString("trackName", item.optString("name", ""))))
                    || !wantedArtist.equals(normalize(item.optString("artistName", "")))) continue;
            long candidateMs = Math.round(item.optDouble("duration", 0) * 1000.0);
            long delta = durationMs > 0 && candidateMs > 0 ? Math.abs(candidateMs - durationMs) : Long.MAX_VALUE - 1;
            if (durationMs > 0 && delta > 2000) continue;
            if (best == null || delta < bestDelta) { best = item; bestDelta = delta; }
        }
        return best == null ? null : from(best);
    }

    private static boolean usable(JSONObject value) {
        return value != null && !value.optBoolean("instrumental", false)
                && (!value.optString("syncedLyrics", "").trim().isEmpty()
                || !value.optString("plainLyrics", "").trim().isEmpty());
    }

    private static TrackLyrics from(JSONObject value) {
        return new TrackLyrics(value.optString("trackName", value.optString("name", "")),
                value.optString("artistName", ""), value.optString("plainLyrics", ""),
                value.optString("syncedLyrics", ""));
    }

    private static JSONObject request(String address) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status < 200 || status >= 300) throw new IllegalStateException("Lyrics service HTTP " + status);
            return new JSONObject(read(connection));
        } finally { connection.disconnect(); }
    }

    private static JSONArray requestArray(String address) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return new JSONArray();
            return new JSONArray(read(connection));
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        connection.setRequestProperty("Lrclib-App-Id", "KinoDock/3.0");
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
                if (result.length() > 512 * 1024) throw new IllegalStateException("Lyrics response is too large");
            }
        }
        return result.toString();
    }

    private static String encode(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static List<Line> parse(String source) {
        List<Line> result = new ArrayList<>();
        if (source == null || source.trim().isEmpty()) return result;
        for (String raw : source.replace("\r", "").split("\n")) {
            Matcher matcher = TIMESTAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            while (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = fraction == null ? 0 : Long.parseLong(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                times.add((minutes * 60 + seconds) * 1000 + millis);
            }
            String text = TIMESTAMP.matcher(raw).replaceAll("").replaceAll("\\[[^]]*]", "").trim();
            if (text.isEmpty()) continue;
            if (times.isEmpty()) result.add(new Line(-1, text));
            else for (Long at : times) result.add(new Line(at, text));
        }
        result.sort((a, b) -> Long.compare(a.atMs, b.atMs));
        return result;
    }
}
