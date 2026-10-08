package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.json.*;

/** Executes production refactored cache/pipeline code with filesystem/pixel host adapters.
 * No Android codec, WebView, inference, or API-success claim is made by these checks. */
public final class RefactorHostRuntimeChecks {
    static final JSONArray checks = new JSONArray();
    static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks.put(label); }
    static AppSettings settings() {
        AppSettings s = new AppSettings(); s.baseUrl="http://example.invalid/v1"; s.apiKey="test-placeholder";
        s.mode="text"; s.textModel="test-text"; s.imageModel="test-image"; return s;
    }
    static JSONArray legacyFields(AppSettings s) {
        return new JSONArray().put(s.baseUrl).put(s.apiKey).put(s.mode).put(s.textModel).put(s.imageModel)
            .put(s.textPrompt).put(s.imagePrompt).put(s.reasoningEffort).put(s.detectorModel);
    }
    static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}}
    private static void run(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        Context context = new Context(root.toFile()); AppSettings s=settings();
        String legacy=legacyFields(s).toString();
        require(!legacy.equals(s.renderFingerprint()), "1.1.6 separates configurable-color output from legacy red output");
        String content="0123456789abcdef".repeat(4);
        String oldKey=RenderedPageCache.key(content,legacyFields(s).put(DetectorModels.get(s.detectorModel).sha256)+"\npage-v0.9.4-dedup");
        require(!oldKey.equals(PagePipeline.contentKey(content,s)), "legacy red completed pages cannot satisfy new color settings");
        for(String field: List.of("baseUrl","apiKey","mode","textModel","imageModel","textPrompt","imagePrompt","reasoningEffort","detectorModel")) {
            AppSettings changed=settings(); AppSettings.class.getField(field).set(changed,"changed");
            require(!s.renderFingerprint().equals(changed.renderFingerprint()), "output field included: "+field);
        }
        AppSettings tuned=settings(); tuned.maxRetries=7; tuned.textConcurrency=3; tuned.requestTimeoutSeconds=99; tuned.serviceTier="priority";
        require(s.renderFingerprint().equals(tuned.renderFingerprint()), "request scheduling fields preserve output cache");
        PageCacheStore store=new PageCacheStore(context.getCacheDir());
        JSONArray oldFileFields=new JSONArray().put("browser-page-v0.9.4-dedup").put("page").put("doc").put("image").put(100).put(200);
        for(Object value:legacyFields(s)) oldFileFields.put(value);
        File file=store.file("page","doc","image",100,200,s);
        require(!file.getName().equals(sha(oldFileFields.toString())+".png"), "per-reading filename excludes legacy red output");
        ByteArrayOutputStream encoded=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",encoded);
        byte[] png=encoded.toByteArray();
        PageOutcome partial=new PageOutcome(3,2,1,0,0,"partial",TranslationTranscript.unavailable());
        store.reserve(file); try {store.save(file,png,partial);} finally {store.release(file);}
        require(Arrays.equals(png,store.read(file,false)),"cache PNG byte roundtrip");
        PageOutcome restored=store.readOutcome(file);
        require(restored.known && restored.detected==3 && restored.succeeded==2 && restored.failed==1,"outcome companion roundtrip");
        long oldTime=System.currentTimeMillis()-60_000; file.setLastModified(oldTime); long savedTime=file.lastModified();
        store.read(file,false); require(file.lastModified()==savedTime,"recovery read does not touch LRU timestamp");
        store.read(file,true); require(file.lastModified()>savedTime,"automatic read touches LRU timestamp");
        require(store.read(new File(file+".missing"),false)==null,"missing cache returns miss");
        Files.writeString(Path.of(file+".outcome"),"not JSON");
        require(!store.readOutcome(file).known,"corrupt outcome returns unknown");
        Files.write(file.toPath(),new byte[0]); require(store.read(file,false)==null,"empty PNG rejected");
        try(RandomAccessFile large=new RandomAccessFile(file,"rw")){large.setLength(PageCacheStore.MAX_PNG_BYTES+1L);}
        require(store.read(file,false)==null,"oversized PNG rejected");
        Files.delete(file.toPath());
        Bitmap pixels=new Bitmap(2,2,new int[]{0xff112233,0xff445566,0xff778899,0xffaabbcc});
        String pageKey=PagePipeline.contentKey(DetectionCache.contentHash(pixels,()->false),s);
        PageOutcome complete=new PageOutcome(1,1,0,0,0,"complete",TranslationTranscript.unavailable());
        new RenderedPageCache(context).write(pageKey,png,complete);
        long started=System.nanoTime();
        PagePipeline.Page cached=PagePipeline.run(context,null,pixels,s,true,false,()->false,(message,a,b)->{throw new AssertionError("cache hit invoked detection progress");},"none");
        double cacheMs=(System.nanoTime()-started)/1e6;
        require(cached.fromCache() && Arrays.equals(cached.cachedPng,png),"actual PagePipeline.run restores finished page before engine access");
        require(cached.succeeded==1 && !cached.rendered(),"cached result keeps success statistics and ownership flags");
        cached.close(); require(!pixels.isRecycled(),"closing Page does not recycle caller-owned source");
        try { PagePipeline.run(context,null,pixels,s,true,false,()->true,(message,a,b)->{},"none"); throw new AssertionError("cancel ignored"); }
        catch(CancellationException expected){checks.put("cancelled pipeline exits before using cached page");}
        PagePipeline.Page failed=new PagePipeline.Page(); failed.key=pageKey; failed.outcome=partial;
        PagePipeline.remember(context,failed,png);
        require(new RenderedPageCache(context).read(pageKey)==null,"incomplete result is not persisted as completed");
        PagePipeline.Page owned=new PagePipeline.Page(); owned.image=new Bitmap(1,1,new int[]{0}); Bitmap allocation=owned.image;
        owned.close(); owned.close(); require(allocation.isRecycled() && owned.image==null,"owned output recycled exactly once and close is idempotent");
        TranslationEngine.Result result=new TranslationEngine.Result(); result.regions=new ArrayList<>(Collections.nCopies(4,(Region)null));
        result.succeeded=3;result.failed=1;result.summary="summary";result.traceId="trace-test";
        PageOutcome extracted=PagePipeline.outcomeOf(result);
        require(extracted.detected==4 && extracted.failed==1 && extracted.detail.endsWith("trace-test"),"outcome keeps counts and trace");
        require(!PagePipeline.noText(s,"none").incomplete(),"zero-text page remains complete");
        require(PagePipeline.throttleStatus(new BrowserImageLoader.HttpFailure(429,"limited"))==429,"HTTP 429 propagated");
        require(PagePipeline.throttleStatus(new BrowserImageLoader.HttpFailure(503,"unavailable"))==503,"HTTP 503 propagated");
        require(PagePipeline.throttleStatus(new BrowserImageLoader.HttpFailure(500,"error"))==0,"HTTP 500 is not adaptive throttling");
        JSONObject report=new JSONObject().put("passed",true).put("checks",checks.length()).put("labels",checks).put("cacheRestoreHostMs",cacheMs)
            .put("androidRuntimeVerified",false).put("realTranslationCalls",0).put("scope","Actual PagePipeline cached path and helpers, PageCacheStore, AppSettings; Java filesystem and pixel adapters only.");
        Files.writeString(root.resolve("重构运行时检查.json"),report.toString(2));
        System.out.println(checks.length()+" production refactor host checks passed");
    }
}
