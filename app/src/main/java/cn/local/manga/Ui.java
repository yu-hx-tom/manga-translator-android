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
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Window;
import android.view.WindowManager;
import java.util.function.IntConsumer;

/** Shared colors, widget styling and short, interruptible motion. Purely visual; no app state. */
final class Ui {
    static final int BG = 0xffF6F8FC, SURFACE = 0xffFFFFFF, SURFACE_SOFT = 0xffF1F4F9, OUTLINE = 0xffE3E8F0;
    static final int INK = 0xff1F2430, MUTED = 0xff667085, ACCENT = 0xff1A73E8, ACCENT_DEEP = 0xff0B57D0;
    static final int ACCENT_SOFT = 0xffE8F0FE, DANGER = 0xffB3261E, DANGER_SOFT = 0xffFCE8E6, INFO_SOFT = 0xffE8EEF8;
    static final int ICON=0xff3C4657, SUCCESS=0xff137333, SUCCESS_SOFT=0xffE6F4EA, WARNING=0xff8A5700, WARNING_SOFT=0xffFFF3D6;
    static final int DISABLED=0xff8C96A6, DISABLED_SOFT=0xffEEF1F5, PLACEHOLDER=0xffA9B1BD, RIPPLE=0x241A73E8;
    static final int TRANSPARENT=0x00000000, OVERLAY=0xCCFFFFFF, BLACK=0xff000000;
    static final int PURPLE=0xff673AB7,PINK=0xffC2185B,CYAN=0xff00838F,ORANGE=0xffEF6C00,BROWN=0xff795548,GRAY=0xff616161;
    static final int NEUTRAL=0, INFO=1, POSITIVE=2, CAUTION=3, NEGATIVE=4;
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
        // The tint call mutates the ripple, cloning its layers without their drawable state, so the
        // fill would show its disabled color until the first touch. Push the view state down again.
        Drawable background = b.getBackground();
        background.setState(new int[0]); background.setState(b.getDrawableState()); background.jumpToCurrentState();
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
    @Deprecated static LinearLayout topBar(Context c, Button back, String title) {
        back.setText("");back.setContentDescription("返回");style(back,TEXT);back.setPadding(dp(c,12),0,dp(c,12),0);
        Icons.setIcon(back,R.drawable.ic_arrow_back,ICON,24);
        return appBar(c,back,title,null);
    }

    static LinearLayout appBar(Context c,View navigation,String title,String subtitle,View... actions){
        LinearLayout bar=new LinearLayout(c);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setMinimumHeight(dp(c,56));
        bar.setPadding(dp(c,navigation==null?16:4),0,dp(c,4),0);bar.setBackgroundColor(BG);
        if(navigation!=null)bar.addView(navigation,new LinearLayout.LayoutParams(dp(c,48),dp(c,48)));
        LinearLayout titles=new LinearLayout(c);titles.setOrientation(LinearLayout.VERTICAL);titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=heading(c,title,18);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setTag("app-bar-title");titles.addView(name);
        if(subtitle!=null){TextView note=text(c,subtitle,12,MUTED);note.setSingleLine(true);note.setEllipsize(TextUtils.TruncateAt.END);note.setTag("app-bar-subtitle");titles.addView(note);}
        bar.addView(titles,new LinearLayout.LayoutParams(0,dp(c,56),1));
        for(View action:actions)bar.addView(action,new LinearLayout.LayoutParams(dp(c,48),dp(c,48)));
        return bar;
    }

    static LinearLayout menuRow(Context c,int icon,String label,String trailing){
        LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(c,52));row.setPadding(dp(c,12),dp(c,6),dp(c,12),dp(c,6));
        row.setBackground(ripple(null,round(c,SURFACE,12),RIPPLE));
        ImageView image=new ImageView(c);image.setImageDrawable(Icons.icon(c,icon,ICON));image.setImageTintList(Icons.tint(ICON,ACCENT_DEEP));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image,new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));
        TextView text=text(c,label,15,INK);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMarginStart(dp(c,12));row.addView(text,p);
        if(trailing!=null){TextView note=text(c,trailing,12,MUTED);row.addView(note);}
        row.setContentDescription(label+(trailing==null?"":"，"+trailing));return row;
    }
    static TextView chip(Context c,String label,int icon,int tone){
        int foreground=tone==INFO?ACCENT_DEEP:tone==POSITIVE?SUCCESS:tone==CAUTION?WARNING:tone==NEGATIVE?DANGER:MUTED;
        int background=tone==INFO?ACCENT_SOFT:tone==POSITIVE?SUCCESS_SOFT:tone==CAUTION?WARNING_SOFT:tone==NEGATIVE?DANGER_SOFT:SURFACE_SOFT;
        TextView chip=text(c,label,12,foreground);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(c,8),dp(c,4),dp(c,8),dp(c,4));chip.setBackground(round(c,background,10));
        if(icon!=0)Icons.setIcon(chip,icon,foreground,14);return chip;
    }
    static ProgressBar progressLine(Context c){
        ProgressBar bar=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgressTintList(ColorStateList.valueOf(ACCENT));bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(ColorStateList.valueOf(ACCENT_SOFT));
        bar.setMinimumHeight(dp(c,3));bar.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(c,3)));return bar;
    }
    static Segmented segmented(Context c,String[] options,int selected,IntConsumer change){return new Segmented(c,options,selected,change);}
    // Constructed only in Java with required callbacks; XML inflation is unsupported.
    @android.annotation.SuppressLint("ViewConstructor")
    static final class Segmented extends LinearLayout {
        private final Button[] buttons;private final IntConsumer changed;private int selected;
        Segmented(Context c,String[] options,int selected,IntConsumer changed){super(c);this.changed=changed;buttons=new Button[options.length];setPadding(dp(c,3),dp(c,3),dp(c,3),dp(c,3));setBackground(round(c,SURFACE_SOFT,16));
            for(int i=0;i<options.length;i++){final int index=i;Button b=button(c,options[i],TEXT,v->{select(index);if(this.changed!=null)this.changed.accept(index);});b.setTextSize(13);b.setPadding(dp(c,4),0,dp(c,4),0);b.setMinWidth(0);b.setMinimumWidth(0);b.setContentDescription(options[i]);buttons[i]=b;addView(b,new LinearLayout.LayoutParams(0,dp(c,48),1));}select(selected);
        }
        void select(int value){selected=Math.max(0,Math.min(buttons.length-1,value));for(int i=0;i<buttons.length;i++){style(buttons[i],i==selected?TONAL:TEXT);buttons[i].setSelected(i==selected);buttons[i].setPadding(dp(getContext(),4),0,dp(getContext(),4),0);}}
        void icons(int... resources){for(int i=0;i<Math.min(buttons.length,resources.length);i++)Icons.setIcon(buttons[i],resources[i],ACCENT_DEEP,18);}
        int selected(){return selected;}
    }
    static View swatch(Context c,int color,boolean selected){
        FrameLayout hit=new FrameLayout(c);hit.setMinimumHeight(dp(c,48));hit.setMinimumWidth(dp(c,48));hit.setContentDescription(String.format("颜色 #%06X%s",color & 0xffffff,selected?"，已选中":""));
        GradientDrawable shape=round(c,color,12);shape.setStroke(dp(c,1),OUTLINE);FrameLayout dot=new FrameLayout(c);dot.setBackground(shape);FrameLayout.LayoutParams pos=new FrameLayout.LayoutParams(dp(c,24),dp(c,24),Gravity.CENTER);hit.addView(dot,pos);
        if(selected){ImageView mark=new ImageView(c);mark.setImageDrawable(Icons.icon(c,R.drawable.ic_check,Color.luminance(color)>.45?INK:SURFACE));dot.addView(mark,new FrameLayout.LayoutParams(-1,-1));}
        hit.setBackground(ripple(null,round(c,SURFACE,24),RIPPLE));return hit;
    }
    static Sheet sheet(Activity activity,String title){return new Sheet(activity,title);}
    static final class Sheet extends Dialog {
        final LinearLayout body,footer;private final LinearLayout panel;
        Sheet(Activity a,String title){super(a);requestWindowFeature(Window.FEATURE_NO_TITLE);setCanceledOnTouchOutside(true);
            panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);GradientDrawable bg=round(a,SURFACE,20);bg.setCornerRadii(new float[]{dp(a,20),dp(a,20),dp(a,20),dp(a,20),0,0,0,0});panel.setBackground(bg);
            View handle=new View(a);handle.setBackground(round(a,OUTLINE,2));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(a,36),dp(a,4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.topMargin=dp(a,10);hp.bottomMargin=dp(a,4);panel.addView(handle,hp);
            panel.addView(appBar(a,null,title,null,Icons.iconButton(a,R.drawable.ic_close,"关闭面板",v->dismiss())));
            ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(a,12),0,dp(a,12),dp(a,12));scroll.addView(body);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            footer=new LinearLayout(a);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,12));panel.addView(footer);setContentView(panel);
            Window w=getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.35f);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            panel.setOnApplyWindowInsetsListener((v,insets)->{int bottom=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemBars()).bottom:insets.getSystemWindowInsetBottom();v.setPadding(0,0,0,bottom);return insets;});
        }
        @Override public void show(){super.show();getWindow().setLayout(-1,Math.round(getContext().getResources().getDisplayMetrics().heightPixels*.82f));if(motion()){panel.setTranslationY(dp(getContext(),80));panel.setAlpha(0);panel.animate().translationY(0).alpha(1).setDuration(300).setInterpolator(EASE).start();}}
        void item(int icon,String label,Runnable action){item(icon,label,null,true,action);}
        void item(int icon,String label,String reason,boolean enabled,Runnable action){LinearLayout row=menuRow(getContext(),icon,label,reason);row.setEnabled(enabled);row.setAlpha(enabled?1:.4f);row.setOnClickListener(v->{dismiss();action.run();});body.addView(row);}
        void group(String title){TextView label=heading(getContext(),title,13);label.setTextColor(MUTED);body.addView(label,margins(getContext(),12,16,0,4));}
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
