package cn.local.manga;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.content.Context;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.*;
public final class V073IntegrationChecks {
    static int count;static void ok(boolean value,String reason){count++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("engine-detection-cache-");Context context=new Context(root.toFile());
        Bitmap bitmap=new Bitmap(4,4,new int[16]);String hash=DetectionCache.contentHash(bitmap,()->false);
        AppSettings settings=new AppSettings();DetectionCache cache=new DetectionCache(context.getCacheDir());
        Region region=new Region("one",new Rect(0,0,2,2),Collections.singletonList(new Rect(0,0,2,2)),true);
        cache.write(hash,settings.detectorModel,4,4,Collections.singletonList(region),()->false);
        try(TranslationEngine engine=new TranslationEngine(context)){
            int calls=RtDetrDetector.calls;
            ok(engine.detect(bitmap,settings,hash,false,()->false).size()==1&&engine.lastDetectionCacheHit(),"browser detection consumes exact content cache");
            ok(calls==RtDetrDetector.calls,"cached detection performs no model call");
            TranslationStages stages=new TranslationStages(1,64,()->64);ExecutorService workers=Executors.newFixedThreadPool(2);
            CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1);
            try{
                Future<?> held=workers.submit(()->{try{stages.detection(()->{running.countDown();release.await();return null;},()->false,()->2);}catch(Exception e){throw new RuntimeException(e);}});
                ok(running.await(2,TimeUnit.SECONDS),"background model gate held");
                Future<List<Region>> restored=workers.submit(()->engine.detect(bitmap,settings,hash,false,()->false,stages,()->0));
                ok(restored.get(2,TimeUnit.SECONDS).size()==1,"cached regions bypass a busy model gate");release.countDown();held.get(2,TimeUnit.SECONDS);
            }finally{release.countDown();workers.shutdownNow();}
            ok(engine.detect(bitmap,settings,hash,true,()->false).isEmpty()&&!engine.lastDetectionCacheHit(),"force retranslate runs detector again");
            ok(RtDetrDetector.calls==calls+1,"fresh path calls detector once");
            ok(cache.read(hash,settings.detectorModel,4,4,()->false)==null,"successful empty redetection invalidates old result");
            cache.write(hash,settings.detectorModel,4,4,Collections.singletonList(region),()->false);
            settings.detectorModel=DetectorModels.PP_ID;engine.detect(bitmap,settings,hash,false,()->false);
            ok(!engine.lastDetectionCacheHit()&&RtDetrDetector.calls==calls+2,"different detector cannot reuse cached regions");
            try{engine.detect(bitmap,settings,hash,false,()->true);throw new AssertionError("not cancelled");}catch(CancellationException expected){count++;}
        }
        ok(RtDetrDetector.live==0,"cache integration preserves detector resource release");
        Path source=Paths.get(args[0]);String engine=Files.readString(source.resolve("TranslationEngine.java"));
        String fallback=engine.substring(engine.indexOf("    private static int[] renderNearby("),engine.indexOf("    private static void drawInPlace("));
        String shared=engine.substring(engine.indexOf("    private static void drawInPlace("),engine.indexOf("    private static int[] bounds("));
        String repaired=engine.substring(engine.indexOf("    private static int[] renderRepaired("),engine.indexOf("    private static int[] renderNearby("));
        ok(fallback.contains("drawInPlace(canvas,plan,Color.RED,cancelled)")&&!fallback.contains("Color.BLACK"),"fallback explicitly selects red in shared original-box renderer");
        ok(fallback.contains("canvas.clipRect(plan.box[0],plan.box[1],plan.box[2],plan.box[3])")&&fallback.contains("for(Region other:regions)if(!other.id.equals(region.id))")
            &&fallback.contains("canvas.clipOutRect(other.box.left-2,other.box.top-2,other.box.right+2,other.box.bottom+2)")
            &&fallback.indexOf("canvas.clipOutRect")<fallback.indexOf("drawInPlace(canvas")&&fallback.contains("finally{canvas.restoreToCount(saved);}"),"fallback clips original target and every other paragraph plus two pixels before drawing, then restores Canvas state");
        ok(shared.contains("Paint.Style.STROKE")&&shared.contains("setColor(Color.WHITE)")&&shared.contains("Paint.Style.FILL")&&shared.contains("setColor(color)"),"shared text renderer preserves white outline and caller-selected fill");
        ok(repaired.contains("drawInPlace(new Canvas(staged),plan,Color.BLACK,cancelled)")&&repaired.contains("RepairPixels.composite")&&repaired.contains("clipOutRect"),"validated cleanup selects black text and preserves protected-paragraph clipping");
        int[] anchor={10,10,110,110},protectedParagraph={80,10,120,110};
        NearbyTextLayout.Plan overlap=NearbyTextLayout.plan(140,120,anchor,Collections.emptyList(),Collections.emptyList(),"甲乙丙丁一二三四五六七八九十天地",null);
        boolean candidateTouches=false;for(int i=0;i<overlap.points.length;i++)if(overlap.cellLeft(i)<protectedParagraph[2]+2&&overlap.cellLeft(i)+overlap.step>protectedParagraph[0]-2)candidateTouches=true;
        ok(candidateTouches&&overlap.points.length==16,"overlapping paragraph fixture requires protection while retaining the complete transcript grid");
        boolean[] visible=RepairPixels.editable(140,120,new int[][]{anchor},new int[][]{protectedParagraph});
        boolean targetOnly=true,otherPreserved=true,remaining=false;
        for(int y=0;y<120;y++)for(int x=0;x<140;x++){
            if(x<anchor[0]||x>=anchor[2]||y<anchor[1]||y>=anchor[3])targetOnly&=!visible[y*140+x];
            if(x>=protectedParagraph[0]-2&&x<protectedParagraph[2]+2&&y>=protectedParagraph[1]-2&&y<protectedParagraph[3]+2)otherPreserved&=!visible[y*140+x];
            remaining|=visible[y*140+x];
        }
        ok(targetOnly&&otherPreserved&&remaining,"original-box clip minus protected paragraph buffer retains only safe fallback pixels");
        String normal=engine.substring(engine.indexOf("    private static int[] renderLocal("),engine.indexOf("    private static void abortForThrottle("));
        ok(normal.contains("setColor(Color.BLACK)")&&normal.contains("WhiteBubbleCleaner.touchesForeignInk")&&normal.contains("BubbleLayout.plan"),"normal text color and foreign-ink/layout guards retained");
        NearbyTextLayout.Plan plan=new NearbyTextLayout.Plan();plan.box=new int[]{10,20,80,110};plan.padding=5;plan.step=20;plan.columns=3;plan.points="一二三四五六七八九十甲乙".codePoints().toArray();
        ok(plan.rows()==4&&plan.cellLeft(0)>plan.cellLeft(4)&&plan.cellTop(0)<plan.cellTop(1),"fallback columns remain right to left, each column downwards");
        String browser=Files.readString(source.resolve("BrowserActivity.java"));
        ok(browser.contains("pipeline.pixels(prepared.renderMemoryBytes(item.restorationBytes),stopped,priority)")
            &&engine.contains("try(TranslationStages.Lease lease=stages.pixels(job.renderMemoryBytes(AutoTranslationQueue.estimateMemory(source.getWidth(),source.getHeight()),true),cancelled,()->0))"),"automatic and retained-source manual rendering both admit the computed repair memory budget with owned leases");
        ok(browser.contains("agent=web.getSettings().getUserAgentString(),document=documentId")&&browser.contains("pageCacheFile(pageUrl,document,url"),"manual cache writes retain originating document generation");
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.println("V073IntegrationChecks: "+count+" checks passed");
    }
}
