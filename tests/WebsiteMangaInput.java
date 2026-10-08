package cn.local.manga;

import ai.onnxruntime.*;
import android.graphics.Rect;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.*;

/** New website inputs only: real CPU ONNX inference, production grouping/mapping, no network calls. */
public final class WebsiteMangaInput {
    private static final String[] SOURCES={"DetectorModels","RtDetrDetector","RtDetrRegions","Region"};
    static JSONObject read(Path p)throws Exception{return new JSONObject(Files.readString(p).replaceFirst("^\\ufeff",""));}
    static void save(Path p,JSONObject value)throws Exception{Files.createDirectories(p.getParent());Path tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,value.toString(2));Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static int[] box(Rect r){return new int[]{r.left,r.top,r.right,r.bottom};}
    static Rect rect(JSONArray a){return new Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3));}
    static String relative(Path root,Path p){return root.relativize(p).toString().replace('\\','/');}
    static int constant(String name)throws Exception{var f=RtDetrDetector.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(null);}
    static JSONObject sourceHashes(Path project)throws Exception{JSONObject result=new JSONObject();for(String name:SOURCES)result.put(name,hash(project.resolve("app/src/main/java/cn/local/manga/"+name+".java")));return result;}
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static void sameHashes(JSONObject expected,JSONObject actual){require(expected.keySet().equals(actual.keySet()),"Production source hash keys changed");for(String key:expected.keySet())require(expected.getString(key).equals(actual.getString(key)),"Production detector source changed: "+key);}
    static Rect map(Rect r,int width,int height,int w,int h)throws Exception{Method m=RtDetrDetector.class.getDeclaredMethod("map",Rect.class,int.class,int.class,int.class,int.class);m.setAccessible(true);return (Rect)m.invoke(null,r,width,height,w,h);}
    static Region mapped(Region r,int width,int height,int w,int h)throws Exception{List<Rect> lines=new ArrayList<>();for(Rect line:r.lines)lines.add(map(line,width,height,w,h));return new Region(r.id,map(r.box,width,height,w,h),lines,r.vertical,r.contextBox==null?null:map(r.contextBox,width,height,w,h));}
    static int[] workSize(int width,int height)throws Exception{
        require(width>=4&&height>=4,"Invalid source dimensions");
        // Same frozen 0.9.2 detect policy; actual pixel interpolation is explicitly Java2D, not Android Canvas.
        double scale=Math.min(1d,(double)constant("WORK_LIMIT")/Math.max(width,height));
        if((double)height/width>3.5)scale=Math.min(1d,(double)constant("SIZE")/width);
        int w=Math.max(4,(int)Math.round(width*scale)),h=Math.max(4,(int)Math.round(height*scale));
        require((long)w*h<=8_000_000L,"Production detector rejects working image above 8 million pixels");return new int[]{w,h};
    }
    static BufferedImage resized(BufferedImage source,int left,int top,int right,int bottom,int width,int height){
        BufferedImage out=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,width,height);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,width,height,left,top,right,bottom,null);g.dispose();return out;
    }
    static JSONObject regionJson(Region r,int width,int height){JSONArray lines=new JSONArray();for(Rect b:r.lines)lines.put(new JSONArray(box(b)));return new JSONObject().put("id",r.id).put("box",new JSONArray(box(r.box))).put("lines",lines).put("vertical",r.vertical).put("contextBox",r.contextBox==null?JSONObject.NULL:new JSONArray(box(r.contextBox))).put("renderBounds",new JSONArray(box(RtDetrRegions.renderBounds(r,width,height))));}
    static Region region(JSONObject row){List<Rect> lines=new ArrayList<>();for(Object raw:row.getJSONArray("lines"))lines.add(rect((JSONArray)raw));return new Region(row.getString("id"),rect(row.getJSONArray("box")),lines,row.getBoolean("vertical"),row.isNull("contextBox")?null:rect(row.getJSONArray("contextBox")));}
    static JSONObject infer(OrtEnvironment env,OrtSession session,BufferedImage source,Path sourceFile,JSONObject hashes,DetectorModels.Spec spec,int page)throws Exception{
        long start=System.nanoTime();int width=source.getWidth(),height=source.getHeight();int[] size=workSize(width,height);int w=size[0],h=size[1],inputSize=constant("SIZE");
        BufferedImage work=w==width&&h==height?source:resized(source,0,0,width,height,w,h);
        ArrayList<Long> labels=new ArrayList<>();ArrayList<float[]> boxes=new ArrayList<>();ArrayList<Float> scores=new ArrayList<>();JSONArray windows=new JSONArray();
        for(int[] window:RtDetrDetector.sliceWindows(w,h)){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Detection cancelled");
            BufferedImage input=resized(work,0,window[0],w,window[1],inputSize,inputSize);int count=inputSize*inputSize;float[] rgb=new float[3*count];int[] pixels=input.getRGB(0,0,inputSize,inputSize,null,0,inputSize);
            for(int i=0;i<count;i++){int p=pixels[i];rgb[i]=((p>>>16)&255)/255f;rgb[count+i]=((p>>>8)&255)/255f;rgb[2*count+i]=(p&255)/255f;}input.flush();int accepted=0;
            try(OnnxTensor image=OnnxTensor.createTensor(env,FloatBuffer.wrap(rgb),new long[]{1,3,inputSize,inputSize});OnnxTensor sizes=OnnxTensor.createTensor(env,new long[][]{{w,window[1]-window[0]}})){
                try(OrtSession.Result result=session.run(Map.of("images",image,"orig_target_sizes",sizes))){
                    long[][] ls=(long[][])result.get("labels").orElseThrow().getValue();float[][][] bs=(float[][][])result.get("boxes").orElseThrow().getValue();float[][] ss=(float[][])result.get("scores").orElseThrow().getValue();
                    require(ls.length==1&&bs.length==1&&ss.length==1&&ls[0].length==bs[0].length&&ls[0].length==ss[0].length&&ls[0].length<=1000,"Incompatible RT-DETR outputs");
                    for(int i=0;i<ls[0].length;i++){float[] b=bs[0][i];require(b.length==4&&Float.isFinite(ss[0][i]),"Invalid RT-DETR output");for(float v:b)require(Float.isFinite(v),"Nonfinite RT-DETR box");if(ss[0][i]<.3f||ls[0][i]<0||ls[0][i]>2)continue;labels.add(ls[0][i]);scores.add(ss[0][i]);boxes.add(new float[]{b[0],b[1]+window[0],b[2],b[3]+window[0]});accepted++;}
                }
            }
            windows.put(new JSONObject().put("bounds",new JSONArray(new int[]{0,window[0],w,window[1]})).put("acceptedPredictions",accepted));
        }
        long[] ls=new long[labels.size()];float[] ss=new float[scores.size()];JSONArray rawBoxes=new JSONArray(),rawTypes=new JSONArray(),rawScores=new JSONArray();String[] names={"bubble","text_bubble","text_free"};
        for(int i=0;i<ls.length;i++){ls[i]=labels.get(i);ss[i]=scores.get(i);rawBoxes.put(new JSONArray(boxes.get(i)));rawTypes.put(names[(int)ls[i]]);rawScores.put(ss[i]);}
        List<Region> grouped=RtDetrRegions.fromPredictions(w,h,work.getRGB(0,0,w,h,null,0,w),ls,boxes.toArray(new float[0][]),ss);JSONArray regions=new JSONArray();for(Region r:grouped)regions.put(regionJson(mapped(r,width,height,w,h),width,height));if(work!=source)work.flush();
        return new JSONObject().put("schemaVersion",1).put("page",page).put("sourceSha256",hash(sourceFile)).put("width",width).put("height",height).put("workingWidth",w).put("workingHeight",h).put("coordinateSpace","working_image").put("boxes",rawBoxes).put("box_types",rawTypes).put("scores",rawScores).put("sliceWindows",windows).put("regions",regions).put("regionsCoordinateSpace","original_image").put("detectorModel",spec.id).put("modelSha256",spec.sha256).put("productionSourceSha256",hashes).put("runtime",env.getVersion()).put("preprocessing","Frozen production work-size/slicing/RGB-normalization policy; Java2D bilinear pixels").put("productionGroupingAndCoordinateMappingExecuted",true).put("androidCanvasVerified",false).put("networkCalls",0).put("detectionMs",Math.round((System.nanoTime()-start)/1_000_000d)).put("createdAt",Instant.now().toString());
    }
    static void detect(Path root,Path project,int pageCount)throws Exception{
        require(pageCount>0,"PageCount must be positive");Path input=root.resolve("新输入"),pages=input.resolve("逐页"),text=root.resolve("文本队列");JSONObject hashes=sourceHashes(project);Path existing=text.resolve("text_request_plan.json");
        require(!Files.exists(text.resolve("text_batches/plan.json")),"Text requests are already frozen; detect cannot replace their inputs");
        DetectorModels.Spec spec=DetectorModels.get(DetectorModels.DEFAULT_ID);require(spec.id.equals("rtdetr_r50_int8"),"Expected frozen 0.9.2 default INT8 detector");Path model=project.resolve("app/src/main/assets").resolve(spec.asset);require(Files.size(model)==spec.bytes&&hash(model).equals(spec.sha256),"Bundled model hash mismatch");
        for(int page=1;page<=pageCount;page++)require(Files.isRegularFile(pages.resolve(String.format(Locale.ROOT,"第%02d页/原图.png",page))),"Missing NEW source page "+page);
        OrtEnvironment env=OrtEnvironment.getEnvironment();env.setTelemetry(false);require(env.getVersion().startsWith("1.22."),"Host ORT must match Android 1.22 series");JSONArray summary=new JSONArray(),targets=new JSONArray();
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));options.setInterOpNumThreads(1);
            try(OrtSession session=env.createSession(model.toString(),options)){
                RtDetrDetector.validate(session);
                for(int page=1;page<=pageCount;page++){
                    Path dir=pages.resolve(String.format(Locale.ROOT,"第%02d页",page)),sourceFile=dir.resolve("原图.png"),rawFile=dir.resolve("检测原始结果.json"),regionFile=dir.resolve("段落检测.json");BufferedImage source=ImageIO.read(sourceFile.toFile());require(source!=null,"Unreadable source image "+page);JSONObject raw;
                    if(Files.exists(rawFile)){raw=read(rawFile);require(raw.getString("sourceSha256").equals(hash(sourceFile))&&raw.getString("modelSha256").equals(spec.sha256),"Existing NEW detection identity changed "+page);sameHashes(raw.getJSONObject("productionSourceSha256"),hashes);}else{raw=infer(env,session,source,sourceFile,hashes,spec,page);save(rawFile,raw);}
                    JSONObject grouped=new JSONObject(raw.toString());for(String key:new String[]{"boxes","box_types","scores"})grouped.remove(key);grouped.put("coordinateSpace","original_image").put("rawPredictionSha256",hash(rawFile));save(regionFile,grouped);
                    BufferedImage annotated=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=annotated.createGraphics();g.drawImage(source,0,0,null);g.setFont(new Font("Microsoft YaHei",Font.BOLD,Math.max(12,source.getWidth()/65)));g.setStroke(new BasicStroke(Math.max(1,source.getWidth()/650f)));
                    for(Object value:raw.getJSONArray("regions")){
                        JSONObject r=(JSONObject)value;Rect b=rect(r.getJSONArray("box"));String id=String.format(Locale.ROOT,"P%02d_",page)+r.getString("id");Path crop=text.resolve("crops").resolve(id+".png");Files.createDirectories(crop.getParent());BufferedImage cut=source.getSubimage(b.left,b.top,b.width(),b.height());ImageIO.write(cut,"png",crop.toFile());
                        targets.put(new JSONObject().put("id",id).put("page",page).put("regionId",r.getString("id")).put("absoluteBox",r.getJSONArray("box")).put("lines",r.getJSONArray("lines")).put("vertical",r.getBoolean("vertical")).put("contextBox",r.get("contextBox")).put("originalRoi",relative(text,crop)).put("originalSha256",hash(crop)).put("sourcePage",sourceFile.toAbsolutePath().toString()).put("sourceSha256",hash(sourceFile)).put("detectionSha256",hash(regionFile)));
                        g.setColor(new Color(220,25,25));g.drawRect(b.left,b.top,b.width(),b.height());g.drawString(r.getString("id"),b.left,Math.max(15,b.top-3));g.setColor(new Color(0,150,140));for(Object line:r.getJSONArray("lines")){Rect lb=rect((JSONArray)line);g.drawRect(lb.left,lb.top,lb.width(),lb.height());}
                    }
                    g.dispose();ImageIO.write(annotated,"png",dir.resolve("真实检测标注.png").toFile());annotated.flush();source.flush();summary.put(new JSONObject().put("page",page).put("sourcePage",sourceFile.toAbsolutePath().toString()).put("sourceSha256",raw.getString("sourceSha256")).put("predictionSha256",hash(rawFile)).put("regionsSha256",hash(regionFile)).put("regions",raw.getJSONArray("regions").length()).put("rawPredictions",raw.getJSONArray("boxes").length()).put("detectionMs",raw.getLong("detectionMs")));System.out.println("DETECTED page="+page+" regions="+raw.getJSONArray("regions").length()+" raw="+raw.getJSONArray("boxes").length());
                }
            }
        }
        sameHashes(hashes,sourceHashes(project));save(existing,new JSONObject().put("schemaVersion",1).put("newWebsiteInputs",true).put("pageCount",pageCount).put("productionSourceSha256",hashes).put("targets",targets));save(input.resolve("检测汇总.json"),new JSONObject().put("schemaVersion",1).put("pageCount",pageCount).put("regions",targets.length()).put("modelSha256",spec.sha256).put("productionSourceSha256",hashes).put("textPlanSha256",hash(existing)).put("androidCanvasVerified",false).put("networkCalls",0).put("pages",summary));
        StringBuilder html=new StringBuilder("<!doctype html><meta charset='utf-8'><title>新素材真实检测审查</title><style>body{font:16px system-ui;margin:20px;background:#eee}section{background:white;padding:12px;margin:16px 0}figure{display:inline-block;width:48%;margin:0;vertical-align:top}img{max-width:100%}code{font-size:12px}</style><h1>新素材真实检测审查</h1><p>每页左为新原图，右为真实 INT8 ONNX 检测。红框及编号为生产段落，青框为生产原文行锚框。仅供逐页检查漏检、错误合并、截断字；尚不代表视觉验收。没有手填框、没有旧图或旧译文。</p>");
        for(Object value:summary){JSONObject page=(JSONObject)value;String folder=String.format(Locale.ROOT,"逐页/第%02d页/",page.getInt("page"));html.append("<section><h2>第").append(page.getInt("page")).append("页 · ").append(page.getInt("regions")).append("段 · ").append(page.getInt("rawPredictions")).append("个原始候选</h2><figure><figcaption>原图</figcaption><img loading='lazy' src='").append(folder).append("原图.png'></figure><figure><figcaption>真实检测标注</figcaption><img loading='lazy' src='").append(folder).append("真实检测标注.png'></figure></section>");}Files.writeString(input.resolve("检测原图对照.html"),html.toString());System.out.println("DETECTION COMPLETE pages="+pageCount+" regions="+targets.length()+"; no API call.");
    }
    static void export(Path root)throws Exception{
        Path text=root.resolve("文本队列"),resultPath=text.resolve("text_translations.json");JSONObject plan=read(text.resolve("text_request_plan.json")),frozen=read(text.resolve("text_batches/plan.json")),result=read(resultPath);require(hash(text.resolve("text_request_plan.json")).equals(frozen.getString("sourcePlanSha256")),"Text source plan changed after freezing");require(result.optBoolean("runFinished"),"Text queue has not finished; cannot freeze per-page translations");Map<String,JSONObject> returned=new HashMap<>();for(Object value:result.getJSONArray("translations")){JSONObject row=(JSONObject)value;require(returned.put(row.getString("id"),row)==null,"Duplicate response id");}
        Map<Integer,JSONArray> translated=new TreeMap<>(),failures=new TreeMap<>();int total=0,success=0,skips=0,failed=0;
        for(Object value:plan.getJSONArray("targets")){
            JSONObject target=(JSONObject)value;String id=target.getString("id");int page=target.getInt("page");Path source=Paths.get(target.getString("sourcePage"));require(hash(source).equals(target.getString("sourceSha256"))&&hash(source.resolveSibling("段落检测.json")).equals(target.getString("detectionSha256")),"Source/detection changed since text preparation "+id);require(hash(text.resolve(target.getString("originalRoi"))).equals(target.getString("originalSha256")),"Crop changed "+id);JSONObject row=returned.remove(id);require(row!=null,"Missing queue status "+id);translated.computeIfAbsent(page,k->new JSONArray());failures.computeIfAbsent(page,k->new JSONArray());total++;
            boolean accepted=row.optBoolean("actualTextApi")&&(row.optString("status").equals("translated")||row.optString("status").equals("model_skip"));
            JSONObject saved=new JSONObject(row.toString()).put("requestId",id).put("id",target.getString("regionId")).put("sourceSha256",target.getString("sourceSha256"));
            if(accepted){translated.get(page).put(saved);if(row.optBoolean("skip"))skips++;else success++;}else{failures.get(page).put(saved);failed++;}
        }
        require(returned.isEmpty(),"Queue contains unplanned translations");JSONArray pages=new JSONArray();for(int page=1;page<=plan.getInt("pageCount");page++){JSONArray values=translated.getOrDefault(page,new JSONArray()),errors=failures.getOrDefault(page,new JSONArray());Path file=root.resolve("新输入/逐页").resolve(String.format(Locale.ROOT,"第%02d页/真实译文.json",page));JSONObject record=new JSONObject().put("schemaVersion",1).put("page",page).put("actualTextApi",true).put("cachedHistoricalTranslations",false).put("sourceQueueSha256",hash(resultPath)).put("textPlanSha256",hash(text.resolve("text_request_plan.json"))).put("complete",errors.isEmpty()).put("translations",values).put("failures",errors);save(file,record);pages.put(new JSONObject().put("page",page).put("translationSha256",hash(file)).put("received",values.length()).put("failed",errors.length()));}
        save(root.resolve("新输入/文字导出汇总.json"),new JSONObject().put("pageCount",plan.getInt("pageCount")).put("regions",total).put("translated",success).put("modelSkip",skips).put("failed",failed).put("actualApplicationCallsStarted",result.getInt("actualApplicationCallsStarted")).put("billingUnknown",true).put("pages",pages));System.out.println("EXPORTED new text responses: translated="+success+" skip="+skips+" failed="+failed);
    }
    static void selfcheck()throws Exception{
        require(Arrays.equals(workSize(1000,1500),new int[]{1000,1500}),"Ordinary work size");require(Arrays.equals(workSize(2000,3000),new int[]{1067,1600}),"Large page work size");require(Arrays.equals(workSize(800,8000),new int[]{640,6400}),"Long strip work size");int bottom=0;for(int[] window:RtDetrDetector.sliceWindows(640,6400)){require(window[0]<=bottom&&window[1]>bottom,"Slice coverage");bottom=window[1];}require(bottom==6400,"Final slice bottom");Region r=new Region("rt_1",new Rect(11,17,53,71),List.of(new Rect(17,21,30,60)),true,new Rect(8,13,60,75));Region mapped=mapped(r,1000,1500,640,960),again=region(regionJson(mapped,1000,1500));require(Arrays.equals(box(mapped.box),new int[]{17,26,83,111}),"Production coordinate mapping");require(again.id.equals(r.id)&&Arrays.equals(box(again.box),box(mapped.box))&&again.lines.size()==1&&again.vertical&&again.contextBox!=null,"Region JSON roundtrip");System.out.println("WebsiteMangaInput selfcheck passed: work-size, production slicing/mapping, lossless Region schema; synthetic geometry only, no API or sample translations.");
    }
    public static void main(String[] args)throws Exception{String mode=args[0];if(mode.equals("selfcheck")){selfcheck();return;}Path root=Paths.get(args[1]).toAbsolutePath().normalize();if(mode.equals("detect"))detect(root,Paths.get(args[2]).toAbsolutePath().normalize(),Integer.parseInt(args[3]));else if(mode.equals("export"))export(root);else throw new IllegalArgumentException("Unknown mode");}
}
