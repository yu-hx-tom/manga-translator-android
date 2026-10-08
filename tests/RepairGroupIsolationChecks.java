package cn.local.manga;

import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Current production retries against isolated loopback replies; no paid or external API. */
public final class RepairGroupIsolationChecks {
    static int checks;
    static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void scenario(Path root,int failedStatus,boolean failSecondGroup,boolean cancel)throws Exception{
        List<Region> regions=new ArrayList<>();for(String id:List.of("a","b","c","d","e","f"))regions.add(PartialRetryChecks.region(id));
        AtomicInteger calls=new AtomicInteger();List<List<String>> sent=Collections.synchronizedList(new ArrayList<>());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{
            JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            JSONArray content=body.getJSONArray("messages").getJSONObject(0).getJSONArray("content");List<String> ids=new ArrayList<>();
            for(int i=0;i<content.length();i++){String label=content.getJSONObject(i).optString("text");if(label.startsWith("裁切 id="))ids.add(label.substring("裁切 id=".length()));}
            sent.add(ids);int call=calls.incrementAndGet();boolean initial=call==1;
            int status=!initial&&ids.contains(failSecondGroup?"d":"b")?failedStatus:200;
            JSONArray translations=new JSONArray().put(PartialRetryChecks.item("a","不得覆盖已成功段"));
            for(String id:ids)translations.put(PartialRetryChecks.item(id,"补回"+id));
            String text=initial?"{\"translations\":["+PartialRetryChecks.item("a","首轮成功甲")+",{\"id\":\"b\",\"zh\":\"未完":new JSONObject().put("translations",translations).toString();
            String raw=status==200?new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",initial?"length":"stop").put("message",new JSONObject().put("content",text)))).toString():"{\"error\":{\"message\":\"Invalid subset; text HTTP 429 is not the response status\"}}";
            byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";settings.apiKey="loopback-isolation-fixture";settings.maxRetries=1;settings.retryIntervalSeconds=settings.rateLimitWaitSeconds=1;
        try(TranslationEngine engine=new TranslationEngine(new android.content.Context(root.resolve("app").toFile()));TranslationEngine.PreparedText job=PartialRetryChecks.job(root,regions,settings)){
            boolean stopped=false;try{engine.requestTextPage(job,settings,()->cancel&&calls.get()>=2);}catch(CancellationException expected){stopped=true;}
            ok(job.values.get("a").getString("zh").equals("首轮成功甲"),"accepted initial translation never resent or overwritten");
            if(cancel){ok(stopped&&calls.get()==2,"cancellation in a repair prevents every later subset");return;}
            boolean throttle=failedStatus==429||failedStatus==503;
            ok((job.throttleFailure!=null)==throttle,"only actual 429/503 stops the page, not status text in a 400 body");
            if(throttle){ok(calls.get()==3&&!job.values.containsKey("d")&&!job.values.containsKey("f"),"global throttle exhausts first subset retry then stops later subsets");}
            else{
                ok(calls.get()==(failedStatus==500?5:4),"all unaffected repair subsets are submitted once");
                ok(job.values.size()==4&&job.values.containsKey("f"),"later successful subset survives another subset failure");
                ok(job.values.containsKey(failSecondGroup?"b":"d"),"successful earlier/later repair is retained");
                ok(job.failures.keySet().equals(new HashSet<>(failSecondGroup?List.of("d","e"):List.of("b","c"))),"failure records cover only the failed subset");
            }
            for(int i=1;i<sent.size();i++)ok(!sent.get(i).contains("a"),"accepted crop is absent from repair payload");
        }finally{server.stop(0);}
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        scenario(root,400,false,false);scenario(root,400,true,false);scenario(root,500,false,false);
        scenario(root,429,false,false);scenario(root,503,false,false);scenario(root,400,false,true);
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:1/v1";settings.apiKey="unused";
        try(TranslationEngine engine=new TranslationEngine(new android.content.Context(root.resolve("interrupted").toFile()));TranslationEngine.PreparedText job=PartialRetryChecks.job(root,List.of(PartialRetryChecks.region("a")),settings)){
            Thread.currentThread().interrupt();boolean stopped=false;
            try{engine.requestTextPage(job,settings,()->false);}catch(CancellationException expected){stopped=true;}
            finally{ok(Thread.interrupted(),"thread interruption remains set until caller clears it");}
            ok(stopped&&job.values.isEmpty(),"interrupted task never starts a request");
        }
        TranslationStages stages=new TranslationStages(1,64,()->64,()->0);
        try{stages.request(()->{throw new ApiClient.RequestFailure("HTTP 429 inside server message",false,0,400);},()->false);}catch(ApiClient.RequestFailure expected){}
        java.lang.reflect.Field cooldown=TranslationStages.class.getDeclaredField("cooldownUntil");cooldown.setAccessible(true);
        ok(cooldown.getLong(stages)==0,"non-throttle status cannot start shared cooldown through message text");
        try{stages.request(()->{throw new ApiClient.RequestFailure("server unavailable",true,0,503);},()->false);}catch(ApiClient.RequestFailure expected){}
        ok(cooldown.getLong(stages)>0,"real 503 starts shared cooldown even without HTTP text in message");
        System.out.println("RepairGroupIsolationChecks: "+checks+" checks passed (current production engine and loopback transport; no external API)");
    }
}
