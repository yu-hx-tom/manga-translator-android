package cn.local.manga;

import android.content.Context;
import android.graphics.Rect;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises production request orchestration, completion caches and admission; no Android pixel rendering. */
public final class ProductionCleanupFlowChecks {
    static int checks;
    static final long MIB=1024L*1024;
    static void ok(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static JSONObject translation(boolean skip)throws Exception{return new JSONObject().put("id","one").put("originalText","原文").put("zh",skip?"":"完整中文译文").put("skip",skip);}
    static byte[] png()throws Exception{
        BufferedImage image=new BufferedImage(16,24,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    static TranslationEngine.PreparedText job(Path root,AppSettings settings,byte[] png,JSONObject value)throws Exception{
        Path directory=Files.createTempDirectory(root,"job-");Region region=new Region("one",new Rect(4,4,12,20),List.of(),true);
        TranslationEngine.PreparedText job=new TranslationEngine.PreparedText(directory.toFile(),List.of(region),List.of(region));job.settings=settings;job.pagePixels=16*24;
        if(value!=null)job.values.put(region.id,value);
        File input=directory.resolve("input.png").toFile(),result=directory.resolve("result.image").toFile(),cached=directory.resolve("cached.image").toFile();Files.write(input.toPath(),png);
        Class<?> type=Class.forName("cn.local.manga.TranslationEngine$RepairInput");
        Constructor<?> constructor=type.getDeclaredConstructor(Region.class,Rect.class,int[][].class,int[][].class,File.class,File.class,File.class);constructor.setAccessible(true);
        Object repair=constructor.newInstance(region,new Rect(0,0,16,24),new int[][]{{4,4,12,20}},new int[][]{{0,0,2,24}},input,result,cached);
        Field repairs=TranslationEngine.PreparedText.class.getDeclaredField("repairs");repairs.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)repairs.get(job);map.put(region.id,repair);
        return job;
    }
    static Path result(TranslationEngine.PreparedText job){return job.directory.toPath().resolve("result.image");}
    static void cancelled(Future<?> future,String reason)throws Exception{
        try{future.get(3,TimeUnit.SECONDS);throw new AssertionError(reason);}catch(ExecutionException e){ok(e.getCause() instanceof CancellationException,reason);}
    }
    static void flow(Path root)throws Exception{
        byte[] image=png(),reply=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",Base64.getEncoder().encodeToString(image)))).toString().getBytes(StandardCharsets.UTF_8);
        AtomicInteger mode=new AtomicInteger(),requests=new AtomicInteger();AtomicReference<Throwable> handlerError=new AtomicReference<>();
        AtomicReference<CountDownLatch> delayedStarted=new AtomicReference<>(),delayedRelease=new AtomicReference<>();List<String> paths=new CopyOnWriteArrayList<>();
        ExecutorService handlers=Executors.newCachedThreadPool(),worker=Executors.newSingleThreadExecutor();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/",exchange->{try{
            paths.add(exchange.getRequestURI().getPath());exchange.getRequestBody().readAllBytes();requests.incrementAndGet();int state=mode.get();
            if(state==3){delayedStarted.get().countDown();delayedRelease.get().await(5,TimeUnit.SECONDS);}
            int status=state==1?408:state==2?429:200;byte[] body=status==200?reply:"{\"error\":{\"message\":\"controlled failure\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
        }catch(IOException expectedDisconnect){/* Live cancellation closes the loopback connection. */}
        catch(Throwable e){handlerError.compareAndSet(null,e);}finally{exchange.close();}});server.start();
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";settings.apiKey="local-cleanup-flow-fixture";settings.imageModel="fixture-image";settings.maxRetries=0;settings.retryIntervalSeconds=1;settings.rateLimitWaitSeconds=1;
        try(TranslationEngine engine=new TranslationEngine(new Context(root.resolve("app").toFile()))){
            try(TranslationEngine.PreparedText job=job(root,settings,image,null)){
                engine.requestTextPage(job,settings,()->false);ok(requests.get()==0,"no valid translation does not request image cleanup");ok(!Files.exists(result(job))&&job.values.isEmpty(),"missing translation cannot become cleanup success");
            }
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(true))){
                engine.requestTextPage(job,settings,()->false);ok(requests.get()==0,"model skip does not request image cleanup");ok(!Files.exists(result(job))&&job.repaired.isEmpty(),"skip is not recorded as a repair");
            }
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                Files.write(result(job),image);engine.requestTextPage(job,settings,()->false);ok(requests.get()==0,"existing prepared cleanup result is reused without billing");ok(Arrays.equals(Files.readAllBytes(result(job)),image),"reuse preserves the exact prepared response");ok(job.repaired.isEmpty(),"reused response awaits pixel validation before repair success");
            }
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                JSONObject value=job.values.get("one");engine.requestTextPage(job,settings,()->false);ok(requests.get()==1,"valid translation invokes actual image endpoint exactly once");ok(paths.equals(List.of("/v1/images/edits")),"cleanup never sends a second translation or OCR request");
                ok(Arrays.equals(Files.readAllBytes(result(job)),image),"production engine saves returned image for pixel phase");ok(job.values.get("one")==value&&value.getString("zh").equals("完整中文译文"),"cleanup retains original valid translation object");
                ok(job.repaired.isEmpty()&&!Files.exists(job.directory.toPath().resolve("cached.image")),"network success neither marks rendered success nor persists unvalidated cleanup cache");
            }
            mode.set(1);int before=requests.get();
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                JSONObject value=job.values.get("one");engine.requestTextPage(job,settings,()->false);ok(requests.get()==before+1,"maxRetries zero makes one request on retryable HTTP 408");
                ok(job.values.get("one")==value&&!job.failures.containsKey("one"),"image failure does not lose or invalidate translated text");ok(job.cleanupFailed.contains("one")&&!Files.exists(result(job))&&job.repaired.isEmpty(),"failed image response remains failed with no image artifact");ok(!job.errors.isEmpty(),"cleanup failure has visible diagnostic evidence");
            }
            mode.set(2);before=requests.get();
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                engine.requestTextPage(job,settings,()->false);ok(requests.get()==before+1&&job.throttleFailure!=null,"throttled cleanup exposes the existing scheduler failure signal");ok(job.cleanupFailed.contains("one")&&job.values.containsKey("one"),"throttle keeps valid text while marking cleanup incomplete");
            }
            mode.set(0);before=requests.get();
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                boolean stopped=false;try{engine.requestTextPage(job,settings,()->true);}catch(CancellationException expected){stopped=true;}
                ok(stopped&&requests.get()==before,"pre-cancelled job makes no cleanup connection");ok(job.values.containsKey("one")&&job.repaired.isEmpty()&&!Files.exists(result(job)),"pre-cancel preserves text without manufacturing cleanup success");
            }
            mode.set(3);before=requests.get();delayedStarted.set(new CountDownLatch(1));delayedRelease.set(new CountDownLatch(1));AtomicBoolean stop=new AtomicBoolean();
            try(TranslationEngine.PreparedText job=job(root,settings,image,translation(false))){
                Future<?> call=worker.submit(()->{engine.requestTextPage(job,settings,stop::get);return null;});
                try{ok(delayedStarted.get().await(2,TimeUnit.SECONDS),"live request reached controlled endpoint");stop.set(true);cancelled(call,"live engine cancellation propagates as cancellation");}
                finally{stop.set(true);delayedRelease.get().countDown();}
                ok(requests.get()==before+1,"cancelled request is not automatically repeated");ok(job.values.containsKey("one")&&job.repaired.isEmpty()&&!Files.exists(result(job)),"live cancellation retains translation and creates no successful cleanup file");
                ok(!job.cleanupFailed.contains("one")&&job.errors.isEmpty(),"cancellation is not swallowed as ordinary cleanup failure");
            }
            ok(handlerError.get()==null,"loopback fixture completed without unexpected handler failures");
        }finally{CountDownLatch release=delayedRelease.get();if(release!=null)release.countDown();server.stop(0);handlers.shutdownNow();worker.shutdownNow();}
    }
    static void completion(Path root)throws Exception{
        PageOutcome partial=new PageOutcome(1,1,0,0,1,"已原位红字兜底",TranslationTranscript.unavailable(),true),restored=PageOutcome.decode(partial.encode());
        ok(partial.incomplete()&&restored.known&&restored.incomplete()&&restored.needsCleanupRetry,"cleanup retry flag survives PageOutcome roundtrip and remains incomplete");
        ok(restored.succeeded==1&&restored.failed==0&&restored.preservedOriginal==1,"cleanup retry does not misreport a valid translated paragraph as translation failure");
        PageOutcome complete=new PageOutcome(1,1,0,0,0,"修图已完成",TranslationTranscript.unavailable(),false);
        ok(!PageOutcome.decode(complete.encode()).incomplete(),"fully rendered page remains complete");
        RenderedPageCache cache=new RenderedPageCache(root.toFile());String key=RenderedPageCache.key("same-image","same-settings");byte[] image=png();
        cache.write(key,image,complete);ok(cache.read(key)!=null,"complete page establishes real rendered cache hit");
        cache.write(key,image,restored);ok(cache.read(key)==null,"cleanup-incomplete attempt invalidates a previously complete rendered cache entry");
        String fresh=RenderedPageCache.key("new-image","same-settings");cache.write(fresh,image,partial);ok(cache.read(fresh)==null,"fallback-only output cannot be cached as a completed page");
    }
    static void admission()throws Exception{
        TranslationStages stages=new TranslationStages(2,256*MIB,()->256*MIB);ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered=new CountDownLatch(1),release=new CountDownLatch(1),secondEntered=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();AtomicLong firstBudget=new AtomicLong(),secondBudget=new AtomicLong();
        try{
            Future<Integer> first=workers.submit(()->stages.withRequests(()->TranslationStages.withImageRequest(()->{firstBudget.set(stages.reservedBytes());peak.accumulateAndGet(active.incrementAndGet(),Math::max);firstEntered.countDown();try{release.await();return 1;}finally{active.decrementAndGet();}},()->false),()->false,()->2));
            ok(firstEntered.await(2,TimeUnit.SECONDS),"first image request enters admitted action");ok(firstBudget.get()==96*MIB&&stages.reservedBytes()==96*MIB,"image envelope owns exactly 96 MiB inside the shared pixel budget");
            Future<Integer> second=workers.submit(()->stages.withRequests(()->TranslationStages.withImageRequest(()->{secondBudget.set(stages.reservedBytes());peak.accumulateAndGet(active.incrementAndGet(),Math::max);secondEntered.countDown();active.decrementAndGet();return 2;},()->false),()->false,()->2));
            ok(!secondEntered.await(180,TimeUnit.MILLISECONDS),"second image request waits while first is active");release.countDown();ok(first.get(2,TimeUnit.SECONDS)==1&&second.get(2,TimeUnit.SECONDS)==2&&peak.get()==1,"image actions execute serially and preserve their results");
            ok(secondBudget.get()==96*MIB&&stages.reservedBytes()==0,"second request receives its own budget and all bytes are released");
            boolean failed=false;try{stages.withRequests(()->TranslationStages.withImageRequest(()->{throw new IOException("controlled image failure");},()->false),()->false,()->2);}catch(IOException expected){failed=true;}
            ok(failed&&stages.reservedBytes()==0,"exception propagates and releases image pixel reservation");
            AtomicBoolean ran=new AtomicBoolean();boolean stopped=false;try{TranslationStages.withImageRequest(()->{ran.set(true);return null;},()->true);}catch(CancellationException expected){stopped=true;}
            ok(stopped&&!ran.get()&&stages.reservedBytes()==0,"pre-cancel never enters action or retains budget");
            TranslationStages tooSmall=new TranslationStages(1,95*MIB,()->128*MIB);failed=false;
            try{tooSmall.withRequests(()->TranslationStages.withImageRequest(()->{ran.set(true);return null;},()->false),()->false,()->2);}catch(Exception expected){failed=true;}
            ok(failed&&!ran.get()&&stages.reservedBytes()==0,"96 MiB admission cannot bypass configured pixel maximum");
            AtomicBoolean stop=new AtomicBoolean();CountDownLatch waiting=new CountDownLatch(1);TranslationStages constrained=new TranslationStages(2,128*MIB,()->128*MIB);TranslationStages.Lease occupied=constrained.pixels(40*MIB,()->false);
            try{
                Future<?> blocked=workers.submit(()->constrained.withRequests(()->{waiting.countDown();return TranslationStages.withImageRequest(()->{ran.set(true);return null;},stop::get);},()->false,()->2));
                ok(waiting.await(2,TimeUnit.SECONDS),"image request starts waiting for available budget");
                boolean awaiting=false;try{blocked.get(180,TimeUnit.MILLISECONDS);}catch(TimeoutException expected){awaiting=true;}
                ok(awaiting&&!ran.get(),"image cannot run while existing reservation leaves less than 96 MiB");stop.set(true);cancelled(blocked,"cancellation while awaiting pixel budget propagates");ok(!ran.get()&&stages.reservedBytes()==40*MIB,"cancelled waiter does not release another action's reservation");
            }finally{occupied.close();}
            ok(stages.reservedBytes()==0,"preexisting pixel lease also releases cleanly");
            ok(workers.submit(()->stages.withRequests(()->TranslationStages.withImageRequest(()->7,()->false),()->false,()->2)).get(2,TimeUnit.SECONDS)==7,"semaphore and budget remain usable after exceptions and cancellation");
            ok(stages.reservedBytes()==0,"final image action leaves no global reservation");
        }finally{release.countDown();workers.shutdownNow();}
    }
    static void memory(Path root)throws Exception{
        ok(TranslationEngine.cleanupDecodeSample(4000,4000,2000,2000)==2,"16 MP result decodes to at most the 4 MP ROI before scaling");
        ok(TranslationEngine.cleanupDecodeSample(4001,3999,2000,2000)==4,"odd sampled dimensions use ceiling and cannot exceed ROI allocation bound");
        ok(TranslationEngine.cleanupDecodeSample(6000,1,1,1)==8192,"extremely narrow result has bounded nonzero decoded dimensions");
        ok(TranslationEngine.cleanupDecodeSample(16,24,16,24)==1,"same-size response preserves full source resolution");
        ok(TranslationEngine.cleanupRecoveryDecodeSample(948,1659,154,781,4)==2,"padded tall return can recover useful strip resolution within four ROI areas");
        ok(TranslationEngine.cleanupRecoveryDecodeSample(948,1659,154,781,2)==0,"recovery refuses a second resolution step above the bounded allocation");
        ok(TranslationEngine.cleanupRecoveryDecodeSample(4000,4000,2000,2000,2)==0,"equal-aspect returns do not trigger padding decode recovery");
        ok(TranslationEngine.cleanupRecoveryDecodeSample(16,24,16,24,1)==0,"full-resolution decode cannot be retried finer");
        int[][] outputs={{1,1},{3,5},{17,31},{1024,1536},{4001,3999},{6000,2666},{2666,6000},{6000,1},{1,6000}};
        int[][] targets={{1,1},{7,9},{52,68},{144,226},{230,353},{2000,2000},{4000,1000}};
        for(int[] output:outputs)for(int[] target:targets){
            int sample=TranslationEngine.cleanupDecodeSample(output[0],output[1],target[0],target[1]);long decoded=((output[0]+(long)sample-1)/sample)*((output[1]+(long)sample-1)/sample),limit=(long)target[0]*target[1];
            ok(sample>0&&(sample&(sample-1))==0&&decoded<=limit,"sample is codec-compatible and upper-rounded result fits ROI allocation");
            if(sample>1){int finer=sample/2;ok(((output[0]+(long)finer-1)/finer)*((output[1]+(long)finer-1)/finer)>limit,"chosen sample retains the finest resolution allowed by ROI budget");}
        }
        long page=4_000_000,roi=4_000_000,baseline=AutoTranslationQueue.estimateMemory(2000,2000);
        long automatic=TranslationEngine.cleanupRenderMemoryBytes(baseline,page,roi,false),manual=TranslationEngine.cleanupRenderMemoryBytes(baseline,page,roi,true);
        ok(automatic>baseline&&automatic>=16*MIB+page*4+roi*60,"repair budget also covers the bounded finer padding decode and recovery arrays");
        ok(manual==automatic+page*4,"manual repair additionally reserves the retained original page bitmap");
        ok(TranslationEngine.cleanupRenderMemoryBytes(baseline,page,0,false)==baseline&&TranslationEngine.cleanupRenderMemoryBytes(baseline,page,0,true)==baseline,"pages without an available cleanup image retain established memory admission");
        ok(TranslationEngine.cleanupRenderMemoryBytes(512*MIB,page,roi,false)==512*MIB,"repair budget cannot reduce an already larger page budget");
        try(TranslationEngine.PreparedText job=job(root,new AppSettings(),png(),translation(false))){
            ok(job.renderMemoryBytes(0)==0,"failed or missing cleanup response does not reserve unused repair buffers");
            Files.write(result(job),png());ok(job.renderMemoryBytes(0)==16*MIB+384*68,"prepared job budgets exact saved page and bounded response recovery dimensions");
            ok(job.renderMemoryBytes(0,true)==16*MIB+384*72,"prepared manual job includes retained source page allocation");
        }
        TranslationStages stages=new TranslationStages(1,512*MIB,()->512*MIB);boolean failed=false;
        try(TranslationStages.Lease lease=stages.pixels(automatic,()->false)){
            ok(stages.reservedBytes()==automatic,"computed repair budget is actually admitted by shared pixel stages");throw new IOException("controlled render failure");
        }catch(IOException expected){failed=true;}
        ok(failed&&stages.reservedBytes()==0,"render failure releases the entire computed repair budget");
        boolean stopped=false;try(TranslationStages.Lease lease=stages.pixels(manual,()->false)){throw new CancellationException("controlled render cancel");}catch(CancellationException expected){stopped=true;}
        ok(stopped&&stages.reservedBytes()==0,"render cancellation releases retained-source repair budget");
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);flow(root);completion(root.resolve("page-cache"));admission();memory(root);
        System.out.println("ProductionCleanupFlowChecks: "+checks+" checks passed (real engine/local HTTP/cache/admission; no paid API or Android pixel rendering)");
    }
}
