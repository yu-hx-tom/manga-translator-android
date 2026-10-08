package cn.local.manga;
import android.graphics.Rect;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
/** Exercises the actual engine session/refcount/cache implementation with only detector construction replaced. */
public final class RtDetrSessionChecks {
    static int checks;static void ok(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static TranslationEngine engine()throws Exception{Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(TranslationEngine)((sun.misc.Unsafe)f.get(null)).allocateInstance(TranslationEngine.class);}
    static AppSettings settings(String id){AppSettings s=new AppSettings();s.detectorModel=id;return s;}
    static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    public static void main(String[] args)throws Exception{
        String[] ids=DetectorModels.IDS;TranslationEngine a=engine(),b=engine();
        a.detect(null,settings(ids[0]));b.detect(null,settings(ids[0]));ok(RtDetrDetector.created==1&&RtDetrDetector.live==1,"same model shares one session");
        b.detect(null,settings(ids[1]));ok(RtDetrDetector.live==1&&RtDetrDetector.peak==1,"switch closes old model before allocating new");
        ok(RtDetrDetector.events.equals(Arrays.asList("new:"+ids[0],"close:"+ids[0],"new:"+ids[1])),"exact close-before-new switch order");
        a.close();ok(RtDetrDetector.live==1,"old engine close cannot close newer model used by another engine");a.close();ok(RtDetrDetector.live==1,"engine close is idempotent");
        b.detect(null,settings(ids[1]));b.close();ok(RtDetrDetector.live==0,"last user closes current session");
        try{b.detect(null,settings(ids[0]));throw new AssertionError("closed engine ran");}catch(CancellationException expected){checks++;}
        TranslationEngine first=engine(),second=engine();first.detect(null,settings(ids[0]));RtDetrDetector.failId=ids[2];
        try{second.detect(null,settings(ids[2]));throw new AssertionError("constructor should fail");}catch(Exception expected){ok(expected.getMessage().equals("controlled constructor failure"),"constructor failure propagates without silent fallback");}
        ok(RtDetrDetector.live==0,"failed replacement does not resurrect old model");RtDetrDetector.failId="";second.detect(null,settings(ids[2]));first.close();ok(RtDetrDetector.live==1,"failure recovery preserves correct client refcount");second.close();ok(RtDetrDetector.live==0,"recovered final client releases session");
        TranslationEngine interrupted=engine();int before=RtDetrDetector.created;Thread.currentThread().interrupt();try{interrupted.detect(null,settings(ids[0]));throw new AssertionError("cancel");}catch(CancellationException expected){checks++;}finally{Thread.interrupted();interrupted.close();}ok(before==RtDetrDetector.created,"cancellation before detection allocates no model");
        ExecutorService pool=Executors.newFixedThreadPool(3);List<Future<?>> tasks=new ArrayList<>();for(String id:ids)tasks.add(pool.submit(()->{try(TranslationEngine e=engine()){for(int i=0;i<5;i++)e.detect(null,settings(id));}catch(Exception ex){throw new RuntimeException(ex);}}));for(Future<?> task:tasks)task.get();pool.shutdown();
        ok(RtDetrDetector.peak==1&&RtDetrDetector.peakCalls==1&&RtDetrDetector.live==0,"concurrent engines serialize model ownership and release all sessions");
        TranslationEngine identityEngine=engine();Method identity=TranslationEngine.class.getDeclaredMethod("identityData",String.class,Region.class,AppSettings.class);identity.setAccessible(true);Region region=new Region("test",new Rect(0,0,10,20),Collections.singletonList(new Rect(0,0,10,20)),true);AppSettings s=settings(ids[0]);s.apiKey="host-test-secret";
        String key=(String)identity.invoke(identityEngine,"fixed-crop",region,s);ok(key.matches("[0-9a-f]{64}"),"cache identity contains digest only");ok(key.equals(identity.invoke(identityEngine,"fixed-crop",region,s)),"identical inputs have stable identity");
        s.detectorModel=ids[1];ok(!key.equals(identity.invoke(identityEngine,"fixed-crop",region,s)),"different detector model cannot share cached crop translations");s.detectorModel=ids[0];s.apiKey="another-account";ok(!key.equals(identity.invoke(identityEngine,"fixed-crop",region,s)),"different accounts remain cache isolated");identityEngine.close();
        JSONObject hashes=new JSONObject();Path source=Paths.get(args[1]);for(String name:new String[]{"TranslationEngine","Region","RtDetrRegions","WhiteBubbleCleaner"})hashes.put(name,hash(source.resolve(name+".java")));
        JSONObject report=new JSONObject().put("passed",true).put("checks",checks).put("sourceSha256",hashes).put("peakLiveSessions",RtDetrDetector.peak).put("peakConcurrentDetectionCalls",RtDetrDetector.peakCalls).put("paidApiUsed",false).put("androidRuntimeVerified",false).put("scope","Windows JVM executes production TranslationEngine session lock/refcount/cache identity; host-only fake detector and bypassed Context constructor; ONNX model inference and Android lifecycle not executed");
        Path out=Paths.get(args[0]);Files.createDirectories(out);Files.writeString(out.resolve("结果.json"),report.toString(2));System.out.println("RtDetrSessionChecks: "+checks+" checks passed");
    }
}
