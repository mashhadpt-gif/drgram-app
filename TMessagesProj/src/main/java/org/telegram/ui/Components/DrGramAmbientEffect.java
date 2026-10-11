/*
 * This is the source code of DrGram, a premium customization of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramAmbientEffect — DrGram's signature ambient particle waves (Fix 16).
 * Nothing like it exists in any other Telegram client: the chat itself carries
 * the brand mood of the active palette.
 *  - Gold Luxe: a slow shower of twinkling golden stars (four-point brilliants,
 *    five-point stars and six-point sparkles) drifting across the chat.
 *  - LOVE (Red Luxe): soft hearts rising gently from the bottom of the chat.
 *
 * Waves are spawned periodically by SizeNotifierFrameLayout on a Handler so the
 * canvas stays idle (zero redraws) between waves — the chat breathes magic
 * without draining the battery. Drawn at low alpha so readability is preserved.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

public class DrGramAmbientEffect {

    // Gold Luxe palette
    private static final int GOLD       = 0xfff5b90d;
    private static final int GOLD_LIGHT = 0xffffd94d;
    private static final int GOLD_PALE  = 0xfffff0a0;
    private static final int CHAMPAGNE  = 0xfffff6d8;
    // LOVE palette
    private static final int CRIMSON    = 0xffe62e3c;
    private static final int ROSE       = 0xffff5a6e;
    private static final int BLUSH      = 0xffff8fa3;
    private static final int PEARL      = 0xffffe3e8;

    private static final int[] STAR_PALETTE = { GOLD, GOLD, GOLD_LIGHT, GOLD_LIGHT, GOLD_PALE, GOLD_PALE, CHAMPAGNE };
    private static final int[] LOVE_PALETTE = { CRIMSON, CRIMSON, ROSE, ROSE, BLUSH, PEARL };

    private static final int SHAPE_STAR4 = 0;
    private static final int SHAPE_STAR5 = 1;
    private static final int SHAPE_STAR6 = 2;
    private static final int SHAPE_HEART = 3;

    private static class Particle {
        float x, y;
        float vx, vy;
        float baseAlpha;
        float lifeTime, currentTime;
        float scale;
        float rotation, rotationSpeed;
        float phase, twinkleSpeed;
        float swayPhase, swaySpeed, swayAmp;
        float pulsePhase, pulseSpeed;
        int color;
        int shape;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private long lastAnimationTime = 0;

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();

    public DrGramAmbientEffect() {
        paint.setStyle(Paint.Style.FILL);
        for (int a = 0; a < 64; a++) {
            freeParticles.add(new Particle());
        }
    }

    public boolean isEmpty() {
        return particles.isEmpty();
    }

    private Particle obtain() {
        if (!freeParticles.isEmpty()) {
            return freeParticles.remove(freeParticles.size() - 1);
        }
        return new Particle();
    }

    /**
     * Spawns one ambient wave matched to the active brand palette.
     * love = true → hearts rise from the bottom; false → golden starfall from the top.
     */
    public void spawnWave(boolean love, float w, float h) {
        if (w <= 0) {
            w = 1080;
        }
        if (h <= 0) {
            h = 1920;
        }
        if (love) {
            int count = 12 + Utilities.random.nextInt(7);
            for (int a = 0; a < count; a++) {
                Particle p = obtain();
                p.x = Utilities.random.nextFloat() * w;
                p.y = h + AndroidUtilities.dp(16) + Utilities.random.nextFloat() * AndroidUtilities.dp(70);
                p.vx = (Utilities.random.nextFloat() - 0.5f) * 0.2f;
                p.vy = -(0.4f + Utilities.random.nextFloat() * 0.5f);
                p.color = LOVE_PALETTE[Utilities.random.nextInt(LOVE_PALETTE.length)];
                p.baseAlpha = 0.13f + Utilities.random.nextFloat() * 0.15f;
                p.currentTime = 0;
                p.lifeTime = 5000 + Utilities.random.nextInt(3000);
                p.scale = 0.9f + Utilities.random.nextFloat() * 1.7f;
                p.rotation = (Utilities.random.nextFloat() - 0.5f) * 40.0f;
                p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (8.0f + Utilities.random.nextFloat() * 22.0f);
                p.phase = 0;
                p.twinkleSpeed = 0;
                p.swayPhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
                p.swaySpeed = 0.003f + Utilities.random.nextFloat() * 0.004f;
                p.swayAmp = 0.5f + Utilities.random.nextFloat() * 0.9f;
                p.pulsePhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
                p.pulseSpeed = 0.006f + Utilities.random.nextFloat() * 0.007f;
                p.shape = SHAPE_HEART;
                particles.add(p);
            }
        } else {
            int count = 16 + Utilities.random.nextInt(9);
            for (int a = 0; a < count; a++) {
                Particle p = obtain();
                p.x = Utilities.random.nextFloat() * w;
                // 60% fall in from above, 40% already hang in the sky twinkling
                if (Utilities.random.nextInt(100) < 60) {
                    p.y = -AndroidUtilities.dp(14) - Utilities.random.nextFloat() * AndroidUtilities.dp(60);
                    p.vy = 0.3f + Utilities.random.nextFloat() * 0.5f;
                } else {
                    p.y = Utilities.random.nextFloat() * h * 0.72f;
                    p.vy = 0.04f + Utilities.random.nextFloat() * 0.12f;
                }
                p.vx = (Utilities.random.nextFloat() - 0.5f) * 0.16f;
                p.color = STAR_PALETTE[Utilities.random.nextInt(STAR_PALETTE.length)];
                p.baseAlpha = 0.14f + Utilities.random.nextFloat() * 0.18f;
                p.currentTime = 0;
                p.lifeTime = 3800 + Utilities.random.nextInt(2700);
                p.scale = 0.8f + Utilities.random.nextFloat() * 1.6f;
                p.rotation = Utilities.random.nextFloat() * 360.0f;
                p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (10.0f + Utilities.random.nextFloat() * 30.0f);
                p.phase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
                p.twinkleSpeed = 0.004f + Utilities.random.nextFloat() * 0.006f;
                p.swayPhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
                p.swaySpeed = 0.003f + Utilities.random.nextFloat() * 0.004f;
                p.swayAmp = 0.35f + Utilities.random.nextFloat() * 0.75f;
                p.pulsePhase = 0;
                p.pulseSpeed = 0;
                int r = Utilities.random.nextInt(100);
                if (r < 42) {
                    p.shape = SHAPE_STAR4;
                } else if (r < 74) {
                    p.shape = SHAPE_STAR5;
                } else {
                    p.shape = SHAPE_STAR6;
                }
                particles.add(p);
            }
        }
    }

    private void updateParticles(long dt) {
        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            if (particle.currentTime >= particle.lifeTime) {
                if (freeParticles.size() < 96) {
                    freeParticles.add(particle);
                }
                particles.remove(a);
                a--;
                count--;
                continue;
            }
            particle.currentTime += dt;

            particle.x += particle.vx * dt / 500.0f;
            particle.y += particle.vy * dt / 500.0f;
            particle.x += (float) Math.sin(particle.swayPhase + particle.currentTime * particle.swaySpeed)
                    * particle.swayAmp * dt / 1100.0f;
            particle.rotation += particle.rotationSpeed * dt / 1000.0f;
        }
    }

    private void drawStar4(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float k = r * 0.22f;
        path.reset();
        path.moveTo(cx, cy - r);
        path.quadTo(cx + k, cy - k, cx + r, cy);
        path.quadTo(cx + k, cy + k, cx, cy + r);
        path.quadTo(cx - k, cy + k, cx - r, cy);
        path.quadTo(cx - k, cy - k, cx, cy - r);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawStar5(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float inner = r * 0.45f;
        path.reset();
        for (int a = 0; a < 10; a++) {
            float radius = (a % 2 == 0) ? r : inner;
            double angle = Math.PI / 5.0 * a - Math.PI / 2.0;
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            if (a == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawStar6(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float inner = r * 0.42f;
        path.reset();
        for (int a = 0; a < 12; a++) {
            float radius = (a % 2 == 0) ? r : inner;
            double angle = Math.PI / 6.0 * a - Math.PI / 2.0;
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            if (a == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawHeart(Canvas canvas, float cx, float cy, float r, float rotation, float squash) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        path.reset();
        path.moveTo(cx, cy + r * 0.92f * squash);
        path.cubicTo(
                cx - r * 1.30f, cy + r * 0.10f * squash,
                cx - r * 0.92f, cy - r * 1.02f * squash,
                cx, cy - r * 0.38f * squash
        );
        path.cubicTo(
                cx + r * 0.92f, cy - r * 1.02f * squash,
                cx + r * 1.30f, cy + r * 0.10f * squash,
                cx, cy + r * 0.92f * squash
        );
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    public void onDraw(View parent, Canvas canvas) {
        if (parent == null || canvas == null || particles.isEmpty()) {
            return;
        }

        long newTime = System.currentTimeMillis();
        if (lastAnimationTime == 0) {
            lastAnimationTime = newTime;
        }
        long dt = Math.min(17, newTime - lastAnimationTime);
        lastAnimationTime = newTime;

        updateParticles(dt);

        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            float progress = particle.currentTime / particle.lifeTime;
            float fadeIn = Math.min(1.0f, particle.currentTime / Math.max(1.0f, particle.lifeTime * 0.14f));
            float fadeOut = Math.min(1.0f, (particle.lifeTime - particle.currentTime) / Math.max(1.0f, particle.lifeTime * 0.30f));
            float twinkle = particle.twinkleSpeed > 0
                    ? 0.55f + 0.45f * (float) Math.sin(particle.phase + particle.currentTime * particle.twinkleSpeed)
                    : 1.0f;
            float alpha = particle.baseAlpha * fadeIn * fadeOut * twinkle;
            if (alpha <= 0.01f) {
                continue;
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(particle.color);
            paint.setAlpha((int) (255 * alpha));

            if (particle.shape == SHAPE_HEART) {
                float squash = 1.0f + 0.14f * (float) Math.max(0.0, Math.sin(particle.pulsePhase + particle.currentTime * particle.pulseSpeed));
                float radius = AndroidUtilities.dp(2.8f) * particle.scale;
                drawHeart(canvas, particle.x, particle.y, radius, particle.rotation, squash);
            } else {
                float radius = AndroidUtilities.dp(2.2f) * particle.scale;
                switch (particle.shape) {
                    case SHAPE_STAR4:
                        drawStar4(canvas, particle.x, particle.y, radius, particle.rotation);
                        break;
                    case SHAPE_STAR5:
                        drawStar5(canvas, particle.x, particle.y, radius, particle.rotation);
                        break;
                    default:
                        drawStar6(canvas, particle.x, particle.y, radius, particle.rotation);
                        break;
                }
            }
        }

        paint.setAlpha(255);

        if (!particles.isEmpty()) {
            parent.invalidate();
        } else {
            lastAnimationTime = 0;
        }
    }
}
