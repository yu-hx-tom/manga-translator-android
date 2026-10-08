package cn.local.manga;

import android.graphics.Rect;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.*;

/** Uses saved real model predictions, real source pages and production paragraph/cleanup/layout code. */
public final class RtDetrRegionsChecks {
    private static int checks;
    private static void ok(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
    private static String hash(Path path)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));return HexFormat.of().formatHex(digest);}
    private static JSONArray box(Rect b){return new JSONArray(new int[]{b.left,b.top,b.right,b.bottom});}
    static BufferedImage copy(BufferedImage source){BufferedImage image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.drawImage(source,0,0,null);g.dispose();return image;}
    static List<Region> predict(BufferedImage image,JSONObject prediction){
        JSONArray raw=prediction.getJSONArray("boxes"),types=prediction.getJSONArray("box_types"),confidence=prediction.getJSONArray("scores");
        long[] labels=new long[raw.length()];float[][] boxes=new float[raw.length()][4];float[] scores=new float[raw.length()];
        for(int i=0;i<raw.length();i++){String type=types.getString(i);labels[i]=type.equals("bubble")?0:type.equals("text_bubble")?1:type.equals("text_free")?2:-1;scores[i]=confidence.getFloat(i);for(int k=0;k<4;k++)boxes[i][k]=raw.getJSONArray(i).getFloat(k);}
        int w=image.getWidth(),h=image.getHeight();return RtDetrRegions.fromPredictions(w,h,image.getRGB(0,0,w,h,null,0,w),labels,boxes,scores);
    }
    static JSONObject render(BufferedImage source,BufferedImage output,Region region,List<Region> regions,String phrase)throws Exception{
        JSONObject record=new JSONObject().put("id",region.id).put("box",box(region.box)).put("vertical",region.vertical).put("phrase",phrase);
        JSONArray anchors=new JSONArray();for(Rect line:region.lines)anchors.put(box(line));record.put("lines",anchors);
        if(region.lines.isEmpty())return record.put("status","refused").put("reason","no_reliable_ink_anchors");
        Rect roi=RtDetrRegions.renderBounds(region,source.getWidth(),source.getHeight());int w=roi.right-roi.left,h=roi.bottom-roi.top;
        int[][] lines=new int[region.lines.size()][4];int[] edges=new int[lines.length];
        for(int i=0;i<lines.length;i++){Rect r=region.lines.get(i);lines[i]=new int[]{r.left-roi.left,r.top-roi.top,r.right-roi.left,r.bottom-roi.top};edges[i]=Math.min(r.right-r.left,r.bottom-r.top);}
        Arrays.sort(edges);int estimate=edges[edges.length/2];int[] pixels=source.getRGB(roi.left,roi.top,w,h,null,0,w);
        WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(pixels,w,h,lines,estimate);
        int[][] foreign=RtDetrRegions.foreignLines(regions,region.id,roi.left,roi.top);
        if(!mask.whiteBackground)return record.put("status","refused").put("reason","unconfirmed_glyph_cleanup");
        if(WhiteBubbleCleaner.touchesForeignInk(pixels,w,h,mask,foreign))return record.put("status","refused").put("reason","foreign_ink");
        boolean[] area=(mask.whiteBackground||mask.texturedBackground)?mask.interior:WhiteBubbleCleaner.complexTextArea(pixels,w,h,lines);
        if(area==null)return record.put("status","refused").put("reason","unsafe_background");
        area=WhiteBubbleCleaner.excludeForeign(area,w,h,foreign);
        BubbleLayout.Plan plan;
        try{plan=BubbleLayout.plan(phrase,area,w,h,region.vertical,estimate,lines);}
        catch(Exception cannotFit){return record.put("status","refused").put("reason","layout_fit");}
        if(BubbleLayout.touchesForeignLines(plan,foreign))return record.put("status","refused").put("reason","foreign_layout");
        int outside=0;for(int[] cell:plan.cells)for(int y=cell[1];y<cell[3];y++)for(int x=cell[0];x<cell[2];x++)if(x<0||y<0||x>=w||y>=h||!area[y*w+x])outside++;
        ok(outside==0,"all production layout cells stay in safe area");
        int[] erased=pixels.clone();WhiteBubbleCleaner.apply(erased,mask);
        int changesOutside=0;for(int i=0;i<pixels.length;i++)if(pixels[i]!=erased[i]&&!mask.erase[i])changesOutside++;
        ok(changesOutside==0,"cleanup changes only production erase mask");
        // Commit only changed cleanup pixels and clipped cells, preserving earlier neighboring results.
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(mask.erase[y*w+x])output.setRGB(roi.left+x,roi.top+y,erased[y*w+x]);
        Graphics2D g=output.createGraphics();g.setFont(new Font("Microsoft YaHei",Font.PLAIN,plan.font));g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);FontMetrics metrics=g.getFontMetrics();
        for(int i=0;i<plan.cells.length;i++){int[] cell=plan.cells[i];String text=new String(Character.toChars(plan.codepoints[i]));int x=roi.left+(cell[0]+cell[2]-metrics.stringWidth(text))/2,y=roi.top+(cell[1]+cell[3]-metrics.getHeight())/2+metrics.getAscent();
            Shape clip=g.getClip();g.clipRect(roi.left+cell[0],roi.top+cell[1],cell[2]-cell[0],cell[3]-cell[1]);
            if(!mask.whiteBackground){g.setColor(Color.WHITE);for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)g.drawString(text,x+dx,y+dy);}
            g.setColor(Color.BLACK);g.drawString(text,x,y);g.setClip(clip);
        }g.dispose();
        return record.put("status",mask.whiteBackground?"cleaned_layout":"preserved_original_overlay").put("font",plan.font).put("erasedPixels",mask.pixels).put("cellsOutsideSafeArea",outside).put("changesOutsideMask",changesOutside).put("roi",box(roi));
    }
    private static void synthetic()throws Exception{
        int w=200,h=180;BufferedImage source=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=source.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setColor(Color.BLACK);g.drawRect(20,10,115,150);
        for(int x:new int[]{50,88})for(int y=30;y<135;y+=17){g.fillRect(x,y,2,11);g.fillRect(x+8,y,2,11);g.fillRect(x,y+4,10,2);}g.dispose();
        long[] labels={0,1,1,1,2,1,1};float[] scores={.99f,.9f,.8f,.29f,.8f,Float.NaN,.8f};float[][] boxes={{20,10,136,161},{48,28,101,145},{48,28,100,144},{30,20,110,150},{150,20,190,80},{5,5,20,20},{0,0,Float.NaN,20}};
        List<Region> regions=RtDetrRegions.fromPredictions(w,h,source.getRGB(0,0,w,h,null,0,w),labels,boxes,scores);
        ok(regions.size()==2,"bubble/low confidence/NaN removed, same-class duplicate removed, separate candidate kept");
        Region text=regions.stream().filter(r->r.box.left<100).findFirst().get(),blank=regions.stream().filter(r->r.box.left>100).findFirst().get();
        ok(text.lines.size()==2,"two paragraph columns extracted from ink");
        ok(text.lines.stream().allMatch(r->r.right-r.left<=20&&r.right-r.left<(text.box.right-text.box.left)/2),"paragraph rectangle never masquerades as a text column");
        ok(blank.lines.isEmpty(),"blank paragraph carries no fabricated ink anchor");
        JSONObject rendered=render(source,copy(source),text,Collections.singletonList(text),"离线排版测试");
        ok(rendered.getString("status").equals("cleaned_layout"),"closed white bubble remains safely renderable");
        Region other=new Region("unresolved",new Rect(85,25,105,145),Collections.emptyList(),true);
        int[][] excluded=RtDetrRegions.foreignLines(Arrays.asList(text,other),text.id,0,0);
        ok(excluded.length==1&&excluded[0][0]==85,"unresolved neighboring paragraph still protected");
        ok(render(source,copy(source),text,Arrays.asList(text,other),"测试").getString("reason").equals("foreign_ink"),"unresolved paragraph cannot be erased through another region");
        List<Region> oneBubble=RtDetrRegions.fromPredictions(w,h,source.getRGB(0,0,w,h,null,0,w),new long[]{0,0,1,1},new float[][]{{45,25,65,145},{83,25,105,145},{48,28,64,145},{86,28,102,145}},new float[]{.9f,.9f,.9f,.9f});
        ok(oneBubble.size()==1&&oneBubble.get(0).lines.size()==2,"two paragraphs with different model bubble hints merge only through actual same closed paper");
        ok(oneBubble.get(0).contextBox.left==21&&oneBubble.get(0).box.left==48,"paper boundary supplies context without expanding API text crop into whole bubble");
        BufferedImage separated=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);g=separated.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setColor(Color.BLACK);g.drawRect(20,10,65,150);g.drawRect(85,10,65,150);
        for(int x:new int[]{48,110})for(int y=30;y<135;y+=17){g.fillRect(x,y,2,11);g.fillRect(x+8,y,2,11);g.fillRect(x,y+4,10,2);}g.dispose();
        List<Region> separate=RtDetrRegions.fromPredictions(w,h,separated.getRGB(0,0,w,h,null,0,w),new long[]{1,1},new float[][]{{45,28,62,145},{107,28,124,145}},new float[]{.9f,.9f});
        ok(separate.size()==2,"black boundary between adjacent closed bubbles is never merged");
        Region leftRegion=separate.stream().min(Comparator.comparingInt(r->r.box.left)).get(),rightRegion=separate.stream().max(Comparator.comparingInt(r->r.box.left)).get();
        BufferedImage sequential=copy(separated);JSONObject first=render(separated,sequential,leftRegion,separate,"测试"),second;
        ok(first.getString("status").equals("cleaned_layout"),"first overlapping-context region renders");int[] previous=sequential.getRGB(20,10,64,150,null,0,64);
        Rect roiA=RtDetrRegions.renderBounds(leftRegion,w,h),roiB=RtDetrRegions.renderBounds(rightRegion,w,h);ok(roiA.right>roiB.left,"adjacent region analysis contexts overlap in fixture");
        second=render(separated,sequential,rightRegion,separate,"另段");ok(second.getString("status").equals("cleaned_layout"),"second overlapping-context region renders");
        ok(Arrays.equals(previous,sequential.getRGB(20,10,64,150,null,0,64)),"transparent cleanup/text commit preserves earlier translated bubble despite overlapping source context");
        BufferedImage open=copy(separated);g=open.createGraphics();g.setColor(Color.WHITE);g.fillRect(20,10,131,151);g.dispose();
        ok(RtDetrRegions.fromPredictions(w,h,open.getRGB(0,0,w,h,null,0,w),new long[]{1,1},new float[][]{{45,28,62,145},{107,28,124,145}},new float[]{.9f,.9f}).size()==2,"page exterior white cannot merge unrelated paragraph predictions");
        long[] adjacent={1,1};float[][] adjacentBoxes={{10,10,30,80},{31,10,51,80}};float[] good={.9f,.9f};
        ok(RtDetrRegions.fromPredictions(w,h,source.getRGB(0,0,w,h,null,0,w),adjacent,adjacentBoxes,good).size()==2,"adjacent paragraphs never merged");
        long[] invalidLabels={1,1,3,1};float[][] invalidBoxes={{-20,-20,10,10},{20,20,10,40},{20,20,30,30},{Float.POSITIVE_INFINITY,0,30,30}};float[] values={.8f,.8f,.9f,.9f};
        List<Region> clipped=RtDetrRegions.fromPredictions(w,h,source.getRGB(0,0,w,h,null,0,w),invalidLabels,invalidBoxes,values);
        ok(clipped.size()==1&&clipped.get(0).box.left==0&&clipped.get(0).box.top==0,"finite valid boxes clamped; inverted/unknown/infinite rejected");
        Thread.currentThread().interrupt();try{RtDetrRegions.fromPredictions(w,h,source.getRGB(0,0,w,h,null,0,w),adjacent,adjacentBoxes,good);throw new AssertionError("cancel");}
        catch(java.util.concurrent.CancellationException expected){checks++;}finally{Thread.interrupted();}
    }
    public static void main(String[] args)throws Exception{
        Path fixture=Paths.get(args[0]),predictions=Paths.get(args[1]),out=Paths.get(args[2]),sourceCode=Paths.get(args[3]);Files.createDirectories(out);synthetic();
        JSONObject hashes=new JSONObject();for(String name:new String[]{"RtDetrRegions","Region","WhiteBubbleCleaner","BubbleLayout","TranslationEngine"})hashes.put(name,hash(sourceCode.resolve(name+".java")));
        JSONObject totals=new JSONObject();JSONArray pages=new JSONArray();String[] models={"rtdetr_default_v4_s_int8_t4","rtdetr_legacy_r50_fp32_t4","rtdetr_legacy_r50_int8_t4"};
        StringBuilder html=new StringBuilder("<!doctype html><meta charset='utf-8'><title>RT-DETR段落与安全回填检查</title><style>body{font-family:sans-serif;background:#eee}section{display:flex;gap:8px}img{width:32%;object-fit:contain}p{max-width:1100px}</style><h1>三模型×十页：段落与安全回填</h1><p>使用已保存的真实模型预测、原始页，以及生产Java拆行/清字/排版。中文为固定离线测试句，不是翻译。红框是段落，蓝框是墨迹锚点；无法确认背景、布局或其他段保护时保留原图。不是手机截图或召回率评测。</p>");
        for(String model:models){Path directory=out.resolve(model);Files.createDirectories(directory);int cleaned=0,overlay=0,refused=0,paragraphs=0,empty=0;
            for(int page=1;page<=10;page++){String stem=String.format(Locale.ROOT,"page%02d",page);Path input=fixture.resolve(String.format(Locale.ROOT,"样本%02d.png",page));BufferedImage image=ImageIO.read(input.toFile());
                JSONObject prediction=new JSONObject(Files.readString(predictions.resolve(model).resolve(stem+".json")));
                if(prediction.has("input_sha256"))ok(prediction.getString("input_sha256").equals(hash(input)),"prediction/source image hash matches");
                List<Region> regions=predict(image,prediction);BufferedImage marked=copy(image),rendered=copy(image);Graphics2D g=marked.createGraphics();JSONArray entries=new JSONArray();
                for(Region region:regions){paragraphs++;if(region.lines.isEmpty())empty++;Rect b=region.box;g.setColor(Color.RED);g.drawRect(b.left,b.top,b.right-b.left,b.bottom-b.top);g.setColor(Color.BLUE);for(Rect line:region.lines)g.drawRect(line.left,line.top,line.right-line.left,line.bottom-line.top);
                    JSONObject record=render(image,rendered,region,regions,"这是离线排版测试。");
                    if("layout_fit".equals(record.optString("reason")))record.put("shortTextTrial",render(image,copy(image),region,regions,"测试"));
                    entries.put(record);String status=record.getString("status");if(status.equals("cleaned_layout"))cleaned++;else if(status.equals("preserved_original_overlay"))overlay++;else refused++;
                }g.dispose();ImageIO.write(image,"png",directory.resolve(stem+"_original.png").toFile());ImageIO.write(marked,"png",directory.resolve(stem+"_anchors.png").toFile());ImageIO.write(rendered,"png",directory.resolve(stem+"_layout.png").toFile());
                JSONObject record=new JSONObject().put("model",model).put("page",page).put("inputSha256",hash(input)).put("paragraphs",entries);pages.put(record);Files.writeString(directory.resolve(stem+".json"),record.toString(2));
                html.append("<h2>").append(model).append(" · ").append(page).append("</h2><section>");for(String kind:new String[]{"original","anchors","layout"})html.append("<img src='").append(model).append('/').append(stem).append('_').append(kind).append(".png'>");html.append("</section>");
            }
            totals.put(model,new JSONObject().put("paragraphs",paragraphs).put("cleanedLayout",cleaned).put("preservedOriginalOverlay",overlay).put("refused",refused).put("unresolvedAnchors",empty));
        }
        for(String name:hashes.keySet())ok(hashes.getString(name).equals(hash(sourceCode.resolve(name+".java"))),"production source unchanged during host run");
        JSONObject report=new JSONObject().put("passed",true).put("checks",checks).put("sourceSha256",hashes).put("totals",totals).put("pages",pages).put("paidApiUsed",false).put("androidRuntimeVerified",false).put("scope","Saved real predictions on 10 original pages per model; production Java paragraph, cleanup and cell-layout; fixed offline phrase and desktop font, not real translation or accuracy metric");
        Files.writeString(out.resolve("结果.json"),report.toString(2));Files.writeString(out.resolve("对照.html"),html.toString());System.out.println(totals.toString(2));System.out.println("RtDetrRegionsChecks: "+checks+" checks passed; 30 saved-prediction pages, no API or model inference");
    }
}
