package cn.local.manga;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;

/** Production layout geometry only; font rasterization stays an Android runtime acceptance boundary. */
public final class CropTextLayoutChecks {
    static int checks;
    static void ok(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static void equal(float a,float b,String reason){ok(Math.abs(a-b)<.001f,reason);}
    static void compare(int pageWidth,int pageHeight,int[] anchor,int left,int top,String text,boolean vertical){
        int[] untouched=anchor.clone();NearbyTextLayout.Plan page=NearbyTextLayout.plan(pageWidth,pageHeight,anchor,Collections.emptyList(),Collections.emptyList(),text,null,vertical,null);
        NearbyTextLayout.Plan crop=NearbyTextLayout.forCrop(pageWidth,pageHeight,anchor,left,top,vertical,text);
        equal(page.font,crop.font,"repair crop uses exact whole-page font size");equal(page.step,crop.step,"repair crop preserves complete grid scale");
        ok(page.rows()==crop.rows()&&page.columns==crop.columns&&Arrays.equals(page.points,crop.points),"crop retains rows, columns and every Unicode codepoint");
        ok(crop.vertical==vertical&&Arrays.equals(anchor,untouched),"requested orientation is honored without modifying original anchor");
        ok(Arrays.equals(crop.box,new int[]{page.box[0]-left,page.box[1]-top,page.box[2]-left,page.box[3]-top}),"only crop coordinate origin changes");
        for(int i=0;i<crop.points.length;i++){
            equal(page.cellLeft(i)-left,crop.cellLeft(i),"glyph x maps back to the identical page coordinate");equal(page.cellTop(i)-top,crop.cellTop(i),"glyph y maps back to the identical page coordinate");
            ok(crop.cellLeft(i)>=crop.box[0]-.001f&&crop.cellTop(i)>=crop.box[1]-.001f
                &&crop.cellLeft(i)+crop.step<=crop.box[2]+.001f&&crop.cellTop(i)+crop.step<=crop.box[3]+.001f,"complete glyph clipping cell remains inside original target");
        }
    }
    public static void main(String[] args)throws Exception{
        String text="甲乙丙丁 一二三四五六七八九十天地";
        for(boolean vertical:new boolean[]{false,true}){
            compare(1061,1500,new int[]{434,856,530,1034},410,832,text,vertical);
            compare(1061,1500,new int[]{123,210,623,300},100,180,"横竖阅读次序和完整字符😀𠮷",vertical);
            compare(320,240,new int[]{-2,-3,75,99},0,0,"页边界裁切测试",vertical);
        }
        NearbyTextLayout.Plan fixed=NearbyTextLayout.forCrop(1061,1500,new int[]{434,856,530,1034},410,832,true,text);
        NearbyTextLayout.Plan previous=NearbyTextLayout.plan(144,226,new int[]{24,24,120,202},Collections.emptyList(),Collections.emptyList(),text,null);
        ok(fixed.font>20&&Math.abs(previous.font-fixed.font)<.001f,"same P19 target has identical type scale on a page or translated crop; neither uses a page-width cap");
        boolean stopped=false;Thread.currentThread().interrupt();try{NearbyTextLayout.forCrop(1061,1500,new int[]{434,856,530,1034},410,832,true,text);}catch(CancellationException expected){stopped=true;}finally{Thread.interrupted();}
        ok(stopped,"crop layout still propagates interruption before any rendering");
        System.out.println("CropTextLayoutChecks: "+checks+" checks passed (production forCrop geometry; no Android font rasterization)");
    }
}
