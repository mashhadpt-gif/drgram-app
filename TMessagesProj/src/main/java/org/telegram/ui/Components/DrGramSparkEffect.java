/*
 * This is the source code of DrGram, a premium customization of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramSparkEffect — premium gold spark burst animation.
 * Pure Java particle system drawn on the chat overlay canvas, inspired by
 * Telegram's FireworksEffect/Confetti: upward-biased fan of glowing sparks
 * (dots, four-point stars and velocity streaks) with gravity, drag and twinkle.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

public class DrGramSparkEffect {

    // DrGram metallic gold palette with a rare crimson accent
    private static final int GOLD_DEEP   = 0xffc08a10;
    private static final int GOLD        = 0xfff5b90d;
    private static final int GOLD_LIGHT  = 0xffffd94d;
    private static final int GOLD_PALE   = 0xfffff0a0;
    private static final int CRIMSON     = 0xffe63b52;

    private static final int TYPE_DOT     = 0;
    private static final int TYPE_STAR    = 1;
    private static final int TYPE_STREAK  = 2;
    private static final int TYPE_FLASH   = 3;
    private static final int TYPE_DIAMOND = 4;
    private static final int TYPE_HEXSTAR = 5;
    private static final int TYPE_RING    = 6;

    private static final int[] PALETTE = { GOLD, GOLD, GOLD_LIGHT, GOLD_LIGHT, GOLD_PALE, GOLD_DEEP, CRIMSON };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();

    private long lastAnimationTime = 0;

    private static class Particle {
        float x, y;
        float vx, vy;
        float alpha;
        float lifeTime, currentTime;
        float scale;
        float rotation, rotationSpeed;
        float twinklePhase, twinkleSpeed;
        int color;
        int type;
    }

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();

    public DrGramSparkEffect() {
        paint.setStyle(Paint.Style.FILL);
        for (int a = 0; a < 240; a++) {
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

    private static int randomGoldColor() {
        return PALETTE[Utilities.random.nextInt(PALETTE.length)];
    }

    /**
     * Spawns a premium spark burst centered at (cx, cy).
     * Safe to call repeatedly — bursts simply add more particles.
     */
    public void burst(float cx, float cy) {
        burst(cx, cy, 1.0f);
    }

    /**
     * Spawns a spark burst with a given intensity (1.0 = normal send spark,
     * >= 1.5 = grand celebration with shockwave ring and extra particles).
     */
    public void burst(float cx, float cy, float intensity) {
        // fast expanding glow flash at the origin
        Particle flash = obtain();
        flash.x = cx;
        flash.y = cy;
        flash.vx = 0;
        flash.vy = 0;
        flash.color = GOLD_PALE;
        flash.alpha = 0.55f;
        flash.currentTime = 0;
        flash.lifeTime = (int) (320 + Utilities.random.nextInt(120) * intensity);
        flash.scale = intensity;
        flash.rotation = 0;
        flash.rotationSpeed = 0;
        flash.twinklePhase = 0;
        flash.twinkleSpeed = 0;
        flash.type = TYPE_FLASH;
        particles.add(flash);

        // grand celebrations get an expanding shockwave ring
        if (intensity >= 1.5f) {
            Particle ring = obtain();
            ring.x = cx;
            ring.y = cy;
            ring.vx = 0;
            ring.vy = 0;
            ring.color = GOLD;
            ring.alpha = 0.5f;
            ring.currentTime = 0;
            ring.lifeTime = 620;
            ring.scale = intensity;
            ring.rotation = 0;
            ring.rotationSpeed = 0;
            ring.twinklePhase = 0;
            ring.twinkleSpeed = 0;
            ring.type = TYPE_RING;
            particles.add(ring);
        }

        int count = (int) ((36 + Utilities.random.nextInt(16)) * intensity); // DrGram Fix 15
        for (int a = 0; a < count; a++) {
            Particle p = obtain();
            p.x = cx + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(16);
            p.y = cy + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(10);

            // upward-biased fan (around -90 degrees), wide spread like confetti
            float angle = (float) (-Math.PI / 2.0 + (Utilities.random.nextFloat() - 0.5f) * Math.PI * 1.4);
            float speed = 1.3f + Utilities.random.nextFloat() * 2.3f;
            p.vx = (float) Math.cos(angle) * speed * 1.55f * intensity;
            p.vy = (float) Math.sin(angle) * speed * 1.55f * intensity;

            p.color = randomGoldColor();
            p.alpha = 1.0f;
            p.currentTime = 0;
            p.lifeTime = (int) ((800 + Utilities.random.nextInt(850)) * Math.min(1.3f, 0.85f + intensity * 0.15f));
            p.scale = Math.max(1.2f, Utilities.random.nextFloat() * 2.6f) * (0.9f + intensity * 0.2f);
            p.rotation = Utilities.random.nextFloat() * 360.0f;
            p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (120.0f + Utilities.random.nextFloat() * 240.0f);
            p.twinklePhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
            p.twinkleSpeed = 0.008f + Utilities.random.nextFloat() * 0.012f;

            int r = Utilities.random.nextInt(100);
            if (r < 38) {
                p.type = TYPE_DOT;
            } else if (r < 62) {
                p.type = TYPE_STAR;
            } else if (r < 78) {
                p.type = TYPE_STREAK;
            } else if (r < 91) {
                p.type = TYPE_DIAMOND;
            } else {
                p.type = TYPE_HEXSTAR;
            }
            particles.add(p);
        }
    }

    private void updateParticles(long dt) {
        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            if (particle.currentTime >= particle.lifeTime) {
                if (freeParticles.size() < 260) {
                    freeParticles.add(particle);
                }
                particles.remove(a);
                a--;
                count--;
                continue;
            }
            particle.currentTime += dt;
            if (particle.type == TYPE_FLASH) {
                continue;
            }
            particle.x += particle.vx * dt / 500.0f;
            particle.y += particle.vy * dt / 500.0f;
            // gravity + air drag
            particle.vy += dt / 90.0f;
            particle.vx -= particle.vx * dt * 0.00045f;
            particle.rotation += particle.rotationSpeed * dt / 1000.0f;
        }
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float k = r * 0.22f;
        starPath.reset();
        starPath.moveTo(cx, cy - r);
        starPath.quadTo(cx + k, cy - k, cx + r, cy);
        starPath.quadTo(cx + k, cy + k, cx, cy + r);
        starPath.quadTo(cx - k, cy + k, cx - r, cy);
        starPath.quadTo(cx - k, cy - k, cx, cy - r);
        starPath.close();
        canvas.drawPath(starPath, paint);
        canvas.restore();
    }

    /** Six-point brilliant star. */
    private void drawHexStar(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float inner = r * 0.42f;
        starPath.reset();
        for (int a = 0; a < 12; a++) {
            float radius = (a % 2 == 0) ? r : inner;
            double angle = Math.PI / 6.0 * a - Math.PI / 2.0;
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            if (a == 0) {
                starPath.moveTo(px, py);
            } else {
                starPath.lineTo(px, py);
            }
        }
        starPath.close();
        canvas.drawPath(starPath, paint);
        canvas.restore();
    }

    /** Rotating gem facet (rhombus body + bright inner highlight). */
    private void drawDiamond(Canvas canvas, float cx, float cy, float r, float rotation, int color, float alpha) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        paint.setStyle(Paint.Style.FILL);
        // body
        paint.setColor(color);
        paint.setAlpha((int) (255 * alpha));
        starPath.reset();
        starPath.moveTo(cx, cy - r);
        starPath.lineTo(cx + r * 0.62f, cy);
        starPath.lineTo(cx, cy + r);
        starPath.lineTo(cx - r * 0.62f, cy);
        starPath.close();
        canvas.drawPath(starPath, paint);
        // bright facet highlight
        paint.setColor(GOLD_PALE);
        paint.setAlpha((int) (255 * alpha * 0.85f));
        starPath.reset();
        starPath.moveTo(cx, cy - r);
        starPath.lineTo(cx, cy + r * 0.15f);
        starPath.lineTo(cx - r * 0.3f, cy - r * 0.1f);
        starPath.close();
        canvas.drawPath(starPath, paint);
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
            float fade = 1.0f - AndroidUtilities.decelerateInterpolator.getInterpolation(progress);
            float twinkle = 0.65f + 0.35f * (float) Math.sin(particle.twinklePhase + particle.currentTime * particle.twinkleSpeed);
            boolean sparkly = particle.type == TYPE_STAR || particle.type == TYPE_HEXSTAR || particle.type == TYPE_DIAMOND;
            float alpha = particle.alpha * fade * (sparkly ? twinkle : 1.0f);
            if (alpha <= 0.02f) {
                continue;
            }

            int color = particle.color;
            switch (particle.type) {
                case TYPE_FLASH: {
                    float radius = AndroidUtilities.dp(34) * particle.scale * (0.4f + progress * 1.8f);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(particle.x, particle.y, radius, paint);
                    break;
                }
                case TYPE_RING: {
                    float radius = AndroidUtilities.dp(18) * particle.scale * (0.3f + progress * 3.4f);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(AndroidUtilities.dp(1.6f) * (1.0f - progress * 0.6f));
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(particle.x, particle.y, radius, paint);
                    break;
                }
                case TYPE_DIAMOND: {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    float radius = AndroidUtilities.dp(2.4f) * particle.scale;
                    drawDiamond(canvas, particle.x, particle.y, radius, particle.rotation, color, alpha);
                    break;
                }
                case TYPE_HEXSTAR: {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    float radius = AndroidUtilities.dp(2.2f) * particle.scale;
                    drawHexStar(canvas, particle.x, particle.y, radius, particle.rotation);
                    break;
                }
                case TYPE_DOT: {
                    float radius = AndroidUtilities.dp(1.6f) * particle.scale;
                    // outer glow
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha * 0.30f));
                    canvas.drawCircle(particle.x, particle.y, radius * 2.4f, paint);
                    // core
                    paint.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(particle.x, particle.y, radius, paint);
                    break;
                }
                case TYPE_STAR: {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    float radius = AndroidUtilities.dp(2.6f) * particle.scale;
                    drawStar(canvas, particle.x, particle.y, radius, particle.rotation);
                    break;
                }
                case TYPE_STREAK: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(color);
                    paint.setStrokeWidth(AndroidUtilities.dp(1.1f) * particle.scale);
                    paint.setAlpha((int) (255 * alpha));
                    float len = 3.2f;
                    canvas.drawLine(
                            particle.x - particle.vx * len,
                            particle.y - particle.vy * len,
                            particle.x,
                            particle.y,
                            paint
                    );
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
