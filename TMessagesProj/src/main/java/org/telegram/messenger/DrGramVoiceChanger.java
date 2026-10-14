/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Voice Changer (Fix 37) — send-side voice transformation for outgoing
 * voice messages. Unlike the playback-only FX in DrGramMedia, this modifies the
 * audio before it is encoded, so the recipient hears the effect.
 *
 *   • Male → Female / Female → Male: streaming WSOLA pitch shifter
 *     (resample by r, then time-stretch by r with weighted overlap-add and a
 *     similarity-guided grain search). Algorithm validated offline: duration
 *     preserved exactly, pitch tracks the target ratio.
 *   • Studio echo: two-tap filtered early-reflection delay for a soft
 *     studio-room ambience.
 *
 * The recorder feeds 16 kHz mono PCM-16 buffers; processing is in place and
 * emits exactly the requested sample count per call (short internal latency,
 * zero-padded for the first frames), so the opus encoder path is untouched.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;

public class DrGramVoiceChanger {

    public static final int MODE_OFF = 0;
    public static final int MODE_M2F = 1;
    public static final int MODE_F2M = 2;

    private static final float M2F_RATIO = 1.32f;
    private static final float F2M_RATIO = 0.76f;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_media", Context.MODE_PRIVATE);
    }

    public static int getMode() {
        try {
            return prefs().getInt("voice_send_mode", MODE_OFF);
        } catch (Throwable t) {
            return MODE_OFF;
        }
    }

    public static void setMode(int mode) {
        prefs().edit().putInt("voice_send_mode", mode).apply();
    }

    public static boolean isEchoEnabled() {
        try {
            return prefs().getBoolean("voice_send_echo", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEchoEnabled(boolean value) {
        prefs().edit().putBoolean("voice_send_echo", value).apply();
    }

    /** True when any send-side processing is active. */
    public static boolean active() {
        return getMode() != MODE_OFF || isEchoEnabled();
    }

    //region session

    private static volatile Chain chain;

    /** Starts (or resets) processing state for a fresh recording. */
    public static void beginSession(int sampleRate) {
        try {
            if (sampleRate <= 0 || !active()) {
                chain = null;
                return;
            }
            float ratio = getMode() == MODE_F2M ? F2M_RATIO : (getMode() == MODE_M2F ? M2F_RATIO : 1.0f);
            chain = new Chain(ratio, isEchoEnabled(), sampleRate);
        } catch (Throwable t) {
            chain = null;
            FileLog.e(t);
        }
    }

    public static void endSession() {
        chain = null;
    }

    /**
     * Processes one recorder buffer in place. Always returns the sample-byte
     * count to feed downstream (equal to len — the chain emits exactly the
     * requested amount, zero-padded while its internal pipeline warms up).
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

    //endregion

    //region streaming chain (validated prototype: scripts/wsola_proto.py)

    static final class Chain {
        // WSOLA tuning validated offline (W512/H128/T64 — exact pitch tracking
        // for both directions, duration delta 0.00%)
        static final int W = 512;          // grain window, 32 ms @ 16 kHz
        static final int HA = 128;         // ideal input hop, 8 ms
        static final int T = 64;           // search tolerance, +/-4 ms
        static final int SEARCH_STEP = 6;
        static final int TEMPLATE = W / 2; // similarity template length

        final float ratio;
        final int hs;                      // output hop = round(HA * ratio)
        final float[] hann;
        final boolean echo;
        final int sampleRate;

        // resampler carry
        short[] resTail = new short[0];
        double resPos = 0.0;

        // wsola state
        short[] buf = new short[0];
        int bufBase = 0;
        int step = 0;
        short[] expected = null;
        double[] ola = new double[0];
        double[] wsum = new double[0];
        int olaBase = 0;
        int nextEmit = 0;

        // echo state
        float[] d1, d2;
        int p1, p2;
        float lp;

        Chain(float ratio, boolean echo, int sampleRate) {
            this.ratio = ratio;
            this.echo = echo;
            this.sampleRate = sampleRate;
            this.hs = Math.max(1, Math.round(HA * ratio));
            hann = new float[W];
            for (int i = 0; i < W; i++) {
                hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (W - 1)));
            }
            if (echo) {
                int delay1 = Math.round(sampleRate * 0.065f);
                int delay2 = Math.round(sampleRate * 0.119f);
                d1 = new float[Math.max(1, delay1)];
                d2 = new float[Math.max(1, delay2)];
            }
        }

        int process(ByteBuffer buffer, int len) {
            int samples = len / 2;
            if (samples <= 0) {
                return len;
            }
            buffer.position(0);
            buffer.limit(len);
            // read input
            short[] in = new short[samples];
            for (int i = 0; i < samples; i++) {
                in[i] = buffer.getShort();
            }
            // 1) resample by ratio (pitch shift, duration / ratio)
            pushResampled(in);
            // 2) render wsola steps while input allows
            renderAvailable();
            // 3) emit exactly `samples` (weighted overlap-add normalized)
            short[] out = new short[samples];
            int avail = olaBase + ola.length - nextEmit;
            int take = Math.min(samples, Math.max(0, avail));
            for (int i = 0; i < take; i++) {
                int rel = nextEmit - olaBase + i;
                double w = wsum[rel];
                double v = w > 1e-6 ? ola[rel] / w : 0.0;
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[i] = (short) v;
            }
            nextEmit += samples;
            if (nextEmit - olaBase > W * 4) {
                int cut = nextEmit - olaBase - W;
                double[] nola = new double[ola.length - cut];
                double[] nwsum = new double[wsum.length - cut];
                System.arraycopy(ola, cut, nola, 0, nola.length);
                System.arraycopy(wsum, cut, nwsum, 0, nwsum.length);
                ola = nola;
                wsum = nwsum;
                olaBase += cut;
            }
            // 4) optional studio echo
            if (echo) {
                applyEcho(out);
            }
            // write back
            buffer.position(0);
            buffer.limit(len);
            for (int i = 0; i < samples; i++) {
                buffer.putShort(out[i]);
            }
            buffer.position(0); // leave the buffer exactly as the caller expects
            return len;
        }

        private void pushResampled(short[] in) {
            double r = ratio;
            short[] x;
            if (resTail.length > 0) {
                x = new short[resTail.length + in.length];
                System.arraycopy(resTail, 0, x, 0, resTail.length);
                System.arraycopy(in, 0, x, resTail.length, in.length);
            } else {
                x = in;
            }
            if (x.length < 2) {
                resTail = x.clone();
                return;
            }
            int nOut = (int) Math.floor((x.length - 1 - resPos) / r);
            if (nOut < 0) {
                nOut = 0;
            }
            short[] out = new short[nOut];
            for (int k = 0; k < nOut; k++) {
                double idx = resPos + k * r;
                int i0 = (int) idx;
                double frac = idx - i0;
                int i1 = Math.min(i0 + 1, x.length - 1);
                double v = x[i0] * (1.0 - frac) + x[i1] * frac;
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[k] = (short) v;
            }
            if (nOut > 0) {
                int last = (int) (resPos + (nOut - 1) * r);
                resPos = (resPos + (nOut - 1) * r) - last;
                short[] tail = new short[x.length - last];
                System.arraycopy(x, last, tail, 0, tail.length);
                resTail = tail;
            } else {
                resTail = x.clone();
            }
            // append to wsola input buffer
            short[] nb = new short[buf.length + out.length];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(out, 0, nb, buf.length, out.length);
            buf = nb;
        }

        private void renderAvailable() {
            while (true) {
                int gIdeal = step * HA;               // absolute input-domain position
                int hi = gIdeal + T;
                if (hi + W > bufBase + buf.length) {
                    break;
                }
                int tau = gIdeal;
                if (expected != null) {
                    double best = -1e18;
                    int btau = gIdeal;
                    for (int t = gIdeal - T; t <= hi; t += SEARCH_STEP) {
                        int a = t - bufBase;
                        if (a < 0 || a + TEMPLATE > buf.length) {
                            continue;
                        }
                        double num = 0, c2 = 0, e2 = 0;
                        for (int i = 0; i < TEMPLATE; i++) {
                            double cv = buf[a + i];
                            double ev = expected[i];
                            num += cv * ev;
                            c2 += cv * cv;
                            e2 += ev * ev;
                        }
                        double score = num / (Math.sqrt(c2 * e2) + 1e-9);
                        if (score > best) {
                            best = score;
                            btau = t;
                        }
                    }
                    tau = btau;
                }
                int a = tau - bufBase;
                int opos = step * hs;
                int endNeeded = opos + W;
                if (endNeeded > olaBase + ola.length) {
                    int grow = endNeeded - (olaBase + ola.length);
                    double[] nola = new double[ola.length + grow];
                    double[] nwsum = new double[wsum.length + grow];
                    System.arraycopy(ola, 0, nola, 0, ola.length);
                    System.arraycopy(wsum, 0, nwsum, 0, wsum.length);
                    ola = nola;
                    wsum = nwsum;
                }
                int rel = opos - olaBase;
                for (int i = 0; i < W; i++) {
                    ola[rel + i] += buf[a + i] * hann[i];
                    wsum[rel + i] += hann[i] + 1e-9;
                }
                // expected continuation: offset by the OUTPUT hop — grain k's
                // content at its offset hs lands where grain k+1's head sits
                int cont = tau + hs - bufBase;
                if (cont >= 0 && cont + TEMPLATE <= buf.length) {
                    short[] exp = new short[TEMPLATE];
                    System.arraycopy(buf, cont, exp, 0, TEMPLATE);
                    expected = exp;
                }
                step++;
                int keepFrom = step * HA - T - 4;      // absolute
                if (keepFrom > bufBase) {
                    int cut = keepFrom - bufBase;
                    if (cut >= buf.length) {
                        buf = new short[0];
                        bufBase = keepFrom;
                    } else {
                        short[] nb = new short[buf.length - cut];
                        System.arraycopy(buf, cut, nb, 0, nb.length);
                        buf = nb;
                        bufBase = keepFrom;
                    }
                }
            }
        }

        private void applyEcho(short[] out) {
            for (int i = 0; i < out.length; i++) {
                float dry = out[i];
                float w1 = d1[p1];
                d1[p1] = dry;
                p1 = (p1 + 1) % d1.length;
                float w2 = d2[p2];
                d2[p2] = dry;
                p2 = (p2 + 1) % d2.length;
                lp = 0.65f * lp + 0.35f * w2;
                float v = dry * 0.78f + w1 * 0.24f + lp * 0.13f;
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[i] = (short) v;
            }
        }
    }

    //endregion
}
