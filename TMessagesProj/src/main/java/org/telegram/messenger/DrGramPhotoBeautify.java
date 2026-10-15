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
        float noseRadiusX, noseRadiusY, mouthHalfW, mouthHalfH;
        float eyeLX, eyeLY, eyeRX, eyeRY; // DrGram Fix 47: eye anchors for under-eye work
        boolean hasPreciseLandmarks;
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
        return process(bitmap, cameraFaces, strength, nose, teeth, fxId, 0f, 0f);
    }

    /** Full still-photo beauty pipeline, including conservative eye/jaw shaping. */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth, int fxId, float eyes, float jaw) {
        return process(bitmap, cameraFaces, strength, nose, teeth, fxId, eyes, jaw, 0f, 0f, 0f);
    }

    /**
     * DrGram Fix 46: full pipeline plus the standalone makeup suite
     * (blush / lip tint / skin brightening), all 0..1, zero = off.
     */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth, int fxId, float eyes, float jaw, float blush, float lips, float bright) {
        return process(bitmap, cameraFaces, strength, nose, teeth, fxId, eyes, jaw, blush, lips, bright, 0f, 0f);
    }

    /**
     * DrGram Fix 47: complete pipeline — adds under-eye brightening (dark
     * circles) and mid-face cheek slimming on top of the Fix 46 suite.
     * All intensities 0..1, zero = off.
     */
    public static Bitmap process(Bitmap bitmap, Camera.Face[] cameraFaces, float strength, float nose, float teeth, int fxId, float eyes, float jaw, float blush, float lips, float bright, float dark, float slim) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return bitmap;
        }
        fxId = Math.max(0, Math.min(20, fxId));
        eyes = clamp01(eyes);
        jaw = clamp01(jaw);
        blush = clamp01(blush);
        lips = clamp01(lips);
        bright = clamp01(bright);
        dark = clamp01(dark);
        slim = clamp01(slim);
        if (strength <= 0.001f && nose <= 0.001f && teeth <= 0.001f && eyes <= 0.001f && jaw <= 0.001f
                && blush <= 0.001f && lips <= 0.001f && bright <= 0.001f && dark <= 0.001f && slim <= 0.001f && fxId == 0) {
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

            // Geometry edits use a source copy so sampling never feeds on already warped pixels.
            if (!faces.isEmpty() && (eyes > 0.001f || jaw > 0.001f || slim > 0.001f) && (long) W * H <= 8_000_000L) {
                // Keep the extra source copy bounded on high-resolution phones.
                warpFaceShape(px, W, H, faces, eyes, jaw, slim); // DrGram Fix 47: slim
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
            if (bright > 0.001f && !faces.isEmpty()) {
                brightenSkin(px, W, H, faces, bright); // DrGram Fix 46
            }
            if (dark > 0.001f && !faces.isEmpty()) {
                brightenUnderEyes(px, W, H, faces, dark); // DrGram Fix 47
            }
            if (strength > 0.001f && !faces.isEmpty()) {
                faceGlow(px, W, H, faces, strength);
            }
            if ((blush > 0.001f || lips > 0.001f) && !faces.isEmpty()) {
                applyMakeup(px, W, H, faces, blush, lips); // DrGram Fix 46
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

    /** DrGram Fix 47: slim band added; eyes/jaw keep the Fix 46 behaviour. */
    private static void warpFaceShape(int[] px, int W, int H, ArrayList<FaceBox> faces, float eyes, float jaw, float slim) {
        int[] source = px.clone();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float sx = x, sy = y;
                for (FaceBox face : faces) {
                    float hw = Math.min(Math.abs(face.halfW), W * 0.24f);
                    float hh = Math.min(Math.abs(face.halfH), H * 0.30f);
                    if (hw < 4f || hh < 4f) continue;
                    float dx = x - face.cx;
                    float dy = y - face.cy;
                    if (Math.abs(dx) > hw * 1.15f || Math.abs(dy) > hh * 1.20f) continue;

                    if (eyes > 0.001f) {
                        for (int side = -1; side <= 1; side += 2) {
                            float ex = side < 0 ? face.eyeLX : face.eyeRX;
                            float ey = side < 0 ? face.eyeLY : face.eyeRY;
                            float qx = (sx - ex) / Math.max(1f, hw * 0.24f);
                            float qy = (sy - ey) / Math.max(1f, hh * 0.13f);
                            float d = qx * qx + qy * qy;
                            if (d < 1f) {
                                float edge = 1f - d;
                                float amount = eyes * 0.13f * edge * edge;
                                sx = ex + (sx - ex) * (1f - amount);
                                sy = ey + (sy - ey) * (1f - amount);
                            }
                        }
                    }
                    if (jaw > 0.001f) {
                        float vertical = (y - (face.cy + hh * 0.02f)) / Math.max(1f, hh * 0.95f);
                        float rise = smoothBeauty(0.05f, 0.32f, vertical);
                        float fall = 1f - smoothBeauty(0.82f, 1.08f, vertical);
                        float lateral = 1f - smoothBeauty(hw * 0.48f, hw * 0.98f, Math.abs(dx));
                        float amount = jaw * 0.10f * rise * fall * lateral;
                        sx = face.cx + (sx - face.cx) / Math.max(0.84f, 1f - amount);
                    }
                    // DrGram Fix 47: cheek slimming — mirrors the shader band:
                    // mid-face horizontal narrowing that fades before the eye
                    // line and at the mouth so features stay untouched.
                    if (slim > 0.001f) {
                        float ty = (y - face.cy) / Math.max(1f, hh);
                        float band = smoothBeauty(-0.18f, 0.04f, ty) * (1f - smoothBeauty(0.34f, 0.58f, ty));
                        float lateral = 1f - smoothBeauty(hw * 0.44f, hw * 0.96f, Math.abs(dx));
                        float amount = slim * 0.10f * band * lateral;
                        sx = face.cx + (sx - face.cx) / Math.max(0.84f, 1f - amount);
                    }
                }
                px[y * W + x] = sampleBilinear(source, W, H, sx, sy);
            }
        }
    }

    private static float smoothBeauty(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x < edge0 ? 0f : 1f;
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static int sampleBilinear(int[] src, int W, int H, float x, float y) {
        x = Math.max(0f, Math.min(W - 1.001f, x));
        y = Math.max(0f, Math.min(H - 1.001f, y));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(W - 1, x0 + 1), y1 = Math.min(H - 1, y0 + 1);
        float fx = x - x0, fy = y - y0;
        int c00 = src[y0 * W + x0], c10 = src[y0 * W + x1];
        int c01 = src[y1 * W + x0], c11 = src[y1 * W + x1];
        int a = blendChannel(c00 >>> 24, c10 >>> 24, c01 >>> 24, c11 >>> 24, fx, fy);
        int r = blendChannel((c00 >> 16) & 255, (c10 >> 16) & 255, (c01 >> 16) & 255, (c11 >> 16) & 255, fx, fy);
        int g = blendChannel((c00 >> 8) & 255, (c10 >> 8) & 255, (c01 >> 8) & 255, (c11 >> 8) & 255, fx, fy);
        int b = blendChannel(c00 & 255, c10 & 255, c01 & 255, c11 & 255, fx, fy);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int blendChannel(int c00, int c10, int c01, int c11, float fx, float fy) {
        float top = c00 + (c10 - c00) * fx;
        float bottom = c01 + (c11 - c01) * fx;
        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * fy)));
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
                b.noseRadiusX = b.halfW * 0.18f; b.noseRadiusY = b.halfH * 0.16f;
                b.mouthHalfW = b.halfW * 0.28f; b.mouthHalfH = b.halfH * 0.09f;
                // DrGram Fix 47: geometric eye estimate is the default; real
                // hardware landmarks below replace it when the camera supplies them.
                b.eyeLX = b.cx - b.halfW * 0.27f; b.eyeLY = b.cy - b.halfH * 0.20f;
                b.eyeRX = b.cx + b.halfW * 0.27f; b.eyeRY = b.cy - b.halfH * 0.20f;
                // Use hardware landmarks for the still-photo path too, when provided.
                try {
                    android.graphics.Point leftEye = f.leftEye;
                    android.graphics.Point rightEye = f.rightEye;
                    android.graphics.Point mouth = f.mouth;
                    if (leftEye != null && rightEye != null) {
                        float eyeX = ((leftEye.x + rightEye.x) * 0.5f + 1000f) / 2000f * W;
                        float eyeY = ((leftEye.y + rightEye.y) * 0.5f + 1000f) / 2000f * H;
                        b.noseX = eyeX;
                        b.noseY = eyeY + (b.mouthY - eyeY) * 0.58f;
                        float eyeDistance = (float) Math.hypot(rightEye.x - leftEye.x, rightEye.y - leftEye.y) / 2000f * W;
                        b.noseRadiusX = Math.max(2f, eyeDistance * 0.19f);
                        b.noseRadiusY = Math.max(2f, eyeDistance * 0.16f * H / Math.max(1f, W));
                        b.hasPreciseLandmarks = true;
                        // DrGram Fix 47: real hardware eye anchors.
                        b.eyeLX = (leftEye.x + 1000f) / 2000f * W;
                        b.eyeLY = (leftEye.y + 1000f) / 2000f * H;
                        b.eyeRX = (rightEye.x + 1000f) / 2000f * W;
                        b.eyeRY = (rightEye.y + 1000f) / 2000f * H;
                    }
                    if (mouth != null) {
                        b.mouthX = (mouth.x + 1000f) / 2000f * W;
                        b.mouthY = (mouth.y + 1000f) / 2000f * H;
                        b.mouthHalfW = Math.max(2f, b.halfW * 0.22f);
                        b.mouthHalfH = Math.max(2f, b.halfH * 0.07f);
                        b.hasPreciseLandmarks = true;
                    }
                } catch (Throwable ignore) {
                    // Optional camera landmarks are not available on all devices.
                }
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

    /**
     * Accurate landmark detector using Google Mobile Vision (already included by
     * the app). Detection runs on a downscaled copy to keep camera memory bounded.
     * Nose and mouth masks are derived from landmarks rather than face-box guesses.
     */
    private static ArrayList<FaceBox> detectFacesFallback(int[] px, int W, int H) {
        ArrayList<FaceBox> list = new ArrayList<>();
        Bitmap probe = null;
        com.google.android.gms.vision.face.FaceDetector detector = null;
        try {
            // DrGram Fix 46: multi-scale ML Kit retry — the 640 probe misses
            // small / distant faces; one higher-resolution pass rescues them
            // before the legacy chains are tried.
            final int[] probeScales = {640, 1080};
            for (int scaleIdx = 0; scaleIdx < probeScales.length && list.isEmpty(); scaleIdx++) {
                final int maxSide = probeScales[scaleIdx];
                float scale = Math.min(1f, maxSide / (float) Math.max(W, H));
                int probeW = Math.max(160, Math.round(W * scale));
                int probeH = Math.max(160, Math.round(H * scale));
                int[] sample = new int[probeW * probeH];
                for (int y = 0; y < probeH; y++) {
                    int srcY = Math.min(H - 1, (int) ((long) y * H / probeH));
                    for (int x = 0; x < probeW; x++) {
                        int srcX = Math.min(W - 1, (int) ((long) x * W / probeW));
                        sample[y * probeW + x] = px[srcY * W + srcX] | 0xFF000000;
                    }
                }
                if (probe != null && !probe.isRecycled()) {
                    probe.recycle();
                }
                probe = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888);
                probe.setPixels(sample, 0, probeW, 0, 0, probeW, probeH);

                // DrGram Fix 45/46: bundled ML Kit first — accurate landmarks, offline,
                // no Play-Services model download. Falls through to GMS Vision and
                // the legacy detector when it finds nothing or fails.
                ArrayList<FaceBox> mlkit = detectMlKitFaces(probe, W, H);
                if (!mlkit.isEmpty()) {
                    list.addAll(mlkit);
                }
            }
            if (!list.isEmpty()) {
                return list;
            }

            detector = new com.google.android.gms.vision.face.FaceDetector.Builder(ApplicationLoader.applicationContext)
                    .setLandmarkType(com.google.android.gms.vision.face.FaceDetector.ALL_LANDMARKS)
                    .setMode(com.google.android.gms.vision.face.FaceDetector.ACCURATE_MODE)
                    .setProminentFaceOnly(false)
                    .build();
            if (!detector.isOperational()) {
                return detectLegacyFaces(px, W, H);
            }
            android.util.SparseArray<com.google.android.gms.vision.face.Face> found =
                    detector.detect(new com.google.android.gms.vision.Frame.Builder().setBitmap(probe).build());
            for (int i = 0; i < found.size() && list.size() < 5; i++) {
                com.google.android.gms.vision.face.Face face = found.valueAt(i);
                if (face == null || face.getWidth() < 18 || face.getHeight() < 18) continue;
                FaceBox b = new FaceBox();
                float invScaleX = W / (float) probe.getWidth(), invScaleY = H / (float) probe.getHeight();
                b.cx = (face.getPosition().x + face.getWidth() * 0.5f) * invScaleX;
                b.cy = (face.getPosition().y + face.getHeight() * 0.5f) * invScaleY;
                b.halfW = face.getWidth() * 0.5f * invScaleX;
                b.halfH = face.getHeight() * 0.5f * invScaleY;
                b.noseX = b.cx;
                b.noseY = b.cy + b.halfH * 0.08f;
                b.mouthX = b.cx;
                b.mouthY = b.cy + b.halfH * 0.48f;
                b.noseRadiusX = Math.max(2f, b.halfW * 0.15f);
                b.noseRadiusY = Math.max(2f, b.halfH * 0.13f);
                b.mouthHalfW = Math.max(2f, b.halfW * 0.25f);
                b.mouthHalfH = Math.max(2f, b.halfH * 0.07f);
                // DrGram Fix 47: geometric eye estimate; GMS landmarks replace it below.
                b.eyeLX = b.cx - b.halfW * 0.27f; b.eyeLY = b.cy - b.halfH * 0.20f;
                b.eyeRX = b.cx + b.halfW * 0.27f; b.eyeRY = b.cy - b.halfH * 0.20f;
                float leftEyeX = Float.NaN, rightEyeX = Float.NaN, eyeY = Float.NaN;
                float noseLandmarkX = Float.NaN, noseLandmarkY = Float.NaN;
                float mouthLeftX = Float.NaN, mouthRightX = Float.NaN, mouthLandmarkY = Float.NaN;
                for (com.google.android.gms.vision.face.Landmark landmark : face.getLandmarks()) {
                    android.graphics.PointF p = landmark.getPosition();
                    float lx = p.x * invScaleX, ly = p.y * invScaleY;
                    switch (landmark.getType()) {
                        case com.google.android.gms.vision.face.Landmark.LEFT_EYE:
                            leftEyeX = lx; eyeY = Float.isNaN(eyeY) ? ly : (eyeY + ly) * 0.5f; break;
                        case com.google.android.gms.vision.face.Landmark.RIGHT_EYE:
                            rightEyeX = lx; eyeY = Float.isNaN(eyeY) ? ly : (eyeY + ly) * 0.5f; break;
                        case com.google.android.gms.vision.face.Landmark.NOSE_BASE:
                            noseLandmarkX = lx; noseLandmarkY = ly; break;
                        case com.google.android.gms.vision.face.Landmark.LEFT_MOUTH:
                            mouthLeftX = lx; mouthLandmarkY = Float.isNaN(mouthLandmarkY) ? ly : (mouthLandmarkY + ly) * 0.5f; break;
                        case com.google.android.gms.vision.face.Landmark.RIGHT_MOUTH:
                            mouthRightX = lx; mouthLandmarkY = Float.isNaN(mouthLandmarkY) ? ly : (mouthLandmarkY + ly) * 0.5f; break;
                        case com.google.android.gms.vision.face.Landmark.BOTTOM_MOUTH:
                            if (Float.isNaN(mouthLandmarkY)) mouthLandmarkY = ly;
                            else mouthLandmarkY = (mouthLandmarkY + ly) * 0.5f;
                            break;
                    }
                }
                if (!Float.isNaN(noseLandmarkX) && !Float.isNaN(noseLandmarkY)) {
                    b.noseX = noseLandmarkX; b.noseY = noseLandmarkY;
                    if (!Float.isNaN(leftEyeX) && !Float.isNaN(rightEyeX)) {
                        float eyeDistance = Math.abs(rightEyeX - leftEyeX);
                        b.noseRadiusX = Math.max(2f, eyeDistance * 0.16f);
                        b.noseRadiusY = Math.max(2f, eyeDistance * 0.15f);
                    }
                    b.hasPreciseLandmarks = true;
                }
                if (!Float.isNaN(mouthLeftX) && !Float.isNaN(mouthRightX)) {
                    b.mouthX = (mouthLeftX + mouthRightX) * 0.5f;
                    b.mouthHalfW = Math.max(2f, Math.abs(mouthRightX - mouthLeftX) * 0.58f);
                    b.mouthHalfH = Math.max(2f, b.halfH * 0.055f);
                    b.mouthY = Float.isNaN(mouthLandmarkY) ? b.mouthY : mouthLandmarkY;
                    b.hasPreciseLandmarks = true;
                }
                // DrGram Fix 47: real GMS eye landmarks for under-eye work.
                if (!Float.isNaN(leftEyeX) && !Float.isNaN(rightEyeX) && !Float.isNaN(eyeY)) {
                    b.eyeLX = leftEyeX; b.eyeLY = eyeY;
                    b.eyeRX = rightEyeX; b.eyeRY = eyeY;
                }
                list.add(b);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (detector != null) {
                try { detector.release(); } catch (Throwable ignore) {}
            }
            if (probe != null && !probe.isRecycled()) probe.recycle();
        }
        return list.isEmpty() ? detectLegacyFaces(px, W, H) : list;
    }

    /** DrGram Fix 45: bundled ML Kit detector — accurate face rects with real nose/eye/mouth landmarks, fully offline. */
    private static ArrayList<FaceBox> detectMlKitFaces(Bitmap probe, int W, int H) {
        ArrayList<FaceBox> list = new ArrayList<>();
        try {
            ArrayList<DrGramFaceEngine.FaceResult> found = DrGramFaceEngine.detectBlocking(probe, 2500);
            if (found.isEmpty()) {
                return list;
            }
            float invScaleX = W / (float) probe.getWidth();
            float invScaleY = H / (float) probe.getHeight();
            for (int i = 0; i < found.size() && list.size() < 5; i++) {
                DrGramFaceEngine.FaceResult f = found.get(i);
                if (f == null) continue;
                FaceBox b = new FaceBox();
                b.cx = f.cx * invScaleX;
                b.cy = f.cy * invScaleY;
                b.halfW = Math.max(4f, f.halfW * invScaleX);
                b.halfH = Math.max(4f, f.halfH * invScaleY);
                b.noseX = Float.isNaN(f.noseX) ? b.cx : f.noseX * invScaleX;
                b.noseY = Float.isNaN(f.noseY) ? b.cy + b.halfH * 0.08f : f.noseY * invScaleY;
                b.mouthX = Float.isNaN(f.mouthX) ? b.cx : f.mouthX * invScaleX;
                b.mouthY = Float.isNaN(f.mouthY) ? b.cy + b.halfH * 0.48f : f.mouthY * invScaleY;
                // DrGram Fix 47: geometric eye estimate; ML Kit landmarks replace it below.
                b.eyeLX = b.cx - b.halfW * 0.27f; b.eyeLY = b.cy - b.halfH * 0.20f;
                b.eyeRX = b.cx + b.halfW * 0.27f; b.eyeRY = b.cy - b.halfH * 0.20f;
                if (!Float.isNaN(f.leftEyeX) && !Float.isNaN(f.rightEyeX)) {
                    float eyeDistance = Math.abs(f.rightEyeX - f.leftEyeX) * invScaleX;
                    b.noseRadiusX = Math.max(2f, eyeDistance * 0.16f);
                    b.noseRadiusY = Math.max(2f, eyeDistance * 0.15f);
                    b.hasPreciseLandmarks = true;
                    // DrGram Fix 47: real ML Kit eye anchors.
                    b.eyeLX = f.leftEyeX * invScaleX;
                    b.eyeLY = f.leftEyeY * invScaleY;
                    b.eyeRX = f.rightEyeX * invScaleX;
                    b.eyeRY = f.rightEyeY * invScaleY;
                } else {
                    b.noseRadiusX = Math.max(2f, b.halfW * 0.15f);
                    b.noseRadiusY = Math.max(2f, b.halfH * 0.13f);
                }
                b.mouthHalfW = Math.max(2f, b.halfW * 0.25f);
                b.mouthHalfH = Math.max(2f, b.halfH * (Float.isNaN(f.mouthX) ? 0.07f : 0.055f));
                if (!Float.isNaN(f.mouthX)) {
                    b.hasPreciseLandmarks = true;
                }
                list.add(b);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return list;
    }

    /** Last-resort detector for devices where Google Mobile Vision is unavailable. */
    private static ArrayList<FaceBox> detectLegacyFaces(int[] px, int W, int H) {
        ArrayList<FaceBox> list = new ArrayList<>();
        Bitmap bmp = null;
        try {
            int probeW = 320;
            int probeH = Math.max(1, Math.round(H * (probeW / (float) W)));
            int[] probe = new int[probeW * probeH];
            for (int y = 0; y < probeH; y++) {
                int sy = Math.min(H - 1, (int) ((long) y * H / probeH));
                for (int x = 0; x < probeW; x++) {
                    int c = px[sy * W + Math.min(W - 1, (int) ((long) x * W / probeW))];
                    int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                    probe[y * probeW + x] = 0xFF000000 | ((rr & 0xF8) << 16) | ((gg & 0xFC) << 8) | (bb & 0xF8);
                }
            }
            bmp = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.RGB_565);
            bmp.setPixels(probe, 0, probeW, 0, 0, probeW, probeH);
            android.media.FaceDetector legacy = new android.media.FaceDetector(probeW, probeH, 2);
            android.media.FaceDetector.Face[] found = new android.media.FaceDetector.Face[2];
            int n = legacy.findFaces(bmp, found);
            for (int i = 0; i < n; i++) {
                android.media.FaceDetector.Face face = found[i];
                if (face == null || face.confidence() < 0.4f) continue;
                PointF mid = new PointF(); face.getMidPoint(mid);
                float d = face.eyesDistance();
                FaceBox b = new FaceBox();
                b.cx = mid.x / probeW * W; b.cy = (mid.y + d * 0.55f) / probeH * H;
                b.halfW = d * 1.15f / probeW * W; b.halfH = d * 1.55f / probeH * H;
                b.noseX = b.cx; b.noseY = b.cy + b.halfH * 0.08f;
                b.mouthX = b.cx; b.mouthY = b.cy + b.halfH * 0.48f;
                b.noseRadiusX = b.halfW * 0.15f; b.noseRadiusY = b.halfH * 0.13f;
                b.mouthHalfW = b.halfW * 0.25f; b.mouthHalfH = b.halfH * 0.07f;
                b.eyeLX = b.cx - b.halfW * 0.27f; b.eyeLY = b.cy - b.halfH * 0.20f; // DrGram Fix 47
                b.eyeRX = b.cx + b.halfW * 0.27f; b.eyeRY = b.cy - b.halfH * 0.20f; // DrGram Fix 47
                list.add(b);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
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
            // Landmark-sized mask: avoid pulling cheeks and the area beside the nose.
            int rx = Math.max(2, (int) (f.noseRadiusX > 0 ? f.noseRadiusX : f.halfW * 0.15f));
            int ry = Math.max(2, (int) (f.noseRadiusY > 0 ? f.noseRadiusY : f.halfH * 0.13f));
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
            // Restrict whitening to the mouth landmarks; don't brighten skin around the lips.
            int rx = Math.max(2, (int) (f.mouthHalfW > 0 ? f.mouthHalfW : f.halfW * 0.25f));
            int ry = Math.max(2, (int) (f.mouthHalfH > 0 ? f.mouthHalfH : f.halfH * 0.07f));
            int cx = (int) f.mouthX, cy = (int) f.mouthY;
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

    /**
     * DrGram Fix 46: standalone user-controlled makeup. Anchors come from the
     * detected face (ML Kit landmarks when available), scaled 0..1 by the
     * beauty panel sliders; zero keeps each feature untouched.
     */
    private static void applyMakeup(int[] px, int W, int H, ArrayList<FaceBox> faces, float blush, float lips) {
        for (FaceBox f : faces) {
            if (blush > 0.001f) {
                float rx = f.halfW * 0.24f, ry = f.halfH * 0.13f;
                int color = 0xFFEF8A9A; // soft rose, layered by slider strength
                tintEllipse(px, W, H, f.cx - f.halfW * 0.36f, f.cy + f.halfH * 0.20f, rx, ry, color, 0.30f * blush);
                tintEllipse(px, W, H, f.cx + f.halfW * 0.36f, f.cy + f.halfH * 0.20f, rx, ry, color, 0.30f * blush);
            }
            if (lips > 0.001f) {
                tintEllipse(px, W, H, f.mouthX, f.mouthY - f.halfH * 0.02f,
                        f.halfW * 0.25f, f.halfH * 0.085f, 0xFFB92A4B, 0.36f * lips);
            }
        }
    }

    /**
     * DrGram Fix 46: skin-masked, face-weighted luminance lift — a cleaner
     * brightening than the legacy flat glow (hair/eyes/background stay put).
     */
    private static void brightenSkin(int[] px, int W, int H, ArrayList<FaceBox> faces, float bright) {
        float add = 4f + 22f * bright;
        for (int i = 0; i < faces.size(); i++) {
            FaceBox f = faces.get(i);
            int x0 = Math.max(0, (int) (f.cx - f.halfW * 1.3f)), x1 = Math.min(W - 1, (int) (f.cx + f.halfW * 1.3f));
            int y0 = Math.max(0, (int) (f.cy - f.halfH * 1.3f)), y1 = Math.min(H - 1, (int) (f.cy + f.halfH * 1.3f));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int idx = y * W + x;
                    int c = px[idx];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    float w = faceWeightAt(faces, x, y) * skinLikelihood(c);
                    if (w <= 0.02f) {
                        continue;
                    }
                    int nr = clamp255((int) (r + add * w));
                    int ng = clamp255((int) (g + add * w * 0.985f));
                    int nb = clamp255((int) (b + add * w * 0.94f));
                    px[idx] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                }
            }
        }
    }

    /**
     * DrGram Fix 47: under-eye (dark circle) brightening for still photos.
     * Mirrors the shader pass — a soft ellipse under each eye anchor lifts
     * only the darker pixels toward the surrounding skin tone, so the eye
     * itself, lashes and highlights stay untouched.
     */
    private static void brightenUnderEyes(int[] px, int W, int H, ArrayList<FaceBox> faces, float dark) {
        for (FaceBox face : faces) {
            float hw = Math.min(Math.abs(face.halfW), W * 0.24f);
            float hh = Math.min(Math.abs(face.halfH), H * 0.30f);
            if (hw < 4f || hh < 4f) continue;
            float rx = Math.max(2f, hw * 0.17f);
            float ry = Math.max(2f, hh * 0.075f);
            float[][] anchors = {
                    {face.eyeLX, face.eyeLY + hh * 0.085f},
                    {face.eyeRX, face.eyeRY + hh * 0.085f}
            };
            for (float[] a : anchors) {
                float cx = a[0], cy = a[1];
                if (Float.isNaN(cx) || Float.isNaN(cy)) continue;
                int x0 = Math.max(0, (int) (cx - rx)), x1 = Math.min(W - 1, (int) (cx + rx));
                int y0 = Math.max(0, (int) (cy - ry)), y1 = Math.min(H - 1, (int) (cy + ry));
                for (int y = y0; y <= y1; y++) {
                    float dy = (y - cy) / ry;
                    for (int x = x0; x <= x1; x++) {
                        float dx = (x - cx) / rx;
                        float edge = 1f - dx * dx - dy * dy;
                        if (edge <= 0f) continue;
                        float falloff = 1f - smoothBeauty(0.55f, 1.35f, (float) Math.sqrt(dx * dx + dy * dy));
                        if (falloff <= 0f) continue;
                        int idx = y * W + x, c = px[idx];
                        float r = ((c >> 16) & 255) / 255f;
                        float g = ((c >> 8) & 255) / 255f;
                        float b = (c & 255) / 255f;
                        float lum = r * 0.299f + g * 0.587f + b * 0.114f;
                        // Only the darker half-moon: bright pixels stay as they are.
                        float isDark = 1f - smoothBeauty(0.18f, 0.52f, lum);
                        if (isDark <= 0f) continue;
                        float k = dark * falloff * isDark * 0.55f;
                        int nr = (int) (((c >> 16) & 255) + 255f * 0.075f * k);
                        int ng = (int) (((c >> 8) & 255) + 255f * 0.066f * k);
                        int nb = (int) ((c & 255) + 255f * 0.060f * k);
                        px[idx] = (c & 0xFF000000) | (clamp255(nr) << 16) | (clamp255(ng) << 8) | clamp255(nb);
                    }
                }
            }
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

    //region portrait background blur (Fix 45)

    /**
     * DrGram Fix 45: portrait mode for photo stories — everything that is not
     * the subject gets a creamy blur while the subject (hair edges included,
     * via the segmentation mask's soft alpha) stays sharp. Never blurs blind:
     * when the segmentation model is unavailable the photo is returned intact.
     */
    public static void applyPortraitBackgroundBlur(Bitmap bitmap, float strength) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null || strength <= 0.01f) {
            return;
        }
        Bitmap mask = null;
        try {
            final int W = bitmap.getWidth(), H = bitmap.getHeight();
            if (W < 16 || H < 16 || (long) W * H > 12_000_000) {
                return;
            }
            mask = DrGramFaceEngine.personMaskBlocking(bitmap, 4000);
            if (mask == null) {
                return;
            }
            if (mask.getWidth() != W || mask.getHeight() != H) {
                Bitmap scaled = Bitmap.createScaledBitmap(mask, W, H, true);
                if (scaled != null && scaled != mask) {
                    mask.recycle();
                    mask = scaled;
                }
            }
            if (mask.getWidth() != W || mask.getHeight() != H) {
                return;
            }

            int[] px = new int[W * H];
            bitmap.getPixels(px, 0, W, 0, 0, W, H);
            int[] mp = new int[W * H];
            mask.getPixels(mp, 0, W, 0, 0, W, H);

            // background source: strong downscale + two box-blur passes + upscale
            int bw = Math.max(24, W / 14), bh = Math.max(24, H / 14);
            Bitmap small = Bitmap.createScaledBitmap(bitmap, bw, bh, true);
            int[] sp = new int[bw * bh];
            small.getPixels(sp, 0, bw, 0, 0, bw, bh);
            int radius = Math.max(2, Math.round(2f + strength * 3f));
            boxBlur(sp, bw, bh, radius);
            boxBlur(sp, bw, bh, radius);
            small.setPixels(sp, 0, bw, 0, 0, bw, bh);
            Bitmap blurred = Bitmap.createScaledBitmap(small, W, H, true);
            if (blurred == null) {
                small.recycle();
                return;
            }
            int[] bg = new int[W * H];
            blurred.getPixels(bg, 0, W, 0, 0, W, H);
            small.recycle();
            blurred.recycle();

            for (int i = 0; i < px.length; i++) {
                float a = (mp[i] >>> 24) / 255f;
                // soft step keeps hair / fuzzy edges natural instead of a hard cutout
                float person = smoothstep(0.30f, 0.62f, a);
                if (person >= 0.999f) continue;
                int fg = px[i], bgc = bg[i];
                int ar = Math.round(((fg >> 16) & 0xFF) * person + ((bgc >> 16) & 0xFF) * (1f - person));
                int ag = Math.round(((fg >> 8) & 0xFF) * person + ((bgc >> 8) & 0xFF) * (1f - person));
                int ab = Math.round((fg & 0xFF) * person + (bgc & 0xFF) * (1f - person));
                px[i] = 0xFF000000 | (ar << 16) | (ag << 8) | ab;
            }
            bitmap.setPixels(px, 0, W, 0, 0, W, H);
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (mask != null && !mask.isRecycled()) {
                mask.recycle();
            }
        }
    }

    private static void boxBlur(int[] px, int w, int h, int radius) {
        if (px == null || w <= 0 || h <= 0 || radius <= 0) return;
        int[] tmp = new int[px.length];
        int div = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = px[row + Math.max(0, Math.min(w - 1, i))];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int addI = Math.min(w - 1, x + radius + 1);
                int remI = Math.max(0, x - radius);
                int ca = px[row + addI], cr = px[row + remI];
                sr += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                sg += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                sb += (ca & 0xFF) - (cr & 0xFF);
            }
        }
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int addI = Math.min(h - 1, y + radius + 1);
                int remI = Math.max(0, y - radius);
                int ca = tmp[addI * w + x], cr = tmp[remI * w + x];
                sr += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                sg += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                sb += (ca & 0xFF) - (cr & 0xFF);
            }
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    //endregion
}
