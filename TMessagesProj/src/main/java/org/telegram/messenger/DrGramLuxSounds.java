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
     * Returns the resource URI of the brand chime for the active theme, or null
     * when the override is off (stock sound stays).
     */
    public static String channelSound() {
        if (!isEnabled()) {
            return null;
        }
        try {
            int res = DrGramThemeInstaller.isLoveMode()
                    ? R.raw.drgram_notif_love
                    : R.raw.drgram_notif_gold;
            return "android.resource://" + ApplicationLoader.getApplicationId() + "/" + res;
        } catch (Throwable t) {
            return null;
        }
    }
}
