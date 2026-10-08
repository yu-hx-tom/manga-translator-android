package cn.local.manga;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.json.*;

/** Executes the extracted production classifier, real downloader and real queue using only localhost. */
public final class BrowserThrottleChecks {
    private static final JSONArray checks=new JSONArray();
    private static Method classifier;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.put(label);}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static int status(Throwable failure)throws Exception{return (Integer)classifier.invoke(null,failure);}
    private static void classification()throws Exception{
        check(status(new ApiClient.RequestFailure("HTTP 400 body mentions HTTP 429 and HTTP 503",false,0,400))==0,"API 400 is not throttled by misleading response text");
        check(status(new ApiClient.RequestFailure("ordinary message",true,0,429))==429,"API 429 comes from its actual status");
        check(status(new ApiClient.RequestFailure("message mentions HTTP 429",true,0,503))==503,"API 503 retains actual status despite conflicting text");
        check(status(new Exception("HTTP 429 HTTP 503"))==0,"ordinary exception text cannot trigger throttling");
        check(status(new BrowserImageLoader.HttpFailure(429,"ordinary message"))==429,"website 429 keeps typed throttling");
        check(status(new BrowserImageLoader.HttpFailure(503,"HTTP 429"))==503,"website 503 keeps its actual status");
        check(status(new BrowserImageLoader.HttpFailure(400,"HTTP 429 HTTP 503"))==0,"website 400 is not throttled by its message");
        check(status(new ApiClient.RequestFailure("HTTP 429",true,0,500))==0,"other retryable HTTP errors are not classified as 429 or 503");
        check(status(new CancellationException("HTTP 429"))==0,"cancellation is not a throttle");
        check(status(new OutOfMemoryError("HTTP 503"))==0,"memory pressure remains distinct from HTTP throttling");
        check(status(null)==0,"missing failure has no throttle status");
    }
    private static int downloads(Path out)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();
        for(int code:new int[]{400,401,403,429,503})server.createContext("/"+code,exchange->{
            calls.incrementAndGet();byte[] body="response text: HTTP 429 HTTP 503".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();Context context=new Context(out.resolve("http").toFile());
        try{
            for(int code:new int[]{400,401,403,429,503}){
                try{BrowserImageLoader.load(context,origin+"/"+code,origin+"/reader","local-throttle-regression",()->false);throw new AssertionError("Downloader accepted HTTP "+code);}
                catch(BrowserImageLoader.HttpFailure failure){
                    check(failure.status==code,"real localhost downloader preserves HTTP "+code);
                    check(status(failure)==(code==429||code==503?code:0),"downloaded HTTP "+code+" flows through the production browser classifier");
                }
            }
            check(calls.get()==5,"each localhost error makes exactly one downloader request");
            try{BrowserImageLoader.load(context,origin+"/429",origin+"/reader","local-throttle-regression",()->true);throw new AssertionError("Cancelled downloader request was accepted");}
            catch(CancellationException expected){check(calls.get()==5,"cancellation stops before issuing a downloader request");}
            return calls.get();
        }finally{server.stop(0);}
    }
    private static void backoff(long expectedDelay,boolean throttled,Long custom,String label){
        AutoTranslationQueue queue=new AutoTranslationQueue();queue.configureTextTarget(8);queue.resume(1000);
        List<AutoTranslationQueue.Image> images=new ArrayList<>();for(int i=0;i<8;i++)images.add(new AutoTranslationQueue.Image("i"+i,"https://fixture.invalid/"+i,false,false,i));queue.scan(images);
        if(custom==null)queue.backoff(1000,throttled);else queue.backoff(1000,throttled,custom);
        check(queue.take(1000+expectedDelay-1,1024L*1024*1024,2L*1024*1024*1024).isEmpty(),label+" blocks work until its deadline");
        check(queue.take(1000+expectedDelay,1024L*1024*1024,2L*1024*1024*1024).size()==4,label+" resumes at the deadline with concurrency halved");
    }
    private static void queueRules()throws Exception{
        backoff(5000,false,null,"ordinary website failure");
        backoff(30000,status(new BrowserImageLoader.HttpFailure(429,""))!=0,null,"website 429");
        backoff(30000,status(new BrowserImageLoader.HttpFailure(503,""))!=0,null,"website 503");
        for(int code:new int[]{400,429,503}){
            int actual=status(new ApiClient.RequestFailure("HTTP 429",true,0,code));
            long delay=ApiClient.retryWaitMillis(2,30,actual,0),expected=code==429?30000:2000;
            check(delay==expected,"API "+code+" uses the configured interval or actual 429 wait");
            backoff(expected,actual!=0,delay,"API "+code);
        }
    }
    public static void main(String[] args)throws Exception{
        Path project=Path.of(args[0]),classes=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        Path src=project.resolve("app/src/main/java/cn/local/manga"),browser=src.resolve("BrowserActivity.java");
        String source=Files.readString(browser).replace("\r\n","\n"),method=FeatherCacheKeyChecks.method(source,"private static int throttleStatus(");
        Path extracted=out.resolve("ActualBrowserThrottle.java");Files.writeString(extracted,"package cn.local.manga; public final class ActualBrowserThrottle {\n"+method.replace("private static int","public static int")+"\n}");
        int compiled=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",classes.toString(),extracted.toString());
        if(compiled!=0)throw new AssertionError("Actual browser helper compilation failed");
        classifier=Class.forName("cn.local.manga.ActualBrowserThrottle").getMethod("throttleStatus",Throwable.class);
        classification();int localCalls=downloads(out);queueRules();
        String auto=FeatherCacheKeyChecks.method(source,"private void translateAuto(");
        check(!auto.contains("contains(\"HTTP 429\")")&&!auto.contains("contains(\"HTTP 503\")"),"browser auto path no longer infers HTTP status from error prose");
        check(auto.indexOf("if(output.throttleFailure!=null)")>=0&&auto.indexOf("if(output.throttleFailure!=null)")<auto.indexOf("if(output.succeeded==0&&output.failed>0)"),"result throttle state is retained before an empty-output summary exception");
        JSONObject hashes=new JSONObject();for(String name:new String[]{"BrowserActivity","BrowserImageLoader","ApiClient","AutoTranslationQueue","TranslationEngine","CacheFiles","SourceImageCache"})hashes.put(name+".java",hash(src.resolve(name+".java")));
        JSONObject report=new JSONObject().put("passed",true).put("checksPassed",checks.length()).put("checks",checks).put("sourceSha256",hashes).put("localhostHttpRequests",localCalls)
            .put("externalNetworkCalls",0).put("paidApiCalls",0).put("androidRuntimeVerified",false)
            .put("scope","Compiled exact production BrowserActivity.throttleStatus; current BrowserImageLoader and AutoTranslationQueue; real loopback HTTP errors with host-only Context/CookieManager adapters. No image decoding or Android UI claims.");
        Files.writeString(out.resolve("验证结果.json"),report.toString(2));System.out.println("BrowserThrottleChecks: "+checks.length()+" checks passed, "+localCalls+" localhost requests, no external API.");
    }
}
