package com.vdx.kinodock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Batched ZenQuotes source. Network results are cached by the caller; rendering never depends on the API. */
final class DailyQuoteApi {
    static final String PREFS = "your_tv_daily_quote";
    static final class Quote {
        final String line;
        final String credit;
        Quote(String line, String credit) { this.line = line; this.credit = credit; }
    }

    private DailyQuoteApi() { }

    /** ZenQuotes batch endpoint returns a reusable list, avoiding a request for each Home rotation. */
    static List<Quote> fetchBatch() throws Exception {
        JSONArray response = new JSONArray(request("https://zenquotes.io/api/quotes"));
        List<Quote> quotes = new ArrayList<>();
        for (int i = 0; i < response.length(); i++) {
            JSONObject item = response.optJSONObject(i);
            if (item == null) continue;
            String line = item.optString("q", "").trim();
            String author = item.optString("a", "Unknown").trim();
            if (line.isEmpty() || !isAppropriate(line)) continue;
            quotes.add(new Quote(shorten(line), author.isEmpty() ? "Unknown" : author));
        }
        if (quotes.isEmpty()) throw new IllegalStateException("No suitable quotes returned");
        return quotes;
    }

    static String serialize(List<Quote> quotes) {
        JSONArray array = new JSONArray();
        for (Quote quote : quotes) {
            JSONObject item = new JSONObject();
            try { item.put("line", quote.line); item.put("credit", quote.credit); array.put(item); }
            catch (Exception ignored) { }
        }
        return array.toString();
    }

    static List<Quote> parse(String json) {
        List<Quote> quotes = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String line = item.optString("line", "").trim();
                if (isAppropriate(line)) quotes.add(new Quote(line,
                        item.optString("credit", "").replace(" · ZenQuotes.io", "").replace("ZenQuotes.io", "").trim()));
            }
        } catch (Exception ignored) { }
        return quotes;
    }

    private static String request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("ZenQuotes HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally { connection.disconnect(); }
    }

    private static String shorten(String text) {
        String[] words = text.trim().split("\\s+");
        if (words.length <= 18) return text.trim();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 18; i++) { if (i > 0) result.append(' '); result.append(words[i]); }
        return result.append('…').toString();
    }

    private static boolean containsProfanity(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        String[] blocked = {
                "fuck", "fucker", "fuckface", "motherfucker", "shit", "shitty", "shithead", "bullshit",
                "bitch", "son of a bitch", "asshole", "arse", "arsehole", "dumbass", "jackass", "damn",
                "bastard", "cunt", "cock", "cocksucker", "dick", "dickhead", "douche", "douchebag",
                "piss", "pissed", "prick", "pussy", "slut", "whore", "twat", "wanker", "bugger",
                "bollocks", "crap", "dipshit", "piece of shit", "retard", "retarded", "fag", "faggot",
                "nigger", "nigga", "spic", "chink", "kike", "tranny", "stupid", "stupidity",
                "stupidly", "idiot", "idiotic", "moron", "moronic", "imbecile"
        };
        for (String word : blocked) if (text.matches(".*\\b" + word + "\\b.*")) return true;
        return false;
    }

    static boolean isAppropriate(String quote) {
        return quote != null && !quote.trim().isEmpty() && !containsProfanity(quote);
    }
}
