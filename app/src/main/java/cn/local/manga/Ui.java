package cn.local.manga;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared colors, widget styling and short, interruptible motion. Purely visual; no app state. */
final class Ui {
    static final int BG = 0xffF6F8FC, SURFACE = 0xffFFFFFF, SURFACE_SOFT = 0xffF1F4F9, OUTLINE = 0xffE3E8F0;
    static final int INK = 0xff1F2430, MUTED = 0xff667085, ACCENT = 0xff1A73E8, ACCENT_DEEP = 0xff0B57D0;
    static final int ACCENT_SOFT = 0xffE8F0FE, DANGER = 0xffB3261E, DANGER_SOFT = 0xffFCE8E6, INFO_SOFT = 0xffE8EEF8;
    static final int PRIMARY = 0, TONAL = 1, OUTLINED = 2, TEXT = 3, DANGER_TONAL = 4;
    /** Material "emphasized decelerate"-like curve: quick start, gentle settle. */
    static final Interpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);
    static final Interpolator STANDARD = new PathInterpolator(.4f, 0f, .2f, 1f);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static boolean motion() { return ValueAnimator.areAnimatorsEnabled(); }

    static GradientDrawable round(Context c, int color, float radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(c, radius)); return shape;
    }
    static GradientDrawable card(Context c, float radius) {
        GradientDrawable shape = round(c, SURFACE, radius); shape.setStroke(dp(c, 1), OUTLINE); return shape;
    }
    static Drawable ripple(Drawable content, Drawable mask, int color) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }
    private static ColorStateList enabled(int disabled, int normal) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{disabled, normal});
    }

    /** Applies one of the button kinds; disabled colors follow View.setEnabled automatically. */
    static Button style(Button b, int kind) {
        Context c = b.getContext();
        int fill, fillOff, text, textOff, stroke = 0, rippleColor;
        switch (kind) {
            case PRIMARY: fill = ACCENT; fillOff = 0xffE3E7EE; text = 0xffFFFFFF; textOff = 0xff9AA3B2; rippleColor = 0x40FFFFFF; break;
            case OUTLINED: fill = SURFACE; fillOff = SURFACE; text = 0xff3C4657; textOff = 0xffA9B1BD; stroke = 0xffD5DCE6; rippleColor = 0x1F1A73E8; break;
            case TEXT: fill = 0x00FFFFFF; fillOff = 0x00FFFFFF; text = ACCENT_DEEP; textOff = 0xffA9B1BD; rippleColor = 0x241A73E8; break;
            case DANGER_TONAL: fill = DANGER_SOFT; fillOff = 0xffF3EEED; text = DANGER; textOff = 0xffC4A9A6; rippleColor = 0x24B3261E; break;
            default: fill = ACCENT_SOFT; fillOff = 0xffEEF1F5; text = ACCENT_DEEP; textOff = 0xffA0A9B8; rippleColor = 0x241A73E8; break;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(c, 14));
        shape.setColor(enabled(fillOff, fill));
        if (stroke != 0) shape.setStroke(dp(c, 1), enabled(0xffE6EAF0, stroke));
        b.setBackground(ripple(shape, round(c, 0xffFFFFFF, 14), rippleColor));
        b.setBackgroundTintList(null);
        b.setTextColor(enabled(textOff, text));
        b.setTypeface(MEDIUM);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        b.setElevation(0);
        pressable(b);
        return b;
    }
    static Button button(Context c, String text, int kind, View.OnClickListener listener) {
        Button b = new Button(c); b.setText(text); style(b, kind); b.setOnClickListener(listener); return b;
    }

    /** Press-to-shrink feedback driven by the pressed state, so it never consumes touch events. */
    static void pressable(View view) {
        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, scale(.96f, 90));
        states.addState(new int[]{}, scale(1f, 220));
        view.setStateListAnimator(states);
    }
    private static Animator scale(float to, long ms) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(null, View.SCALE_X, to), ObjectAnimator.ofFloat(null, View.SCALE_Y, to));
        set.setDuration(ms); set.setInterpolator(EASE); return set;
    }

    /** Rounded input that fades to a white, outlined state while focused. */
    static void field(EditText e) {
        Context c = e.getContext();
        GradientDrawable idle = round(c, SURFACE_SOFT, 12); idle.setStroke(dp(c, 1), 0xffE1E6EE);
        GradientDrawable focused = round(c, SURFACE, 12); focused.setStroke(dp(c, 2), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.setEnterFadeDuration(160); states.setExitFadeDuration(160);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        e.setBackground(states);
        e.setTextColor(INK); e.setHintTextColor(0xff98A2B3);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    }

    static TextView text(Context c, CharSequence value, float sp, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(dp(c, 2), 1f); return v;
    }
    static TextView heading(Context c, CharSequence value, float sp) {
        TextView v = text(c, value, sp, INK); v.setTypeface(Typeface.DEFAULT_BOLD); return v;
    }

    /** White rounded section with an optional title and caption. */
    static LinearLayout section(Context c, ViewGroup parent, String title, String caption, int topMargin) {
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        card.setBackground(card(c, 20));
        smoothLayout(card);
        if (title != null) card.addView(heading(c, title, 17));
        if (caption != null) { TextView note = text(c, caption, 13, MUTED); card.addView(note, margins(c, 0, 4, 0, 4)); }
        parent.addView(card, margins(c, 0, topMargin, 0, 0));
        return card;
    }

    /** Back button + title row used by every secondary screen. */
    static LinearLayout topBar(Context c, Button back, String title) {
        LinearLayout bar = new LinearLayout(c); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(c, 6), dp(c, 6), dp(c, 16), dp(c, 6));
        style(back, TEXT); back.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        bar.addView(back, new LinearLayout.LayoutParams(-2, dp(c, 48)));
        TextView name = heading(c, title, 18); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)); return p;
    }

    /** System-bar (and keyboard, when requested) padding for edge-to-edge windows. */
    static void insets(View root, int l, int t, int r, int b, boolean keyboard) {
        Context c = root.getContext();
        root.setOnApplyWindowInsetsListener((v, i) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int ime = keyboard ? i.getInsets(WindowInsets.Type.ime()).bottom : 0;
                left = bars.left; top = bars.top; right = bars.right; bottom = Math.max(bars.bottom, ime);
            } else {
                left = i.getSystemWindowInsetLeft(); top = i.getSystemWindowInsetTop();
                right = i.getSystemWindowInsetRight(); bottom = i.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(c, l) + left, dp(c, t) + top, dp(c, r) + right, dp(c, b) + bottom);
            return i;
        });
    }

    static void roundClip(View view, float radius) {
        final int r = dp(view.getContext(), radius);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) { outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        view.setClipToOutline(true);
    }

    /** Animates child appear/disappear and the container's own size changes. */
    static void smoothLayout(ViewGroup group) {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(200);
        t.setInterpolator(LayoutTransition.CHANGING, STANDARD);
        t.setInterpolator(LayoutTransition.CHANGE_APPEARING, STANDARD);
        t.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, STANDARD);
        group.setLayoutTransition(t);
    }

    /** Children fade up one after another when a screen first opens. */
    static void enter(ViewGroup group, long delay) {
        if (!motion()) return;
        float lift = dp(group.getContext(), 16);
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.setAlpha(0f); child.setTranslationY(lift);
            child.animate().alpha(1f).translationY(0f).setStartDelay(delay + Math.min(shown++, 8) * 45L)
                    .setDuration(380).setInterpolator(EASE).start();
        }
    }

    /** Fade a view in/out. Interrupting a running fade is safe: cancel() skips the old end action. */
    static void reveal(View v, boolean show, int hiddenState) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(1f).setDuration(180).setInterpolator(STANDARD).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(220).setInterpolator(STANDARD)
                    .withEndAction(() -> { v.setVisibility(hiddenState); v.setAlpha(1f); }).start();
        }
    }

    /** Slides a bar in from below when it becomes visible. */
    static void slideIn(View v, boolean show) {
        v.animate().cancel();
        if (!show) { v.setVisibility(View.GONE); v.setTranslationY(0); v.setAlpha(1f); return; }
        if (v.getVisibility() == View.VISIBLE) return;
        v.setVisibility(View.VISIBLE);
        if (!motion()) return;
        v.setTranslationY(dp(v.getContext(), 28)); v.setAlpha(0f);
        v.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(EASE).start();
    }

    static void shake(View v) {
        if (!motion()) return;
        float d = dp(v.getContext(), 1);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, 8 * d, -6 * d, 4 * d, -2 * d, 0);
        a.setDuration(380); a.start();
    }

    /** Smoothly recolors a GradientDrawable; the current color is remembered on the view. */
    static void tint(View owner, GradientDrawable shape, int to) {
        Object old = owner.getTag(R_TAG_COLOR);
        Object running = owner.getTag(R_TAG_ANIM);
        if (running instanceof Animator) ((Animator) running).cancel();
        int from = old instanceof Integer ? (Integer) old : to;
        owner.setTag(R_TAG_COLOR, to);
        if (from == to || !motion()) { shape.setColor(to); return; }
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(260); a.setInterpolator(STANDARD);
        a.addUpdateListener(x -> shape.setColor((Integer) x.getAnimatedValue()));
        owner.setTag(R_TAG_ANIM, a); a.start();
    }
    private static final int R_TAG_COLOR = R.id.ui_tint_color, R_TAG_ANIM = R.id.ui_tint_animator;

    /** Wraps a view (e.g. a Spinner) in a rounded field-colored frame. */
    static FrameLayout framed(View child) {
        Context c = child.getContext();
        FrameLayout frame = new FrameLayout(c);
        GradientDrawable bg = round(c, SURFACE_SOFT, 12); bg.setStroke(dp(c, 1), 0xffE1E6EE);
        frame.setBackground(bg);
        frame.addView(child, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    /** Status line that briefly fades in whenever its text actually changes. */
    static final class StatusText extends TextView {
        private boolean ready;
        StatusText(Context c) { super(c); ready = true; }
        @Override public void setText(CharSequence value, BufferType type) {
            boolean changed = ready && !TextUtils.equals(getText(), value);
            super.setText(value, type);
            if (!changed || !isAttachedToWindow() || !motion()) return;
            animate().cancel();
            setAlpha(.2f); setTranslationY(dp(getContext(), 3));
            animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(EASE).start();
        }
    }
}
