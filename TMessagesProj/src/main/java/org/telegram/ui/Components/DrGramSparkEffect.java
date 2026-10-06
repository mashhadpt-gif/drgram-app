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

    private static final int TYPE_DOT    = 0;
    private static final int TYPE_STAR   = 1;
    private static final int TYPE_STREAK = 2;
    private static final int TYPE_FLASH  = 3;

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
        for (int a = 0; a < 80; a++) {
            freeParticles.add(new Particle());
        }
    }

    public boolean isEmpty() {
        return particles.isEmpty();
    }

    private Particle obtain() {
        if (!freeParticles.isEmpty()) {
            return freeParticles.remove(0);
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
        // fast expanding glow flash at the origin
        Particle flash = obtain();
        flash.x = cx;
        flash.y = cy;
        flash.vx = 0;
        flash.vy = 0;
        flash.color = GOLD_PALE;
        flash.alpha = 0.55f;
        flash.currentTime = 0;
        flash.lifeTime = 320 + Utilities.random.nextInt(120);
        flash.scale = 1.0f;
        flash.rotation = 0;
        flash.rotationSpeed = 0;
        flash.twinklePhase = 0;
        flash.twinkleSpeed = 0;
        flash.type = TYPE_FLASH;
        particles.add(flash);

        int count = 24 + Utilities.random.nextInt(14);
        for (int a = 0; a < count; a++) {
            Particle p = obtain();
            p.x = cx + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(16);
            p.y = cy + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(10);

            // upward-biased fan (around -90 degrees), wide spread like confetti
            float angle = (float) (-Math.PI / 2.0 + (Utilities.random.nextFloat() - 0.5f) * Math.PI * 1.4);
            float speed = 1.1f + Utilities.random.nextFloat() * 1.9f;
            p.vx = (float) Math.cos(angle) * speed * 1.55f;
            p.vy = (float) Math.sin(angle) * speed * 1.55f;

            p.color = randomGoldColor();
            p.alpha = 1.0f;
            p.currentTime = 0;
            p.lifeTime = 650 + Utilities.random.nextInt(750);
            p.scale = Math.max(1.0f, Utilities.random.nextFloat() * 2.1f);
            p.rotation = Utilities.random.nextFloat() * 360.0f;
            p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (120.0f + Utilities.random.nextFloat() * 240.0f);
            p.twinklePhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
            p.twinkleSpeed = 0.008f + Utilities.random.nextFloat() * 0.012f;

            int r = Utilities.random.nextInt(100);
            if (r < 50) {
                p.type = TYPE_DOT;
            } else if (r < 82) {
                p.type = TYPE_STAR;
            } else {
                p.type = TYPE_STREAK;
            }
            particles.add(p);
        }
    }

    private void updateParticles(long dt) {
        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            if (particle.currentTime >= particle.lifeTime) {
                if (freeParticles.size() < 120) {
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
            float alpha = particle.alpha * fade * (particle.type == TYPE_STAR ? twinkle : 1.0f);
            if (alpha <= 0.02f) {
                continue;
            }

            int color = particle.color;
            switch (particle.type) {
                case TYPE_FLASH: {
                    float radius = AndroidUtilities.dp(26) * (0.4f + progress * 1.8f);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(particle.x, particle.y, radius, paint);
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
