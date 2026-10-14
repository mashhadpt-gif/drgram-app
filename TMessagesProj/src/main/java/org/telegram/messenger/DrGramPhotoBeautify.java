/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Photo Beautify (Fix 41) — one-shot Java post-processor for camera
 * photos (stories, profile pictures). The live GLSL shader covers video,
 * but the still-photo JPEG path never touches the GPU pipeline, which is
 * why beautify "did nothing" on photo stories before this class existed.
 *
 * Pipeline (mirrors the shader):
 *   • skin smoothing — skin-chroma-masked bilateral box blur, weighted by
 *     detected face ellipses (stronger on the face, gentle around it)
 *   • nose slimming — horizontal pinch around the derived nose anchor
 *   • teeth whitening — yellow-ish bright pixels inside the mouth ellipse
 *     are pushed toward a neutral white
 *   • soft beauty glow inside the face
 *
 * Faces come either from Camera1's preview face detection (fast, taken
 * straight before the shutter) or from android.media.FaceDetector as a
 * fallback. All processing is optional and wrapped by the caller.
 */
package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.hardware.Camera;

import java.util.ArrayList;

public class DrGramPhotoBeautify {

    private static final class FaceBox {
        float cx, cy, halfW, halfH; // pixels
        float noseX, noseY, mouthX, mouthY;
    }

    /**
     * Processes the bitmap in place (returns the same bitmap). When no faces
     * are found the pass degrades to a global gentle skin smooth.
     */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return bitmap;
        }
        if (strength <= 0.001f && nose <= 0.001f && teeth <= 0.001f) {
            return bitmap;
        }
        try {
            final int W = bitmap.getWidth(), H = bitmap.getHeight();
            if (W < 8 || H < 8 || (long) W * H > 12_000_000) {
                return bitmap;
            }
            int[] px = new int[W * H];
            bitmap.getPixels(px, 0, W, 0, 0, W, H);

            ArrayList<FaceBox> faces = collectFaces(cameraFaces, W, H);
            if (faces.isEmpty()) {
                faces = detectFacesFallback(px, W, H);
            }

            if (strength > 0.001f) {
                smoothSkin(px, W, H, faces, strength);
            }
            if (nose > 0.001f && !faces.isEmpty()) {
                pinchNose(px, W, H, faces, nose);
            }
            if (teeth > 0.001f && !faces.isEmpty()) {
                whitenTeeth(px, W, H, faces, teeth);
            }
            if (strength > 0.001f && !faces.isEmpty()) {
                faceGlow(px, W, H, faces, strength);
            }

            bitmap.setPixels(px, 0, W, 0, 0, W, H);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return bitmap;
    }

    //region face collection

    private static ArrayList<FaceBox> collectFaces(Camera.Face[] cameraFaces, int W, int H) {
        ArrayList<FaceBox> list = new ArrayList<>();
        if (cameraFaces == null) {
            return list;
        }
        try {
            for (Camera.Face f : cameraFaces) {
                if (f == null || f.rect == null) {
                    continue;
                }
                FaceBox b = new FaceBox();
                // Camera1 face rect lives in (-1000..1000) sensor space
                float cx = ((f.rect.left + f.rect.right) * 0.5f + 1000f) * 0.0005f;
                float cy = ((f.rect.top + f.rect.bottom) * 0.5f + 1000f) * 0.0005f;
                float hw = Math.max(0.03f, (f.rect.width() * 0.5f) / 2000f);
                float hh = Math.max(0.03f, (f.rect.height() * 0.5f) / 2000f);
                b.cx = cx * W; b.cy = cy * H;
                b.halfW = hw * W; b.halfH = hh * H;
                b.noseX = b.cx;                 b.noseY = b.cy + b.halfH * 0.08f;
                b.mouthX = b.cx;                b.mouthY = b.cy + b.halfH * 0.55f;
                list.add(b);
                if (list.size() >= 2) {
                    break;
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return list;
    }

    /** android.media.FaceDetector fallback (RGB_565, downscaled probe). */
    private static ArrayList<FaceBox> detectFacesFallback(int[] px, int W, int H) {
        ArrayList<FaceBox> list = new ArrayList<>();
        try {
            int probeW = 480;
            int probeH = Math.max(1, Math.round(H * (probeW / (float) W)));
            int[] probe = new int[probeW * probeH];
            for (int y = 0; y < probeH; y++) {
                int srcY = Math.min(H - 1, (int) ((long) y * H / probeH));
                for (int x = 0; x < probeW; x++) {
                    int srcX = Math.min(W - 1, (int) ((long) x * W / probeW));
                    int c = px[srcY * W + srcX];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    probe[y * probeW + x] = 0xFF000000 | ((r & 0xF8) << 16) | ((g & 0xFC) << 8) | (b & 0xF8);
                }
            }
            Bitmap bmp = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.RGB_565);
            bmp.setPixels(probe, 0, probeW, 0, 0, probeW, probeH);
            android.media.FaceDetector detector = new android.media.FaceDetector(probeW, probeH, 2);
            android.media.FaceDetector.Face[] found = new android.media.FaceDetector.Face[2];
            int n = detector.findFaces(bmp, found);
            bmp.recycle();
            for (int i = 0; i < n; i++) {
                android.media.FaceDetector.Face f = found[i];
                if (f == null || f.confidence() < 0.4f) {
                    continue;
                }
                PointF mid = new PointF();
                f.getMidPoint(mid);
                float d = f.eyesDistance();
                FaceBox b = new FaceBox();
                b.cx = mid.x / probeW * W;
                b.cy = (mid.y + d * 0.55f) / probeH * H;
                b.halfW = d * 1.15f / probeW * W;
                b.halfH = d * 1.55f / probeH * H;
                b.noseX = b.cx;               b.noseY = b.cy + b.halfH * 0.10f;
                b.mouthX = b.cx;              b.mouthY = b.cy + b.halfH * 0.55f;
                list.add(b);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return list;
    }

    //endregion

    //region passes

    private static boolean insideAnyFace(ArrayList<FaceBox> faces, float x, float y, float grow) {
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            float dx = (x - f.cx) / (f.halfW * grow);
            float dy = (y - f.cy) / (f.halfH * grow);
            if (dx * dx + dy * dy <= 1f) {
                return true;
            }
        }
        return false;
    }

    private static float skinLikelihood(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        int sat = mx - mn;
        int rG = r - g, gB = g - b;
        float lum = (r * 76 + g * 150 + b * 29) / 255f;
        float m = 1f;
        m *= clamp01((rG - 4) / 18f);
        m *= 1f - clamp01((rG - 40) / 24f);
        m *= 1f - clamp01((gB - 24) / 24f);
        m *= clamp01((lum - 30) / 44f);
        m *= 1f - clamp01((sat - 140) / 80f);
        return clamp01(m);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Bilateral 3x3(stepped) smoothing restricted to skin + face areas. */
    private static void smoothSkin(int[] px, int W, int H, ArrayList<FaceBox> faces, float strength) {
        int[] src = new int[px.length];
        System.arraycopy(px, 0, src, 0, px.length);
        int step = Math.max(2, Math.round(Math.min(W, H) / 320f));
        float amt = 0.30f + 0.45f * strength;

        // bounding box of all faces (grown); with no faces treat everything
        int x0 = 0, y0 = 0, x1 = W - 1, y1 = H - 1;
        if (!faces.isEmpty()) {
            x0 = W; y0 = H; x1 = 0; y1 = 0;
            for (FaceBox f : faces) {
                x0 = Math.min(x0, (int) (f.cx - f.halfW * 1.6f));
                x1 = Math.max(x1, (int) (f.cx + f.halfW * 1.6f));
                y0 = Math.min(y0, (int) (f.cy - f.halfH * 1.6f));
                y1 = Math.max(y1, (int) (f.cy + f.halfH * 1.6f));
            }
            x0 = Math.max(0, x0); y0 = Math.max(0, y0);
            x1 = Math.min(W - 1, x1); y1 = Math.min(H - 1, y1);
        }

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float fw = faces.isEmpty() ? 0.55f : faceWeightAt(faces, x, y);
                if (fw <= 0.02f) {
                    continue;
                }
                int idx = y * W + x;
                int c = src[idx];
                float skin = skinLikelihood(c);
                if (skin <= 0.05f) {
                    continue;
                }
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -step; dy <= step; dy += Math.max(1, step / 2)) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= H) continue;
                    for (int dx = -step; dx <= step; dx += Math.max(1, step / 2)) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= W) continue;
                        int s = src[yy * W + xx];
                        int sr = (s >> 16) & 0xFF, sg = (s >> 8) & 0xFF, sb = s & 0xFF;
                        int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
                        int dist = Math.abs(sr - cr) + Math.abs(sg - cg) + Math.abs(sb - cb);
                        if (dist > 110) {
                            continue; // keep edges (eyes, brows, lips) crisp
                        }
                        r += sr; g += sg; b += sb; n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                float br = (c >> 16) & 0xFF, bg = (c >> 8) & 0xFF, bb = c & 0xFF;
                float k = amt * skin * fw;
                int nr = (int) (br + ((r / (float) n) - br) * k);
                int ng = (int) (bg + ((g / (float) n) - bg) * k);
                int nb = (int) (bb + ((b / (float) n) - bb) * k);
                px[idx] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
            }
        }
    }

    private static float faceWeightAt(ArrayList<FaceBox> faces, float x, float y) {
        float w = 0f;
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            float dx = (x - f.cx) / (f.halfW * 1.25f);
            float dy = (y - f.cy) / (f.halfH * 1.25f);
            float e = dx * dx + dy * dy;
            if (e < 1f) {
                w = Math.max(w, 1f - e * 0.55f);
            }
        }
        return clamp01(w);
    }

    /** Horizontal pinch at the nose anchor — slims the nose naturally. */
    private static void pinchNose(int[] px, int W, int H, ArrayList<FaceBox> faces, float nose) {
        int[] src = new int[px.length];
        System.arraycopy(px, 0, src, 0, px.length);
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            int rx = Math.max(2, (int) (f.halfW * 0.60f));
            int ry = Math.max(2, (int) (f.halfH * 0.85f));
            int cx = (int) f.noseX, cy = (int) f.noseY;
            float squeeze = 1f - nose * 0.34f;
            for (int dy = -ry; dy <= ry; dy++) {
                int y = cy + dy;
                if (y < 0 || y >= H) continue;
                float fy = dy / (float) ry;
                for (int dx = -rx; dx <= rx; dx++) {
                    int x = cx + dx;
                    if (x < 0 || x >= W) continue;
                    float fx = dx / (float) rx;
                    float e = fx * fx + fy * fy;
                    if (e >= 1f) {
                        continue;
                    }
                    float fall = (1f - e);
                    float sq = 1f - (1f - squeeze) * fall * fall;
                    float sxf = cx + dx / sq;
                    int sx = Math.max(0, Math.min(W - 1, Math.round(sxf)));
                    px[y * W + x] = src[y * W + sx];
                }
            }
        }
    }

    private static void whitenTeeth(int[] px, int W, int H, ArrayList<FaceBox> faces, float teeth) {
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            int rx = Math.max(2, (int) (f.halfW * 0.50f));
            int ry = Math.max(2, (int) (f.halfH * 0.26f));
            int cx = (int) (f.mouthX), cy = (int) (f.mouthY - f.halfH * 0.10f);
            for (int dy = -ry; dy <= ry; dy++) {
                int y = cy + dy;
                if (y < 0 || y >= H) continue;
                float fy = dy / (float) ry;
                for (int dx = -rx; dx <= rx; dx++) {
                    int x = cx + dx;
                    if (x < 0 || x >= W) continue;
                    float fx = dx / (float) rx;
                    float e = fx * fx + fy * fy;
                    if (e >= 1f) {
                        continue;
                    }
                    float fall = 1f - e;
                    int idx = y * W + x;
                    int c = px[idx];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    float lum = (r * 76 + g * 150 + b * 29) / 255f;
                    float yellow = (r + g) * 0.5f - b - 14f;
                    if (lum < 70f || yellow < 6f) {
                        continue;
                    }
                    float gate = clamp01((lum - 70f) / 60f) * clamp01(yellow / 26f);
                    float amt = clamp01(fall * gate * teeth * 1.1f) * 0.85f;
                    float target = Math.max(lum + 56f, 158f);
                    int nr = (int) (r + (target - r) * amt);
                    int ng = (int) (g + (target - g) * amt);
                    int nb = (int) (b + (target - b) * amt);
                    px[idx] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                }
            }
        }
    }

    private static void faceGlow(int[] px, int W, int H, ArrayList<FaceBox> faces, float strength) {
        float add = 6f + 16f * strength;
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            int x0 = Math.max(0, (int) (f.cx - f.halfW)), x1 = Math.min(W - 1, (int) (f.cx + f.halfW));
            int y0 = Math.max(0, (int) (f.cy - f.halfH)), y1 = Math.min(H - 1, (int) (f.cy + f.halfH));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int idx = y * W + x;
                    int c = px[idx];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    float w = faceWeightAt(faces, x, y) * skinLikelihood(c);
                    if (w <= 0.02f) {
                        continue;
                    }
                    int nr = Math.min(255, (int) (r + add * w));
                    int ng = Math.min(255, (int) (g + add * w));
                    int nb = Math.min(255, (int) (b + add * w * 0.9f));
                    px[idx] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                }
            }
        }
    }

    //endregion
}
