package cn.local.manga;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** A 48dp-wide page scrubber. The bubble reports the target before/during scroll. */
    // Constructed only in Java with required callbacks; XML inflation is unsupported.
    @android.annotation.SuppressLint("ViewConstructor")
final class QuickPageRail extends View {
    private int count,current;private boolean dragging;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final IntConsumer change;
    QuickPageRail(Context c,IntConsumer change){super(c);this.change=change;setFocusable(true);setContentDescription("快速翻页");}
    void update(int page,int total){current=Math.max(0,page);count=total;setContentDescription("快速翻页，第 "+(total==0?0:current+1)+" 页，共 "+total+" 页");invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(count<2)return;float x=getWidth()*.72f,top=Ui.dp(getContext(),24),bottom=getHeight()-top;paint.setStrokeWidth(Ui.dp(getContext(),3));paint.setColor(Ui.OUTLINE);canvas.drawLine(x,top,x,bottom,paint);float y=top+(bottom-top)*current/(count-1f);paint.setColor(Ui.ACCENT);canvas.drawCircle(x,y,Ui.dp(getContext(),6),paint);
        if(dragging){float h=Ui.dp(getContext(),32),cy=Math.max(h/2,Math.min(getHeight()-h/2,y));paint.setColor(Ui.ACCENT_DEEP);canvas.drawRoundRect(0,cy-h/2,getWidth(),cy+h/2,Ui.dp(getContext(),10),Ui.dp(getContext(),10),paint);paint.setColor(Ui.SURFACE);paint.setTextSize(Ui.dp(getContext(),12));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(String.valueOf(current+1),getWidth()/2f,cy-(paint.ascent()+paint.descent())/2,paint);}}
    private void select(float y){float pad=Ui.dp(getContext(),24);current=Math.max(0,Math.min(count-1,Math.round((y-pad)/Math.max(1,getHeight()-2*pad)*(count-1))));change.accept(current);invalidate();}
    @Override public boolean onTouchEvent(MotionEvent e){if(count<2)return false;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:dragging=true;getParent().requestDisallowInterceptTouchEvent(true);select(e.getY());return true;case MotionEvent.ACTION_MOVE:select(e.getY());return true;case MotionEvent.ACTION_UP:select(e.getY());performClick();case MotionEvent.ACTION_CANCEL:dragging=false;getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,1,Math.max(1,count),current+1));info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){int page=current;if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null)page=Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))-1;else if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)page++;else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)page--;else return super.performAccessibilityAction(action,args);current=Math.max(0,Math.min(Math.max(0,count-1),page));change.accept(current);invalidate();return true;}
}
