package cn.local.manga;

import android.content.Context;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.List;
import java.util.concurrent.CancellationException;

/** Optional device-only checks; compiling this class is not runtime acceptance. No network. */
public final class RtDetrInstrumentation {
    private RtDetrInstrumentation(){}
    public static String run(Context context)throws Exception{
        Bitmap page=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setTextSize(32);
        new Canvas(page).drawText("日本語 テスト",60,100,ink);
        int loaded=0;
        try{
            for(String id:DetectorModels.IDS){
                if(DetectorModels.PP_ID.equals(id))continue;
                RtDetrDetector detector=new RtDetrDetector(context,id);
                try{
                    List<Region> regions=detector.detect(page);
                    for(Region r:regions)if(r.box.left<0||r.box.top<0||r.box.right>480||r.box.bottom>640)throw new AssertionError("original coordinates");
                    Thread.currentThread().interrupt();
                    try{detector.detect(page);throw new AssertionError("cancel ignored");}catch(CancellationException expected){}finally{Thread.interrupted();}
                    loaded++;
                }finally{detector.close();}
                try{detector.detect(page);throw new AssertionError("closed detector ran");}catch(IllegalStateException expected){}
                detector.close();
            }
            try(Detector pp=new Detector(context)){pp.detect(page);loaded++;}
            return "selected models loaded sequentially="+loaded+"; bounded coordinates, cancellation before run, closed-session rejection. No accuracy assertion.";
        }
        finally{Thread.interrupted();page.recycle();}
    }
}

