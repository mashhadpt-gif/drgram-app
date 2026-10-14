/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Weather (Fix 40) — آب و هوا for the smart-command engine, built on
 * the free open-meteo.com APIs (no key needed):
 *   • geocoding: https://geocoding-api.open-meteo.com/v1/search?name=City
 *   • forecast:  https://api.open-meteo.com/v1/forecast?...&current=...
 * The city is configured once from the smart-reply settings and cached with
 * its coordinates; network work runs on Utilities.globalQueue, the answer is
 * posted back to the chat through DrGramSmartReply.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DrGramWeather {

    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fa&format=json&name=";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_smartreply", Context.MODE_PRIVATE);
    }

    public static String getCity() {
        try {
            return prefs().getString("weather_city", "تهران");
        } catch (Throwable t) {
            return "تهران";
        }
    }

    /** Saves the city directly (coordinates fetched lazily on the next answer). */
    public static void setCity(String name, double lat, double lon) {
        try {
            prefs().edit()
                    .putString("weather_city", name)
                    .putString("weather_lat", String.format(Locale.US, "%.4f", lat))
                    .putString("weather_lon", String.format(Locale.US, "%.4f", lon))
                    .apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Resolves a city name to coordinates (network thread). Returns null on failure. */
    private static double[] geocode(String name) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(GEO_URL + URLEncoder.encode(name, "UTF-8"));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "DrGram");
            InputStream is = conn.getInputStream();
            JSONObject root = new JSONObject(readAll(is));
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            JSONObject first = results.getJSONObject(0);
            return new double[]{first.optDouble("latitude", 0), first.optDouble("longitude", 0), first.optLong("population", 0)};
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    private static double[] coords() {
        try {
            double lat = Double.parseDouble(prefs().getString("weather_lat", "35.6892"));
            double lon = Double.parseDouble(prefs().getString("weather_lon", "51.3890"));
            return new double[]{lat, lon};
        } catch (Throwable t) {
            return new double[]{35.6892, 51.3890};
        }
    }

    /** Builds the Persian weather answer for the configured city (network thread). */
    public static String fetchAnswer() {
        String city = getCity();
        double[] c = coords();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(FORECAST_URL
                    + "?latitude=" + c[0] + "&longitude=" + c[1]
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "DrGram");
            InputStream is = conn.getInputStream();
            JSONObject root = new JSONObject(readAll(is));
            JSONObject current = root.getJSONObject("current");
            double temp = current.optDouble("temperature_2m", Double.NaN);
            double feels = current.optDouble("apparent_temperature", Double.NaN);
            int humidity = current.optInt("relative_humidity_2m", -1);
            double wind = current.optDouble("wind_speed_10m", Double.NaN);
            int code = current.optInt("weather_code", -1);
            JSONObject daily = root.optJSONObject("daily");
            String[] dayNames = {"یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه"};
            String today = "";
            if (daily != null) {
                try {
                    JSONArray times = daily.getJSONArray("time");
                    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    String now = df.format(new Date());
                    int idx = 0;
                    for (int i = 0; i < times.length(); i++) {
                        if (now.equals(times.getString(i))) {
                            idx = i;
                            break;
                        }
                    }
                    double max = daily.getJSONArray("temperature_2m_max").getDouble(idx);
                    double min = daily.getJSONArray("temperature_2m_min").getDouble(idx);
                    today = "\n\uD83C\uDF21️ بیشینه/کمینهٔ امروز: " + faNum(String.format(Locale.US, "%.0f", max)) + "° / " + faNum(String.format(Locale.US, "%.0f", min)) + "°";
                } catch (Throwable ignore) {}
            }
            String weekday = dayNames[(new Date().getDay() + 1) % 7];
            StringBuilder b = new StringBuilder();
            b.append("\u2600\uFE0F آب و هوای ").append(city).append(" (").append(weekday).append(")\n\n");
            b.append(describe(code)).append("\n");
            b.append("\uD83C\uDF21️ دمای الان: ").append(faNum(String.format(Locale.US, "%.1f", temp))).append("°\n");
            if (!Double.isNaN(feels)) {
                b.append("\uD83E\uDDE1 دمای احساسی: ").append(faNum(String.format(Locale.US, "%.1f", feels))).append("°\n");
            }
            if (humidity >= 0) {
                b.append("\uD83D\uDCA7 رطوبت: ").append(faNum(String.valueOf(humidity))).append("٪\n");
            }
            if (!Double.isNaN(wind)) {
                b.append("\uD83C\uDF2C\uFE0F باد: ").append(faNum(String.format(Locale.US, "%.0f", wind))).append(" کیلومتر/ساعت");
            }
            if (today.length() > 0) {
                b.append(today);
            }
            return b.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    /** Changes the city asynchronously; callback gets a Persian status line. */
    public static void changeCity(String name, Runnable onUi) {
        Utilities.globalQueue.postRunnable(() -> {
            double[] c = geocode(name);
            String status;
            if (c != null) {
                setCity(name, c[0], c[1]);
                status = "✅ شهر تنظیم شد: " + name;
            } else {
                status = "⛔️ شهری با نام «" + name + "» پیدا نشد";
            }
            final String s = status;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    changeCityToast(s);
                } catch (Throwable ignore) {}
                if (onUi != null) {
                    onUi.run();
                }
            });
        });
    }

    private static void changeCityToast(String s) {
        android.widget.Toast.makeText(ApplicationLoader.applicationContext, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** WMO weather interpretation codes → Persian. */
    private static String describe(int code) {
        if (code < 0) {
            return "\uD83C\uDF24 وضعیت نامشخص";
        }
        if (code == 0) return "\u2600\uFE0F آسمان صاف";
        if (code == 1 || code == 2) return "\uD83C\uDF24 کمی ابری";
        if (code == 3) return "\u2601\uFE0F ابری";
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F مه‌آلود";
        if (code == 51 || code == 53 || code == 55) return "\uD83C\uDF27\uFE0F نم‌نم باران";
        if (code == 56 || code == 57) return "\uD83C\uDF27\uFE0F باران یخ‌زده";
        if (code == 61) return "\uD83C\uDF27\uFE0F باران سبک";
        if (code == 63) return "\uD83C\uDF27\uFE0F باران متوسط";
        if (code == 65) return "\uD83C\uDF27\uFE0F باران شدید";
        if (code == 66 || code == 67) return "\uD83C\uDF27\uFE0F باران یخ‌زده";
        if (code == 71 || code == 73) return "\u2744\uFE0F برف";
        if (code == 75 || code == 77) return "\u2744\uFE0F برف سنگین";
        if (code == 80 || code == 81) return "\uD83C\uDF27\uFE0F رگبار باران";
        if (code == 82) return "\uD83C\uDF27\uFE0F رگبار شدید";
        if (code == 85 || code == 86) return "\u2744\uFE0F رگبار برف";
        if (code == 95) return "\u26C8\uFE0F رعد و برق";
        if (code == 96 || code == 99) return "\u26C8\uFE0F توفان تندری با تگرگ";
        return "\uD83C\uDF24 وضعیت نامشخص";
    }

    private static String faNum(String s) {
        try {
            return DrGramJalaliDate.faDigits(s);
        } catch (Throwable t) {
            return s;
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }
}
