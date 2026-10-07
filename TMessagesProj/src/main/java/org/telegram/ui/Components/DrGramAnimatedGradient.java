/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Animated Gradient — a self-contained moving gradient drawable used
 * as the story viewer backdrop. Four DrGram palette colors slide through one
 * another on a diagonal axis, so the background slowly drifts while a story
 * plays. Written from scratch; the drawable pauses itself whenever it is not
 * visible (zero alpha or empty bounds) to avoid wasted frames.
 */
package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DrGramAnimatedGradient extends Drawable {

    // DrGram gold, coral, violet, teal
    private static final int[] PALETTE = {0xFFF2B33C, 0xFFE85D4A, 0xFF7B4BD6, 0xFF1FA98F};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator;
    private float phase;
    private int paintAlpha = 255;

    public DrGramAnimatedGradient() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(12000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        if (bounds.isEmpty() || paintAlpha == 0) {
            if (animator.isRunning()) {
                animator.cancel();
            }
            return;
        }
        if (!animator.isRunning()) {
            animator.start();
        }
        int width = bounds.width();
        int height = bounds.height();
        int[] colors = new int[PALETTE.length];
        for (int i = 0; i < PALETTE.length; ++i) {
            colors[i] = mix(PALETTE[i], PALETTE[(i + 1) % PALETTE.length], wave(phase + i / (float) PALETTE.length));
        }
        float drift = (float) Math.sin(phase * Math.PI * 2.0);
        LinearGradient shader = new LinearGradient(
                0, (float) height * (-0.2f + 0.4f * (drift * 0.5f + 0.5f)),
                width, (float) height * (0.7f + 0.5f * (drift * 0.5f + 0.5f)),
                colors, null, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        paint.setAlpha(paintAlpha);
        canvas.drawRect(bounds, paint);
    }

    private static float wave(float t) {
        float fraction = t - (float) Math.floor(t);
        return 0.5f - 0.5f * (float) Math.cos(fraction * Math.PI * 2.0);
    }

    private static int mix(int colorA, int colorB, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        int red = (int) (Color.red(colorA) * (1f - fraction) + Color.red(colorB) * fraction);
        int green = (int) (Color.green(colorA) * (1f - fraction) + Color.green(colorB) * fraction);
        int blue = (int) (Color.blue(colorA) * (1f - fraction) + Color.blue(colorB) * fraction);
        return Color.rgb(red, green, blue);
    }

    @Override
    public void setAlpha(int alpha) {
        if (paintAlpha != alpha) {
            paintAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return paintAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
