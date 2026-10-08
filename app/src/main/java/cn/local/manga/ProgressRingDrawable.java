package cn.local.manga;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** 24-unit progress ring. Animation is stopped by its owning screen on pause. */
final class ProgressRingDrawable extends Drawable implements Animatable, Runnable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle=new RectF(3,3,21,21), square=new RectF(9,9,15,15);
    private int color=Ui.DANGER,alpha=255;
    private float progress, target=-1;
    private boolean indeterminate=true,stopping,running;
    private ValueAnimator transition;
    void update(float value,boolean unknown,boolean isStopping){
        indeterminate=unknown;stopping=isStopping;
        value=Math.max(0,Math.min(1,value));
        if(target!=value){target=value;if(transition!=null)transition.cancel();
            if(Ui.motion()&&!unknown){transition=ValueAnimator.ofFloat(progress,value);transition.setDuration(300);transition.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidateSelf();});transition.start();}
            else progress=value;
        }
        if(unknown)start();else stop();invalidateSelf();
    }
    @Override public void draw(Canvas canvas){
        int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);paint.setStrokeCap(Paint.Cap.ROUND);
        if(!stopping){paint.setAlpha(Math.round(alpha*.28f));canvas.drawOval(circle,paint);}
        paint.setAlpha(alpha);float rotation=indeterminate&&Ui.motion()?(SystemClock.uptimeMillis()%1000)*.36f:0;
        canvas.drawArc(circle,-90+rotation,indeterminate?(stopping?180:120):360*progress,false,paint);
        if(!stopping){paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(square,1.2f,1.2f,paint);}canvas.restoreToCount(save);
    }
    @Override public void setTint(int value){color=value;invalidateSelf();}
    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public void start(){if(running||!Ui.motion())return;running=true;scheduleSelf(this,SystemClock.uptimeMillis()+16);}
    @Override public void stop(){running=false;unscheduleSelf(this);}
    void dispose(){stop();if(transition!=null)transition.cancel();}
    @Override public boolean isRunning(){return running;}
    @Override public void run(){if(!running)return;invalidateSelf();scheduleSelf(this,SystemClock.uptimeMillis()+16);}
    @Override public boolean setVisible(boolean visible,boolean restart){boolean changed=super.setVisible(visible,restart);if(!visible)stop();else if(indeterminate)start();return changed;}
}
