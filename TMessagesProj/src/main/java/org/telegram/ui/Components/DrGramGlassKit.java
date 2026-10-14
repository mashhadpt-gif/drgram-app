/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 36: DrGramGlassKit — the shared glassmorphism design system that
 * Fix 34 introduced on the Tools page, extracted so EVERY LuxGram page
 * (control center, style gallery and every sub-page) renders the same
 * minimal, translucent, luxury look.
 *
 *   pageGradient  — calm onyx canvas (Gold / LOVE aware)
 *   panel         — one translucent glass panel with a hairline accent stroke
 *   heroPanel     — slightly brighter glass for page heroes
 *   rowRipple     — quiet white bloom touch feedback
 *   applyDividers — hairline row separators inside a panel
 */
package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DrGramThemeInstaller;

public final class DrGramGlassKit {

    public static final int ROW_TITLE = 0xFFF1EADA;
    public static final int GOLD_HINT = 0x8FC7BA93;
    public static final int LOVE_HINT = 0x8FD8A8B4;

    private DrGramGlassKit() {
    }

    public static boolean love() {
        return DrGramThemeInstaller.isLoveMode();
    }

    public static int accent() {
        return DrGramThemeInstaller.accentColor();
    }

    public static int hint() {
        return love() ? LOVE_HINT : GOLD_HINT;
    }

    /** The calm onyx canvas every LuxGram page shares. */
    public static GradientDrawable pageGradient() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                love()
                        ? new int[] {0xFF0E070B, 0xFF1C0D13, 0xFF0E070B}
                        : new int[] {0xFF0B0A07, 0xFF191409, 0xFF0B0A07});
    }

    /** Translucent glass panel with a hairline accent stroke. */
    public static GradientDrawable panel(Context ctx, float radiusDp) {
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(AndroidUtilities.dp(radiusDp));
        panel.setColor(0x12FFFFFF);
        panel.setStroke(AndroidUtilities.dp(1), love() ? 0x30F06A82 : 0x2EF4D27A);
        return panel;
    }

    /** Brighter glass for page heroes. */
    public static GradientDrawable heroPanel(Context ctx) {
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(AndroidUtilities.dp(24));
        panel.setColor(0x16FFFFFF);
        panel.setStroke(AndroidUtilities.dp(1), love() ? 0x4DF06A82 : 0x4DF4D27A);
        return panel;
    }

    /** Quiet rounded touch feedback — no gold glare. */
    public static Drawable rowRipple() {
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(AndroidUtilities.dp(12));
            mask.setColor(0xFFFFFFFF);
            return new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x24FFFFFF), null, mask);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hairline separators inset to the row padding. */
    public static void applyDividers(LinearLayout list) {
        list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        list.setDividerDrawable(new InsetDrawable(
                new ColorDrawable(0x12FFFFFF),
                AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0));
    }

    /** Section label — small, accent, letter-spaced (the Fix 34 rhythm). */
    public static TextView header(Context ctx, String title, int topDp) {
        TextView header = new TextView(ctx);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.04f);
        header.setTextColor(accent());
        header.setText("❖  " + title);
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(topDp <= 0 ? 24 : topDp),
                AndroidUtilities.dp(21), AndroidUtilities.dp(3));
        return header;
    }

    /** Muted section hint. */
    public static TextView hint(Context ctx, String text) {
        TextView hintView = new TextView(ctx);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        hintView.setTextColor(hint());
        hintView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        hintView.setText(text == null ? "" : text);
        return hintView;
    }
}
