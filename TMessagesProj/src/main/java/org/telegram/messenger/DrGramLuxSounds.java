/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Lux Sounds (Fix 37) — a bundled luxury notification chime that rides
 * on the brand theme: a bright gold bell arpeggio for Gold Luxe and a warm
 * glass-harp sigh for LOVE mode. Applied wherever the user has not chosen a
 * personal sound (system default still selected), so custom per-chat sounds
 * always win.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class DrGramLuxSounds {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_media", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return prefs().getBoolean("lux_notif_sounds", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean("lux_notif_sounds", value).apply();
    }

    /**
     * Returns the sound URI used for brand notifications: the user's own pick
     * for the active theme (Fix 38) when set, otherwise the bundled chime, or
     * null when the override is off (stock sound stays).
     */
    public static String channelSound() {
        if (!isEnabled()) {
            return null;
        }
        try {
            String custom = DrGramThemeInstaller.isLoveMode() ? customLoveSound() : customGoldSound();
            if (custom != null && new java.io.File(custom).exists()) {
                return custom;
            }
            int res = DrGramThemeInstaller.isLoveMode()
                    ? R.raw.drgram_notif_love
                    : R.raw.drgram_notif_gold;
            return "android.resource://" + ApplicationLoader.getApplicationId() + "/" + res;
        } catch (Throwable t) {
            return null;
        }
    }

    //region Fix 38 — personal chime per theme

    /** File path of the user's own LOVE chime, null when untouched. */
    public static String customLoveSound() {
        try {
            String p = prefs().getString("lux_notif_love_path", null);
            return p != null && new java.io.File(p).exists() ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** File path of the user's own GOLD chime, null when untouched. */
    public static String customGoldSound() {
        try {
            String p = prefs().getString("lux_notif_gold_path", null);
            return p != null && new java.io.File(p).exists() ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setCustomSound(boolean love, String path) {
        prefs().edit().putString(love ? "lux_notif_love_path" : "lux_notif_gold_path", path).apply();
    }

    /** Copies a picked audio Uri into the app cache so the grant survives reboots. */
    public static String importSound(Context ctx, Uri uri) {
        try {
            java.io.File dir = new java.io.File(ApplicationLoader.applicationContext.getCacheDir(), "drgram_sounds");
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, love_or_gold_name());
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            try {
                in.close();
            } catch (Throwable ignore) {}
            fos.close();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String love_or_gold_name() {
        return "lux_chime_" + System.currentTimeMillis() + ".audio";
    }

    //endregion
}
