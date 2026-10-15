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

    // ================= DrGram Fix 48: 468-point face mesh =================
    // Dense facial-organ geometry (real lip polygons, eye rings, jaw oval,
    // nose bridge) for the still-photo beauty pipeline. Bundled TFLite models
    // — fully offline, no Play Services download. Best for selfie-distance
    // faces; when the mesh cannot see a face the callers fall back to the
    // face-detection / GMS / legacy chain unchanged.

    /** Dense organ geometry for one face, in the probed bitmap's pixel space. */
    public static final class MeshResult {
        public float cx, cy, halfW, halfH;
        public float noseX = Float.NaN, noseY = Float.NaN;
        public float leftEyeX = Float.NaN, leftEyeY = Float.NaN;
        public float rightEyeX = Float.NaN, rightEyeY = Float.NaN;
        // Eye ring half-extents (real eyelid bounds, not face-box ratios).
        public float leftEyeHalfW = Float.NaN, leftEyeHalfH = Float.NaN;
        public float rightEyeHalfW = Float.NaN, rightEyeHalfH = Float.NaN;
        // Mean y of the lower eyelid points — the under-eye work anchor.
        public float leftEyeLowerY = Float.NaN, rightEyeLowerY = Float.NaN;
        // Outer lip polygon (upper top contour + reversed lower bottom contour).
        public float[] lipOuterX, lipOuterY;
        // Inner mouth opening (between the lips) — teeth-whitening gate.
        public float[] lipInnerX, lipInnerY;
        // Face oval contour (jawline / cheeks).
        public float[] ovalX, ovalY;
        public int pointCount;
    }

    private static volatile com.google.mlkit.vision.facemesh.FaceMeshDetector meshDetector;
    private static final Object meshLock = new Object();

    private static com.google.mlkit.vision.facemesh.FaceMeshDetector meshDetector() {
        if (meshDetector == null) {
            synchronized (meshLock) {
                if (meshDetector == null) {
                    com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions opts =
                        new com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions.Builder()
                            .setUseCase(com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions.FACE_MESH)
                            .build();
                    meshDetector = com.google.mlkit.vision.facemesh.FaceMeshDetection.getClient(opts);
                }
            }
        }
        return meshDetector;
    }

    /** Blocking 468-point face mesh detection. Empty list = nothing found / engine unavailable. */
    public static ArrayList<MeshResult> meshDetectBlocking(Bitmap bitmap, long timeoutMs) {
        ArrayList<MeshResult> out = new ArrayList<>();
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return out;
        }
        try {
            com.google.mlkit.vision.common.InputImage image =
                com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0);
            com.google.android.gms.tasks.Task<List<com.google.mlkit.vision.facemesh.FaceMesh>> task =
                meshDetector().process(image);
            List<com.google.mlkit.vision.facemesh.FaceMesh> meshes =
                com.google.android.gms.tasks.Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS);
            if (meshes == null) {
                return out;
            }
            for (com.google.mlkit.vision.facemesh.FaceMesh mesh : meshes) {
                if (mesh == null) continue;
                MeshResult r = new MeshResult();
                try {
                    Rect bb = mesh.getBoundingBox();
                    if (bb == null || bb.width() < 28 || bb.height() < 28) continue;
                    r.cx = bb.exactCenterX();
                    r.cy = bb.exactCenterY();
                    r.halfW = Math.max(4f, bb.width() * 0.5f);
                    r.halfH = Math.max(4f, bb.height() * 0.5f);
                    List<com.google.mlkit.vision.facemesh.FaceMeshPoint> all = mesh.getAllPoints();
                    r.pointCount = all == null ? 0 : all.size();
                    if (r.pointCount < 100) continue; // BOUNDING_BOX_ONLY fallback — not usable here
                    // DrGram Fix 48: contour constants are ints on FaceMesh
                    // itself (per the official quickstart sample).
                    fillRing(r, mesh, com.google.mlkit.vision.facemesh.FaceMesh.LEFT_EYE, true);
                    fillRing(r, mesh, com.google.mlkit.vision.facemesh.FaceMesh.RIGHT_EYE, false);
                    fillLips(r, mesh);
                    fillOval(r, mesh);
                    fillNose(r, mesh);
                    boolean eyesOk = !Float.isNaN(r.leftEyeX) && !Float.isNaN(r.rightEyeX);
                    boolean lipsOk = r.lipOuterX != null && r.lipOuterX.length >= 6;
                    if (!eyesOk && !lipsOk) continue;
                    out.add(r);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        } catch (Throwable t) {
            // NoClassDefFoundError on stripped builds lands here — chain keeps working.
            FileLog.e(t);
        }
        return out;
    }

    private static void fillRing(MeshResult r, com.google.mlkit.vision.facemesh.FaceMesh mesh,
                                 int contourType, boolean left) {
        try {
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> pts =
                mesh.getPoints(contourType);
            if (pts == null || pts.isEmpty()) return;
            float sx = 0, sy = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                com.google.mlkit.vision.facemesh.FaceMeshPoint p = pts.get(i);
                if (p == null || p.getPosition() == null) continue;
                float x = p.getPosition().getX(), y = p.getPosition().getY();
                sx += x; sy += y;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            int n = pts.size();
            if (n == 0) return;
            // Second pass with a fixed midline: mean y of the points that sit
            // below the ring's vertical centre (the lower-lid region).
            float lowerSum = 0; int lowerN = 0;
            float midY = (minY + maxY) * 0.5f;
            for (int i = 0; i < pts.size(); i++) {
                com.google.mlkit.vision.facemesh.FaceMeshPoint p = pts.get(i);
                if (p == null || p.getPosition() == null) continue;
                float y = p.getPosition().getY();
                if (y > midY) { lowerSum += y; lowerN++; }
            }
            float cx = sx / n, cy = sy / n;
            float hw = Math.max(1.5f, (maxX - minX) * 0.5f);
            float hh = Math.max(1.5f, (maxY - minY) * 0.5f);
            if (left) {
                r.leftEyeX = cx; r.leftEyeY = cy;
                r.leftEyeHalfW = hw; r.leftEyeHalfH = hh;
                r.leftEyeLowerY = lowerN > 0 ? lowerSum / lowerN : cy + hh;
            } else {
                r.rightEyeX = cx; r.rightEyeY = cy;
                r.rightEyeHalfW = hw; r.rightEyeHalfH = hh;
                r.rightEyeLowerY = lowerN > 0 ? lowerSum / lowerN : cy + hh;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void fillLips(MeshResult r, com.google.mlkit.vision.facemesh.FaceMesh mesh) {
        try {
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> upperTop =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.UPPER_LIP_TOP);
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> lowerBottom =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.LOWER_LIP_BOTTOM);
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> upperBottom =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.UPPER_LIP_BOTTOM);
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> lowerTop =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.LOWER_LIP_TOP);
            r.lipOuterX = joinReversed(upperTop, lowerBottom);
            r.lipOuterY = joinReversedY(upperTop, lowerBottom);
            r.lipInnerX = joinReversed(upperBottom, lowerTop);
            r.lipInnerY = joinReversedY(upperBottom, lowerTop);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** X coords of (contourA ++ reverse(contourB)) — a closed ring around the organ. */
    private static float[] joinReversed(List<com.google.mlkit.vision.facemesh.FaceMeshPoint> a,
                                        List<com.google.mlkit.vision.facemesh.FaceMeshPoint> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return null;
        float[] out = new float[a.size() + b.size()];
        int k = 0;
        for (int i = 0; i < a.size(); i++) out[k++] = a.get(i).getPosition().getX();
        for (int i = b.size() - 1; i >= 0; i--) out[k++] = b.get(i).getPosition().getX();
        return out;
    }

    private static float[] joinReversedY(List<com.google.mlkit.vision.facemesh.FaceMeshPoint> a,
                                         List<com.google.mlkit.vision.facemesh.FaceMeshPoint> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return null;
        float[] out = new float[a.size() + b.size()];
        int k = 0;
        for (int i = 0; i < a.size(); i++) out[k++] = a.get(i).getPosition().getY();
        for (int i = b.size() - 1; i >= 0; i--) out[k++] = b.get(i).getPosition().getY();
        return out;
    }

    private static void fillOval(MeshResult r, com.google.mlkit.vision.facemesh.FaceMesh mesh) {
        try {
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> oval =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.FACE_OVAL);
            if (oval == null || oval.size() < 8) return;
            r.ovalX = new float[oval.size()];
            r.ovalY = new float[oval.size()];
            for (int i = 0; i < oval.size(); i++) {
                r.ovalX[i] = oval.get(i).getPosition().getX();
                r.ovalY[i] = oval.get(i).getPosition().getY();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void fillNose(MeshResult r, com.google.mlkit.vision.facemesh.FaceMesh mesh) {
        try {
            List<com.google.mlkit.vision.facemesh.FaceMeshPoint> bridge =
                mesh.getPoints(com.google.mlkit.vision.facemesh.FaceMesh.NOSE_BRIDGE);
            if (bridge == null || bridge.isEmpty()) return;
            float tipX = Float.NaN, tipY = -Float.MAX_VALUE;
            for (int i = 0; i < bridge.size(); i++) {
                com.google.mlkit.vision.facemesh.FaceMeshPoint p = bridge.get(i);
                if (p == null || p.getPosition() == null) continue;
                if (p.getPosition().getY() > tipY) {
                    tipY = p.getPosition().getY();
                    tipX = p.getPosition().getX();
                }
            }
            r.noseX = tipX;
            r.noseY = tipY;
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void releaseMesh() {
        synchronized (meshLock) {
            if (meshDetector != null) {
                try { meshDetector.close(); } catch (Throwable ignore) {}
                meshDetector = null;
            }
        }
    }

    // ================= end Fix 48 face mesh =================

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

    /**
     * Blocking subject segmentation (GMS ML Kit, thin — model may need a one-time
     * Play Services download; null = unavailable). Builds a full-size ALPHA_8 mask
     * from the per-subject cutout bitmaps, whose soft alpha keeps hair edges natural.
     */
    public static Bitmap personMaskBlocking(Bitmap bitmap, long timeoutMs) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() == null) {
            return null;
        }
        Bitmap mask = null;
        try {
            com.google.mlkit.vision.segmentation.subject.SubjectSegmenter segmenter =
                com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(
                    new com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder()
                        .enableMultipleSubjects(
                            new com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.SubjectResultOptions.Builder()
                                .enableSubjectBitmap()
                                .build())
                        .build());
            com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult result =
                com.google.android.gms.tasks.Tasks.await(
                    segmenter.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)),
                    timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null || result.getSubjects().isEmpty()) {
                return null;
            }
            final int mw = bitmap.getWidth(), mh = bitmap.getHeight();
            mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8);
            android.graphics.Canvas mc = new android.graphics.Canvas(mask);
            android.graphics.Paint mp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            boolean any = false;
            for (int i = 0; i < result.getSubjects().size(); i++) {
                com.google.mlkit.vision.segmentation.subject.Subject s = result.getSubjects().get(i);
                if (s == null || s.getBitmap() == null) continue;
                mc.drawBitmap(s.getBitmap(), s.getStartX(), s.getStartY(), mp);
                any = true;
            }
            return any ? mask : null;
        } catch (Throwable t) {
            FileLog.e(t);
            if (mask != null && !mask.isRecycled()) mask.recycle();
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
