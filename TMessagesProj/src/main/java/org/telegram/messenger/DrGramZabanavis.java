/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Zabanavis (زیانویس — Fix 38): message annotation with beautiful,
 * Persian-safe styles. The old-school "fancy font" tricks use Unicode
 * combining strikethrough characters (U+0335/U+0336) which render as broken
 * double lines over RTL glyphs, and Mathematical Alphanumeric blocks which
 * have no Persian mapping at all. This implementation avoids both:
 *
 *   • entity styles  → Telegram-native formatting (bold/italic/strike/
 *     underline/code/spoiler) — shaped perfectly by the platform text stack
 *   • ornament styles → decorative glyph frames (✦ ﴿ ﴾ ★ ✿ ❥ ⋆ ┊ ılılı)
 *     wrapped around the untouched original text
 *
 * Own messages are edited in place; anyone else's message is transformed to
 * the clipboard so it can be restyled-forwarded.
 */
package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class DrGramZabanavis {

    public static final int STYLE_NORMAL = 0;      // حالت معمولی — strip entities
    public static final int STYLE_BOLD = 1;        // متن برجسته
    public static final int STYLE_ITALIC = 2;      // متن کج
    public static final int STYLE_STRIKE = 3;      // متن خط‌خورده
    public static final int STYLE_UNDERLINE = 4;   // متن زیرخط
    public static final int STYLE_ORN_1 = 5;       // ✦ متن ✦
    public static final int STYLE_ORN_2 = 6;       // ﴿ متن ﴾
    public static final int STYLE_ORN_3 = 7;       // ★·.·´¯`·.·★
    public static final int STYLE_ORN_4 = 8;       // ✿ متن ✿
    public static final int STYLE_BOLD_ITALIC = 9; // برجسته + کج
    public static final int STYLE_BOLD_UNDER = 10; // برجسته + زیرخط
    public static final int STYLE_MONO = 11;       // کد / مونو
    public static final int STYLE_STRIKE_BOLD = 12;// خط‌خورده + برجسته
    public static final int STYLE_SPOILER = 13;    // محو

    private static final String ORN_1_A = "✦ ", ORN_1_B = " ✦";
    private static final String ORN_2_A = "﴿ ", ORN_2_B = " ﴾";
    private static final String ORN_3 = "★·.·´¯`·.·★ ";
    private static final String ORN_4_A = "✿ ", ORN_4_B = " ✿";

    private static int[] styleIds() {
        return new int[]{
                STYLE_NORMAL, STYLE_BOLD, STYLE_ITALIC, STYLE_STRIKE, STYLE_UNDERLINE,
                STYLE_ORN_1, STYLE_ORN_2, STYLE_ORN_3, STYLE_ORN_4,
                STYLE_BOLD_ITALIC, STYLE_BOLD_UNDER, STYLE_MONO, STYLE_STRIKE_BOLD, STYLE_SPOILER
        };
    }

    public static String[] styleNames() {
        int[] ids = styleIds();
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            names[i] = styleName(ids[i]);
        }
        return names;
    }

    private static String styleName(int style) {
        switch (style) {
            case STYLE_NORMAL: return LocaleController.getString(R.string.DrGramZabNormal);
            case STYLE_BOLD: return LocaleController.getString(R.string.DrGramZabBold);
            case STYLE_ITALIC: return LocaleController.getString(R.string.DrGramZabItalic);
            case STYLE_STRIKE: return LocaleController.getString(R.string.DrGramZabStrike);
            case STYLE_UNDERLINE: return LocaleController.getString(R.string.DrGramZabUnderline);
            case STYLE_ORN_1: return LocaleController.getString(R.string.DrGramZabOne);
            case STYLE_ORN_2: return LocaleController.getString(R.string.DrGramZabTwo);
            case STYLE_ORN_3: return LocaleController.getString(R.string.DrGramZabThree);
            case STYLE_ORN_4: return LocaleController.getString(R.string.DrGramZabFour);
            case STYLE_BOLD_ITALIC: return LocaleController.getString(R.string.DrGramZabFive);
            case STYLE_BOLD_UNDER: return LocaleController.getString(R.string.DrGramZabSix);
            case STYLE_MONO: return LocaleController.getString(R.string.DrGramZabSeven);
            case STYLE_STRIKE_BOLD: return LocaleController.getString(R.string.DrGramZabEight);
            case STYLE_SPOILER: return LocaleController.getString(R.string.DrGramZabNine);
            default: return LocaleController.getString(R.string.DrGramZabNormal);
        }
    }

    /** Shows the style picker exactly like the classic زیانویس radio dialog. */
    public static void showPicker(BaseFragment fragment, final int styleInUse, final Listener listener) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context ctx = fragment.getParentActivity();
        String[] names = styleNames();
        final int[] ids = styleIds();

        // Telegram's AlertDialog has no setSingleChoiceItems — build the radio
        // rows by hand so the picker looks like the classic style dialog
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(list);
        final int[] pickedHolder = {-1};
        for (int i = 0; i < ids.length; i++) {
            final int style = ids[i];
            android.widget.RadioButton rb = new android.widget.RadioButton(ctx);
            rb.setText(names[i]);
            rb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            rb.setChecked(ids[i] == styleInUse);
            rb.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(11), AndroidUtilities.dp(16), AndroidUtilities.dp(11));
            int accent = accentColor();
            rb.setHighlightColor(0x00000000);
            try {
                android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{accent, 0x66888888});
                rb.setButtonTintList(csl);
            } catch (Throwable ignore) {}
            rb.setOnClickListener(v -> {
                DrGramHaptics.tick(v);
                pickedHolder[0] = style;
            });
            list.addView(rb, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        AlertDialog dialog = new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DrGramZabanavisTitle))
                .setView(scroll)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    if (listener != null && pickedHolder[0] >= 0) {
                        listener.onStylePicked(pickedHolder[0]);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
    }

    public interface Listener {
        void onStylePicked(int style);
    }

    /**
     * Applies a style to the raw message text.
     *
     * @param raw      the plain message text (messageOwner.message)
     * @param entities the message's current entities (may be null)
     * @return a Result with the new text and the replacement entity list
     *         (null entities = send as plain text)
     */
    public static Result apply(int style, String raw, ArrayList<TLRPC.MessageEntity> entities) {
        Result r = new Result();
        if (raw == null) {
            raw = "";
        }
        String text = raw;
        ArrayList<TLRPC.MessageEntity> out = null;
        switch (style) {
            case STYLE_NORMAL:
                out = null; // plain text, no entities
                break;
            case STYLE_BOLD:
                out = single(new TLRPC.TL_messageEntityBold(), text.length());
                break;
            case STYLE_ITALIC:
                out = single(new TLRPC.TL_messageEntityItalic(), text.length());
                break;
            case STYLE_STRIKE:
                out = single(new TLRPC.TL_messageEntityStrike(), text.length());
                break;
            case STYLE_UNDERLINE:
                out = single(new TLRPC.TL_messageEntityUnderline(), text.length());
                break;
            case STYLE_MONO:
                out = single(new TLRPC.TL_messageEntityCode(), text.length());
                break;
            case STYLE_SPOILER:
                out = single(new TLRPC.TL_messageEntitySpoiler(), text.length());
                break;
            case STYLE_BOLD_ITALIC:
                out = pair(new TLRPC.TL_messageEntityBold(), new TLRPC.TL_messageEntityItalic(), text.length());
                break;
            case STYLE_BOLD_UNDER:
                out = pair(new TLRPC.TL_messageEntityBold(), new TLRPC.TL_messageEntityUnderline(), text.length());
                break;
            case STYLE_STRIKE_BOLD:
                out = pair(new TLRPC.TL_messageEntityStrike(), new TLRPC.TL_messageEntityBold(), text.length());
                break;
            case STYLE_ORN_1:
                text = ORN_1_A + raw.trim() + ORN_1_B;
                break;
            case STYLE_ORN_2:
                text = ORN_2_A + raw.trim() + ORN_2_B;
                break;
            case STYLE_ORN_3:
                text = ORN_3 + raw.trim() + "\n" + ORN_3;
                break;
            case STYLE_ORN_4:
                text = ORN_4_A + raw.trim() + ORN_4_B;
                break;
            default:
                break;
        }
        r.text = text;
        r.entities = out;
        return r;
    }

    public static class Result {
        public String text;
        public ArrayList<TLRPC.MessageEntity> entities; // null = plain
    }

    private static ArrayList<TLRPC.MessageEntity> single(TLRPC.MessageEntity e, int len) {
        e.offset = 0;
        e.length = Math.max(0, len);
        ArrayList<TLRPC.MessageEntity> list = new ArrayList<>();
        list.add(e);
        return list;
    }

    private static ArrayList<TLRPC.MessageEntity> pair(TLRPC.MessageEntity a, TLRPC.MessageEntity b, int len) {
        a.offset = 0;
        a.length = Math.max(0, len);
        b.offset = 0;
        b.length = Math.max(0, len);
        ArrayList<TLRPC.MessageEntity> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        return list;
    }

    /**
     * Convenience: transform + copy a foreign message so it can be restyled-forwarded.
     * Returns the transformed text, or null when clipboard is unavailable.
     */
    public static String copyStyled(Context ctx, int style, String raw, ArrayList<TLRPC.MessageEntity> entities) {
        Result r = apply(style, raw, entities);
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("message", r.text));
            return r.text;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Theme-aware accent for the picker preview chips, kept simple. */
    public static int accentColor() {
        return Theme.getColor(Theme.key_chats_actionBackground);
    }
}
