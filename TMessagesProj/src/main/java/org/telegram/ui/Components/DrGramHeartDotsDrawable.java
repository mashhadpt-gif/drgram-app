/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Heart Dots (Fix 38): the typing status indicator for the LOVE theme —
 * three tiny beating hearts instead of the classic bouncing dots. A drop-in
 * StatusDrawable that mirrors TypingDotsDrawable's animation loop exactly
 * (same timings, same animation-lock behavior), only the glyph is a heart.
 */
package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;

public class DrGramHeartDotsDrawable extends StatusDrawable {

    private int currentAccount = UserConfig.selectedAccount;
    private boolean isChat = false;
    private float[] scales = new float[3];
    private float[] startTimes = new float[] {0, 150, 300};
    private float[] elapsedTimes = new float[] {0, 0, 0};
    private long lastUpdateTime = 0;
    private boolean started = false;
    private DecelerateInterpolator decelerateInterpolator = new DecelerateInterpolator();

    private final Path heartPath = new Path();
    private Paint currentPaint;

    public DrGramHeartDotsDrawable(boolean createPaint) {
        if (createPaint) {
            currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
    }

    @Override
    public void setColor(int color) {
        if (currentPaint != null) {
            currentPaint.setColor(color);
        }
    }

    @Override
    public void setIsChat(boolean value) {
        isChat = value;
    }

    private void update() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;
        if (dt > 50) {
            dt = 50;
        }
        for (int a = 0; a < 3; a++) {
            elapsedTimes[a] += dt;
            float timeSinceStart = elapsedTimes[a] - startTimes[a];
            if (timeSinceStart > 0) {
                if (timeSinceStart <= 320) {
                    float diff = decelerateInterpolator.getInterpolation(timeSinceStart / 320.0f);
                    scales[a] = 1.33f + diff;
                } else if (timeSinceStart <= 640) {
                    float diff = decelerateInterpolator.getInterpolation((timeSinceStart - 320.0f) / 320.0f);
                    scales[a] = 1.33f + (1 - diff);
                } else if (timeSinceStart >= 800) {
                    elapsedTimes[a] = 0;
                    startTimes[a] = 0;
                    scales[a] = 1.33f;
                } else {
                    scales[a] = 1.33f;
                }
            } else {
                scales[a] = 1.33f;
            }
        }
        invalidateLimited();
    }

    @Override
    public void start() {
        lastUpdateTime = System.currentTimeMillis();
        started = true;
        invalidateSelf();
    }

    @Override
    public void stop() {
        for (int a = 0; a < 3; a++) {
            elapsedTimes[a] = 0;
            scales[a] = 1.33f;
        }
        startTimes[0] = 0;
        startTimes[1] = 150;
        startTimes[2] = 300;
        started = false;
    }

    @Override
    public void draw(Canvas canvas) {
        int x = getBounds().left;
        int y;
        if (isChat) {
            y = AndroidUtilities.dp(8.5f) + getBounds().top;
        } else {
            y = AndroidUtilities.dp(9.3f) + getBounds().top;
        }
        Paint paint;
        if (currentPaint != null) {
            paint = currentPaint;
        } else {
            paint = Theme.chat_statusPaint;
            paint.setAlpha(255);
        }
        drawHeart(canvas, paint, x + AndroidUtilities.dp(4), y, scales[0] * AndroidUtilities.density);
        drawHeart(canvas, paint, x + AndroidUtilities.dp(10), y, scales[1] * AndroidUtilities.density);
        drawHeart(canvas, paint, x + AndroidUtilities.dp(16), y, scales[2] * AndroidUtilities.density);
        checkUpdate();
    }

    private void drawHeart(Canvas canvas, Paint paint, float cx, float cy, float s) {
        // unit heart centered on (0,0)
        heartPath.reset();
        heartPath.moveTo(0, s * 0.62f);
        heartPath.cubicTo(-s * 1.1f, -s * 0.1f, -s * 0.62f, -s * 0.95f, 0, -s * 0.32f);
        heartPath.cubicTo(s * 0.62f, -s * 0.95f, s * 1.1f, -s * 0.1f, 0, s * 0.62f);
        heartPath.close();
        int save = canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(heartPath, paint);
        canvas.restoreToCount(save);
    }

    private void checkUpdate() {
        if (started) {
            if (!NotificationCenter.getInstance(currentAccount).isAnimationInProgress()) {
                update();
            } else {
                AndroidUtilities.runOnUIThread(this::checkUpdate, 100);
            }
        }
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (currentPaint != null) {
            currentPaint.setColorFilter(cf);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(20);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(18);
    }
}
