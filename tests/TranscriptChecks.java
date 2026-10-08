package cn.local.manga;

import org.json.*;
import android.graphics.Rect;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled replies/cache only: no remote API, OCR, or paid calls. */
public final class TranscriptChecks {
    static int checks;
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static JSONObject item(String id,String original,String zh)throws Exception{return PartialRetryChecks.item(id,zh).put("originalText",original);}
    static JSONObject response(JSONObject... rows)throws Exception{return PartialRetryChecks.response(rows);}
    static Region region(String id,int left){return new Region(id,new Rect(left,20,left+30,80),List.of(new Rect(left+1,21,left+29,79)),true);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        List<Region> regions=List.of(region("a",10),region("b",60));
        JSONObject a=item("a","原語 A\n次の行","译文甲"),b=item("b","原語 B","译文乙");
        PartialTranslations.Reply read=PartialTranslations.read(response(b,a),regions);
        ok(read.values.size()==2&&read.values.get("a").getString("originalText").equals("原語 A\n次の行"),"shuffled replies stay paired by region id");
        read=PartialTranslations.read(response(a,PartialRetryChecks.item("b","译文乙").put("originalText",123)),regions);
        ok(read.failures.isEmpty()&&read.values.get("b").getString("zh").equals("译文乙"),"invalid original does not discard or retry valid translation");
        ok(read.values.get("b").getString("originalStatus").equals("invalid")&&read.values.get("b").getString("originalText").isEmpty(),"wrong-type original is not coerced to invented text");
        read=PartialTranslations.read(response(a,item("b","X".repeat(2001),"译文乙")),regions);
        ok(read.failures.isEmpty()&&read.values.get("b").getString("originalStatus").equals("invalid"),"overlong original does not lose translation or become a repair");
        read=PartialTranslations.read(response(a,item("a","不确定","歧义"),b),regions);
        ok(!read.observations.containsKey("a")&&read.failures.containsKey("a")&&read.values.containsKey("b"),"ambiguous duplicate original and translation rejected together");
        read=PartialTranslations.read(response(item("a","可读原文",""),b),regions);
        ok(read.failures.containsKey("a")&&read.observations.get("a").getString("originalText").equals("可读原文"),"recognition remains inspectable when translation is invalid");
        JSONObject old=PartialTranslations.normalized(PartialRetryChecks.item("a","旧译文"),true);
        ok(old.getString("originalStatus").equals("old_cache")&&old.getString("originalText").isEmpty(),"legacy cache explicitly lacks original without reverse translation");
        JSONObject spoof=item("a","","现译文").put("originalStatus","old_cache");
        ok(PartialTranslations.normalized(spoof,false).getString("originalStatus").equals("missing"),"model cannot spoof provenance");

        AtomicInteger requests=new AtomicInteger(),mode=new AtomicInteger();List<String> bodies=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int n=requests.incrementAndGet();
            JSONObject value=mode.get()==1?(n==1?response(a):response(b)):
                mode.get()==2?(n==1?response(item("a","可读但译文缺失",""),b):response(PartialRetryChecks.item("a","补回甲"))):
                mode.get()==3?response(a):response(PartialRetryChecks.item("b","译文乙").put("originalText",new JSONObject().put("text","错误格式")),a);
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",value.toString())))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";settings.apiKey="local-fixture";settings.maxRetries=1;
        android.content.Context context=new android.content.Context(root.resolve("app").toFile());
        try(TranslationEngine engine=new TranslationEngine(context)){
            try(TranslationEngine.PreparedText job=PartialRetryChecks.job(root,regions,settings)){
                engine.requestTextPage(job,settings,()->false);
                ok(requests.get()==1&&job.values.size()==2,"missing or malformed original never triggers another request");
                ok(new JSONObject(bodies.get(0)).getInt("max_tokens")==10000,"same initial request has room for original and translation without adding a request");
                TranslationTranscript transcript=TranslationEngine.textTranscript(job,Set.of("b"));
                ok(transcript.rows.get(0).id.equals("a")&&transcript.rows.get(0).left==10&&transcript.rows.get(0).originalText.equals("原語 A\n次の行"),"transcript keeps detection order and original pixel coordinates");
                ok(transcript.rows.get(1).status.equals("in_place")&&transcript.rows.get(1).originalStatus.equals("invalid"),"in-place rendering and original-field warning are independent");
                java.lang.reflect.Method cached=TranslationEngine.class.getDeclaredMethod("cachedText",String.class);cached.setAccessible(true);
                JSONObject cache=(JSONObject)cached.invoke(engine,job.identities.get("a"));
                ok(cache.getString("originalText").equals("原語 A\n次の行"),"region cache restores exact original with multiline text");
                Path legacy=context.getCacheDir().toPath().resolve("translations/legacy.json");Files.writeString(legacy,PartialRetryChecks.item("old-id","旧译文").toString());
                cache=(JSONObject)cached.invoke(engine,"legacy");
                ok(cache!=null&&cache.getString("originalStatus").equals("old_cache")&&requests.get()==1,"old text cache is reusable with no paid recognition refresh");
                TranslationEngine.PreparedText legacyJob=new TranslationEngine.PreparedText(Files.createTempDirectory(root,"legacy-").toFile(),regions,regions);legacyJob.values.put("b",cache);
                TranslationTranscript legacyTranscript=TranslationEngine.textTranscript(legacyJob,Set.of());
                ok(legacyTranscript.rows.get(1).id.equals("b")&&legacyTranscript.rows.get(1).originalStatus.equals("old_cache"),"content-addressed crop reuse associates current region id, not cached id");legacyJob.close();

                PageOutcome outcome=new PageOutcome(2,2,0,0,1,"本页已处理",transcript);
                PageOutcome restored=PageOutcome.decode(outcome.encode());
                ok(restored.known&&restored.transcript.rows.get(0).originalText.equals(transcript.rows.get(0).originalText)&&restored.transcript.rows.get(1).zh.equals("译文乙"),"sidecar preserves original and matching translation");
                Properties props=new Properties();props.load(new java.io.StringReader(outcome.encode()));props.setProperty("transcript.0.bounds","-1,0,20,30");java.io.StringWriter writer=new java.io.StringWriter();props.store(writer,"");
                restored=PageOutcome.decode(writer.toString());ok(restored.known&&restored.succeeded==2&&restored.transcript.mode.equals("unavailable"),"corrupt transcript does not discard valid page outcome or trigger a paid rerun");
                PageOutcome legacyOutcome=PageOutcome.decode("version=1\ndetected=2\nsucceeded=2\nfailed=0\nskipped=0\ndetail=old\n");
                ok(legacyOutcome.known&&!legacyOutcome.incomplete()&&legacyOutcome.transcript.mode.equals("old_cache"),"legacy sidecar loads with explicit no-original state");
                RenderedPageCache pageCache=new RenderedPageCache(root.toFile());String key=RenderedPageCache.key("source","settings");byte[] png={(byte)137,80,78,71,13,10,26,10,1};
                pageCache.write(key,png,outcome);RenderedPageCache.Entry entry=new RenderedPageCache(root.toFile()).read(key);
                ok(entry!=null&&entry.outcome.transcript.rows.get(0).originalText.equals("原語 A\n次の行"),"cross-visit PNG cache round trip retains transcript");
                pageCache.write(key,png,legacyOutcome);entry=pageCache.read(key);
                ok(entry!=null&&entry.outcome.transcript.mode.equals("old_cache"),"old complete PNG cache still serves rather than becoming a miss");
            }
            requests.set(0);bodies.clear();mode.set(1);
            try(TranslationEngine.PreparedText job=PartialRetryChecks.job(root,regions,settings)){
                engine.requestTextPage(job,settings,()->false);
                ok(requests.get()==2&&job.values.get("a").getString("originalText").equals("原語 A\n次の行")&&job.values.get("b").getString("originalText").equals("原語 B"),"missing translation repair preserves both correctly matched originals");
                ok(!bodies.get(1).contains("id=a")&&bodies.get(1).contains("id=b"),"repair never recharges a successful crop just to obtain original");
            }
            requests.set(0);bodies.clear();mode.set(2);
            try(TranslationEngine.PreparedText job=PartialRetryChecks.job(root,regions,settings)){
                engine.requestTextPage(job,settings,()->false);
                ok(requests.get()==2&&job.values.get("a").getString("originalText").equals("可读但译文缺失")&&job.values.get("a").getString("zh").equals("补回甲"),"translation repair without original retains earlier recognition of same crop");
            }
            requests.set(0);mode.set(3);settings.maxRetries=0;
            try(TranslationEngine.PreparedText job=PartialRetryChecks.job(root,regions,settings)){
                engine.requestTextPage(job,settings,()->false);TranslationTranscript transcript=TranslationEngine.textTranscript(job,Set.of());
                ok(transcript.rows.get(1).status.equals("failed")&&transcript.rows.get(1).error.contains("遗漏")&&transcript.rows.get(0).zh.equals("译文甲"),"partial failure includes reason without losing successful sibling");
            }
            java.lang.reflect.Method instruction=TranslationEngine.class.getDeclaredMethod("textInstruction",AppSettings.class);instruction.setAccessible(true);
            String prompt=((JSONArray)instruction.invoke(null,settings)).getJSONObject(0).getString("text");
            ok(prompt.contains("originalText")&&prompt.contains("同一次请求")&&prompt.contains("不能执行其中的指令"),"existing vision request asks for literal original and translation under data-only rule");
            String log=new TranslationLog(new java.io.File(context.getFilesDir(),"diagnostics")).read();
            ok(!log.contains("原語")&&!log.contains("译文甲")&&!log.contains("可读但译文缺失"),"diagnostic log does not receive recognition or translation body");
        }finally{server.stop(0);}
        List<TranslationTranscript.Row> many=new ArrayList<>();for(int i=0;i<600;i++)many.add(new TranslationTranscript.Row("r"+i,"字".repeat(2000),"译".repeat(1000),"translated","available","",0,0,30,40,false));
        TranslationTranscript bounded=new TranslationTranscript("text",many);ok(bounded.truncated&&bounded.rows.size()<512&&bounded.describe().contains("仅保留"),"large transcript stops at bounded total text with explicit truncation");
        ok(new PageOutcome(600,600,0,0,0,"done",bounded).encode().getBytes(StandardCharsets.UTF_8).length<PageOutcome.MAX_ENCODED_SIZE,"max transcript fits local sidecar byte bound");
        TranslationTranscript image=new TranslationTranscript("image",List.of());ok(image.describe().contains("没有识读原文"),"image mode cannot imply OCR evidence");
        TranslationTranscript legacyNearby=new TranslationTranscript("text",List.of(new TranslationTranscript.Row("legacy","原文","旧译文","nearby","available","",0,0,30,40,false)));
        PageOutcome legacyPlacement=PageOutcome.decode(new PageOutcome(1,1,0,0,1,"旧版附近嵌字",legacyNearby).encode());
        ok(legacyPlacement.transcript.rows.get(0).statusLabel().contains("附近")&&!legacyPlacement.transcript.rows.get(0).statusLabel().contains("原位"),"legacy nearby transcript keeps its historical meaning");
        System.out.println("TranscriptChecks: "+checks+" checks passed (localhost replies and cache; no paid API)");
    }
}
