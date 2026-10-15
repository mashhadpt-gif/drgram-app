package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DrGram Fix 45: ML Kit powered face detection + selfie segmentation.
 *
 * Bundled models (com.google.mlkit) — no Play Services module download,
 * fully offline, deterministic across devices. This replaces the weak
 * detection stack: hardware Camera.Face (unavailable on many front cameras)
 * and the GMS-vision thin model (needs a runtime download, often not
 * operational) and the legacy android.media detector (eyes only).
 *
 * All blocking calls MUST run on a background thread (Tasks.await).
 */
public class DrGramFaceEngine {

    public static final class FaceResult {
        public float cx, cy, halfW, halfH;      // pixels, bitmap space
        public float noseX = Float.NaN, noseY = Float.NaN;
        public float mouthX = Float.NaN, mouthY = Float.NaN;
        public float leftEyeX = Float.NaN, leftEyeY = Float.NaN;
        public float rightEyeX = Float.NaN, rightEyeY = Float.NaN;
        public float smiling = -1f, leftEyeOpen = -1f, rightEyeOpen = -1f;
    }

    private static volatile com.google.mlkit.vision.face.FaceDetector faceDetector;
    private static final Object faceLock = new Object();

    private static com.google.mlkit.vision.face.FaceDetector detector() {
        if (faceDetector == null) {
            synchronized (faceLock) {
                if (faceDetector == null) {
                    com.google.mlkit.vision.face.FaceDetectorOptions opts =
                        new com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                            .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                            .setLandmarkMode(com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .setContourMode(com.google.mlkit.vision.face.FaceDetectorOptions.CONTOUR_MODE_NONE)
                            .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                            .setMinFaceSize(0.07f)
                            .build();
                    faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(opts);
                }
            }
        }
        return faceDetector;
    }

    /** Blocking ML Kit face detection. Empty list = nothing found / engine unavailable. */
    public static ArrayList<FaceResult> detectBlocking(Bitmap bitmap, long timeoutMs) {
        ArrayList<FaceResult> out = new ArrayList<>();
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return out;
        }
        try {
            com.google.mlkit.vision.common.InputImage image =
                com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0);
            com.google.android.gms.tasks.Task<List<com.google.mlkit.vision.face.Face>> task =
                detector().process(image);
            List<com.google.mlkit.vision.face.Face> faces =
                com.google.android.gms.tasks.Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS);
            if (faces == null) {
                return out;
            }
            for (com.google.mlkit.vision.face.Face f : faces) {
                if (f == null) continue;
                Rect bb = f.getBoundingBox();
                if (bb == null || bb.width() < 24 || bb.height() < 24) continue;
                FaceResult r = new FaceResult();
                r.cx = bb.exactCenterX();
                r.cy = bb.exactCenterY();
                r.halfW = Math.max(4f, bb.width() * 0.5f);
                r.halfH = Math.max(4f, bb.height() * 0.5f);
                attach(r, f, com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE, true, false);
                attach(r, f, com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE, false, true);
                attach(r, f, com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE, false, false);
                PointF mouthBottom = landmarkPos(f, com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM);
                PointF mouthLeft = landmarkPos(f, com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT);
                PointF mouthRight = landmarkPos(f, com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT);
                if (mouthLeft != null && mouthRight != null) {
                    r.mouthX = (mouthLeft.x + mouthRight.x) * 0.5f;
                    r.mouthY = (mouthLeft.y + mouthRight.y) * 0.5f;
                } else if (mouthBottom != null) {
                    r.mouthX = mouthBottom.x;
                    r.mouthY = mouthBottom.y;
                }
                Float sm = f.getSmilingProbability();
                if (sm != null) r.smiling = sm;
                Float lo = f.getLeftEyeOpenProbability();
                if (lo != null) r.leftEyeOpen = lo;
                Float ro = f.getRightEyeOpenProbability();
                if (ro != null) r.rightEyeOpen = ro;
                out.add(r);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return out;
    }

    private static PointF landmarkPos(com.google.mlkit.vision.face.Face f, int type) {
        com.google.mlkit.vision.face.FaceLandmark lm = f.getLandmark(type);
        return lm == null ? null : lm.getPosition();
    }

    private static void attach(FaceResult r, com.google.mlkit.vision.face.Face f, int type, boolean nose, boolean leftEye) {
        PointF p = landmarkPos(f, type);
        if (p == null) return;
        if (nose) {
            r.noseX = p.x;
            r.noseY = p.y;
        } else if (leftEye) {
            r.leftEyeX = p.x;
            r.leftEyeY = p.y;
        } else {
            r.rightEyeX = p.x;
            r.rightEyeY = p.y;
        }
    }

    /** Blocking selfie segmentation. Returns an ALPHA_8 mask sized to input (alpha = person confidence), or null. */
    public static Bitmap personMaskBlocking(Bitmap bitmap, long timeoutMs) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return null;
        }
        try {
            com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions opts =
                new com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.Builder()
                    .setDetectorMode(com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build();
            com.google.mlkit.vision.segmentation.selfie.SelfieSegmentation segmenter =
                com.google.mlkit.vision.segmentation.selfie.SelfieSegmentation.getClient(opts);
            com.google.mlkit.vision.segmentation.selfie.SelfieSegmentationMask result =
                com.google.android.gms.tasks.Tasks.await(
                    segmenter.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)),
                    timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null || result.getBuffer() == null) {
                return null;
            }
            Bitmap buffer = result.getBuffer();
            Bitmap copy = buffer.copy(Bitmap.Config.ALPHA_8, false);
            // do NOT recycle buffer — the segmenter may hold an internal reference
            return copy;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public static void release() {
        synchronized (faceLock) {
            if (faceDetector != null) {
                try { faceDetector.close(); } catch (Throwable ignore) {}
                faceDetector = null;
            }
        }
    }
}
