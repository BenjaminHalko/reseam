// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;

public final class SwitchView extends View implements Checkable {
    private static final int TRACK_WIDTH = 52;
    private static final int TRACK_HEIGHT = 32;
    private static final int OUTLINE = 2;
    private static final int THUMB_ON = 24;
    private static final int THUMB_OFF = 16;
    private static final long DURATION = 150;

    private final Colors colors;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final ArgbEvaluator evaluator = new ArgbEvaluator();
    private final float density;
    private boolean checked;
    /** 0 off, 1 on; animates between them. */
    private float progress;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    SwitchView(Ui ui) {
        super(ui.context);
        colors = ui.colors();
        density = getResources().getDisplayMetrics().density;
        setMinimumWidth(ui.px(TRACK_WIDTH));
        setMinimumHeight(ui.px(TRACK_HEIGHT));
        setOnClickListener(v -> toggle());
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchView view, boolean checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    /** Changes the state without telling the listener, for a value that changed elsewhere. */
    @Override
    public void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        animateTo(checked ? 1f : 0f);
    }

    @Override
    public void toggle() {
        setChecked(!checked);
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        if (!isLaidOut()) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target).setDuration(DURATION);
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = TRACK_WIDTH * density;
        float height = TRACK_HEIGHT * density;
        float left = (getWidth() - width) / 2;
        float top = (getHeight() - height) / 2;
        float radius = height / 2;
        int trackOn = colors.primary;
        int trackOff = colors.surfaceContainerHighest;

        track.set(left, top, left + width, top + height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(trackOff, trackOn));
        canvas.drawRoundRect(track, radius, radius, paint);
        if (progress < 1f) {
            float inset = OUTLINE * density / 2;
            track.inset(inset, inset);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(OUTLINE * density);
            paint.setColor(Colors.withOpacity(colors.outline, 1f - progress));
            canvas.drawRoundRect(track, radius - inset, radius - inset, paint);
        }

        float thumb = (THUMB_OFF + (THUMB_ON - THUMB_OFF) * progress) * density / 2;
        float start = left + radius;
        float end = left + width - radius;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(colors.outline, colors.onPrimary));
        canvas.drawCircle(start + (end - start) * progress, top + radius, thumb, paint);
    }

    private int blend(int off, int on) {
        return (int) evaluator.evaluate(progress, off, on);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : Tokens.DISABLED);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Switch.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setChecked(checked);
    }
}
