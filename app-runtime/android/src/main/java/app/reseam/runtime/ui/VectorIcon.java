// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

public final class VectorIcon extends Drawable {
    private final VectorImage image;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path scaled = new Path();
    private final int tint;

    /** {@code size} is the intrinsic size in pixels of the image's longer side. */
    public VectorIcon(VectorImage image, int size, int tint) {
        this.image = image;
        this.size = size;
        this.tint = tint;
    }

    @Override
    public void draw(Canvas canvas) {
        matrix.setRectToRect(new RectF(0f, 0f, image.width, image.height), new RectF(getBounds()),
                Matrix.ScaleToFit.CENTER);
        float scale = matrix.mapRadius(1f);
        int alpha = paint.getAlpha();
        for (VectorImage.Shape shape : image.shapes) {
            shape.path.transform(matrix, scaled);
            if (shape.fill != null) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(shape.fill.resolve(tint));
                paint.setAlpha(Math.round(paint.getAlpha() * alpha / 255f));
                canvas.drawPath(scaled, paint);
            }
            if (shape.stroke != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(shape.stroke.ink.resolve(tint));
                paint.setAlpha(Math.round(paint.getAlpha() * alpha / 255f));
                paint.setStrokeWidth(shape.stroke.width * scale);
                paint.setStrokeCap(shape.stroke.cap);
                paint.setStrokeJoin(shape.stroke.join);
                canvas.drawPath(scaled, paint);
            }
            paint.setAlpha(alpha);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(size * Math.min(1f, image.width / image.height));
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(size * Math.min(1f, image.height / image.width));
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
