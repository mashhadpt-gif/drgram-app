/*
 * This is the source code of DrGram, a premium customization of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramGoldRainEffect — premium golden dust rain animation.
 * Pure Java particle system drawn on the chat overlay canvas, companion to
 * DrGramSparkEffect. Triggered when someone reacts to your message: a soft
 * glitter puff lifts from the bubble, then rains back down as shimmering
 * golden dust with sinusoidal sway, rotation and twinkle, while a fine
 * drizzle of gold falls across the whole chat width.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

public class DrGramGoldRainEffect {

    // DrGram metallic gold palette with a rare crimson accent
    private static final int GOLD_DEEP  = 0xffc08a10;
    private static final int GOLD       = 0xfff5b90d;
    private static final int GOLD_LIGHT = 0xffffd94d;
    private static final int GOLD_PALE  = 0xfffff0a0;
    private static final int CRIMSON    = 0xffe63b52;

    private static final int TYPE_DUST    = 0;
    private static final int TYPE_TRAIL   = 1;
    private static final int TYPE_SPARKLE = 2;

    private static final int[] RAIN_PALETTE = { GOLD, GOLD, GOLD_LIGHT, GOLD_LIGHT, GOLD_PALE, GOLD_PALE, GOLD_DEEP, CRIMSON };

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
        float swayPhase, swaySpeed, swayAmp;
        float fadeIn, fadeOut;
        int color;
        int type;
    }

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();

    public DrGramGoldRainEffect() {
        paint.setStyle(Paint.Style.FILL);
        for (int a = 0; a < 200; a++) {
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

    private static int randomRainColor() {
        return RAIN_PALETTE[Utilities.random.nextInt(RAIN_PALETTE.length)];
    }

    private Particle setup(Particle p, float x, float y, float vx, float vy, int lifeBase, int lifeVar, float scale) {
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.color = randomRainColor();
        p.alpha = 1.0f;
        p.currentTime = 0;
        p.lifeTime = lifeBase + Utilities.random.nextInt(lifeVar);
        p.scale = scale;
        p.rotation = Utilities.random.nextFloat() * 360.0f;
        p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (40.0f + Utilities.random.nextFloat() * 160.0f);
        p.swayPhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
        p.swaySpeed = 0.004f + Utilities.random.nextFloat() * 0.006f;
        p.swayAmp = 0.25f + Utilities.random.nextFloat() * 0.75f;
        p.fadeIn = p.lifeTime * 0.08f;
        p.fadeOut = p.lifeTime * 0.3f;
        return p;
    }

    /**
     * Spawns the golden rain celebration centered on a chat bubble at (cx, cy):
     * a glitter puff rises from the bubble and rains back down, plus a fine
     * full-width drizzle falling from the top of the chat.
     *
     * @param cx    bubble center x
     * @param cy    bubble center y
     * @param width chat content width for the top drizzle
     */
    public void rain(float cx, float cy, float width) {
        // 1) glitter puff lifted from the bubble
        int puff = 26 + Utilities.random.nextInt(12);
        for (int a = 0; a < puff; a++) {
            Particle p = setup(obtain(),
                    cx + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(42),
                    cy + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(14),
                    (Utilities.random.nextFloat() - 0.5f) * 0.9f,
                    -(0.8f + Utilities.random.nextFloat() * 1.4f),
                    1400, 900, 0.8f + Utilities.random.nextFloat() * 1.5f);
            int r = Utilities.random.nextInt(100);
            if (r < 52) {
                p.type = TYPE_DUST;
            } else if (r < 76) {
                p.type = TYPE_TRAIL;
            } else {
                p.type = TYPE_SPARKLE;
            }
            particles.add(p);
        }

        // 2) full-width golden drizzle from the top
        int drizzle = 30 + Utilities.random.nextInt(14);
        for (int a = 0; a < drizzle; a++) {
            Particle p = setup(obtain(),
                    Utilities.random.nextFloat() * width,
                    -AndroidUtilities.dp(10) - Utilities.random.nextFloat() * AndroidUtilities.dp(60),
                    (Utilities.random.nextFloat() - 0.5f) * 0.3f,
                    0.7f + Utilities.random.nextFloat() * 1.1f,
                    1900, 1100, 0.6f + Utilities.random.nextFloat() * 1.4f);
            int r = Utilities.random.nextInt(100);
            if (r < 46) {
                p.type = TYPE_DUST;
            } else if (r < 72) {
                p.type = TYPE_TRAIL;
            } else {
                p.type = TYPE_SPARKLE;
            }
            particles.add(p);
        }
    }

    private void updateParticles(long dt) {
        int count = particles.size();
        for (int a = 0; a < count; a++) {
            Particle particle = particles.get(a);
            if (particle.currentTime >= particle.lifeTime) {
                if (freeParticles.size() < 300) {
                    freeParticles.add(particle);
                }
                particles.remove(a);
                a--;
                count--;
                continue;
            }
            particle.currentTime += dt;

            // gentle gravity, terminal velocity keeps the dust floating
            particle.vy += dt / 220.0f;
            if (particle.vy > 2.6f) {
                particle.vy = 2.6f;
            }
            particle.vx -= particle.vx * dt * 0.0009f;

            particle.x += particle.vx * dt / 500.0f;
            particle.y += particle.vy * dt / 500.0f;
            // horizontal golden sway
            particle.x += (float) Math.sin(particle.swayPhase + particle.currentTime * particle.swaySpeed)
                    * particle.swayAmp * dt / 900.0f;
            particle.rotation += particle.rotationSpeed * dt / 1000.0f;
        }
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float k = r * 0.24f;
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
            float fadeIn = Math.min(1.0f, particle.currentTime / Math.max(1.0f, particle.fadeIn));
            float fadeOut = Math.min(1.0f, (particle.lifeTime - particle.currentTime) / Math.max(1.0f, particle.fadeOut));
            float twinkle = 0.6f + 0.4f * (float) Math.sin(particle.swayPhase + particle.currentTime * 0.012f);
            float alpha = particle.alpha * fadeIn * fadeOut * (particle.type == TYPE_SPARKLE ? twinkle : 1.0f);
            if (alpha <= 0.02f) {
                continue;
            }

            int color = particle.color;
            switch (particle.type) {
                case TYPE_DUST: {
                    float radius = AndroidUtilities.dp(1.4f) * particle.scale;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha * 0.28f));
                    canvas.drawCircle(particle.x, particle.y, radius * 2.6f, paint);
                    paint.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(particle.x, particle.y, radius, paint);
                    break;
                }
                case TYPE_TRAIL: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(color);
                    paint.setStrokeWidth(AndroidUtilities.dp(0.9f) * particle.scale);
                    paint.setAlpha((int) (255 * alpha * 0.9f));
                    float len = AndroidUtilities.dp(5) * particle.scale * (0.6f + particle.vy / 2.6f);
                    canvas.drawLine(particle.x, particle.y - len, particle.x, particle.y, paint);
                    break;
                }
                case TYPE_SPARKLE: {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    float radius = AndroidUtilities.dp(2.2f) * particle.scale;
                    drawStar(canvas, particle.x, particle.y, radius, particle.rotation);
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
