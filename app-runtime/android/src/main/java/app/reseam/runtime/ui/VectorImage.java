// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A vector image from a bundle's settings, such as an icon or a logo. Its paths hold only the
 * absolute move, line, cubic and close commands the patch SDK writes.
 */
public final class VectorImage {
    final float width;
    final float height;
    final List<Shape> shapes;

    private VectorImage(float width, float height, List<Shape> shapes) {
        this.width = width;
        this.height = height;
        this.shapes = shapes;
    }

    static VectorImage stroked(String... data) {
        List<Shape> shapes = new ArrayList<>();
        for (String path : data) {
            shapes.add(new Shape(path(path), null, new Stroke(Ink.TINT, 2f, Paint.Cap.ROUND, Paint.Join.ROUND), false));
        }
        return new VectorImage(24f, 24f, Collections.unmodifiableList(shapes));
    }

    /** Reads the patch SDK's form; {@code null} reads as no image. */
    public static VectorImage read(JSONObject json) throws JSONException {
        if (json == null) return null;
        JSONArray paths = json.getJSONArray("paths");
        List<Shape> shapes = new ArrayList<>(paths.length());
        for (int i = 0; i < paths.length(); i++) {
            JSONObject shape = paths.getJSONObject(i);
            JSONObject fill = shape.optJSONObject("fill");
            JSONObject stroke = shape.optJSONObject("stroke");
            shapes.add(new Shape(
                    path(shape.getString("data")),
                    fill == null ? null : ink(fill),
                    stroke == null ? null : new Stroke(
                            ink(stroke),
                            (float) stroke.getDouble("width"),
                            Paint.Cap.valueOf(stroke.getString("cap").toUpperCase(java.util.Locale.ROOT)),
                            Paint.Join.valueOf(stroke.getString("join").toUpperCase(java.util.Locale.ROOT))),
                    fill != null && fill.getBoolean("evenOdd")));
        }
        return new VectorImage((float) json.getDouble("width"), (float) json.getDouble("height"),
                Collections.unmodifiableList(shapes));
    }

    private static Ink ink(JSONObject json) throws JSONException {
        return json.isNull("paint") ? Ink.TINT : new Ink(Color.parseColor(json.getString("paint")));
    }

    private static Path path(String data) {
        Path path = new Path();
        String[] tokens = data.trim().split(" ");
        int i = 0;
        while (i < tokens.length) {
            switch (tokens[i++]) {
                case "M":
                    path.moveTo(Float.parseFloat(tokens[i++]), Float.parseFloat(tokens[i++]));
                    break;
                case "L":
                    path.lineTo(Float.parseFloat(tokens[i++]), Float.parseFloat(tokens[i++]));
                    break;
                case "C":
                    path.cubicTo(Float.parseFloat(tokens[i++]), Float.parseFloat(tokens[i++]),
                            Float.parseFloat(tokens[i++]), Float.parseFloat(tokens[i++]),
                            Float.parseFloat(tokens[i++]), Float.parseFloat(tokens[i++]));
                    break;
                case "Z":
                    path.close();
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected path command " + tokens[i - 1]);
            }
        }
        return path;
    }

    static final class Ink {
        static final Ink TINT = new Ink(0);

        final int color;

        private Ink(int color) {
            this.color = color;
        }

        int resolve(int tint) {
            return this == TINT ? tint : color;
        }
    }

    static final class Shape {
        final Path path;
        final Ink fill;
        final Stroke stroke;

        Shape(Path path, Ink fill, Stroke stroke, boolean evenOdd) {
            this.path = path;
            this.fill = fill;
            this.stroke = stroke;
            path.setFillType(evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        }
    }

    static final class Stroke {
        final Ink ink;
        final float width;
        final Paint.Cap cap;
        final Paint.Join join;

        Stroke(Ink ink, float width, Paint.Cap cap, Paint.Join join) {
            this.ink = ink;
            this.width = width;
            this.cap = cap;
            this.join = join;
        }
    }
}
