package com.vdx.kinodock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small, failure-isolated sources for the rotating Home highlight. */
final class HomeHighlightApi {
    static final class Card {
        final String kind;
        final String title;
        final String credit;
        final String url;

        Card(String kind, String title, String credit, String url) {
            this.kind = kind;
            this.title = title;
            this.credit = credit;
            this.url = url;
        }
    }

    private HomeHighlightApi() { }

    static List<Card> onThisDay(LocalDate date) throws Exception {
        String month = date.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.ROOT);
        JSONObject response = new JSONObject(request("https://slashyear.com/api/date/" + month + "-"
                + date.getDayOfMonth() + ".json", null));
        JSONArray groups = response.optJSONArray("groups");
        List<JSONObject> entries = new ArrayList<>();
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                JSONArray items = group == null ? null : group.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item != null && !item.optString("text", "").trim().isEmpty()) entries.add(item);
                }
            }
        }
        if (entries.isEmpty()) throw new IllegalStateException("No history entries for this date");
        Collections.sort(entries, (left, right) -> Integer.compare(
                right.optInt("year", Integer.MIN_VALUE), left.optInt("year", Integer.MIN_VALUE)));
        List<Card> cards = new ArrayList<>();
        int count = Math.min(30, entries.size());
        for (int i = 0; i < count; i++) {
            JSONObject item = entries.get((int) ((long) i * entries.size() / count));
            String text = item.optString("text", "").trim();
            if (text.length() > 190) text = text.substring(0, 187).trim() + "…";
            JSONObject cite = item.optJSONObject("cite");
            String year = item.optString("year_label", "History").replaceFirst("\\s+CE$", "");
            cards.add(new Card("On this day", text, "On this day · " + year,
                    cite == null ? "" : cite.optString("url", "")));
        }
        return cards;
    }

    static List<Card> currentsNews() throws Exception {
        String key = BuildConfig.CURRENTS_API_KEY.trim();
        if (key.isEmpty()) throw new IllegalStateException("Currents API key is not configured");
        String address = "https://api.currentsapi.services/v1/latest-news?country=IN&language=en&page_size=20";
        JSONObject response = new JSONObject(request(address, key));
        JSONArray news = response.optJSONArray("news");
        if (news == null) throw new IllegalStateException("Currents returned no news list");
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < news.length(); i++) {
            JSONObject item = news.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "").trim();
            String link = item.optString("url", "").trim();
            if (!title.isEmpty() && seen.add(title.toLowerCase(Locale.ROOT))) {
                String publisher = "";
                if (!link.isEmpty()) try { publisher = new URL(link).getHost().replaceFirst("^www\\.", ""); }
                catch (Exception ignored) { }
                if (publisher.isEmpty()) publisher = item.optString("author", "").trim();
                cards.add(new Card("Latest news", title,
                        publisher.isEmpty() ? "India · Currents" : "Source · " + publisher, link));
            }
        }
        if (cards.isEmpty()) throw new IllegalStateException("Currents returned no usable headlines");
        return cards;
    }

    private static String request(String address, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        if (apiKey != null) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Home feed HTTP " + status);
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
