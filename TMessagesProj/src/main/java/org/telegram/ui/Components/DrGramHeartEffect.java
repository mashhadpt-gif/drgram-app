/*
 * This is the source code of DrGram, a premium customization of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramHeartEffect — LOVE Mode heart particle system (Fix 16).
 * Pure Java particle system drawn on the chat overlay canvas, companion to
 * DrGramSparkEffect. When the DrGram Red Luxe (LOVE) palette is active, every
 * message send erupts into a fan of glowing hearts instead of gold sparks, and
 * every reaction to your message makes hearts rise from the bubble and drizzle
 * back down across the whole chat. Hearts carry a gentle heartbeat pulse,
 * sinusoidal flutter and a soft rose glow.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

public class DrGramHeartEffect {

    // DrGram LOVE palette — romantic reds with soft blush accents
    private static final int CRIMSON_DEEP = 0xffb3121f;
    private static final int CRIMSON      = 0xffe62e3c;
    private static final int ROSE         = 0xffff5a6e;
    private static final int BLUSH        = 0xffff8fa3;
    private static final int PEARL        = 0xffffe3e8;

    private static final int TYPE_HEART   = 0;
    private static final int TYPE_TINY    = 1;
    private static final int TYPE_OUTLINE = 2;

    private static final int[] LOVE_PALETTE = { CRIMSON, CRIMSON, ROSE, ROSE, ROSE, BLUSH, BLUSH, PEARL, CRIMSON_DEEP };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heartPath = new Path();

    private long lastAnimationTime = 0;

    private static class Particle {
        float x, y;
        float vx, vy;
        float alpha;
        float lifeTime, currentTime;
        float scale;
        float rotation, rotationSpeed;
        float swayPhase, swaySpeed, swayAmp;
        float pulsePhase, pulseSpeed;
        float fadeIn, fadeOut;
        int color;
        int type;
    }

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Particle> freeParticles = new ArrayList<>();

    public DrGramHeartEffect() {
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

    private static int randomLoveColor() {
        return LOVE_PALETTE[Utilities.random.nextInt(LOVE_PALETTE.length)];
    }

    private Particle setup(Particle p, float x, float y, float vx, float vy, int lifeBase, int lifeVar, float scale) {
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.color = randomLoveColor();
        p.alpha = 1.0f;
        p.currentTime = 0;
        p.lifeTime = lifeBase + Utilities.random.nextInt(lifeVar);
        p.scale = scale;
        p.rotation = (Utilities.random.nextFloat() - 0.5f) * 44.0f;
        p.rotationSpeed = (Utilities.random.nextBoolean() ? -1 : 1) * (16.0f + Utilities.random.nextFloat() * 46.0f);
        p.swayPhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
        p.swaySpeed = 0.004f + Utilities.random.nextFloat() * 0.006f;
        p.swayAmp = 0.45f + Utilities.random.nextFloat() * 0.85f;
        p.pulsePhase = Utilities.random.nextFloat() * (float) (Math.PI * 2.0);
        p.pulseSpeed = 0.008f + Utilities.random.nextFloat() * 0.010f;
        p.fadeIn = p.lifeTime * 0.08f;
        p.fadeOut = p.lifeTime * 0.32f;
        int r = Utilities.random.nextInt(100);
        if (r < 50) {
            p.type = TYPE_HEART;
        } else if (r < 78) {
            p.type = TYPE_TINY;
        } else {
            p.type = TYPE_OUTLINE;
        }
        return p;
    }

    /**
     * Spawns a heart burst centered at (cx, cy). Intensity 1.0 = normal send,
     * >= 1.5 = grand celebration with a soft rose flash at the origin.
     */
    public void burst(float cx, float cy, float intensity) {
        // soft rose glow flash at the origin
        Particle flash = setup(obtain(), cx, cy, 0, 0, 320, 120, intensity);
        flash.type = TYPE_HEART;
        flash.color = PEARL;
        flash.alpha = 0.5f;
        flash.pulseSpeed = 0;
        particles.add(flash);

        int count = (int) ((26 + Utilities.random.nextInt(14)) * intensity);
        for (int a = 0; a < count; a++) {
            Particle p = obtain();
            p.x = cx + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(18);
            p.y = cy + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(12);

            // upward-biased fan, slightly narrower than sparks (hearts float, not shrapnel)
            float angle = (float) (-Math.PI / 2.0 + (Utilities.random.nextFloat() - 0.5f) * Math.PI * 1.25);
            float speed = 1.0f + Utilities.random.nextFloat() * 2.4f;
            p.vx = (float) Math.cos(angle) * speed * 1.45f * intensity;
            p.vy = (float) Math.sin(angle) * speed * 1.45f * intensity;

            setup(p, p.x, p.y, p.vx, p.vy,
                    (int) ((900 + Utilities.random.nextInt(800)) * Math.min(1.3f, 0.85f + intensity * 0.15f)),
                    200,
                    Math.max(1.1f, Utilities.random.nextFloat() * 2.2f) * (0.9f + intensity * 0.18f));
            particles.add(p);
        }
    }

    /**
     * Spawns the LOVE-mode reaction celebration centered on a chat bubble at
     * (cx, cy): hearts lift off the bubble and a full-width heart drizzle
     * falls from the top of the chat.
     */
    public void rain(float cx, float cy, float width) {
        // 1) hearts lifting from the bubble
        int puff = 20 + Utilities.random.nextInt(10);
        for (int a = 0; a < puff; a++) {
            Particle p = setup(obtain(),
                    cx + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(46),
                    cy + (Utilities.random.nextFloat() - 0.5f) * AndroidUtilities.dp(16),
                    (Utilities.random.nextFloat() - 0.5f) * 0.8f,
                    -(0.7f + Utilities.random.nextFloat() * 1.3f),
                    1500, 900, 0.8f + Utilities.random.nextFloat() * 1.4f);
            particles.add(p);
        }

        // 2) full-width heart drizzle from the top
        int drizzle = 24 + Utilities.random.nextInt(12);
        for (int a = 0; a < drizzle; a++) {
            Particle p = setup(obtain(),
                    Utilities.random.nextFloat() * width,
                    -AndroidUtilities.dp(12) - Utilities.random.nextFloat() * AndroidUtilities.dp(70),
                    (Utilities.random.nextFloat() - 0.5f) * 0.3f,
                    0.55f + Utilities.random.nextFloat() * 0.95f,
                    2000, 1100, 0.7f + Utilities.random.nextFloat() * 1.3f);
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

            // gentle gravity — hearts float, terminal velocity keeps them dreamy
            particle.vy += dt / 150.0f;
            if (particle.vy > 2.4f) {
                particle.vy = 2.4f;
            }
            particle.vx -= particle.vx * dt * 0.0006f;

            particle.x += particle.vx * dt / 500.0f;
            particle.y += particle.vy * dt / 500.0f;
            // sinusoidal flutter
            particle.x += (float) Math.sin(particle.swayPhase + particle.currentTime * particle.swaySpeed)
                    * particle.swayAmp * dt / 850.0f;
            particle.rotation += particle.rotationSpeed * dt / 1000.0f;
        }
    }

    /** Bezier heart centered at (cx, cy); r = half width, squash = heartbeat pulse factor. */
    private void buildHeart(float cx, float cy, float r, float squash) {
        heartPath.reset();
        heartPath.moveTo(cx, cy + r * 0.92f * squash);
        heartPath.cubicTo(
                cx - r * 1.30f, cy + r * 0.10f * squash,
                cx - r * 0.92f, cy - r * 1.02f * squash,
                cx, cy - r * 0.38f * squash
        );
        heartPath.cubicTo(
                cx + r * 0.92f, cy - r * 1.02f * squash,
                cx + r * 1.30f, cy + r * 0.10f * squash,
                cx, cy + r * 0.92f * squash
        );
        heartPath.close();
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
            float alpha = particle.alpha * fadeIn * fadeOut;
            if (alpha <= 0.02f) {
                continue;
            }

            // heartbeat pulse: gentle double-beat scale, fading as the heart dies
            float pulse = particle.pulseSpeed > 0
                    ? 1.0f + 0.16f * (float) Math.max(0.0, Math.sin(particle.pulsePhase + particle.currentTime * particle.pulseSpeed)) * (1.0f - progress * 0.5f)
                    : 1.0f;

            float radius = AndroidUtilities.dp(2.6f) * particle.scale * pulse;
            int color = particle.color;

            switch (particle.type) {
                case TYPE_HEART: {
                    // outer glow
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha * 0.26f));
                    canvas.drawCircle(particle.x, particle.y, radius * 2.5f, paint);
                    // solid heart
                    paint.setAlpha((int) (255 * alpha));
                    canvas.save();
                    canvas.rotate(particle.rotation, particle.x, particle.y);
                    buildHeart(particle.x, particle.y, radius, 1.0f);
                    canvas.drawPath(heartPath, paint);
                    canvas.restore();
                    break;
                }
                case TYPE_OUTLINE: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(color);
                    paint.setStrokeWidth(AndroidUtilities.dp(1.0f) * particle.scale * (1.0f - progress * 0.35f));
                    paint.setAlpha((int) (255 * alpha));
                    canvas.save();
                    canvas.rotate(particle.rotation, particle.x, particle.y);
                    buildHeart(particle.x, particle.y, radius, 1.0f);
                    canvas.drawPath(heartPath, paint);
                    canvas.restore();
                    break;
                }
                case TYPE_TINY: {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    paint.setAlpha((int) (255 * alpha));
                    canvas.save();
                    canvas.rotate(particle.rotation, particle.x, particle.y);
                    buildHeart(particle.x, particle.y, radius * 0.55f, 1.0f);
                    canvas.drawPath(heartPath, paint);
                    canvas.restore();
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
