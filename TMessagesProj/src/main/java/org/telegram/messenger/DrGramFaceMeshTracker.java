package org.telegram.messenger;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * DrGram Fix 50: Instagram-grade face tracking.
 *
 * MediaPipe Face Landmarker — the open industry standard behind
 * Instagram/Snap/TikTok-class face filters: a dense 478-point 3D face mesh
 * (468 landmarks + 10 iris points) running in VIDEO mode with true
 * frame-to-frame temporal tracking. Face identity is tracked across frames,
 * so the beautification anchors GLIDE with the face instead of jumping —
 * the exact weakness of the previous box/landmark detectors. Iris points
 * give exact eye centres; the full mesh gives real organ polygons.
 *
 * Design:
 * - Bundled model (assets/models/face_landmarker.task), fully offline.
 * - CPU delegate: no EGL-context requirement on the calling thread,
 *   deterministic, no native-crash risk on exotic GPUs.
 * - One process-wide engine, all entry points synchronized; timestamps are
 *   forced strictly monotonic (VIDEO-mode requirement).
 * - Live tracking output passes through an adaptive per-slot EMA: fast face
 *   movement is followed closely, micro-jitter is damped hard. Slot
 *   identity is re-matched by nearest centre every frame, so two faces
 *   cannot silently swap slots.
 * - Any failure (init, inference, model) marks the engine dead and callers
 *   fall back to the ML Kit chain untouched.
 */
public final class DrGramFaceMeshTracker {

    public static final int MAX_FACES = 2;

    /** Normalized tracking output for the live camera/beauty shader feed. */
    public static final class TrackResult {
        public int count;
        // Per slot i (0..1), all normalized to the source bitmap [0,1]:
        public final float[] faceData = new float[8];  // cx, cy, halfW, halfH
        public final float[] noseData = new float[4];  // x, y
        public final float[] mouthData = new float[4]; // x, y
        public final float[] eyeLData = new float[4];  // x, y
        public final float[] eyeRData = new float[4];  // x, y
    }

    // ================= canonical face mesh topology =================
    // Ordered rings from the MediaPipe canonical face model.

    private static final int[] FACE_OVAL = {
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365,
            379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93,
            234, 127, 162, 21, 54, 103, 67, 109
    };
    // Upper outer lip left corner -> right corner, then lower outer back.
    private static final int[] LIPS_OUTER_RING = {
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291,
            375, 321, 405, 314, 17, 84, 181, 91, 146
    };
    // Inner mouth opening (between the lips).
    private static final int[] LIPS_INNER_RING = {
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95
    };
    private static final int[] LEFT_EYE_RING = {
            263, 249, 390, 373, 374, 380, 381, 382, 362, 466,
            388, 387, 386, 385, 384, 398
    };
    private static final int[] RIGHT_EYE_RING = {
            33, 7, 163, 144, 145, 153, 154, 155, 133, 173,
            157, 158, 159, 160, 161, 246
    };
    private static final int[] NOSE_BRIDGE = {168, 6, 197, 195, 5, 4};
    // Attention (iris) points — present when the model returns 478 landmarks.
    private static final int IRIS_R_CENTER = 468;
    private static final int IRIS_L_CENTER = 473;

    private static final int MIN_MESH_POINTS = 460;

    // ================= engine state =================

    private static final Object lock = new Object();
    private static com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker engine;
    private static boolean dead;
    private static long lastTs;
    private static int failStreak;

    // Slot smoothing state (normalized source-bitmap space).
    private static int slotCount = -1;
    private static final float[] slotFace = new float[8];
    private static final float[] slotNose = new float[4];
    private static final float[] slotMouth = new float[4];
    private static final float[] slotEyeL = new float[4];
    private static final float[] slotEyeR = new float[4];

    private DrGramFaceMeshTracker() {
    }

    private static com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker ensureEngine() {
        if (dead) {
            return null;
        }
        if (engine != null) {
            return engine;
        }
        try {
            com.google.mediapipe.tasks.core.BaseOptions base =
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                            .setModelAssetPath("models/face_landmarker.task")
                            .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                            .build();
            com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions opts =
                    com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.VIDEO)
                            .setNumFaces(MAX_FACES)
                            .setMinFaceDetectionConfidence(0.4f)
                            .setMinFacePresenceConfidence(0.4f)
                            .setMinTrackingConfidence(0.4f)
                            .setOutputFaceBlendshapes(false)
                            .build();
            engine = com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
                    .createFromOptions(ApplicationLoader.applicationContext, opts);
            lastTs = 0;
            failStreak = 0;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("DrGramFaceMeshTracker engine ready (478-pt VIDEO mode)");
            }
            return engine;
        } catch (Throwable t) {
            // NoClassDefFoundError on stripped builds / OOM / missing asset.
            FileLog.e(t);
            dead = true;
            return null;
        }
    }

    private static void dropEngine() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignore) {
            }
            engine = null;
        }
    }

    /** True when the mesh tracker is alive and usable. */
    public static boolean available() {
        synchronized (lock) {
            return !dead && ensureEngine() != null;
        }
    }

    /** Frees the engine; the next call re-creates it lazily. */
    public static void release() {
        synchronized (lock) {
            dropEngine();
            slotCount = -1;
        }
    }

    // ================= live tracking (camera path) =================

    /**
     * Tracks faces on one camera frame (blocking). Returns a smoothed
     * TrackResult, or a result with count == 0 when no face is found, or
     * null when the engine is unavailable (caller falls back to ML Kit).
     *
     * @param timestampMs wall-clock-ish timestamp; forced monotonic internally
     */
    public static TrackResult track(Bitmap frame, long timestampMs) {
        synchronized (lock) {
            com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker lm = ensureEngine();
            if (lm == null || frame == null || frame.isRecycled() || frame.getConfig() == null) {
                return null;
            }
            List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> faces;
            try {
                long ts = Math.max(timestampMs, lastTs + 1);
                lastTs = ts;
                com.google.mediapipe.framework.image.MPImage image =
                        new com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build();
                com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult result =
                        lm.detectForVideo(image, ts);
                faces = result == null ? null : result.faceLandmarks();
                failStreak = 0;
            } catch (Throwable t) {
                FileLog.e(t);
                // A failed VIDEO-mode graph can keep rejecting; rebuild cleanly.
                dropEngine();
                if (++failStreak >= 3) {
                    dead = true;
                }
                return null;
            }
            if (faces == null || faces.isEmpty()) {
                if (slotCount != 0) {
                    slotCount = 0;
                }
                TrackResult empty = new TrackResult();
                empty.count = 0;
                return empty;
            }
            // Keep only faces with a full mesh — a thin result must never
            // poison the smoothed slots.
            List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> valid =
                    new ArrayList<>();
            for (int i = 0; i < faces.size() && valid.size() < MAX_FACES; i++) {
                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> f = faces.get(i);
                if (f != null && f.size() >= MIN_MESH_POINTS) {
                    valid.add(f);
                }
            }
            if (valid.isEmpty()) {
                if (slotCount != 0) {
                    slotCount = 0;
                }
                TrackResult empty = new TrackResult();
                empty.count = 0;
                return empty;
            }
            int n = valid.size();
            TrackResult out = new TrackResult();
            out.count = n;
            // Raw per-face geometry first, sorted left-to-right for stable slots.
            float[][] face = new float[n][4];
            float[][] nose = new float[n][2];
            float[][] mouth = new float[n][2];
            float[][] eyeL = new float[n][2];
            float[][] eyeR = new float[n][2];
            for (int i = 0; i < n; i++) {
                readFace(valid.get(i), face[i], nose[i], mouth[i], eyeL[i], eyeR[i]);
            }
            final Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Float.compare(face[a][0], face[b][0]));

            if (slotCount != n) {
                // Reacquire / face count changed — seed slots directly.
                for (int s = 0; s < n; s++) {
                    int src = order[s];
                    copyFace(slotFace, s, face[src], slotNose, s, nose[src], slotMouth, s, mouth[src], slotEyeL, s, eyeL[src], slotEyeR, s, eyeR[src]);
                }
                slotCount = n;
            } else {
                // Same count: optimal slot assignment (n <= 2 → try both permutations).
                int[] assign = bestAssignment(face, nose, mouth, eyeL, eyeR, order, n);
                for (int s = 0; s < n; s++) {
                    int src = assign[s];
                    smoothInto(slotFace, s * 4, face[src], 0, 4);
                    smoothInto(slotNose, s * 2, nose[src], 0, 2);
                    smoothInto(slotMouth, s * 2, mouth[src], 0, 2);
                    smoothInto(slotEyeL, s * 2, eyeL[src], 0, 2);
                    smoothInto(slotEyeR, s * 2, eyeR[src], 0, 2);
                }
            }
            for (int s = 0; s < n; s++) {
                System.arraycopy(slotFace, s * 4, out.faceData, s * 4, 4);
                System.arraycopy(slotNose, s * 2, out.noseData, s * 2, 2);
                System.arraycopy(slotMouth, s * 2, out.mouthData, s * 2, 2);
                System.arraycopy(slotEyeL, s * 2, out.eyeLData, s * 2, 2);
                System.arraycopy(slotEyeR, s * 2, out.eyeRData, s * 2, 2);
            }
            return out;
        }
    }

    /** Chooses identity or swapped slot mapping — lower total centre distance wins. */
    private static int[] bestAssignment(float[][] face, float[][] nose, float[][] mouth,
                                        float[][] eyeL, float[][] eyeR, Integer[] order, int n) {
        int[] identity = new int[n];
        for (int s = 0; s < n; s++) {
            identity[s] = order[s];
        }
        if (n < 2) {
            return identity;
        }
        int[] swapped = {identity[1], identity[0]};
        double dIdentity = centreDistance(face, identity[0], 0) + centreDistance(face, identity[1], 1);
        double dSwapped = centreDistance(face, swapped[0], 0) + centreDistance(face, swapped[1], 1);
        return dSwapped < dIdentity ? swapped : identity;
    }

    private static double centreDistance(float[][] face, int src, int slot) {
        float dx = face[src][0] - slotFace[slot * 4];
        float dy = face[src][1] - slotFace[slot * 4 + 1];
        return Math.hypot(dx, dy);
    }

    // ================= photo one-shot (mesh polygons) =================

    /**
     * One-shot dense detection for still photos. Returns MeshResults with
     * coordinates in the PROBE BITMAP's pixel space (same convention as
     * DrGramFaceEngine.meshDetectBlocking), or null when unavailable.
     * No temporal smoothing — static input.
     */
    public static ArrayList<DrGramFaceEngine.MeshResult> trackMesh(Bitmap frame) {
        synchronized (lock) {
            com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker lm = ensureEngine();
            if (lm == null || frame == null || frame.isRecycled() || frame.getConfig() == null) {
                return null;
            }
            List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> faces;
            try {
                long ts = Math.max(System.currentTimeMillis(), lastTs + 1);
                lastTs = ts;
                com.google.mediapipe.framework.image.MPImage image =
                        new com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build();
                com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult result =
                        lm.detectForVideo(image, ts);
                faces = result == null ? null : result.faceLandmarks();
                failStreak = 0;
            } catch (Throwable t) {
                FileLog.e(t);
                dropEngine();
                if (++failStreak >= 3) {
                    dead = true;
                }
                return null;
            }
            if (faces == null || faces.isEmpty()) {
                return null;
            }
            float w = frame.getWidth(), h = frame.getHeight();
            ArrayList<DrGramFaceEngine.MeshResult> out = new ArrayList<>();
            for (int i = 0; i < faces.size() && out.size() < MAX_FACES; i++) {
                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts = faces.get(i);
                if (pts == null || pts.size() < MIN_MESH_POINTS) {
                    continue;
                }
                DrGramFaceEngine.MeshResult r = new DrGramFaceEngine.MeshResult();
                fillMeshResult(r, pts, w, h);
                boolean eyesOk = !Float.isNaN(r.leftEyeX) && !Float.isNaN(r.rightEyeX);
                boolean lipsOk = r.lipOuterX != null && r.lipOuterX.length >= 6;
                if (!eyesOk && !lipsOk) {
                    continue;
                }
                out.add(r);
            }
            return out;
        }
    }

    /** Fills a MeshResult (probe-pixel space) from one face's normalized landmarks. */
    private static void fillMeshResult(DrGramFaceEngine.MeshResult r,
                                       List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts,
                                       float w, float h) {
        float ovalMinX = Float.MAX_VALUE, ovalMaxX = -Float.MAX_VALUE;
        float ovalMinY = Float.MAX_VALUE, ovalMaxY = -Float.MAX_VALUE;
        float ovalSumX = 0, ovalSumY = 0;
        for (int idx : FACE_OVAL) {
            if (idx >= pts.size()) return;
            float x = pts.get(idx).x() * w, y = pts.get(idx).y() * h;
            ovalSumX += x;
            ovalSumY += y;
            ovalMinX = Math.min(ovalMinX, x);
            ovalMaxX = Math.max(ovalMaxX, x);
            ovalMinY = Math.min(ovalMinY, y);
            ovalMaxY = Math.max(ovalMaxY, y);
        }
        int on = FACE_OVAL.length;
        r.cx = ovalSumX / on;
        r.cy = ovalSumY / on;
        r.halfW = Math.max(4f, (ovalMaxX - ovalMinX) * 0.5f);
        r.halfH = Math.max(4f, (ovalMaxY - ovalMinY) * 0.5f);
        r.pointCount = pts.size();

        // Nose tip: lowest bridge point (same rule the ML Kit path was tuned with).
        float tipX = Float.NaN, tipY = -Float.MAX_VALUE;
        for (int idx : NOSE_BRIDGE) {
            if (idx >= pts.size()) continue;
            float y = pts.get(idx).y() * h;
            if (y > tipY) {
                tipY = y;
                tipX = pts.get(idx).x() * w;
            }
        }
        r.noseX = tipX;
        r.noseY = tipY;

        fillEye(r, pts, LEFT_EYE_RING, IRIS_L_CENTER, true, w, h);
        fillEye(r, pts, RIGHT_EYE_RING, IRIS_R_CENTER, false, w, h);

        r.lipOuterX = ringCoords(pts, LIPS_OUTER_RING, w, true);
        r.lipOuterY = ringCoords(pts, LIPS_OUTER_RING, h, false);
        r.lipInnerX = ringCoords(pts, LIPS_INNER_RING, w, true);
        r.lipInnerY = ringCoords(pts, LIPS_INNER_RING, h, false);

        r.ovalX = ringCoords(pts, FACE_OVAL, w, true);
        r.ovalY = ringCoords(pts, FACE_OVAL, h, false);
    }

    private static void fillEye(DrGramFaceEngine.MeshResult r,
                                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts,
                                int[] ring, int irisIndex, boolean left, float w, float h) {
        float sx = 0, sy = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int count = 0;
        for (int idx : ring) {
            if (idx >= pts.size()) continue;
            float x = pts.get(idx).x() * w, y = pts.get(idx).y() * h;
            sx += x;
            sy += y;
            count++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (count == 0) {
            return;
        }
        float cx = sx / count, cy = sy / count;
        // Iris centre is strictly better than the ring mean when present.
        if (irisIndex < pts.size()) {
            cx = pts.get(irisIndex).x() * w;
            cy = pts.get(irisIndex).y() * h;
        }
        float hw = Math.max(1.5f, (maxX - minX) * 0.5f);
        float hh = Math.max(1.5f, (maxY - minY) * 0.5f);
        // Mean y of the lower-lid half — the under-eye work anchor.
        float midY = (minY + maxY) * 0.5f;
        float lowerSum = 0;
        int lowerN = 0;
        for (int idx : ring) {
            if (idx >= pts.size()) continue;
            float y = pts.get(idx).y() * h;
            if (y > midY) {
                lowerSum += y;
                lowerN++;
            }
        }
        if (left) {
            r.leftEyeX = cx;
            r.leftEyeY = cy;
            r.leftEyeHalfW = hw;
            r.leftEyeHalfH = hh;
            r.leftEyeLowerY = lowerN > 0 ? lowerSum / lowerN : cy + hh;
        } else {
            r.rightEyeX = cx;
            r.rightEyeY = cy;
            r.rightEyeHalfW = hw;
            r.rightEyeHalfH = hh;
            r.rightEyeLowerY = lowerN > 0 ? lowerSum / lowerN : cy + hh;
        }
    }

    private static float[] ringCoords(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts,
                                      int[] ring, float scale, boolean isX) {
        float[] out = new float[ring.length];
        for (int i = 0; i < ring.length; i++) {
            int idx = ring[i];
            if (idx >= pts.size()) {
                return null;
            }
            com.google.mediapipe.tasks.components.containers.NormalizedLandmark p = pts.get(idx);
            out[i] = (isX ? p.x() : p.y()) * scale;
        }
        return out;
    }

    // ================= per-face geometry read =================

    private static void readFace(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts,
                                 float[] face, float[] nose, float[] mouth, float[] eyeL, float[] eyeR) {
        if (pts == null || pts.size() < MIN_MESH_POINTS) {
            face[0] = face[1] = -1f;
            return;
        }
        // Face centre + extents from the oval ring — far more stable than a
        // detector box (this is what keeps the "face centre" from jumping).
        float sumX = 0, sumY = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int idx : FACE_OVAL) {
            float x = pts.get(idx).x(), y = pts.get(idx).y();
            sumX += x;
            sumY += y;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int on = FACE_OVAL.length;
        face[0] = sumX / on;
        face[1] = sumY / on;
        face[2] = Math.max(0.02f, (maxX - minX) * 0.5f);
        face[3] = Math.max(0.02f, (maxY - minY) * 0.5f);

        // Nose tip (lowest bridge point).
        float tipX = face[0], tipY = face[1] + face[3] * 0.08f, best = -Float.MAX_VALUE;
        for (int idx : NOSE_BRIDGE) {
            if (idx >= pts.size()) continue;
            float y = pts.get(idx).y();
            if (y > best) {
                best = y;
                tipX = pts.get(idx).x();
                tipY = y;
            }
        }
        nose[0] = tipX;
        nose[1] = tipY;

        // Mouth centre from the outer lip ring.
        float mSumX = 0, mSumY = 0;
        int mCount = 0;
        for (int idx : LIPS_OUTER_RING) {
            if (idx >= pts.size()) continue;
            mSumX += pts.get(idx).x();
            mSumY += pts.get(idx).y();
            mCount++;
        }
        if (mCount > 0) {
            mouth[0] = mSumX / mCount;
            mouth[1] = mSumY / mCount;
        } else {
            mouth[0] = face[0];
            mouth[1] = face[1] + face[3] * 0.5f;
        }

        readEye(pts, LEFT_EYE_RING, IRIS_L_CENTER, eyeL);
        readEye(pts, RIGHT_EYE_RING, IRIS_R_CENTER, eyeR);
    }

    private static void readEye(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts,
                                int[] ring, int irisIndex, float[] out) {
        float sx = 0, sy = 0;
        int count = 0;
        for (int idx : ring) {
            if (idx >= pts.size()) continue;
            sx += pts.get(idx).x();
            sy += pts.get(idx).y();
            count++;
        }
        if (count == 0) {
            out[0] = Float.NaN;
            out[1] = Float.NaN;
            return;
        }
        float cx = sx / count, cy = sy / count;
        if (irisIndex < pts.size()) {
            cx = pts.get(irisIndex).x();
            cy = pts.get(irisIndex).y();
        }
        out[0] = cx;
        out[1] = cy;
    }

    // ================= adaptive slot smoothing =================

    /**
     * One-Euro-flavoured EMA: alpha grows with the observed centre jump, so
     * quick head movement is followed (no lag ghosting) while micro-jitter
     * around a still face is damped hard — the "centre keeps changing" fix.
     */
    private static void smoothInto(float[] state, int stateOff, float[] src, int srcOff, int len) {
        float dx = src[srcOff] - state[stateOff];
        float dy = src[srcOff + 1] - state[stateOff + 1];
        float jump = (float) Math.sqrt(dx * dx + dy * dy);
        float alpha = 0.35f + Math.min(0.55f, jump * 14f);
        for (int i = 0; i < len; i++) {
            state[stateOff + i] += (src[srcOff + i] - state[stateOff + i]) * alpha;
        }
    }

    private static void copyFace(float[] face, int fi, float[] fsrc,
                                 float[] nose, int ni, float[] nsrc,
                                 float[] mouth, int mi, float[] msrc,
                                 float[] eyeL, int li, float[] lsrc,
                                 float[] eyeR, int ri, float[] rsrc) {
        System.arraycopy(fsrc, 0, face, fi * 4, 4);
        System.arraycopy(nsrc, 0, nose, ni * 2, 2);
        System.arraycopy(msrc, 0, mouth, mi * 2, 2);
        System.arraycopy(lsrc, 0, eyeL, li * 2, 2);
        System.arraycopy(rsrc, 0, eyeR, ri * 2, 2);
    }
}
