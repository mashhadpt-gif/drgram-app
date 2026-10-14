/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Voice Changer (Fix 40 v2) — send-side + call-side voice
 * transformation for outgoing voice messages AND phone/voice-chat calls.
 *
 *   • Male → Female / Female → Male: dual-pointer crossfade delay-line pitch
 *     shifter (the standard hardware-style shifter). Two reads of a ring
 *     buffer, half a window apart, weighted by Hann gains whose sum is
 *     EXACTLY 1.0 at every phase — so there is no amplitude wobble and no
 *     warble; the pitch tracks the ratio exactly at any sample rate.
 *     (Fix 37's WSOLA search was verified offline to cancel its own pitch
 *     shift on periodic signals at 16 kHz and to double-shift at 48 kHz —
 *     the reason the feature "did nothing". This replacement was validated
 *     the same way: 200 Hz -> 264.0 Hz at 48 kHz and 16 kHz alike.)
 *   • Studio echo (Fix 40: much more audible taps): استودیو (soft room),
 *     غار (long dark cave), صحنه (bright stage).
 *
 *   The recorder (MediaController, 48 kHz mono PCM-16) feeds process();
 *   WebRtcAudioRecord (calls & voice chats) feeds processCall(); both emit
 *   exactly the requested sample count per call, so every downstream opus
 *   encoder path is untouched.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;

public class DrGramVoiceChanger {

    public static final int MODE_OFF = 0;
    public static final int MODE_M2F = 1;
    public static final int MODE_F2M = 2;

    // DrGram Fix 38: echo room characters
    public static final int ECHO_OFF = 0;
    public static final int ECHO_STUDIO = 1; // soft two-tap room
    public static final int ECHO_CAVE = 2;   // long dark reflections
    public static final int ECHO_STAGE = 3;  // bright three-tap hall

    private static final float M2F_RATIO = 1.32f;
    private static final float F2M_RATIO = 0.76f;

    // DrGram Fix 41: intensity presets — Fix 40's single ratios (1.32 / 0.76)
    // were perceived as "nothing changed": a pure 32 % shift keeps a male
    // voice inside the male range and a 24 % drop keeps a female voice female.
    // Three levels + formant-tilt EQ (below) make the gender flip unmistakable.
    public static final float[] M2F_RATIOS = {1.38f, 1.55f, 1.72f};
    public static final float[] F2M_RATIOS = {0.72f, 0.66f, 0.60f};

    public static final int INTENSITY_LIGHT = 0;
    public static final int INTENSITY_MEDIUM = 1;
    public static final int INTENSITY_STRONG = 2;

    /** Voice-change intensity 0..2 (default STRONG so the change is audible). */
    public static int getIntensity() {
        try {
            int v = prefs().getInt("voice_intensity", INTENSITY_STRONG);
            if (v < INTENSITY_LIGHT) v = INTENSITY_LIGHT;
            if (v > INTENSITY_STRONG) v = INTENSITY_STRONG;
            return v;
        } catch (Throwable t) {
            return INTENSITY_STRONG;
        }
    }

    public static void setIntensity(int intensity) {
        if (intensity < INTENSITY_LIGHT || intensity > INTENSITY_STRONG) {
            intensity = INTENSITY_STRONG;
        }
        prefs().edit().putInt("voice_intensity", intensity).apply();
    }

    private static float currentRatio(int mode) {
        int i = getIntensity();
        if (mode == MODE_M2F) {
            return M2F_RATIOS[Math.max(0, Math.min(M2F_RATIOS.length - 1, i))];
        }
        if (mode == MODE_F2M) {
            return F2M_RATIOS[Math.max(0, Math.min(F2M_RATIOS.length - 1, i))];
        }
        return 1.0f;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_media", Context.MODE_PRIVATE);
    }

    public static int getMode() {
        try {
            int mode = prefs().getInt("voice_send_mode", MODE_OFF);
            return mode < MODE_OFF || mode > MODE_F2M ? MODE_OFF : mode;
        } catch (Throwable t) {
            return MODE_OFF;
        }
    }

    public static void setMode(int mode) {
        if (mode < MODE_OFF || mode > MODE_F2M) {
            mode = MODE_OFF;
        }
        prefs().edit().putInt("voice_send_mode", mode).apply();
    }

    public static boolean isEchoEnabled() {
        return getEchoStyle() != ECHO_OFF;
    }

    /** DrGram Fix 38: current echo room character. Migrates the Fix 37 boolean. */
    public static int getEchoStyle() {
        try {
            SharedPreferences p = prefs();
            if (p.contains("voice_send_echo_style")) {
                int style = p.getInt("voice_send_echo_style", ECHO_OFF);
                return style < ECHO_OFF || style > ECHO_STAGE ? ECHO_OFF : style;
            }
            return p.getBoolean("voice_send_echo", false) ? ECHO_STUDIO : ECHO_OFF;
        } catch (Throwable t) {
            return ECHO_OFF;
        }
    }

    public static void setEchoStyle(int style) {
        try {
            if (style < ECHO_OFF || style > ECHO_STAGE) {
                style = ECHO_OFF;
            }
            prefs().edit().putInt("voice_send_echo_style", style).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Kept for compatibility with older callers. */
    public static void setEchoEnabled(boolean value) {
        setEchoStyle(value ? ECHO_STUDIO : ECHO_OFF);
    }

    /** True when any send-side processing is active. */
    public static boolean active() {
        return getMode() != MODE_OFF || isEchoEnabled();
    }

    //region sessions

    private static volatile Chain chain;
    private static volatile Chain callChain;

    /** Starts (or resets) processing state for a fresh voice recording. */
    public static void beginSession(int sampleRate) {
        try {
            if (sampleRate <= 0 || !active()) {
                chain = null;
                return;
            }
            chain = new Chain(currentRatio(getMode()), getEchoStyle(), sampleRate);
        } catch (Throwable t) {
            chain = null;
            FileLog.e(t);
        }
    }

    public static void endSession() {
        chain = null;
    }

    /** DrGram Fix 40: call/voice-chat uplink session (kept separate from recordings). */
    public static void beginCallSession(int sampleRate) {
        try {
            if (sampleRate <= 0 || !active()) {
                callChain = null;
                return;
            }
            callChain = new Chain(currentRatio(getMode()), getEchoStyle(), sampleRate);
        } catch (Throwable t) {
            callChain = null;
            FileLog.e(t);
        }
    }

    public static void endCallSession() {
        callChain = null;
    }

    /** True when a call uplink chain exists (cheap guard for the WebRTC read loop). */
    public static boolean callActive() {
        return callChain != null;
    }

    //endregion

    /**
     * Processes one recorder buffer in place. Always returns the sample-byte
     * count to feed downstream (equal to len — the chain emits exactly the
     * requested amount, zero-padded while the ring buffer warms up).
     */
    public static int process(ByteBuffer buffer, int len) {
        Chain c = chain;
        if (c == null || len <= 0) {
            return len;
        }
        try {
            return c.process(buffer, len);
        } catch (Throwable t) {
            chain = null; // never risk the recording pipeline
            FileLog.e(t);
            return len;
        }
    }

    /** DrGram Fix 40: processes one call uplink buffer in place. */
    public static int processCall(ByteBuffer buffer, int len) {
        Chain c = callChain;
        if (c == null || len <= 0) {
            return len;
        }
        try {
            return c.process(buffer, len);
        } catch (Throwable t) {
            callChain = null;
            FileLog.e(t);
            return len;
        }
    }

    //endregion

    //region streaming chain (validated prototype: scripts/chain3_proto.py)

    static final class Chain {
        final float ratio;
        final int echoStyle;
        final int sampleRate;
        final int w;          // crossfade window, 40 ms
        final int half;
        final float grow;     // delay change per output sample = 1 - ratio
        final float[] gain;   // Hann table indexed by delay sample
        final float[] ring;
        final int ringCap;
        double delay;         // pointer 0 delay, kept in (0, w]
        int wpos;             // absolute write position

        // DrGram Fix 41: formant-tilt one-pole filters. Male→Female gets a
        // brightening tilt (voice-tract shortening illusion), Female→Male a
        // chest boost + darkness. Simple x + k·(x−lp) / x − k·(x−lp) forms are
        // unconditionally stable at any sample rate.
        final boolean m2f, f2m;
        float lpBright, lpDark, lpChest;
        float aBright, aDark, aChest;
        final float TILT_BRIGHT = 0.42f, TILT_DARK = 0.38f, CHEST = 0.30f;

        private static float onePoleA(float freqHz, float sr) {
            return (float) (1.0 - Math.exp(-2.0 * Math.PI * freqHz / sr));
        }

        // echo state
        float[] d1, d2, d3;
        int p1, p2, p3;
        float lp;

        Chain(float ratio, int echoStyle, int sampleRate) {
            this.ratio = ratio;
            this.echoStyle = echoStyle;
            this.sampleRate = sampleRate;
            m2f = ratio > 1.001f;
            f2m = ratio < 0.999f;
            if (m2f) {
                aBright = onePoleA(1400f, sampleRate); // tilt source band
                aDark = 1f;
                aChest = 1f;
            } else if (f2m) {
                aBright = 1f;
                aDark = onePoleA(2600f, sampleRate);   // highs to remove
                aChest = onePoleA(280f, sampleRate);   // chest band
            }
            w = Math.max(256, Math.round(sampleRate * 0.040f));
            half = w / 2;
            gain = new float[w + 1];
            for (int i = 0; i <= w; i++) {
                gain[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / w));
            }
            ringCap = w + 4096;
            ring = new float[ringCap];
            delay = half;
            grow = 1.0f - ratio;
            if (echoStyle == ECHO_STUDIO) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.092f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.156f))];
            } else if (echoStyle == ECHO_CAVE) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.140f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.310f))];
            } else if (echoStyle == ECHO_STAGE) {
                d1 = new float[Math.max(1, Math.round(sampleRate * 0.055f))];
                d2 = new float[Math.max(1, Math.round(sampleRate * 0.110f))];
                d3 = new float[Math.max(1, Math.round(sampleRate * 0.210f))];
            }
        }

        /** DrGram Fix 41: spectral tilt — brightens M→F, darkens + chests F→M. */
        private float tilt(float x) {
            if (m2f) {
                lpBright += aBright * (x - lpBright);
                return x + TILT_BRIGHT * (x - lpBright);
            }
            if (f2m) {
                lpDark += aDark * (x - lpDark);
                lpChest += aChest * (x - lpChest);
                return (x - TILT_DARK * (x - lpDark)) + CHEST * (lpChest - 0.45f * x);
            }
            return x;
        }

        int process(ByteBuffer buffer, int len) {
            int samples = len / 2;
            if (samples <= 0) {
                return len;
            }
            buffer.position(0);
            buffer.limit(len);
            boolean shift = Math.abs(ratio - 1.0f) > 0.001f;
            if (!shift) {
                // passthrough (echo still applies below)
                if (echoStyle == ECHO_OFF) {
                    buffer.position(0);
                    return len;
                }
                short[] in = new short[samples];
                for (int i = 0; i < samples; i++) {
                    in[i] = buffer.getShort();
                }
                for (int i = 0; i < samples; i++) {
                    float v = tilt(in[i]);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    in[i] = (short) v;
                }
                applyEcho(in);
                buffer.position(0);
                buffer.limit(len);
                for (int i = 0; i < samples; i++) {
                    buffer.putShort(in[i]);
                }
                buffer.position(0);
                return len;
            }
            short[] out = new short[samples];
            for (int n = 0; n < samples; n++) {
                if (wpos >= Integer.MAX_VALUE - 65536) { // DrGram Fix 40: >12 h call guard
                    wpos = 0;
                }
                ring[wpos % ringCap] = buffer.getShort();
                wpos++;
                double d0 = delay % w;
                if (d0 <= 0) {
                    d0 += w;
                }
                double d1p = d0 + half;
                if (d1p > w) {
                    d1p -= w;
                }
                float g0 = gain[(int) d0];
                float g1 = gain[(int) d1p];
                float v = readLerp(d0) * g0 + readLerp(d1p) * g1;
                v = tilt(v); // DrGram Fix 41: formant tilt
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[n] = (short) v;
                delay += grow;
            }
            if (echoStyle != ECHO_OFF) {
                applyEcho(out);
            }
            buffer.position(0);
            buffer.limit(len);
            for (int i = 0; i < samples; i++) {
                buffer.putShort(out[i]);
            }
            buffer.position(0); // leave the buffer exactly as the caller expects
            return len;
        }

        /** Ring read at (wpos - delay) with linear interpolation. */
        private float readLerp(double d) {
            double pos = wpos - d;
            int i0 = (int) Math.floor(pos % ringCap);
            if (i0 < 0) {
                i0 += ringCap;
            }
            float frac = (float) (pos - Math.floor(pos));
            float a = ring[i0];
            float b = ring[i0 + 1 == ringCap ? 0 : i0 + 1];
            return a * (1.0f - frac) + b * frac;
        }

        private void applyEcho(short[] out) {
            if (echoStyle == ECHO_STAGE) {
                // صحنه — bright hall: three early reflections
                for (int i = 0; i < out.length; i++) {
                    float dry = out[i];
                    float w1 = d1[p1];
                    d1[p1] = dry;
                    p1 = (p1 + 1) % d1.length;
                    float w2 = d2[p2];
                    d2[p2] = dry;
                    p2 = (p2 + 1) % d2.length;
                    float w3 = d3[p3];
                    d3[p3] = dry;
                    p3 = (p3 + 1) % d3.length;
                    float v = dry * 0.80f + w1 * 0.30f + w2 * 0.22f + w3 * 0.14f;
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    out[i] = (short) v;
                }
                return;
            }
            final boolean cave = echoStyle == ECHO_CAVE;
            for (int i = 0; i < out.length; i++) {
                float dry = out[i];
                float w1 = d1[p1];
                d1[p1] = dry;
                p1 = (p1 + 1) % d1.length;
                float w2 = d2[p2];
                d2[p2] = dry;
                p2 = (p2 + 1) % d2.length;
                if (cave) {
                    // غار — long dark reflections: stronger lowpass + more wet
                    lp = 0.52f * lp + 0.48f * w2;
                    float v = dry * 0.62f + w1 * 0.45f + lp * 0.35f;
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    out[i] = (short) v;
                } else {
                    // استودیو — soft two-tap room
                    lp = 0.60f * lp + 0.40f * w2;
                    float v = dry * 0.72f + w1 * 0.38f + lp * 0.22f;
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    out[i] = (short) v;
                }
            }
        }
    }

    //endregion
}
