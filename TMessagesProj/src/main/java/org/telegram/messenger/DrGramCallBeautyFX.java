/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 49 — live & video-call beauty engine.
 *
 * Sits between the webrtc camera capturer and the native call video source
 * (VideoCapturerDevice) and applies the DrGram beauty pass — edge-aware,
 * skin-masked smoothing + skin brightening + warm glow — to every outgoing
 * frame of:
 *   • live streams (GroupCallActivity)
 *   • live stories (StoryRecorder MODE_LIVE -> LivePlayer)
 *   • RTMP broadcasts
 *   • 1:1 video calls
 *
 * Rendering runs on a dedicated thread with an EGL context that shares the
 * call EGL base (VideoCapturerDevice.eglBase), so incoming OES camera frames
 * can be sampled and the outgoing TEXTURE_2D buffers can be consumed by the
 * encoder and the local preview renderers.
 *
 * Fail-safety first: when the engine is off, frames are forwarded untouched
 * with zero overhead; if anything goes wrong the original frame is forwarded
 * and after repeated failures the engine disables itself for the session.
 */
package org.telegram.messenger;

import android.opengl.GLES20;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.GlTextureFrameBuffer;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.YuvConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import android.os.Handler;
import android.os.HandlerThread;

public class DrGramCallBeautyFX implements org.webrtc.CapturerObserver {

    private static final String TAG = "DrGramCallBeautyFX";

    private static final String VERTEX_SHADER = "" +
        "attribute vec2 aPos;\n" +
        "varying vec2 vUv;\n" +
        "void main() {\n" +
        "  vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);\n" +
        "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
        "}\n";

    // Pass 2: beauty. The y-flip on sampling keeps the FBO round-trip
    // orientation-preserving under the webrtc drawer convention.
    private static final String FRAGMENT_SHADER = "" +
        "precision mediump float;\n" +
        "varying vec2 vUv;\n" +
        "uniform sampler2D sTexture;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform float uSmooth;\n" +
        "uniform float uBright;\n" +
        "uniform float uWarm;\n" +
        "mediump float skinMask(mediump vec3 c) {\n" +
        "  mediump float mx = max(c.r, max(c.g, c.b));\n" +
        "  mediump float mn = min(c.r, min(c.g, c.b));\n" +
        "  mediump float sat = mx - mn;\n" +
        "  mediump float rG = c.r - c.g;\n" +
        "  mediump float gB = c.g - c.b;\n" +
        "  mediump float lum = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  mediump float m = 1.0;\n" +
        "  m *= smoothstep(0.02, 0.09, rG);\n" +
        "  m *= 1.0 - smoothstep(0.16, 0.30, rG);\n" +
        "  m *= 1.0 - smoothstep(0.10, 0.22, gB);\n" +
        "  m *= smoothstep(0.12, 0.30, lum);\n" +
        "  m *= 1.0 - smoothstep(0.55, 0.92, sat);\n" +
        "  return clamp(m, 0.0, 1.0);\n" +
        "}\n" +
        "void main() {\n" +
        "  mediump float fy = 1.0 - vUv.y;\n" +
        "  mediump vec3 base = texture2D(sTexture, vec2(vUv.x, fy)).rgb;\n" +
        "  mediump vec3 rgb = base;\n" +
        "  if (uSmooth > 0.001) {\n" +
        "    mediump vec2 r = (1.4 + 3.4 * uSmooth) * uTexel;\n" +
        "    mediump vec3 sum = base;\n" +
        "    mediump float wsum = 1.0;\n" +
        "    for (int i = 0; i < 12; i++) {\n" +
        "      mediump float ang = float(i) * 0.52359877;\n" +
        "      mediump vec2 off = vec2(cos(ang), sin(ang)) * r;\n" +
        "      mediump vec3 S = texture2D(sTexture, vec2(vUv.x + off.x, fy - off.y)).rgb;\n" +
        "      mediump float d = distance(S, base);\n" +
        "      mediump float w = max(0.0, 1.0 - d * 6.0);\n" +
        "      sum += S * w;\n" +
        "      wsum += w;\n" +
        "    }\n" +
        "    mediump vec3 smoothC = sum / wsum;\n" +
        "    mediump vec3 sum2 = smoothC;\n" +
        "    mediump float wsum2 = 1.0;\n" +
        "    for (int i = 0; i < 8; i++) {\n" +
        "      mediump float ang = float(i) * 0.78539816 + 0.39;\n" +
        "      mediump vec2 off = vec2(cos(ang), sin(ang)) * r * 2.1;\n" +
        "      mediump vec3 S = texture2D(sTexture, vec2(vUv.x + off.x, fy - off.y)).rgb;\n" +
        "      mediump float d = distance(S, smoothC);\n" +
        "      mediump float w = max(0.0, 1.0 - d * 5.0);\n" +
        "      sum2 += S * w;\n" +
        "      wsum2 += w;\n" +
        "    }\n" +
        "    smoothC = mix(smoothC, sum2 / wsum2, 0.5);\n" +
        "    mediump float mask = skinMask(base);\n" +
        "    rgb = mix(base, smoothC, clamp(uSmooth * mask * 1.25, 0.0, 0.92));\n" +
        "  }\n" +
        "  if (uBright > 0.001) {\n" +
        "    rgb += uBright * 0.055 * skinMask(rgb);\n" +
        "  }\n" +
        "  if (uWarm > 0.001) {\n" +
        "    mediump float lum = dot(rgb, vec3(0.299, 0.587, 0.114));\n" +
        "    rgb += uWarm * 0.035 * (1.0 - lum) * skinMask(rgb) * vec3(1.0, 0.68, 0.45);\n" +
        "  }\n" +
        "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);\n" +
        "}\n";

    private final org.webrtc.CapturerObserver delegate;

    private HandlerThread fxThread;
    private Handler fxHandler;
    private EglBase egl;
    private boolean glInited;
    private int program;
    private int aPosLoc;
    private int uTexelLoc, uSmoothLoc, uBrightLoc, uWarmLoc;
    private int quadBuffer;
    private final VideoFrameDrawer frameDrawer = new VideoFrameDrawer();
    private final GlRectDrawer passDrawer = new GlRectDrawer();
    private GlTextureFrameBuffer fboIn;
    private final List<GlTextureFrameBuffer> outPool = new ArrayList<>();
    private final List<GlTextureFrameBuffer> outInUse = new ArrayList<>();
    private YuvConverter yuvConverter;
    private Handler toI420Handler;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean released;
    private int consecutiveErrors;

    public DrGramCallBeautyFX(org.webrtc.CapturerObserver delegate) {
        this.delegate = delegate;
    }

    //region CapturerObserver

    @Override
    public void onFrameCaptured(VideoFrame frame) {
        final int level = DrGramMedia.callBeautyLevel();
        if (released || level <= DrGramMedia.CALL_BEAUTY_OFF || eglBroken()) {
            delegate.onFrameCaptured(frame);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            // previous frame still rendering — never fall behind realtime
            delegate.onFrameCaptured(frame);
            return;
        }
        frame.retain();
        final int lvl = level;
        handler().post(() -> process(frame, lvl));
    }

    @Override
    public void onCapturerStarted(boolean success) {
        delegate.onCapturerStarted(success);
    }

    @Override
    public void onCapturerStopped() {
        delegate.onCapturerStopped();
    }

    //endregion

    private boolean eglBroken() {
        return consecutiveErrors > 45;
    }

    private Handler handler() {
        if (fxThread == null) {
            synchronized (this) {
                if (fxThread == null) {
                    fxThread = new HandlerThread("DrGramCallFX");
                    fxThread.start();
                    fxHandler = new Handler(fxThread.getLooper());
                }
            }
        }
        return fxHandler;
    }

    private void process(VideoFrame frame, int level) {
        try {
            if (released) {
                delegate.onFrameCaptured(frame);
                return;
            }
            ensureGl();
            if (!glInited) {
                delegate.onFrameCaptured(frame);
                return;
            }
            final int rotW = frame.getRotatedWidth();
            final int rotH = frame.getRotatedHeight();
            if (rotW <= 0 || rotH <= 0) {
                delegate.onFrameCaptured(frame);
                return;
            }
            float scale = 1.0f;
            final int maxSide = Math.max(rotW, rotH);
            if (maxSide > 1280) {
                scale = 1280f / maxSide;
            }
            final int w = Math.max(2, (int) (rotW * scale) & ~1);
            final int h = Math.max(2, (int) (rotH * scale) & ~1);

            // pass 1 — decode (OES + matrix + rotation) into an upright FBO
            fboIn.setSize(w, h);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIn.getFrameBufferId());
            GLES20.glViewport(0, 0, w, h);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            frameDrawer.drawFrame(frame, passDrawer);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            // pass 2 — beauty into a pooled output FBO
            GlTextureFrameBuffer out = obtainOut(w, h);
            if (out == null) {
                delegate.onFrameCaptured(frame);
                return;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.getFrameBufferId());
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboIn.getTextureId());
            GLES20.glUniform1i(samplerLoc, 0);
            GLES20.glUniform2f(uTexelLoc, 1.0f / w, 1.0f / h);
            float smooth, bright, warm;
            if (level == DrGramMedia.CALL_BEAUTY_STRONG) {
                smooth = 0.60f; bright = 0.35f; warm = 0.5f;
            } else {
                smooth = 0.40f; bright = 0.22f; warm = 0.3f;
            }
            GLES20.glUniform1f(uSmoothLoc, smooth);
            GLES20.glUniform1f(uBrightLoc, bright);
            GLES20.glUniform1f(uWarmLoc, warm);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
            GLES20.glEnableVertexAttribArray(aPosLoc);
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(aPosLoc);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            int glErr = GLES20.glGetError();
            if (glErr != GLES20.GL_NO_ERROR) {
                returnOut(out);
                FileLog.e("DrGramCallFX: gl error " + glErr);
                noteError();
                delegate.onFrameCaptured(frame);
                return;
            }

            TextureBufferImpl buffer = new TextureBufferImpl(
                w, h, VideoFrame.TextureBuffer.Type.RGB, out.getTextureId(),
                new android.graphics.Matrix(), toI420Handler, yuvConverter,
                () -> returnOut(out));
            VideoFrame outFrame = new VideoFrame(buffer, 0, frame.getTimestampNs());
            consecutiveErrors = 0;
            delegate.onFrameCaptured(outFrame);
        } catch (Throwable t) {
            FileLog.e(t);
            noteError();
            try {
                delegate.onFrameCaptured(frame);
            } catch (Throwable ignore) {}
        } finally {
            frame.release();
            busy.set(false);
        }
    }

    private int samplerLoc;

    private void noteError() {
        consecutiveErrors++;
        if (consecutiveErrors > 45) {
            FileLog.e(TAG, "too many GL failures — beauty disabled for this session");
            release();
        }
    }

    private void ensureGl() {
        if (glInited || released) {
            return;
        }
        try {
            EglBase.Context shared = VideoCapturerDevice.eglBase == null
                ? null : VideoCapturerDevice.eglBase.getEglBaseContext();
            egl = EglBase.create(shared, EglBase.CONFIG_PLAIN);
            egl.createDummyPbufferSurface();
            egl.makeCurrent();

            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                throw new IllegalStateException("program build failed");
            }
            aPosLoc = GLES20.glGetAttribLocation(program, "aPos");
            samplerLoc = GLES20.glGetUniformLocation(program, "sTexture");
            uTexelLoc = GLES20.glGetUniformLocation(program, "uTexel");
            uSmoothLoc = GLES20.glGetUniformLocation(program, "uSmooth");
            uBrightLoc = GLES20.glGetUniformLocation(program, "uBright");
            uWarmLoc = GLES20.glGetUniformLocation(program, "uWarm");

            final float[] quad = new float[]{
                -1f, -1f, 1f, -1f, -1f, 1f,
                1f, -1f, 1f, 1f, -1f, 1f
            };
            int[] bufs = new int[1];
            GLES20.glGenBuffers(1, bufs, 0);
            quadBuffer = bufs[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(quad.length * 4)
                .order(java.nio.ByteOrder.nativeOrder());
            bb.asFloatBuffer().put(quad);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * 4, bb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

            fboIn = new GlTextureFrameBuffer(GLES20.GL_RGBA);
            yuvConverter = new YuvConverter();
            toI420Handler = fxHandler;
            glInited = true;
        } catch (Throwable t) {
            FileLog.e(t);
            glInited = false;
        }
    }

    private int buildProgram(String vs, String fs) {
        int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(v, vs);
        GLES20.glCompileShader(v);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(v, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            FileLog.e("DrGramCallFX vs: " + GLES20.glGetShaderInfoLog(v));
            return 0;
        }
        int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(f, fs);
        GLES20.glCompileShader(f);
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            FileLog.e("DrGramCallFX fs: " + GLES20.glGetShaderInfoLog(f));
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            FileLog.e("DrGramCallFX link: " + GLES20.glGetProgramInfoLog(p));
            return 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private GlTextureFrameBuffer obtainOut(int w, int h) {
        if (!outPool.isEmpty()) {
            GlTextureFrameBuffer fb = outPool.remove(outPool.size() - 1);
            fb.setSize(w, h);
            outInUse.add(fb);
            return fb;
        }
        if (outInUse.size() >= 3) {
            return null; // downstream slow — skip FX for this frame
        }
        try {
            GlTextureFrameBuffer fb = new GlTextureFrameBuffer(GLES20.GL_RGBA);
            fb.setSize(w, h);
            outInUse.add(fb);
            return fb;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private void returnOut(GlTextureFrameBuffer fb) {
        if (released) {
            return;
        }
        handler().post(() -> {
            outInUse.remove(fb);
            if (!outPool.contains(fb) && outPool.size() < 4) {
                outPool.add(fb);
            }
        });
    }

    /** Release all engine resources; the wrapper becomes a pure passthrough. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        Runnable cleanup = () -> {
            try {
                if (egl != null) {
                    egl.makeCurrent();
                }
                for (int i = 0; i < outInUse.size(); i++) {
                    try { outInUse.get(i).release(); } catch (Throwable ignore) {}
                }
                for (int i = 0; i < outPool.size(); i++) {
                    try { outPool.get(i).release(); } catch (Throwable ignore) {}
                }
                outInUse.clear();
                outPool.clear();
                if (fboIn != null) {
                    try { fboIn.release(); } catch (Throwable ignore) {}
                    fboIn = null;
                }
                if (yuvConverter != null) {
                    try { yuvConverter.release(); } catch (Throwable ignore) {}
                    yuvConverter = null;
                }
                try { frameDrawer.release(); } catch (Throwable ignore) {}
                if (quadBuffer != 0) {
                    GLES20.glDeleteBuffers(1, new int[]{quadBuffer}, 0);
                    quadBuffer = 0;
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
                if (egl != null) {
                    try { egl.release(); } catch (Throwable ignore) {}
                    egl = null;
                }
            } catch (Throwable ignore) {}
        };
        if (fxHandler != null) {
            fxHandler.post(() -> {
                cleanup.run();
                if (fxThread != null) {
                    fxThread.quitSafely();
                }
            });
        }
    }
}
