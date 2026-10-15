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
        prefs().edit().putFloat("camera_beautify_strength", clampBeauty(value)).apply();
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
        prefs().edit().putFloat("camera_beautify_nose", clampBeauty(value)).apply();
    }

    public static float beautifyNoseUniform() {
        return isBeautifyEnabled() ? beautifyNose() : 0f;
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
        prefs().edit().putFloat("camera_beautify_teeth", clampBeauty(value)).apply();
    }

    public static float beautifyTeethUniform() {
        return isBeautifyEnabled() ? beautifyTeeth() : 0f;
    }

    /** Conservative eye enlargement amount, applied only inside estimated eye regions. */
    public static float beautifyEyes() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_eyes", 0f));
        } catch (Exception e) {
            return 0f;
        }
    }

    public static void setBeautifyEyes(float value) {
        prefs().edit().putFloat("camera_beautify_eyes", clampBeauty(value)).apply();
    }

    public static float beautifyEyesUniform() {
        return isBeautifyEnabled() ? beautifyEyes() : 0f;
    }

    /** Lower-face contour slimming amount. Keep defaults at zero for natural results. */
    public static float beautifyJaw() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_jaw", 0f));
        } catch (Exception e) {
            return 0f;
        }
    }

    public static void setBeautifyJaw(float value) {
        prefs().edit().putFloat("camera_beautify_jaw", clampBeauty(value)).apply();
    }

    public static float beautifyJawUniform() {
        return isBeautifyEnabled() ? beautifyJaw() : 0f;
    }

    // DrGram Fix 46: standalone makeup suite — blush / lip tint / skin brightening
    public static float beautifyBlush() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_blush", 0f));
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setBeautifyBlush(float value) {
        prefs().edit().putFloat("camera_beautify_blush", clampBeauty(value)).apply();
    }

    public static float beautifyBlushUniform() {
        return isBeautifyEnabled() ? beautifyBlush() : 0f;
    }

    public static float beautifyLips() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_lips", 0f));
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setBeautifyLips(float value) {
        prefs().edit().putFloat("camera_beautify_lips", clampBeauty(value)).apply();
    }

    public static float beautifyLipsUniform() {
        return isBeautifyEnabled() ? beautifyLips() : 0f;
    }

    public static float beautifyBright() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_bright", 0f));
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setBeautifyBright(float value) {
        prefs().edit().putFloat("camera_beautify_bright", clampBeauty(value)).apply();
    }

    public static float beautifyBrightUniform() {
        return isBeautifyEnabled() ? beautifyBright() : 0f;
    }

    // DrGram Fix 47: under-eye brightening (dark circles)

    /** Dark-circle brightening intensity 0..1 (default off for a natural look). */
    public static float beautifyDark() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_dark", 0f));
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setBeautifyDark(float value) {
        prefs().edit().putFloat("camera_beautify_dark", clampBeauty(value)).apply();
    }

    public static float beautifyDarkUniform() {
        return isBeautifyEnabled() ? beautifyDark() : 0f;
    }

    // DrGram Fix 47: mid-face (cheek) slimming

    /** Cheek slimming intensity 0..1 (default off for a natural look). */
    public static float beautifySlim() {
        try {
            return clampBeauty(prefs().getFloat("camera_beautify_slim", 0f));
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void setBeautifySlim(float value) {
        prefs().edit().putFloat("camera_beautify_slim", clampBeauty(value)).apply();
    }

    public static float beautifySlimUniform() {
        return isBeautifyEnabled() ? beautifySlim() : 0f;
    }

    private static float clampBeauty(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    /** Selected camera FX id (0 = none; 1..20 = effect pack). */
    public static int cameraFx() {
        try {
            return prefs().getInt("camera_fx", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setCameraFx(int id) {
        if (id < 0 || id > 20) id = 0;
        prefs().edit().putInt("camera_fx", id).apply();
    }

    // DrGram Fix 45: portrait background blur for photo stories
    public static boolean storyPortraitBg() {
        try {
            return prefs().getBoolean("story_portrait_bg", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setStoryPortraitBg(boolean value) {
        prefs().edit().putBoolean("story_portrait_bg", value).apply();
    }

    // DrGram Fix 45: hands-free story recording (tap shutter to start/stop video)
    public static boolean storyHandsFree() {
        try {
            return prefs().getBoolean("story_hands_free", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setStoryHandsFree(boolean value) {
        prefs().edit().putBoolean("story_hands_free", value).apply();
    }

    /** Save the current camera look as the user's single custom preset. */
    public static void saveBeautyPreset() {
        prefs().edit()
                .putBoolean("preset_enabled", isBeautifyEnabled())
                .putFloat("preset_strength", beautifyStrength())
                .putFloat("preset_nose", beautifyNose())
                .putFloat("preset_teeth", beautifyTeeth())
                .putFloat("preset_eyes", beautifyEyes())
                .putFloat("preset_jaw", beautifyJaw())
                .putFloat("preset_blush", beautifyBlush())
                .putFloat("preset_lips", beautifyLips())
                .putFloat("preset_bright", beautifyBright())
                .putFloat("preset_dark", beautifyDark()) // DrGram Fix 47
                .putFloat("preset_slim", beautifySlim()) // DrGram Fix 47
                .putInt("preset_fx", cameraFx())
                .apply();
    }

    /** Apply the user's saved look. Returns false until a preset has been saved. */
    public static boolean applyBeautyPreset() {
        if (!prefs().getBoolean("preset_saved", false)) {
            return false;
        }
        prefs().edit()
                .putBoolean("camera_beautify", prefs().getBoolean("preset_enabled", true))
                .putFloat("camera_beautify_strength", clampBeauty(prefs().getFloat("preset_strength", 0.55f)))
                .putFloat("camera_beautify_nose", clampBeauty(prefs().getFloat("preset_nose", 0.5f)))
                .putFloat("camera_beautify_teeth", clampBeauty(prefs().getFloat("preset_teeth", 0.6f)))
                .putFloat("camera_beautify_eyes", clampBeauty(prefs().getFloat("preset_eyes", 0f)))
                .putFloat("camera_beautify_jaw", clampBeauty(prefs().getFloat("preset_jaw", 0f)))
                .putFloat("camera_beautify_blush", clampBeauty(prefs().getFloat("preset_blush", 0f)))
                .putFloat("camera_beautify_lips", clampBeauty(prefs().getFloat("preset_lips", 0f)))
                .putFloat("camera_beautify_bright", clampBeauty(prefs().getFloat("preset_bright", 0f)))
                .putFloat("camera_beautify_dark", clampBeauty(prefs().getFloat("preset_dark", 0f))) // DrGram Fix 47
                .putFloat("camera_beautify_slim", clampBeauty(prefs().getFloat("preset_slim", 0f))) // DrGram Fix 47
                .putInt("camera_fx", Math.max(0, Math.min(20, prefs().getInt("preset_fx", 0))))
                .apply();
        return true;
    }

    public static void saveCurrentBeautyPreset() {
        saveBeautyPreset();
        prefs().edit().putBoolean("preset_saved", true).apply();
    }

    /** True when any camera processing is active (drives face detection startup). */
    public static boolean cameraBeautyActive() {
        return isBeautifyEnabled() && (beautifyUniform() > 0.001f
                || beautifyNose() > 0.001f || beautifyTeeth() > 0.001f
                || beautifyEyes() > 0.001f || beautifyJaw() > 0.001f
                || beautifyBlush() > 0.001f || beautifyLips() > 0.001f
                || beautifyBright() > 0.001f);
    }

    /**
     * DrGram Fix 46: true when any live effect needs a detected face
     * (nose / teeth / eyes / jaw / blush / lips). Drives the software
     * face-detection fallback for cameras without hardware detection.
     */
    public static boolean cameraBeautyNeedsFaces() {
        return isBeautifyEnabled() && (beautifyNose() > 0.001f || beautifyTeeth() > 0.001f
                || beautifyEyes() > 0.001f || beautifyJaw() > 0.001f
                || beautifyBlush() > 0.001f || beautifyLips() > 0.001f
                || beautifyDark() > 0.001f || beautifySlim() > 0.001f); // DrGram Fix 47
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

    //region DrGram Fix 49: live & video-call beauty engine

    public static final int CALL_BEAUTY_OFF = 0;
    public static final int CALL_BEAUTY_LIGHT = 1;
    public static final int CALL_BEAUTY_STRONG = 2;

    /** Live stream / video call beauty level: 0 = off, 1 = light, 2 = strong. */
    public static int callBeautyLevel() {
        try {
            int v = prefs().getInt("call_beauty_level", CALL_BEAUTY_OFF);
            if (v < CALL_BEAUTY_OFF) v = CALL_BEAUTY_OFF;
            if (v > CALL_BEAUTY_STRONG) v = CALL_BEAUTY_STRONG;
            return v;
        } catch (Exception e) {
            return CALL_BEAUTY_OFF;
        }
    }

    public static void setCallBeautyLevel(int level) {
        prefs().edit().putInt("call_beauty_level", level).apply();
    }

    //endregion

    //region DrGram Fix 49: story quality & camera grid

    /** HD story upload: higher bitrate (6 Mbps) and 1080p photo-to-video conversion. */
    public static boolean storyHd() {
        try {
            return prefs().getBoolean("story_hd", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setStoryHd(boolean value) {
        prefs().edit().putBoolean("story_hd", value).apply();
    }

    /** 3x3 composition grid over the story camera. */
    public static boolean storyGrid() {
        try {
            return prefs().getBoolean("story_grid", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setStoryGrid(boolean value) {
        prefs().edit().putBoolean("story_grid", value).apply();
    }

    //endregion
}
