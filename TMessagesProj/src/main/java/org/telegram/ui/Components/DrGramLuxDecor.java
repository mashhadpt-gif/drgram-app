/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramLuxDecor — shared luxury/romance decoration helpers (DrGram Fix 24).
 * Every helper is defensive: decoration must never crash a page.
 */
package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

public class DrGramLuxDecor {

    /** Thin fading line — ornament — thin fading line, centered. */
    public static View ornamentDivider(Context ctx, boolean love) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(6), AndroidUtilities.dp(24), AndroidUtilities.dp(6));

        int lineColor = love ? 0x59F06A82 : 0x59F4D27A;
        int glyphColor = love ? 0xFFFFA4B5 : 0xFFFFE6A0;

        View line1 = new View(ctx);
        GradientDrawable g1 = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x00000000, lineColor});
        line1.setBackgroundDrawable(g1);
        box.addView(line1, new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(1)));

        TextView glyph = new TextView(ctx);
        glyph.setText(love ? "♥" : "✦");
        glyph.setTextColor(glyphColor);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        glyph.setGravity(Gravity.CENTER);
        glyph.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        box.addView(glyph, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        View line2 = new View(ctx);
        GradientDrawable g2 = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[] {lineColor, 0x00000000});
        line2.setBackgroundDrawable(g2);
        box.addView(line2, new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(1)));
        return box;
    }

    /** Accent-tinted rounded ripple so every luxury row answers the touch. */
    public static void polishRow(View row, boolean love) {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return; // View.setForeground requires API 23+
            }
            int ripple = love ? 0x30E63B52 : 0x2AF5B90D;
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(AndroidUtilities.dp(17));
            mask.setColor(0xFFFFFFFF);
            row.setForeground(new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ripple), null, mask));
        } catch (Throwable ignore) {
        }
    }

    /** Brand ornament footer closing the page. */
    public static View signatureFooter(Context ctx, boolean love) {
        TextView v = new TextView(ctx);
        v.setText(love ? "♥ ─── DRGRAM · LOVE EDITION ─── ♥" : "✦ ─── DRGRAM · GOLD EDITION ─── ✦");
        v.setTextColor(love ? 0x8CF06A82 : 0x8CF4D27A);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        v.setGravity(Gravity.CENTER);
        v.setLetterSpacing(0.08f);
        v.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        return v;
    }

    /** Adds a faint outer frame around the hero card (double-stroke jewelry look). */
    public static void frameHero(View hero, boolean love) {
        try {
            Drawable base = hero.getBackground();
            if (base == null) {
                return;
            }
            GradientDrawable outer = new GradientDrawable();
            outer.setCornerRadius(AndroidUtilities.dp(28));
            outer.setStroke(AndroidUtilities.dp(1), love ? 0x40E63B52 : 0x40F5B90D);
            outer.setColor(0x00000000);
            LayerDrawable layers = new LayerDrawable(new Drawable[] {base, outer});
            layers.setLayerInset(1,
                    -AndroidUtilities.dp(4), -AndroidUtilities.dp(4),
                    -AndroidUtilities.dp(4), -AndroidUtilities.dp(4));
            hero.setBackgroundDrawable(layers);
        } catch (Throwable ignore) {
        }
    }
}
