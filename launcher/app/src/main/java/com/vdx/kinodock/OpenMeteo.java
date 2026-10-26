package com.vdx.kinodock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Jaipur current conditions; called only while MainActivity is started. */
final class OpenMeteo {
    private static final String CITY_QUERY = "Jaipur";
    private static volatile Location resolvedLocation;

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

    private OpenMeteo() { }

    static Current fetch() throws Exception {
        Location location = location();
        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude + "&current=temperature_2m,weather_code"
                + "&temperature_unit=celsius&timezone=Asia%2FKolkata";
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
    static AirQuality fetchAirQuality() throws Exception {
        Location location = location();
        String endpoint = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + location.latitude
                + "&longitude=" + location.longitude + "&current=us_aqi&timezone=Asia%2FKolkata";
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

    private static Location location() throws Exception {
        Location location = resolvedLocation;
        if (location != null) return location;
        synchronized (OpenMeteo.class) {
            location = resolvedLocation;
            if (location != null) return location;
            String query = URLEncoder.encode(CITY_QUERY, "UTF-8");
            URL url = new URL("https://geocoding-api.open-meteo.com/v1/search?name=" + query
                    + "&count=1&language=en&format=json");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
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
                JSONObject result = new JSONObject(body.toString()).getJSONArray("results").getJSONObject(0);
                location = new Location(result.getString("name"), result.getDouble("latitude"),
                        result.getDouble("longitude"));
                resolvedLocation = location;
                return location;
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String glyph(int code) {
        if (code == 0) return "☼";
        if (code <= 3) return "☁";
        if (code <= 48) return "≋";
        if (code <= 67 || (code >= 80 && code <= 82)) return "☂";
        if (code <= 77 || (code >= 85 && code <= 86)) return "❄";
        if (code >= 95) return "ϟ";
        return "☁";
    }
}
