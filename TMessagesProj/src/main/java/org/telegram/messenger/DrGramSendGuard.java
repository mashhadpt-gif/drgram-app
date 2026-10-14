/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Send Guard (Fix 39) — confirmation before sending accidental-tap
 * content: GIFs, stickers and voice messages.
 *
 *   • Voice: the recorder no longer sends the take the moment the finger
 *     lifts. Releasing now PAUSES the take and shows Telegram's native
 *     recorded-audio panel, which already contains a play button — the user
 *     listens to the take, then taps send (confirm) or trash (discard).
 *     Release paths that used to silently drop the take (small finger drift
 *     or a stolen touch) now land on the same preview panel, so a take can
 *     only be lost on purpose (full slide-to-cancel or the trash button).
 *   • GIF / sticker: a compact dialog with the item's thumbnail asks
 *     "ارسال شود؟" before anything leaves the chat.
 *
 * All three gates have their own switch in Tools (Media section) and default
 * to ON, because the whole point is protection against accidental sends.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

public class DrGramSendGuard {

    private static final String PREF_VOICE = "guard_voice";
    private static final String PREF_STICKER = "guard_sticker";
    private static final String PREF_GIF = "guard_gif";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_send_guard", Context.MODE_PRIVATE);
    }

    public static boolean voiceConfirmEnabled() {
        try {
            return prefs().getBoolean(PREF_VOICE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setVoiceConfirmEnabled(boolean value) {
        try {
            prefs().edit().putBoolean(PREF_VOICE, value).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean stickerConfirmEnabled() {
        try {
            return prefs().getBoolean(PREF_STICKER, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setStickerConfirmEnabled(boolean value) {
        try {
            prefs().edit().putBoolean(PREF_STICKER, value).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean gifConfirmEnabled() {
        try {
            return prefs().getBoolean(PREF_GIF, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setGifConfirmEnabled(boolean value) {
        try {
            prefs().edit().putBoolean(PREF_GIF, value).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Shows the send-confirmation dialog for a sticker or GIF document.
     * When the matching gate is off (or anything fails), the runnable runs
     * immediately — never block a send because of a UI problem.
     */
    public static void confirmDocument(Context ctx, Theme.ResourcesProvider resourcesProvider,
                                       TLRPC.Document document, boolean gif, Runnable onSend) {
        try {
            if (ctx == null || document == null || onSend == null) {
                if (onSend != null) {
                    onSend.run();
                }
                return;
            }
            boolean enabled = gif ? gifConfirmEnabled() : stickerConfirmEnabled();
            if (!enabled) {
                onSend.run();
                return;
            }

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = AndroidUtilities.dp(20);
            container.setPadding(pad, pad, pad, AndroidUtilities.dp(4));

            BackupImageView preview = new BackupImageView(ctx);
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 180);
            boolean hasImage = false;
            if (thumb != null) {
                try {
                    preview.setImage(ImageLocation.getForDocument(thumb, document), "180_180", (Drawable) null, null);
                    hasImage = true;
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
            if (hasImage) {
                int size = AndroidUtilities.dp(160);
                FrameLayout frame = new FrameLayout(ctx);
                frame.addView(preview, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = AndroidUtilities.dp(10);
                container.addView(frame, lp);
            }

            TextView label = new TextView(ctx);
            String name = FileLoader.getDocumentFileName(document);
            String body;
            if (gif) {
                body = LocaleController.getString(R.string.DrGramSgGifAsk);
            } else {
                body = LocaleController.getString(R.string.DrGramSgStickerAsk);
            }
            label.setText(body + (name != null && name.length() > 0 && !gif ? "\n" + name : ""));
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            label.setLineSpacing(AndroidUtilities.dp(2), 1f);
            container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            new AlertDialog.Builder(ctx, resourcesProvider)
                    .setTitle(LocaleController.getString(gif ? R.string.DrGramSgGifTitle : R.string.DrGramSgStickerTitle))
                    .setView(container)
                    .setPositiveButton(LocaleController.getString(R.string.DrGramSgSend), (d, w) -> onSend.run())
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()
                    .show();
        } catch (Throwable t) {
            FileLog.e(t);
            if (onSend != null) {
                onSend.run();
            }
        }
    }
}
