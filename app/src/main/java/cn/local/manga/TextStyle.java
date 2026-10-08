package cn.local.manga;

import android.graphics.Rect;

import org.json.JSONObject;

/** Values use original image pixels; the same values feed preview and export. */
final class TextStyle {
    float fontSize = 24, strokeWidth = 0, lineSpacing = 1, letterSpacing = 0, rotation = 0;
    boolean autoFit = true, custom = false, added = false, deleted = false;
    int strokeColor = 0xffFFFFFF, align = 1;
    String fontFamily = "sans-serif";
    Rect box;

    TextStyle copy() {
        return from(json());
    }

    JSONObject json() {
        try {
            JSONObject j =
                    new JSONObject()
                            .put("fontSize", fontSize)
                            .put("strokeWidth", strokeWidth)
                            .put("lineSpacing", lineSpacing)
                            .put("letterSpacing", letterSpacing)
                            .put("rotation", rotation)
                            .put("autoFit", autoFit)
                            .put("custom", custom)
                            .put("added", added)
                            .put("deleted", deleted)
                            .put("strokeColor", strokeColor)
                            .put("align", align)
                            .put("fontFamily", fontFamily);
            if (box != null)
                j.put("x", box.left).put("y", box.top).put("w", box.width()).put("h", box.height());
            return j;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static TextStyle from(JSONObject j) {
        TextStyle s = new TextStyle();
        if (j == null) return s;
        s.fontSize = (float) Math.max(8, Math.min(96, j.optDouble("fontSize", 24)));
        s.strokeWidth = (float) Math.max(0, Math.min(10, j.optDouble("strokeWidth", 0)));
        s.lineSpacing = (float) Math.max(.5, Math.min(3, j.optDouble("lineSpacing", 1)));
        s.letterSpacing = (float) Math.max(-4, Math.min(30, j.optDouble("letterSpacing", 0)));
        s.rotation = (float) j.optDouble("rotation", 0);
        s.autoFit = j.optBoolean("autoFit", true);
        s.custom = j.optBoolean("custom");
        s.added = j.optBoolean("added");
        s.deleted = j.optBoolean("deleted");
        s.strokeColor = j.optInt("strokeColor", 0xffFFFFFF);
        s.align = Math.max(0, Math.min(2, j.optInt("align", 1)));
        s.fontFamily = j.optString("fontFamily", "sans-serif");
        if (j.has("x"))
            s.box =
                    new Rect(
                            j.optInt("x"),
                            j.optInt("y"),
                            j.optInt("x") + Math.max(8, j.optInt("w")),
                            j.optInt("y") + Math.max(8, j.optInt("h")));
        return s;
    }

    boolean isDefault() {
        return !custom && !added && !deleted && box == null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextStyle
                && json().toString().equals(((TextStyle) o).json().toString());
    }

    @Override
    public int hashCode() {
        return json().toString().hashCode();
    }
}
