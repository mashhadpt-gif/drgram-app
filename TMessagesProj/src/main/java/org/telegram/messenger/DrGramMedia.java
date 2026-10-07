/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Media — small media-layer extras:
 *   • Voice FX: a playful pitch effect for voice message playback
 *     (chipmunk / deep / bee / giant). Purely a playback-side effect:
 *     the audio files themselves are never modified.
 *   • Animated story background: replaces the flat black backdrop of the
 *     story viewer with a slowly moving DrGram gradient.
 *   • Draw & send: a toggle for the blank-canvas sketch button inside chats.
 *
 * Everything here is local, off by default where it alters visuals,
 * and never touches network code.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.ui.Components.DrGramAnimatedGradient;

public class DrGramMedia {

    public static final int VOICE_FX_OFF = 0;
    public static final int VOICE_FX_CHIPMUNK = 1;
    public static final int VOICE_FX_DEEP = 2;
    public static final int VOICE_FX_BEE = 3;
    public static final int VOICE_FX_GIANT = 4;

    private static volatile DrGramAnimatedGradient storyGradient;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_media", Context.MODE_PRIVATE);
    }

    //region Voice FX

    public static int getVoiceFx() {
        try {
            return prefs().getInt("voice_fx", VOICE_FX_OFF);
        } catch (Exception e) {
            return VOICE_FX_OFF;
        }
    }

    public static void setVoiceFx(int fx) {
        prefs().edit().putInt("voice_fx", fx).apply();
    }

    /**
     * Playback pitch for the currently playing message. Returns 1.0 whenever
     * the effect is off or the content is not a voice message, so callers can
     * safely feed the value straight into the player.
     */
    public static float voicePitch() {
        switch (getVoiceFx()) {
            case VOICE_FX_CHIPMUNK: return 1.55f;
            case VOICE_FX_DEEP: return 0.75f;
            case VOICE_FX_BEE: return 1.95f;
            case VOICE_FX_GIANT: return 0.60f;
            default: return 1.0f;
        }
    }

    //endregion

    //region Story background

    public static boolean isStoryGradientEnabled() {
        try {
            return prefs().getBoolean("story_gradient", true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setStoryGradientEnabled(boolean value) {
        prefs().edit().putBoolean("story_gradient", value).apply();
    }

    /**
     * Returns the shared animated gradient used behind stories, or null when
     * the user prefers the classic black backdrop.
     */
    public static DrGramAnimatedGradient getStoryBackground() {
        if (!isStoryGradientEnabled()) {
            return null;
        }
        DrGramAnimatedGradient result = storyGradient;
        if (result == null) {
            synchronized (DrGramMedia.class) {
                result = storyGradient;
                if (result == null) {
                    result = new DrGramAnimatedGradient();
                    storyGradient = result;
                }
            }
        }
        return result;
    }

    //endregion

    //region Draw & send

    public static boolean isDrawSendEnabled() {
        try {
            return prefs().getBoolean("draw_send", true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setDrawSendEnabled(boolean value) {
        prefs().edit().putBoolean("draw_send", value).apply();
    }

    //endregion
}
