package cn.local.manga;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Real loopback HTTP through each release's own unmodified transport and scheduler. */
public final class V073SchedulerBenchmark {
    private static final BooleanSupplier RUNNING=()->false;
    private static final Method TRANSPORT, BIND;
    private static final int SERVICE_MS=200;
    static {
        try {
            TRANSPORT=ApiClient.class.getDeclaredMethod("requestStream",String.class,String.class,String.class,long.class,Class.forName("cn.local.manga.ApiClient$BodyWriter"),String.class,BooleanSupplier.class,int.class,AppSettings.class);
            TRANSPORT.setAccessible(true);
            Method bind=null;
            try { bind=TranslationStages.class.getDeclaredMethod("withRequests",Callable.class,BooleanSupplier.class,IntSupplier.class); bind.setAccessible(true); }
            catch(NoSuchMethodException oldVersion) { /* 0.7.2 binds the entire page via request. */ }
            BIND=bind;
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Object invoke(Method method,Object receiver,Object... args)throws Exception {
        try { return method.invoke(receiver,args); }
        catch(InvocationTargetException wrapped) { Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new AssertionError(cause); }
    }
    private static void http(String address,AppSettings settings)throws Exception {
        byte[] data=(byte[])invoke(TRANSPORT,null,address,"GET",null,-1L,null,null,RUNNING,2048,settings);
        if(!Arrays.equals(data,"ok".getBytes(StandardCharsets.UTF_8)))throw new AssertionError("Unexpected response");
    }
    private static void page(TranslationStages stages,Callable<Void> action,int priority)throws Exception {
        if(BIND==null)stages.request(action,RUNNING);
        else invoke(BIND,stages,action,RUNNING,(IntSupplier)()->priority);
    }
    private static void until(BooleanSupplier condition)throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()) {if(System.nanoTime()>limit)throw new AssertionError("Scenario setup timed out");Thread.sleep(1);}
    }
    private static int queued(TranslationStages stages)throws Exception {
        if(BIND==null) {Field f=TranslationStages.class.getDeclaredField("requests");f.setAccessible(true);return((Semaphore)f.get(stages)).getQueueLength();}
        Field f=TranslationStages.class.getDeclaredField("networkWaiters");f.setAccessible(true);synchronized(stages){return((List<?>)f.get(stages)).size();}
    }
    private static double ms(long nanos){return nanos/1_000_000.0;}
    private static JSONObject run(String version,int concurrency,int retrySeconds,int round,boolean warmup,int queuedBackgroundGroups)throws Exception {
        TranslationStages stages=new TranslationStages(concurrency,64,()->64);int backgroundPages=concurrency*(1+queuedBackgroundGroups),queuedBackgroundPages=concurrency*queuedBackgroundGroups;
        AppSettings options=new AppSettings();options.maxRetries=1;options.retryIntervalSeconds=retrySeconds;options.rateLimitWaitSeconds=1;options.requestTimeoutSeconds=15;
        ExecutorService workers=Executors.newCachedThreadPool(),handlers=Executors.newCachedThreadPool();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        CountDownLatch initialRequests=new CountDownLatch(concurrency),releaseInitial=new CountDownLatch(1);
        Map<String,AtomicInteger> attempts=new ConcurrentHashMap<>();AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),total=new AtomicInteger();
        AtomicLong visibleStarted=new AtomicLong(),visibleArrived=new AtomicLong();List<JSONObject> events=new CopyOnWriteArrayList<>();
        long groupStarted=System.nanoTime();
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();int attempt=attempts.computeIfAbsent(path,k->new AtomicInteger()).incrementAndGet();
            int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);total.incrementAndGet();long received=System.nanoTime();
            boolean visible=path.equals("/visible");if(visible)visibleStarted.compareAndSet(0,received);
            String[] parts=path.split("/");boolean background=!visible;int id=background?Integer.parseInt(parts[2]):-1;
            boolean first=background&&parts[3].equals("0")&&attempt==1;int status=first?502:200;
            events.add(new JSONObject().put("path",path).put("attempt",attempt).put("status",status).put("receivedSinceGroupMs",ms(received-groupStarted)));
            try {
                exchange.getRequestBody().readAllBytes();
                if(first&&id<concurrency){initialRequests.countDown();if(!releaseInitial.await(10,TimeUnit.SECONDS))throw new IOException("initial release timeout");}
                Thread.sleep(SERVICE_MS);byte[] body=(status==200?"ok":"{}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
            }catch(InterruptedException stop){Thread.currentThread().interrupt();}
            finally{active.decrementAndGet();exchange.close();}
        });
        server.start();String address="http://127.0.0.1:"+server.getAddress().getPort();List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<concurrency;i++){final int id=i;jobs.add(workers.submit(()->{page(stages,()->{for(int batch=0;batch<2;batch++)http(address+"/bg/"+id+"/"+batch,options);return null;},2);return null;}));}
            if(!initialRequests.await(10,TimeUnit.SECONDS))throw new AssertionError("Initial requests did not enter");
            // Every run has the same older queued pages before the newly visible page arrives.
            for(int i=concurrency;i<backgroundPages;i++){final int id=i;jobs.add(workers.submit(()->{page(stages,()->{for(int batch=0;batch<2;batch++)http(address+"/bg/"+id+"/"+batch,options);return null;},2);return null;}));}
            until(()->{try{return queued(stages)==queuedBackgroundPages;}catch(Exception e){throw new RuntimeException(e);}});
            Future<Long> current=workers.submit(()->{visibleArrived.set(System.nanoTime());page(stages,()->{http(address+"/visible",options);return null;},0);return System.nanoTime();});
            until(()->{try{return queued(stages)==queuedBackgroundPages+1;}catch(Exception e){throw new RuntimeException(e);}});
            long workloadReleased=System.nanoTime();releaseInitial.countDown();
            long visibleCompleted=current.get(30,TimeUnit.SECONDS);for(Future<?> job:jobs)job.get(30,TimeUnit.SECONDS);long allCompleted=System.nanoTime();
            int expected=3*backgroundPages+1;
            if(total.get()!=expected||peak.get()!=concurrency||stages.networkActive()!=0)throw new AssertionError("Unexpected load/concurrency/leaked permit: "+total+"/"+peak);
            return new JSONObject().put("version",version).put("round",round).put("warmup",warmup).put("concurrency",concurrency).put("retryIntervalSeconds",retrySeconds)
                .put("serverServiceMsPerAttempt",SERVICE_MS).put("backgroundPages",backgroundPages).put("queuedBackgroundGroups",queuedBackgroundGroups).put("batchesPerBackgroundPage",2).put("visiblePages",1).put("retriesPerBackgroundPage",1)
                .put("visibleRequestWaitMs",ms(visibleStarted.get()-visibleArrived.get())).put("visibleCompletedMs",ms(visibleCompleted-visibleArrived.get()))
                .put("allCompletedMs",ms(allCompleted-workloadReleased)).put("setupMs",ms(workloadReleased-groupStarted)).put("actualHttpPeak",peak.get()).put("httpAttempts",total.get())
                .put("sampledAt",java.time.Instant.now().toString()).put("events",new JSONArray(events));
        }finally{releaseInitial.countDown();server.stop(0);workers.shutdownNow();handlers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);handlers.awaitTermination(3,TimeUnit.SECONDS);}
    }
    public static void main(String[] args)throws Exception {
        String version=args[0];if(version.equals("0.7.2")!=(BIND==null))throw new AssertionError("Version/classpath mismatch");
        System.out.println("READY "+version);System.out.flush();
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            for(String line;(line=input.readLine())!=null;) {if(line.equals("quit"))break;String[] p=line.split(" ");
                JSONObject result=run(version,Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Boolean.parseBoolean(p[3]),p.length>4?Integer.parseInt(p[4]):1);
                System.out.println(result.toString());System.out.flush();
            }
        }
    }
}
