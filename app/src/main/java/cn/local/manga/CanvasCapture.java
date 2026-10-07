package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Replays only a bounded, fully recorded drawImage plan. Unsupported canvas operations fail closed. */
final class CanvasCapture {
    static final int MAX_OPS=512, MAX_SOURCES=16;
    interface Loader { Bitmap load(String url) throws Exception; }
    final int width,height;
    final List<Draw> operations;
    private CanvasCapture(int width,int height,List<Draw> operations){this.width=width;this.height=height;this.operations=operations;}

    static final class Draw {
        final String url;
        final int sourceWidth,sourceHeight;
        final float sx,sy,sw,sh,dx,dy,dw,dh;
        final float[] transform;
        final boolean smoothing;
        Draw(JSONObject item)throws Exception {
            url=item.getString("url");
            if(url.length()>12*1024*1024||!(url.matches("(?is)^https?://.+")||url.matches("(?is)^data:image/(png|jpeg|webp|gif);base64,.+")))
                throw new Exception("画布源图地址暂不能重建，请使用翻译当前画面");
            sourceWidth=dimension(item,"sourceWidth",100000);sourceHeight=dimension(item,"sourceHeight",100000);
            sx=number(item,"sx");sy=number(item,"sy");sw=positive(item,"sw");sh=positive(item,"sh");
            dx=number(item,"dx");dy=number(item,"dy");dw=positive(item,"dw");dh=positive(item,"dh");
            if(sx<0||sy<0||(double)sx+sw>sourceWidth||(double)sy+sh>sourceHeight)
                throw new Exception("画布裁切超出源图范围，请使用翻译当前画面");
            JSONArray values=item.getJSONArray("matrix");if(values.length()!=6)throw new Exception("画布变换参数不完整");
            transform=new float[6];for(int i=0;i<6;i++)transform[i]=finite(values.getDouble(i));
            smoothing=item.optBoolean("smoothing",true);
        }
        /** Android's 3x3 row-major representation of Canvas2D's [a,b,c,d,e,f]. */
        float[] matrixValues(){return new float[]{transform[0],transform[2],transform[4],transform[1],transform[3],transform[5],0,0,1};}
    }
    static CanvasCapture parse(JSONObject value)throws Exception {
        if(value==null||!"canvas".equals(value.optString("kind")))throw new Exception("画布绘制记录不可用，请刷新漫画页后重试");
        if(value.optBoolean("unsupported")||!value.optString("error").isEmpty())throw new Exception("无法完整读取此画布，请使用翻译当前画面");
        int width=dimension(value,"width",6000),height=dimension(value,"height",6000);
        if((long)width*height>BrowserImageLoader.MAX_PIXELS)throw new Exception("画布超过 800 万像素，请使用翻译当前画面");
        JSONArray ops=value.getJSONArray("ops");if(ops.length()<1||ops.length()>MAX_OPS)throw new Exception("画布绘制次数超出重建范围，请使用翻译当前画面");
        ArrayList<Draw> draws=new ArrayList<>();HashMap<String,Draw> sources=new HashMap<>();
        long dataChars=0;
        for(int i=0;i<ops.length();i++){
            Draw draw=new Draw(ops.getJSONObject(i));
            Draw previous=sources.get(draw.url);
            if(previous==null){sources.put(draw.url,draw);dataChars+=draw.url.length();if(sources.size()>MAX_SOURCES||dataChars>12L*1024*1024)throw new Exception("画布源图过多，请使用翻译当前画面");}
            else if(previous.sourceWidth!=draw.sourceWidth||previous.sourceHeight!=draw.sourceHeight)
                throw new Exception("画布源图尺寸已变化，无法准确重建");
            draws.add(draw);
        }
        return new CanvasCapture(width,height,draws);
    }
    private static int dimension(JSONObject value,String key,int max)throws Exception {
        double n=value.getDouble(key);if(!Double.isFinite(n)||n<1||n>max||n!=Math.rint(n))throw new Exception("画布尺寸无效");return (int)n;
    }
    private static float finite(double n)throws Exception {if(!Double.isFinite(n)||Math.abs(n)>1_000_000)throw new Exception("画布坐标无效");return (float)n;}
    private static float number(JSONObject value,String key)throws Exception{return finite(value.getDouble(key));}
    private static float positive(JSONObject value,String key)throws Exception{float n=number(value,key);if(n<0.000001f)throw new Exception("画布裁切尺寸无效");return n;}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("已取消");}

    Bitmap render(Loader loader,BooleanSupplier cancelled)throws Exception {
        check(cancelled);
        Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888),source=null;
        String current=null;boolean complete=false;int loads=0;
        try{
            Canvas canvas=new Canvas(result);Paint paint=new Paint();Matrix matrix=new Matrix();
            for(Draw draw:operations){
                check(cancelled);
                // Keep one decoded source: tiled manga normally draws many pieces from the same sprite sheet.
                if(!draw.url.equals(current)){
                    if(++loads>64)throw new Exception("画布切换源图过于频繁，请使用翻译当前画面");
                    if(source!=null){source.recycle();source=null;}
                    source=loader.load(draw.url);current=draw.url;
                    if(source==null||source.isRecycled())throw new Exception("画布源图无法读取");
                    double ratio=(double)source.getWidth()/source.getHeight()/((double)draw.sourceWidth/draw.sourceHeight);
                    if(Math.abs(ratio-1)>.02)throw new Exception("画布源图尺寸已变化，无法准确重建");
                }
                paint.setFilterBitmap(draw.smoothing);matrix.setValues(draw.matrixValues());
                int saved=canvas.save();
                try{
                    canvas.concat(matrix);
                    canvas.clipRect(draw.dx,draw.dy,draw.dx+draw.dw,draw.dy+draw.dh);
                    canvas.translate(draw.dx,draw.dy);canvas.scale(draw.dw/draw.sw,draw.dh/draw.sh);
                    canvas.translate(-draw.sx,-draw.sy);
                    canvas.scale((float)draw.sourceWidth/source.getWidth(),(float)draw.sourceHeight/source.getHeight());
                    canvas.drawBitmap(source,0,0,paint);
                }finally{canvas.restoreToCount(saved);}
            }
            check(cancelled);complete=true;return result;
        }finally{if(source!=null&&!source.isRecycled())source.recycle();if(!complete)result.recycle();}
    }
}
