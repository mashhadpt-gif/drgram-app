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

    // DrGram Fix 40: camera beautify (صاف کردن صورت) for stories & video messages

    /** True when the camera skin-smoothing effect is enabled. */
    public static boolean isBeautifyEnabled() {
        try {
            return prefs().getBoolean("camera_beautify", true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setBeautifyEnabled(boolean value) {
        prefs().edit().putBoolean("camera_beautify", value).apply();
    }

    /** Effect intensity 0..1, persisted separately. */
    public static float beautifyStrength() {
        try {
            float v = prefs().getFloat("camera_beautify_strength", 0.55f);
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            return v;
        } catch (Exception e) {
            return 0.55f;
        }
    }

    public static void setBeautifyStrength(float value) {
        prefs().edit().putFloat("camera_beautify_strength", value).apply();
    }

    /** The shader uniform value actually fed to the camera pipeline (0 = off). */
    public static float beautifyUniform() {
        return isBeautifyEnabled() ? beautifyStrength() : 0f;
    }

    // DrGram Fix 41: full beauty suite (nose slimming + teeth whitening + camera FX)

    /** Nose slimming intensity 0..1. */
    public static float beautifyNose() {
        try {
            float v = prefs().getFloat("camera_beautify_nose", 0.5f);
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            return v;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    public static void setBeautifyNose(float value) {
        prefs().edit().putFloat("camera_beautify_nose", value).apply();
    }

    /** Teeth whitening intensity 0..1. */
    public static float beautifyTeeth() {
        try {
            float v = prefs().getFloat("camera_beautify_teeth", 0.6f);
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            return v;
        } catch (Exception e) {
            return 0.6f;
        }
    }

    public static void setBeautifyTeeth(float value) {
        prefs().edit().putFloat("camera_beautify_teeth", value).apply();
    }

    /** Selected camera FX id (0 = none; 1..12 = effect pack). */
    public static int cameraFx() {
        try {
            return prefs().getInt("camera_fx", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setCameraFx(int id) {
        if (id < 0 || id > 12) id = 0;
        prefs().edit().putInt("camera_fx", id).apply();
    }

    /** True when any camera processing is active (drives face detection startup). */
    public static boolean cameraBeautyActive() {
        return beautifyUniform() > 0.001f || beautifyNose() > 0.001f || beautifyTeeth() > 0.001f;
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
