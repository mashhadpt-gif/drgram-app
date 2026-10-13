/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 32: DrGramThemeTuner — runtime color tuning for the two branded
 * palettes. The bundled .attheme assets keep their original art; this class
 * rewrites accent-family colors (gold hues for Gold Luxe, red hues for LOVE
 * Luxe) according to the user's intensity slider before the theme file is
 * installed. Neutrals (low saturation) and other hue families (links, blue
 * chips...) are never touched, so only the "amount of gold / red" changes.
 *
 * Default intensity (50) renders the light-gold look the brand shipped with:
 * saturation x0.72 / brightness x1.07 for gold, and a gentle softening for red.
 */
package org.telegram.messenger;

import android.graphics.Color;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public final class DrGramThemeTuner {

    // Gold family: hue 28..62 (amber -> yellow-gold)
    private static final float GOLD_HUE_MIN = 28f;
    private static final float GOLD_HUE_MAX = 62f;
    // Red family: hue 330..360 plus 0..12 (crimson -> rose)
    private static final float RED_HUE_MIN = 330f;
    private static final float RED_HUE_MAX = 12f;
    // Below this saturation a color counts as neutral and is left alone.
    private static final float MIN_SATURATION = 0.20f;

    private DrGramThemeTuner() {}

    /**
     * Rewrites the accent-family colors of an .attheme file in place.
     * Returns true when the file was processed (even if nothing changed).
     */
    public static boolean tune(File themeFile, boolean red, int intensity) {
        try {
            StringBuilder out = new StringBuilder((int) themeFile.length() + 512);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(themeFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(tuneLine(line, red, intensity)).append('\n');
            }
            reader.close();
            FileOutputStream fos = new FileOutputStream(themeFile, false);
            fos.write(out.toString().getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static String tuneLine(String line, boolean red, int intensity) {
        int eq = line.indexOf('=');
        if (eq <= 0 || eq >= line.length() - 1) {
            return line;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        // The wallpaper entry is a file offset, not a color — never touch it.
        if (key.startsWith("wallpaper") || key.endsWith("FileOffset")) {
            return line;
        }
        int color;
        try {
            color = Integer.parseInt(value);
        } catch (Throwable ignore) {
            return line;
        }
        int tuned = tuneColor(color, red, intensity);
        if (tuned == color) {
            return line;
        }
        return key + "=" + tuned;
    }

    private static int tuneColor(int color, boolean red, int intensity) {
        int alpha = Color.alpha(color);
        if (alpha == 0) {
            return color;
        }
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv);
        float hue = hsv[0];
        float sat = hsv[1];
        if (sat < MIN_SATURATION) {
            return color;
        }
        boolean hit;
        if (red) {
            hit = hue >= RED_HUE_MIN || hue <= RED_HUE_MAX;
        } else {
            hit = hue >= GOLD_HUE_MIN && hue <= GOLD_HUE_MAX;
        }
        if (!hit) {
            return color;
        }
        // intensity 0..100, 50 = the shipped default
        float factor = (intensity - 50) / 50f;
        float satMul;
        float valMul;
        if (red) {
            // 0 -> soft rose ... 100 -> deep crimson
            satMul = 0.95f * (1f + 0.35f * factor);
            valMul = 1f - 0.25f * factor;
        } else {
            // 0 -> pale champagne ... 50 -> light gold (default) ... 100 -> deep amber
            satMul = 0.72f * (1f + 0.45f * factor);
            valMul = 1.07f * (1f - 0.22f * factor);
        }
        hsv[1] = clamp01(sat * satMul);
        hsv[2] = clamp01(hsv[2] * valMul);
        return (alpha << 24) | (Color.HSVToColor(hsv) & 0x00FFFFFF);
    }

    /**
     * Preview accent for the sliders' live swatch: what the classic brand
     * accent becomes at the given intensity, without touching any file.
     */
    public static int previewAccent(boolean red, int intensity) {
        return tuneColor(red ? 0xFFE63B52 : 0xFFF5B90D, red, intensity);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
