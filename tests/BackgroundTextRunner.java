package cn.local.manga;

import android.graphics.Rect;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Actual production prompt + transport + reply validation, with durable bounded batch attempts. */
public final class BackgroundTextRunner {
    static JSONObject read(Path p)throws Exception{return new JSONObject(Files.readString(p).replaceFirst("^\\ufeff",""));}
    static void save(Path p,JSONObject o)throws Exception{Files.createDirectories(p.getParent());Path t=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(t,o.toString(2));Files.move(t,p,StandardCopyOption.REPLACE_EXISTING);}
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String hash(Path p)throws Exception{return hash(Files.readAllBytes(p));}
    static boolean cancelled(Path root){return Files.exists(root.resolve("CANCEL_TEXT"))||Thread.currentThread().isInterrupted();}
    static void check(Path root){if(cancelled(root))throw new CancellationException("Text queue cancelled");}
    static AppSettings settings(Path config)throws Exception{JSONObject c=read(config);if(!c.optBoolean("enabled",true))throw new Exception("Local API disabled");AppSettings s=new AppSettings();s.baseUrl="http://127.0.0.1:"+c.getInt("port")+"/v1";s.apiKey=c.getString("apiKey");s.textModel="gpt-6-astra";s.reasoningEffort="low";s.serviceTier="auto";s.maxRetries=0;s.requestTimeoutSeconds=600;return s;}
    static JSONArray instruction(AppSettings s)throws Exception{var m=TranslationEngine.class.getDeclaredMethod("textInstruction",AppSettings.class);m.setAccessible(true);return (JSONArray)m.invoke(null,s);}
    static Region region(JSONObject row)throws Exception{JSONArray b=row.getJSONArray("absoluteBox");Rect box=new Rect(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3));return new Region(row.getString("id"),box,List.of(box),row.optBoolean("vertical"));}
    static void prepare(Path root,AppSettings s)throws Exception{
        Path dir=root.resolve("text_batches"),planPath=dir.resolve("plan.json");
        if(Files.exists(planPath)){validate(root,s);System.out.println("Frozen text plan reused; no API call.");return;}
        JSONObject input=read(root.resolve("text_request_plan.json"));JSONArray rows=input.getJSONArray("targets"),jobs=new JSONArray();
        JSONArray base=instruction(s);String prompt=base.getJSONObject(0).getString("text");Files.createDirectories(dir);Files.writeString(dir.resolve("production_prompt.txt"),prompt);
        int at=0;
        while(at<rows.length()){
            int page=rows.getJSONObject(at).getInt("page"),start=at;while(at<rows.length()&&at-start<8&&rows.getJSONObject(at).getInt("page")==page)at++;
            String id=String.format(Locale.ROOT,"TXT_%03d_P%02d",jobs.length()+1,page);Path folder=dir.resolve(id);Files.createDirectories(folder);
            JSONArray content=instruction(s),members=new JSONArray();
            for(int i=start;i<at;i++){
                JSONObject r=new JSONObject(rows.getJSONObject(i).toString());Path source=root.resolve(r.getString("originalRoi"));if(!hash(source).equals(r.getString("originalSha256")))throw new Exception("Original crop hash changed: "+r.getString("id"));
                Path copy=folder.resolve(r.getString("id")+"_original.png");Files.copy(source,copy,StandardCopyOption.REPLACE_EXISTING);
                BufferedImage image=ImageIO.read(copy.toFile());if(image==null)throw new Exception("Unreadable crop");int[] size=ApiClient.textInputSize(image.getWidth(),image.getHeight());BufferedImage scaled=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);Graphics2D g=scaled.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(image,0,0,size[0],size[1],null);g.dispose();Path uploaded=folder.resolve(r.getString("id")+"_upload.png");ImageIO.write(scaled,"png",uploaded.toFile());
                content.put(new JSONObject().put("type","text").put("text",TranslationEngine.cropInstruction(region(r))));
                content.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(uploaded))).put("detail","high")));
                r.put("frozenOriginal",root.relativize(copy).toString()).put("uploadedImage",root.relativize(uploaded).toString()).put("uploadSha256",hash(uploaded));members.put(r);
            }
            Path payload=folder.resolve("content.json");Files.writeString(payload,ApiClient.protocolContent(s,content).toString());
            String identity=hash((hash(payload)+"\n"+s.baseUrl+"\n"+s.textModel+"\n"+s.reasoningEffort+"\n"+s.apiKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jobs.put(new JSONObject().put("id",id).put("page",page).put("members",members).put("payload",root.relativize(payload).toString()).put("payloadSha256",hash(payload)).put("identitySha256",identity));
        }
        save(planPath,new JSONObject().put("schemaVersion",1).put("createdAt",Instant.now().toString()).put("model",s.textModel).put("reasoning",s.reasoningEffort).put("productionPrompt",true).put("productionTransport",true).put("maxAttempts",3).put("concurrency",2).put("sourcePlanSha256",hash(root.resolve("text_request_plan.json"))).put("jobs",jobs));
        snapshot(root,false);System.out.println("TEXT PREPARED: "+rows.length()+" targets in "+jobs.length()+" frozen batches; no API call.");
    }
    static JSONObject validate(Path root,AppSettings s)throws Exception{
        JSONObject plan=read(root.resolve("text_batches/plan.json"));
        for(Object o:plan.getJSONArray("jobs")){JSONObject j=(JSONObject)o;Path p=root.resolve(j.getString("payload"));if(!hash(p).equals(j.getString("payloadSha256")))throw new Exception("Frozen text payload changed");String identity=hash((hash(p)+"\n"+s.baseUrl+"\n"+s.textModel+"\n"+s.reasoningEffort+"\n"+s.apiKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!identity.equals(j.getString("identitySha256")))throw new Exception("Text request settings/credential identity changed");for(Object v:j.getJSONArray("members")){JSONObject m=(JSONObject)v;if(!hash(root.resolve(m.getString("frozenOriginal"))).equals(m.getString("originalSha256"))||!hash(root.resolve(m.getString("uploadedImage"))).equals(m.getString("uploadSha256")))throw new Exception("Frozen text crop changed");}}
        return plan;
    }
    static JSONObject base(JSONObject job){return new JSONObject().put("id",job.getString("id")).put("identitySha256",job.getString("identitySha256")).put("productionTransport",true).put("model","gpt-6-astra").put("reasoning","low").put("appInternalRetries",0);}
    static boolean retryable(Exception e){if(!(e instanceof ApiClient.RequestFailure f)||!f.retryable)return false;String m=String.valueOf(f.getMessage()).toLowerCase(Locale.ROOT);return !m.matches("(?s).*(content.policy|policy.violation|content.filter|safety.violation|unsupported|not supported|不支持|内容拒绝|违反政策).* ".trim())&&(f.status==0||f.status==408||f.status==429||f.status==500||f.status==502||f.status==503||f.status==504);}
    static void finishReply(Path root,JSONObject job,JSONObject reply,int attempt)throws Exception{
        List<Region> regions=new ArrayList<>();for(Object x:job.getJSONArray("members"))regions.add(region((JSONObject)x));PartialTranslations.Reply parsed=PartialTranslations.read(reply,regions);JSONArray values=new JSONArray();int okay=0,skip=0,missing=0;
        for(Region r:regions){JSONObject v=parsed.values.get(r.id);if(v==null){v=new JSONObject().put("id",r.id).put("zh","").put("originalText","").put("skip",false).put("status","invalid_or_missing_reply").put("error",parsed.failures.get(r.id));missing++;}else if(v.optBoolean("skip")){v.put("status","model_skip");skip++;}else if(!"available".equals(v.optString("originalStatus"))){v.put("status","missing_original_text");missing++;}else{v.put("status","translated");okay++;}v.put("sourceText",v.optString("originalText")).put("translationRecord","text_batches/"+job.getString("id")+"/attempt_"+String.format(Locale.ROOT,"%02d",attempt)+"/response.json").put("actualTextApi",true);values.put(v);}
        save(root.resolve("text_batches/"+job.getString("id")+"/result.json"),base(job).put("status",missing==0?"complete":"partial").put("attemptsStarted",attempt).put("completedAt",Instant.now().toString()).put("translated",okay).put("modelSkip",skip).put("invalidOrMissing",missing).put("translations",values));
    }
    static void process(Path root,JSONObject job,AppSettings s)throws Exception{
        Path folder=root.resolve("text_batches/"+job.getString("id")),result=folder.resolve("result.json");check(root);
        if(Files.exists(result)){if(!read(result).getString("identitySha256").equals(job.getString("identitySha256")))throw new Exception("Recorded text identity changed");return;}
        for(int n=1;n<=3;n++){
            check(root);Path dir=folder.resolve(String.format(Locale.ROOT,"attempt_%02d",n)),start=dir.resolve("start.json"),done=dir.resolve("result.json"),response=dir.resolve("response.json");JSONObject previous=null;
            if(Files.exists(start)){
                if(!read(start).getString("identitySha256").equals(job.getString("identitySha256")))throw new Exception("Attempt identity mismatch");
                if(Files.exists(response)){finishReply(root,job,read(response),n);return;}
                if(!Files.exists(done)){save(result,base(job).put("status","in_flight_unknown").put("attemptsStarted",n).put("reason","Started without terminal evidence; no blind resend"));return;}
                previous=read(done);
            }else{
                Files.createDirectories(dir);save(start,base(job).put("status","request_started").put("attempt",n).put("startedAt",Instant.now().toString()));System.out.println(job.getString("id")+" REQUEST_"+n);snapshot(root,false);
                try{JSONObject reply=ApiClient.chatPrepared(s,root.resolve(job.getString("payload")).toFile(),()->cancelled(root),10000);save(response,reply);finishReply(root,job,reply,n);save(done,base(job).put("status","response_saved").put("responseSha256",hash(response)));System.out.println(job.getString("id")+" RESPONSE");return;}
                catch(ApiClient.TranslationReplyFailure partial){save(response,partial.usableReply);finishReply(root,job,partial.usableReply,n);save(done,base(job).put("status","partial_response_saved").put("responseSha256",hash(response)));return;}
                catch(CancellationException e){save(result,base(job).put("status","in_flight_unknown").put("attemptsStarted",n).put("reason","Cancelled while upstream completion unknown"));throw e;}
                catch(Exception e){long delay=Math.max(n==1?5000:15000,e instanceof ApiClient.RequestFailure f?Math.min(300000,Math.max(0,f.retryAfterMs)):0);previous=base(job).put("status","failed").put("retryable",retryable(e)).put("failure",e.toString().replace(s.apiKey,"[redacted]")).put("nextAllowedAtMillis",System.currentTimeMillis()+delay);if(e instanceof ApiClient.RequestFailure f)previous.put("httpStatus",f.status);save(done,previous);System.out.println(job.getString("id")+" FAILED_"+n);}
            }
            if(!previous.optBoolean("retryable")||n==3){save(result,base(job).put("status","terminal_failed").put("attemptsStarted",n).put("failure",previous.optString("failure")));return;}
            while(System.currentTimeMillis()<previous.optLong("nextAllowedAtMillis")){check(root);Thread.sleep(250);}
        }
    }
    static synchronized void snapshot(Path root,boolean finished)throws Exception{
        JSONObject plan=read(root.resolve("text_batches/plan.json"));JSONArray rows=new JSONArray(),batches=new JSONArray();Map<String,Integer> counts=new TreeMap<>();int requests=0;
        for(Object x:plan.getJSONArray("jobs")){JSONObject job=(JSONObject)x;Path dir=root.resolve("text_batches/"+job.getString("id")),file=dir.resolve("result.json");JSONObject result=Files.exists(file)?read(file):base(job).put("status","pending");Map<String,JSONObject> values=new HashMap<>();if(result.has("translations"))for(Object v:result.getJSONArray("translations"))values.put(((JSONObject)v).getString("id"),(JSONObject)v);for(int n=1;n<=3;n++)if(Files.exists(dir.resolve(String.format(Locale.ROOT,"attempt_%02d/start.json",n))))requests++;
            batches.put(new JSONObject().put("id",job.getString("id")).put("status",result.getString("status")));
            for(Object m:job.getJSONArray("members")){String id=((JSONObject)m).getString("id");JSONObject v=values.get(id);if(v==null)v=new JSONObject().put("id",id).put("zh","").put("originalText","").put("sourceText","").put("skip",false).put("status",result.getString("status")).put("actualTextApi",false);rows.put(v);counts.merge(v.getString("status"),1,Integer::sum);}
        }
        save(root.resolve("text_translations.json"),new JSONObject().put("schemaVersion",1).put("updatedAt",Instant.now().toString()).put("runFinished",finished).put("actualApplicationCallsStarted",requests).put("billingUnknown",true).put("counts",counts).put("translations",rows).put("batches",batches));
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);AppSettings s=settings(Paths.get(args[1]));String mode=args[2];
        if(mode.equals("prepare")){prepare(root,s);return;}JSONObject plan=validate(root,s);if(mode.equals("validate")){System.out.println("TEXT VALIDATED: "+plan.getJSONArray("jobs").length()+" frozen batches; no API call.");return;}
        if(!mode.equals("run"))throw new IllegalArgumentException("Unknown mode");
        try(FileChannel channel=FileChannel.open(root.resolve("text_batches/run.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
            if(lock==null)throw new Exception("Text queue already running");ExecutorService pool=Executors.newFixedThreadPool(2);List<Future<?>> tasks=new ArrayList<>();
            try{for(Object row:plan.getJSONArray("jobs")){JSONObject job=(JSONObject)row;tasks.add(pool.submit(()->{try{process(root,job,s);snapshot(root,false);}catch(Exception e){throw new CompletionException(e);}}));}pool.shutdown();for(Future<?> task:tasks)task.get();snapshot(root,true);System.out.println("TEXT QUEUE FINISHED; inspect per-target statuses.");}finally{pool.shutdownNow();}
        }
    }
}
