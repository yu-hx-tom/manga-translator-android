package cn.local.manga;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** The supplied vector paths are immutable; only instance tint and bounds change. */
final class Icons {
    private Icons() {}

    static ColorStateList tint(int normal, int active) {
        return new ColorStateList(
                new int[][] {
                    {-android.R.attr.state_enabled},
                    {android.R.attr.state_selected},
                    {android.R.attr.state_activated},
                    {}
                },
                new int[] {(normal & 0x00ffffff) | 0x61000000, active, active, normal});
    }

    static Drawable icon(Context c, int resource, int color) {
        Drawable d = c.getDrawable(resource).mutate();
        d.setTint(color);
        return d;
    }

    static ImageButton iconButton(
            Context c, int resource, String description, View.OnClickListener listener) {
        if (description == null || description.trim().isEmpty())
            throw new IllegalArgumentException("Icon description is required");
        ImageButton b = new ImageButton(c);
        b.setImageDrawable(icon(c, resource, Ui.ICON));
        b.setImageTintList(tint(Ui.ICON, Ui.ACCENT_DEEP));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        b.setMinimumWidth(Ui.dp(c, 48));
        b.setMinimumHeight(Ui.dp(c, 48));
        b.setContentDescription(description);
        b.setTooltipText(description);
        b.setBackground(Ui.ripple(null, Ui.round(c, Ui.SURFACE, 24), Ui.RIPPLE));
        Ui.pressable(b);
        b.setOnClickListener(listener);
        return b;
    }

    static Button iconTextButton(
            Context c, int resource, String text, int kind, View.OnClickListener listener) {
        Button b = Ui.button(c, text, kind, listener);
        setIcon(
                b,
                resource,
                kind == Ui.PRIMARY
                        ? Ui.SURFACE
                        : kind == Ui.DANGER_TONAL ? Ui.DANGER : Ui.ACCENT_DEEP,
                18);
        return b;
    }

    static void setIcon(TextView view, int resource, int color, int sizeDp) {
        Drawable d = icon(view.getContext(), resource, color);
        int size = Ui.dp(view.getContext(), sizeDp);
        d.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(d, null, null, null);
        view.setCompoundDrawablePadding(Ui.dp(view.getContext(), 8));
        view.setCompoundDrawableTintList(tint(color, color));
    }

    static void change(ImageButton button, int resource, String description) {
        if (button.getTag(R.id.ui_icon_resource) instanceof Integer
                && (Integer) button.getTag(R.id.ui_icon_resource) == resource) return;
        button.setTag(R.id.ui_icon_resource, resource);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.animate().cancel();
        button.setImageResource(resource);
        if (Ui.motion() && button.isAttachedToWindow()) {
            button.setAlpha(.25f);
            button.setRotation(-12);
            button.animate().alpha(1).rotation(0).setDuration(120).start();
        }
    }
}
