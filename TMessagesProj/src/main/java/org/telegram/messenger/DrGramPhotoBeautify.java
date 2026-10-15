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
     * Processes the bitmap in place (returns the same bitmap). When no face
     * can be localized confidently, the original image is preserved; never
     * fall back to blurring the entire frame.
     */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth) {
        return process(bitmap, cameraFaces, strength, nose, teeth, 0);
    }

    /** Applies face beauty (when detectable) and a whole-frame FX to a still photo. */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth, int fxId) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return bitmap;
        }
        fxId = Math.max(0, Math.min(20, fxId));
        if (strength <= 0.001f && nose <= 0.001f && teeth <= 0.001f && fxId == 0) {
            return bitmap;
        }
        try {
            final int W = bitmap.getWidth(), H = bitmap.getHeight();
            if (W < 8 || H < 8 || (long) W * H > 14_000_000) {
                return bitmap;
            }
            int[] px = new int[W * H];
            bitmap.getPixels(px, 0, W, 0, 0, W, H);

            ArrayList<FaceBox> faces = collectFaces(cameraFaces, W, H);
            if (faces.isEmpty()) {
                faces = detectFacesFallback(px, W, H);
            }

            // If face detection fails, skip face-only retouching but still
            // apply whole-frame color FX requested by the user.
            if (!faces.isEmpty() && strength > 0.001f) {
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
            if (fxId > 0) applyCameraFx(px, W, H, fxId);
            if (fxId == 20 && !faces.isEmpty()) {
                applyGlamMakeup(px, W, H, faces);
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


    /** Still-photo counterpart to the live camera color filters. */
    private static void applyCameraFx(int[] px, int W, int H, int fxId) {
        if (fxId == 11) {
            for (int y=0; y<H; y++) {
                int l=y*W, r=l+W-1;
                while (l<r) { int t=px[l]; px[l]=px[r]; px[r]=t; l++; r--; }
            }
            return;
        }
        if (fxId == 10 || fxId == 12) {
            int block = fxId == 10 ? Math.max(5, Math.min(24, Math.min(W,H)/90)) : 1;
            if (fxId == 10) {
                for (int y=0; y<H; y+=block) for (int x=0; x<W; x+=block) {
                    int c=px[y*W+x];
                    for (int yy=y; yy<Math.min(H,y+block); yy++)
                        for (int xx=x; xx<Math.min(W,x+block); xx++) px[yy*W+xx]=c;
                }
            } else {
                for (int i=0; i<px.length; i++) {
                    int c=px[i], r=(c>>16)&255, g=(c>>8)&255, b=c&255;
                    r=Math.min(255,((r+21)/43)*43); g=Math.min(255,((g+21)/43)*43); b=Math.min(255,((b+21)/43)*43);
                    px[i]=(c&0xFF000000)|(r<<16)|(g<<8)|b;
                }
            }
            return;
        }
        for (int y=0; y<H; y++) {
            int[] rowCopy = fxId == 9 ? java.util.Arrays.copyOfRange(px, y * W, (y + 1) * W) : null;
            for (int x=0; x<W; x++) {
            int idx=y*W+x, c=px[idx];
            int a=(c>>>24)&255, r=(c>>16)&255, g=(c>>8)&255, b=c&255;
            float lum=0.299f*r+0.587f*g+0.114f*b;
            switch(fxId) {
                case 1: r=clamp255((int)(r*1.10f+10)); g=clamp255((int)(g*.98f+2)); b=clamp255((int)(b*.86f-2)); break;
                case 2: r=clamp255((int)(r*.90f)); b=clamp255((int)(b*1.12f+4)); break;
                case 3: r=clamp255((int)(r*.82f+38)); g=clamp255((int)(g*.82f+36)); b=clamp255((int)(b*.82f+42)); break;
                case 4: {
                    int nr=clamp255((int)(.393f*r+.769f*g+.189f*b));
                    int ng=clamp255((int)(.349f*r+.686f*g+.168f*b));
                    int nb=clamp255((int)(.272f*r+.534f*g+.131f*b));
                    r=(int)(r*.38f+nr*.62f); g=(int)(g*.38f+ng*.62f); b=(int)(b*.38f+nb*.62f);
                    float nx=(x-W*.5f)/(W*.5f), ny=(y-H*.5f)/(H*.5f), v=Math.max(.60f,1f-.22f*(nx*nx+ny*ny));
                    r=(int)(r*v); g=(int)(g*v); b=(int)(b*v); break;
                }
                case 5: r=clamp255((int)(r*1.08f+8)); g=clamp255((int)(g*.96f)); b=clamp255((int)(b*1.04f+5)); break;
                case 6: r=g=b=clamp255((int)((lum-128f)*1.35f+128f)); break;
                case 7: r=clamp255((int)(r*1.04f+10)); g=clamp255((int)(g*1.03f+9)); b=clamp255((int)(b*1.04f+10)); break;
                case 8: { float avg=(r+g+b)/3f; r=clamp255((int)(avg+(r-avg)*1.65f+4)); g=clamp255((int)(avg+(g-avg)*1.65f+2)); b=clamp255((int)(avg+(b-avg)*1.65f+8)); break; }
                case 9: {
                    int cr=rowCopy[Math.max(0,Math.min(W-1,x+3))], cb=rowCopy[Math.max(0,Math.min(W-1,x-3))];
                    r=(cr>>16)&255; b=cb&255; break;
                }
                case 13: // soft glow
                    r=clamp255((int)(r*0.82f + (float)Math.sqrt(r / 255f)*255f*0.18f + 4));
                    g=clamp255((int)(g*0.82f + (float)Math.sqrt(g / 255f)*255f*0.18f + 3));
                    b=clamp255((int)(b*0.82f + (float)Math.sqrt(b / 255f)*255f*0.18f + 3)); break;
                case 14: // rose
                    r=clamp255((int)(r*1.07f+7)); g=clamp255((int)(g*0.94f)); b=clamp255((int)(b*1.02f+4)); break;
                case 15: // honey
                    r=clamp255((int)(r*1.10f+5)); g=clamp255((int)(g*1.02f+2)); b=clamp255((int)(b*0.88f)); break;
                case 16: // clean
                    r=clamp255((int)((r-128)*1.08f+130)); g=clamp255((int)((g-128)*1.08f+130)); b=clamp255((int)((b-128)*1.08f+131)); break;
                case 17: // latte
                    r=clamp255((int)(r*0.90f+17)); g=clamp255((int)(g*0.89f+15)); b=clamp255((int)(b*0.85f+13)); break;
                case 18: // ocean
                    r=clamp255((int)(r*0.91f)); g=clamp255((int)(g*1.035f+4)); b=clamp255((int)(b*1.08f+4)); break;
                case 19: // peach
                    r=clamp255((int)(r*1.055f+5)); g=clamp255((int)(g*0.98f+2)); b=clamp255((int)(b*0.96f+3)); break;
                case 20: // glam
                    r=clamp255((int)((r-128)*1.12f+133)); g=clamp255((int)((g-128)*1.12f+130)); b=clamp255((int)((b-128)*1.12f+132)); break;
                default: break;
            }
            px[idx]=(a<<24)|(clamp255(r)<<16)|(clamp255(g)<<8)|clamp255(b);
            }
        }
    }

    /** Restrained cheek and lip tint for the Glam preset; only runs inside detected face regions. */
    private static void applyGlamMakeup(int[] px, int W, int H, ArrayList<FaceBox> faces) {
        for (FaceBox f : faces) {
            tintEllipse(px, W, H, f.cx - f.halfW * 0.36f, f.cy + f.halfH * 0.20f,
                    f.halfW * 0.22f, f.halfH * 0.12f, 0xFFE85D78, 0.20f);
            tintEllipse(px, W, H, f.cx + f.halfW * 0.36f, f.cy + f.halfH * 0.20f,
                    f.halfW * 0.22f, f.halfH * 0.12f, 0xFFE85D78, 0.20f);
            tintEllipse(px, W, H, f.mouthX, f.mouthY - f.halfH * 0.02f,
                    f.halfW * 0.24f, f.halfH * 0.075f, 0xFFB92A4B, 0.16f);
        }
    }

    private static void tintEllipse(int[] px, int W, int H, float cx, float cy, float rx, float ry, int tint, float amount) {
        if (rx < 1f || ry < 1f) return;
        int x0 = Math.max(0, (int) (cx - rx)), x1 = Math.min(W - 1, (int) (cx + rx));
        int y0 = Math.max(0, (int) (cy - ry)), y1 = Math.min(H - 1, (int) (cy + ry));
        int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
        for (int y = y0; y <= y1; y++) {
            float dy = (y - cy) / ry;
            for (int x = x0; x <= x1; x++) {
                float dx = (x - cx) / rx;
                float edge = 1f - dx * dx - dy * dy;
                if (edge <= 0f) continue;
                int idx = y * W + x, c = px[idx];
                float k = amount * edge * edge;
                int rr = (int) (((c >> 16) & 255) * (1f - k) + tr * k);
                int gg = (int) (((c >> 8) & 255) * (1f - k) + tg * k);
                int bb = (int) ((c & 255) * (1f - k) + tb * k);
                px[idx] = (c & 0xFF000000) | (clamp255(rr) << 16) | (clamp255(gg) << 8) | clamp255(bb);
            }
        }
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    //endregion
}
