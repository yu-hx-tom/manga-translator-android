package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Font selection only. Saved legacy family names remain readable and are never rewritten on load. */
final class FontChoices {
    static final String NORMAL="sans-serif",BOLD="sans-serif-black",SERIF="serif";
    private static Boolean serifAvailable;
    static Typeface typeface(String family){return BOLD.equals(family)?Typeface.create(NORMAL,Typeface.BOLD):Typeface.create(family==null?NORMAL:family,Typeface.NORMAL);}
    static synchronized boolean hasCjkSerif(){
        if(serifAvailable!=null)return serifAvailable;
        Bitmap normal=sample(NORMAL),serif=sample(SERIF);serifAvailable=!normal.sameAs(serif);normal.recycle();serif.recycle();return serifAvailable;
    }
    private static Bitmap sample(String family){Bitmap image=Bitmap.createBitmap(320,64,Bitmap.Config.ARGB_8888);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Ui.INK);paint.setTextSize(36);paint.setTypeface(typeface(family));paint.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);new Canvas(image).drawText("汉字永国翻译",4,44,paint);return image;}
    static String label(String family){if(BOLD.equals(family)||"sans-serif-medium".equals(family))return "粗黑";if(SERIF.equals(family)&&hasCjkSerif())return "宋体 / 明朝";return "默认黑体";}
}
