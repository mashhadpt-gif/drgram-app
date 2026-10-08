/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramThemeInstaller — guarantees the approved black-gold luxury look is the
 * shipped default AND that it stays applied across restarts, system dark-mode
 * changes and app updates.
 *
 * Fix 10 (theme-revert bug root causes):
 *  1) The previous build applied the theme exactly once with a one-shot flag and
 *     Theme.applyThemeFile copied the theme file ONTO ITSELF (same target path),
 *     truncating it to 0 bytes — so the installed theme was invalid and Telegram's
 *     own theme system fell back to the stock theme on the next launch.
 *  2) On Android 10+ Telegram defaults "auto night theme" to "follow system",
 *     which silently switched to the STOCK night theme (and on day-switch, to the
 *     stock default theme) because the branded file theme is dark.
 *  3) The theme was never persisted as the night theme, so any day/night event
 *     escaped the brand palette.
 * Now: the asset is copied to a TEMP file first (no self-copy), the theme is
 * re-asserted on every app start (self-healing, cheap when healthy), persisted as
 * BOTH the day and the night theme, and Telegram's auto night switching is turned
 * off so a stock theme can never replace the brand look.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class DrGramThemeInstaller {

    public static final String GOLD_THEME_NAME = "DrGram Gold";
    public static final String RED_THEME_NAME = "DrGram Red";

    // Theme.applyThemeFile normalizes the theme name by appending ".attheme",
    // and ThemeInfo.getKey() returns that normalized name — so this is the key
    // stored in the "theme"/"nighttheme" preferences.
    public static final String GOLD_KEY = GOLD_THEME_NAME + ".attheme";
    public static final String RED_KEY = RED_THEME_NAME + ".attheme";

    private static volatile boolean tried = false;

    public static void onAppReady() {
        if (tried) {
            return;
        }
        tried = true;
        ensureDrGramTheme();
    }

    /**
     * Re-asserts the branded theme if the active theme is not one of ours or its
     * file is missing/corrupt. Cheap when already healthy: a string compare and a
     * file size check. Runs on every app start — self-healing after reinstalls,
     * upgrades, or if the user somehow ends up on a stock theme.
     */
    public static void ensureDrGramTheme() {
        try {
            ensureThemeAsset(false);
            ensureThemeAsset(true);
            // Keep the currently selected DrGram brand mode. Do not bind theme
            // switching to Android's uiMode and never fall back to Telegram stock.
            setLoveMode(isLoveMode());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** True when the manually selected LOVE Mode (black + romantic red) is active. */
    public static boolean isLoveMode() {
        try {
            SharedPreferences settings = MessagesController.getGlobalMainSettings();
            String saved = settings.getString("drgram_brand_mode", null);
            if ("love".equals(saved)) {
                return true;
            }
            if ("gold".equals(saved)) {
                return false;
            }
            // One-time migration from the prior DrGram build's persisted switch.
            boolean legacyLove = SharedConfig.drgramThemeRed;
            settings.edit().putString("drgram_brand_mode", legacyLove ? "love" : "gold").apply();
            return legacyLove;
        } catch (Throwable t) {
            FileLog.e(t);
            return SharedConfig.drgramThemeRed;
        }
    }

    /**
     * Selects one branded palette manually. Both Telegram theme slots are pinned
     * to the same DrGram theme and auto-night switching is disabled, so changing
     * Android's system light/dark setting can never activate the stock theme.
     */
    public static boolean setLoveMode(boolean loveMode) {
        try {
            if (!ensureThemeAsset(false) || !ensureThemeAsset(true)) {
                return false;
            }
            String key = loveMode ? RED_KEY : GOLD_KEY;
            Theme.ThemeInfo selected = Theme.getTheme(key);
            if (selected == null) {
                return false;
            }
            SharedPreferences settings = MessagesController.getGlobalMainSettings();
            settings.edit()
                    .putString("drgram_brand_mode", loveMode ? "love" : "gold")
                    .putString("theme", key)
                    .putString("nighttheme", key)
                    .putInt("selectedAutoNightType", Theme.AUTO_NIGHT_TYPE_NONE)
                    .apply();

            SharedConfig.setDrgramThemeRed(loveMode);
            Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
            Theme.cancelAutoNightThemeCallbacks();
            Theme.setCurrentNightTheme(selected);
            Theme.applyTheme(selected, true, loveMode);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static boolean ensureThemeAsset(boolean red) {
        try {
            String name = red ? RED_THEME_NAME : GOLD_THEME_NAME;
            String asset = red ? "drgram_red.attheme" : "drgram_gold.attheme";
            String key = red ? RED_KEY : GOLD_KEY;

            Theme.ThemeInfo existing = Theme.getTheme(key);
            if (existing != null && existing.pathToFile != null) {
                File installed = new File(existing.pathToFile);
                if (installed.exists() && installed.length() > 1024) {
                    return true;
                }
            }

            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return false;
            }

            File tmp = new File(ApplicationLoader.getFilesDirFixed(), name + ".install.tmp");
            if (!copyAsset(ctx, asset, tmp)) {
                return false;
            }
            Theme.ThemeInfo applied = Theme.applyThemeFile(tmp, name, (TLRPC.TL_theme) null, false);
            try {
                tmp.delete();
            } catch (Throwable ignore) {
            }
            return applied != null;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    /**
     * Applies the bundled luxury theme file. red = true → DrGram Red, false → DrGram Gold.
     */
    public static boolean applyVariant(boolean red) {
        return setLoveMode(red);
    }

    /**
     * Fix 10: persist the branded theme as BOTH the day and the night theme and
     * disable Telegram's "auto night theme (follow system)", so a stock theme can
     * never silently replace the brand look on restart or on a dark-mode flip.
     */
    private static void pinBrandedTheme(Theme.ThemeInfo info) {
        try {
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            SharedPreferences.Editor editor = prefs.edit();
            if (!info.getKey().equals(prefs.getString("theme", null))) {
                editor.putString("theme", info.getKey());
            }
            if (!info.getKey().equals(prefs.getString("nighttheme", null))) {
                editor.putString("nighttheme", info.getKey());
            }
            if (prefs.getInt("selectedAutoNightType", -1) != Theme.AUTO_NIGHT_TYPE_NONE) {
                editor.putInt("selectedAutoNightType", Theme.AUTO_NIGHT_TYPE_NONE);
            }
            editor.apply();

            // in-memory sync: keeps the current night theme pointing at the brand
            // theme so day/night events become visual no-ops
            Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
            Theme.cancelAutoNightThemeCallbacks();
            if (Theme.getActiveTheme() == info) {
                Theme.setCurrentNightTheme(info);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Current luxury accent color for the dialogs title & chrome accents.
     */
    public static int accentColor() {
        return isLoveMode() ? 0xFFE63B52 : 0xFFF5B90D;
    }

    private static boolean copyAsset(Context ctx, String asset, File out) {
        InputStream in = null;
        OutputStream os = null;
        try {
            in = ctx.getAssets().open(asset);
            os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
            try {
                if (os != null) os.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
