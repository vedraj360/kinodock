package com.vdx.kinodock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Weather location and current conditions; called only while MainActivity is started. */
final class OpenMeteo {
    static final String PREFS_NAME = "your_tv_weather";
    static final String LOCATION_MODE_KEY = "location_mode";
    static final String LOCATION_MODE_AUTO = "auto_ip";
    static final String LOCATION_MODE_MANUAL = "manual";
    static final String MANUAL_CITY_KEY = "manual_city";
    private static final String LOCATION_MODE_AUTO_FALLBACK = "auto_ip_manual_fallback";

    private static final String LOCATION_CITY_KEY = "location_name";
    private static final String LOCATION_LATITUDE_KEY = "location_latitude";
    private static final String LOCATION_LONGITUDE_KEY = "location_longitude";
    private static final String LOCATION_UPDATED_KEY = "location_updated_at";
    private static final String LOCATION_QUERY_KEY = "location_query";
    private static final String LOCATION_RETRY_AFTER_KEY = "location_retry_after";
    private static final String LOCATION_RETRY_QUERY_KEY = "location_retry_query";
    private static final long IP_LOCATION_REFRESH_MS = 24L * 60L * 60L * 1000L;
    private static final long LOCATION_RETRY_MS = 6L * 60L * 60L * 1000L;

    private static final class Location {
        final String name;
        final double latitude;
        final double longitude;
        Location(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Current {
        final int temperature;
        final int code;
        final String city;
        Current(int temperature, int code, String city) {
            this.temperature = temperature;
            this.code = code;
            this.city = city;
        }
        String display() { return glyph(code) + "  " + temperature + "°C"; }
    }

    static final class AirQuality {
        final int usAqi;
        final String category;
        AirQuality(int usAqi) {
            this.usAqi = usAqi;
            this.category = usAqi <= 50 ? "Good"
                    : usAqi <= 100 ? "Moderate"
                    : usAqi <= 150 ? "Sensitive groups"
                    : usAqi <= 200 ? "Unhealthy"
                    : usAqi <= 300 ? "Very unhealthy" : "Hazardous";
        }
        String display() { return "AQI " + usAqi + " · " + category; }
    }

    static final class ForecastDay {
        final String date;
        final int code;
        final int low;
        final int high;
        ForecastDay(String date, int code, int low, int high) {
            this.date = date;
            this.code = code;
            this.low = low;
            this.high = high;
        }
        String summary() { return glyph(code) + "  " + low + "° / " + high + "°"; }
    }

    /** Three days after today, using the same resolved location as current weather. */
    static java.util.List<ForecastDay> fetchNextThreeDays(Context context) throws Exception {
        Location location = location(context);
        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude
                + "&daily=weather_code,temperature_2m_min,temperature_2m_max&forecast_days=4"
                + "&temperature_unit=celsius&timezone=auto";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        try {
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject daily = new JSONObject(body.toString()).getJSONObject("daily");
            org.json.JSONArray dates = daily.getJSONArray("time");
            org.json.JSONArray codes = daily.getJSONArray("weather_code");
            org.json.JSONArray lows = daily.getJSONArray("temperature_2m_min");
            org.json.JSONArray highs = daily.getJSONArray("temperature_2m_max");
            java.util.ArrayList<ForecastDay> result = new java.util.ArrayList<>();
            for (int i = 1; i < Math.min(4, dates.length()); i++) {
                LocalDate date = LocalDate.parse(dates.getString(i));
                result.add(new ForecastDay(date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())),
                        codes.getInt(i), (int) Math.round(lows.getDouble(i)),
                        (int) Math.round(highs.getDouble(i))));
            }
            if (result.isEmpty()) throw new IllegalStateException("Forecast response had no days");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private OpenMeteo() { }

    static Current fetch(Context context) throws Exception {
        Location location = location(context);
        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude + "&current=temperature_2m,weather_code"
                + "&temperature_unit=celsius&timezone=auto";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        try {
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
            int degrees = (int) Math.round(current.getDouble("temperature_2m"));
            return new Current(degrees, current.getInt("weather_code"), location.name);
        } finally {
            connection.disconnect();
        }
    }

    /** Fetches a current US-scale AQI estimate; callers handle failure independently from weather. */
    static AirQuality fetchAirQuality(Context context) throws Exception {
        Location location = location(context);
        String endpoint = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + location.latitude
                + "&longitude=" + location.longitude + "&current=us_aqi&timezone=auto";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        try {
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
            double value = current.getDouble("us_aqi");
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                throw new IllegalStateException("Invalid AQI value");
            }
            return new AirQuality((int) Math.round(value));
        } finally {
            connection.disconnect();
        }
    }

    static void clearLocationCache(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(LOCATION_CITY_KEY).remove(LOCATION_LATITUDE_KEY)
                .remove(LOCATION_LONGITUDE_KEY).remove(LOCATION_UPDATED_KEY)
                .remove(LOCATION_QUERY_KEY).remove(LOCATION_RETRY_AFTER_KEY)
                .remove(LOCATION_RETRY_QUERY_KEY).apply();
    }

    static void initializeLocation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(LOCATION_MODE_KEY)) return;
        // The previous build cached weather for a fixed city. Drop that old display cache once
        // so it is not shown as if it belonged to the newly detected location.
        prefs.edit().putString(LOCATION_MODE_KEY, LOCATION_MODE_AUTO)
                .remove("current").remove("city").remove("fetched_at")
                .remove("aqi").remove("aqi_fetched_at").apply();
    }

    static String currentLocationLabel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (LOCATION_MODE_MANUAL.equals(prefs.getString(LOCATION_MODE_KEY, LOCATION_MODE_AUTO))) {
            String city = prefs.getString(MANUAL_CITY_KEY, "").trim();
            return city.isEmpty() ? context.getString(R.string.weather_location_manual) : city;
        }
        return context.getString(R.string.weather_location_auto);
    }

    private static synchronized Location location(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String mode = prefs.getString(LOCATION_MODE_KEY, LOCATION_MODE_AUTO);
        boolean manual = LOCATION_MODE_MANUAL.equals(mode);
        String query = manual ? prefs.getString(MANUAL_CITY_KEY, "").trim() : "";
        if (manual && query.isEmpty()) throw new IllegalStateException("Choose a city for weather");

        Location cached = readCachedLocation(prefs);
        String cachedQuery = prefs.getString(LOCATION_QUERY_KEY, "");
        boolean cacheMatches = cached != null && (manual
                ? query.equalsIgnoreCase(cachedQuery)
                : LOCATION_MODE_AUTO.equals(cachedQuery)
                        || LOCATION_MODE_AUTO_FALLBACK.equals(cachedQuery));
        String lookupKey = manual ? query : LOCATION_MODE_AUTO;
        long now = System.currentTimeMillis();
        long cacheAge = now - prefs.getLong(LOCATION_UPDATED_KEY, 0L);
        boolean fresh = cacheMatches && (manual || (cacheAge >= 0 && cacheAge < IP_LOCATION_REFRESH_MS));
        if (fresh) return cached;
        if (lookupKey.equalsIgnoreCase(prefs.getString(LOCATION_RETRY_QUERY_KEY, ""))
                && now < prefs.getLong(LOCATION_RETRY_AFTER_KEY, 0L)) {
            if (cacheMatches) return cached;
            throw new IllegalStateException("Weather location lookup is temporarily deferred");
        }

        try {
            Location result;
            String resultQuery;
            if (manual) {
                result = geocode(query);
                resultQuery = query;
            } else {
                try {
                    result = lookupByIp();
                    resultQuery = LOCATION_MODE_AUTO;
                } catch (Exception ipFailure) {
                    String fallbackCity = prefs.getString(MANUAL_CITY_KEY, "").trim();
                    if (fallbackCity.isEmpty()) throw ipFailure;
                    result = geocode(fallbackCity);
                    resultQuery = LOCATION_MODE_AUTO_FALLBACK;
                }
            }
            prefs.edit().putString(LOCATION_CITY_KEY, result.name)
                    .putFloat(LOCATION_LATITUDE_KEY, (float) result.latitude)
                    .putFloat(LOCATION_LONGITUDE_KEY, (float) result.longitude)
                    .putLong(LOCATION_UPDATED_KEY, now)
                    .putString(LOCATION_QUERY_KEY, resultQuery)
                    .remove(LOCATION_RETRY_AFTER_KEY).remove(LOCATION_RETRY_QUERY_KEY)
                    .apply();
            return result;
        } catch (Exception e) {
            // A failed refresh never clears the last working location. The caller already retains
            // the last successful weather and AQI values when this method cannot resolve one.
            prefs.edit().putString(LOCATION_RETRY_QUERY_KEY, lookupKey)
                    .putLong(LOCATION_RETRY_AFTER_KEY, now + LOCATION_RETRY_MS).apply();
            if (cacheMatches) return cached;
            throw e;
        }
    }

    private static Location readCachedLocation(SharedPreferences prefs) {
        String name = prefs.getString(LOCATION_CITY_KEY, "").trim();
        if (name.isEmpty() || !prefs.contains(LOCATION_LATITUDE_KEY)
                || !prefs.contains(LOCATION_LONGITUDE_KEY)) return null;
        double latitude = prefs.getFloat(LOCATION_LATITUDE_KEY, Float.NaN);
        double longitude = prefs.getFloat(LOCATION_LONGITUDE_KEY, Float.NaN);
        if (Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < -90 || latitude > 90
                || Double.isNaN(longitude) || Double.isInfinite(longitude)
                || longitude < -180 || longitude > 180) return null;
        return new Location(name, latitude, longitude);
    }

    private static Location lookupByIp() throws Exception {
        HttpURLConnection connection = open("https://ipwho.is/?fields=success,message,city,latitude,longitude");
        try {
            JSONObject result = new JSONObject(readBody(connection));
            if (!result.optBoolean("success", false)) {
                throw new IllegalStateException(result.optString("message", "IP location lookup failed"));
            }
            String city = result.optString("city", "").trim();
            if (city.isEmpty()) throw new IllegalStateException("IP location did not include a city");
            return new Location(city, result.getDouble("latitude"), result.getDouble("longitude"));
        } finally {
            connection.disconnect();
        }
    }

    private static Location geocode(String city) throws Exception {
        String query = URLEncoder.encode(city, "UTF-8");
        HttpURLConnection connection = open("https://geocoding-api.open-meteo.com/v1/search?name=" + query
                + "&count=10&language=en&format=json");
        try {
            JSONObject response = new JSONObject(readBody(connection));
            org.json.JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) throw new IllegalStateException("City not found");
            JSONObject result = results.getJSONObject(0);
            String name = result.optString("name", city).trim();
            return new Location(name.isEmpty() ? city : name,
                    result.getDouble("latitude"), result.getDouble("longitude"));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        return connection;
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    static String glyph(int code) {
        if (code == 0) return "☼";
        if (code <= 3) return "☁";
        if (code <= 48) return "≋";
        if (code <= 67 || (code >= 80 && code <= 82)) return "☂";
        if (code <= 77 || (code >= 85 && code <= 86)) return "❄";
        if (code >= 95) return "ϟ";
        return "☁";
    }
}
