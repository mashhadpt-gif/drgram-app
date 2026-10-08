/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramThemeInstaller — guarantees the approved black-gold luxury look is the
 * shipped default. On first launch (and after upgrades that reset it) it imports
 * the bundled drgram_gold.attheme / drgram_red.attheme as real file-based themes,
 * so every color key in the app comes from one complete, hand-tuned palette
 * instead of partial in-code overrides.
 */
package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class DrGramThemeInstaller {

    public static final String GOLD_THEME_NAME = "DrGram Gold";
    public static final String RED_THEME_NAME = "DrGram Red";

    private static volatile boolean tried = false;

    public static void onAppReady() {
        if (tried) {
            return;
        }
        tried = true;
        try {
            if (!SharedConfig.drgramThemeApplied) {
                applyVariant(SharedConfig.drgramThemeRed);
                SharedConfig.setDrgramThemeApplied(true);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Applies the bundled luxury theme file. red = true → DrGram Red, false → DrGram Gold.
     */
    public static boolean applyVariant(boolean red) {
        try {
            String name = red ? RED_THEME_NAME : GOLD_THEME_NAME;
            String asset = red ? "drgram_red.attheme" : "drgram_gold.attheme";
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return false;
            }
            File out = new File(ApplicationLoader.getFilesDirFixed(), name + ".attheme");
            if (!copyAsset(ctx, asset, out)) {
                return false;
            }
            Theme.ThemeInfo info = Theme.getTheme(name);
            if (info != null && info.pathToFile != null && info.pathToFile.equals(out.getAbsolutePath())) {
                // already installed — just re-apply to refresh colors after an app update
                Theme.applyTheme(info, true, true, false);
                return true;
            }
            Theme.ThemeInfo applied = Theme.applyThemeFile(out, name, (TLRPC.TL_theme) null, false);
            return applied != null;
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return false;
    }

    /**
     * Current luxury accent color for the dialogs title & chrome accents.
     */
    public static int accentColor() {
        return SharedConfig.drgramThemeRed ? 0xFFE63B52 : 0xFFF5B90D;
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
