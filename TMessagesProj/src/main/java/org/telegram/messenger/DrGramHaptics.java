/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Haptics (Fix 38): the micro-interaction layer for the glass UI —
 * one quiet CLOCK_TICK when a Lux row is tapped, one firmer pulse when a
 * choice is committed. Every call is swallow-able: haptics must never be
 * able to break a feature.
 */
package org.telegram.messenger;

import android.view.HapticFeedbackConstants;
import android.view.View;

public final class DrGramHaptics {

    private DrGramHaptics() {}

    /** Subtle tick — glass row taps, style picks, slider commits. */
    public static void tick(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (Throwable ignore) {}
    }

    /** Firmer pulse — commit-style actions (apply, switch, send). */
    public static void confirm(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable ignore) {}
    }
}
