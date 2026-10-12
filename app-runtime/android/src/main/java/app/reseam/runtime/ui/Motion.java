// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Material 3 motion, matching Reseam Manager's navigation. */
public final class Motion {
    private static final long MEDIUM2 = 300;
    private static final long FADE_OUT = MEDIUM2 * 35 / 100;
    private static final int SLIDE = 30;
    private static final Interpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private Motion() {}

    /**
     * Shared axis X: both screens slide 30dp, toward the start when {@code forward}, while the
     * outgoing one fades out and the incoming one fades in after it.
     */
    public static Animator sharedAxisX(View outgoing, View incoming, boolean forward) {
        float slide = Tokens.px(incoming.getContext(), SLIDE) * (forward ? 1 : -1);
        // A delayed animator applies its start value only once the delay ends.
        incoming.setAlpha(0f);
        incoming.setTranslationX(slide);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                animate(incoming, View.TRANSLATION_X, slide, 0f, 0, MEDIUM2, EMPHASIZED),
                animate(incoming, View.ALPHA, 0f, 1f, FADE_OUT, MEDIUM2 - FADE_OUT, EMPHASIZED_DECELERATE),
                animate(outgoing, View.TRANSLATION_X, 0f, -slide, 0, MEDIUM2, EMPHASIZED),
                animate(outgoing, View.ALPHA, 1f, 0f, 0, FADE_OUT, EMPHASIZED_ACCELERATE));
        return set;
    }

    private static Animator animate(View view, Property<View, Float> property, float from, float to, long delay,
                                    long duration, Interpolator interpolator) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, from, to);
        animator.setStartDelay(delay);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        return animator;
    }
}
