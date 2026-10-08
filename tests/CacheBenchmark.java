package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Desktop stage benchmark. Real production detector/cache; only Android pixel APIs use AWT. */
public final class CacheBenchmark {
    static double elapsed(long start) { return (System.nanoTime()-start)/1e6; }
    static JSONArray rect(Rect r) { return new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}); }
    static String canonical(List<Region> regions) {
        JSONArray result=new JSONArray();
        for(Region r:regions) { JSONArray lines=new JSONArray();for(Rect l:r.lines)lines.put(rect(l));
            result.put(new JSONArray().put(r.id).put(rect(r.box)).put(r.vertical).put(lines)
                    .put(r.contextBox==null?JSONObject.NULL:rect(r.contextBox))); }
        return result.toString();
    }
    static JSONObject detect(String version,Detector detector,Bitmap image,File cacheDir) throws Exception {
        long start=System.nanoTime();
        List<Region> result;String hash=null;double hashMs=0,readMs=0,inferenceMs=0,writeMs=0;
        boolean hit=false;
        if(version.equals("0.7.2")) { long t=System.nanoTime();result=detector.detect(image);inferenceMs=elapsed(t); }
        else {
            DetectionCache cache=new DetectionCache(cacheDir);
            long t=System.nanoTime();hash=DetectionCache.contentHash(image,()->false);hashMs=elapsed(t);
            t=System.nanoTime();result=cache.read(hash,DetectorModels.PP_ID,image.getWidth(),image.getHeight(),()->false);readMs=elapsed(t);
            hit=result!=null;
            if(!hit) { t=System.nanoTime();result=detector.detect(image);inferenceMs=elapsed(t);
                t=System.nanoTime();cache.write(hash,DetectorModels.PP_ID,image.getWidth(),image.getHeight(),result,()->false);writeMs=elapsed(t); }
        }
        double totalMs=elapsed(start);
        return new JSONObject().put("version",version).put("cacheHit",hit).put("totalMs",totalMs)
                .put("hashMs",hashMs).put("cacheReadMs",readMs).put("fullPlusFourTileDetectionMs",inferenceMs)
                .put("cacheWriteMs",writeMs).put("regions",result.size()).put("resultCanonical",canonical(result));
    }
    static void write(BufferedWriter writer,JSONObject row)throws Exception { writer.write(row.toString());writer.newLine();writer.flush(); }
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        List<Path> pages;
        try(var paths=Files.list(input)){pages=paths.filter(Files::isDirectory).filter(p->Files.isRegularFile(p.resolve("原图.png"))).sorted().toList();}
        int rounds=Integer.parseInt(args[3]),limit=Integer.parseInt(args[4]);
        if(limit>0&&limit<pages.size()){List<Path> picked=new ArrayList<>();for(int i=0;i<limit;i++)picked.add(pages.get(i*(pages.size()-1)/(limit-1)));pages=picked;}
        JSONObject metadata=new JSONObject().put("pages",pages.size()).put("rounds",rounds).put("warmupPagesPerVersion",3)
                .put("javaVersion",System.getProperty("java.version")).put("os",System.getProperty("os.name"))
                .put("availableProcessors",Runtime.getRuntime().availableProcessors()).put("maxHeapBytes",Runtime.getRuntime().maxMemory())
                .put("scope","Unmodified production Detector.java + real Windows ONNX CPU runtime, 4 intra-op threads; production DetectionCache includes ARGB contentHash. Desktop AWT replaces Android Bitmap/Canvas. No HTTP/API/WebView/end-to-end Android timing.")
                .put("diskColdDefinition","New empty application cache directory per page and round. OS filesystem cache is not flushed.")
                .put("modelColdDefinition","Model initialization is recorded separately; 3 pages warm up each model before measured samples.");
        long t=System.nanoTime();Detector old=new Detector(new Context(new File(args[2])));metadata.put("modelInit072Ms",elapsed(t));
        t=System.nanoTime();Detector current=new Detector(new Context(new File(args[2])));metadata.put("modelInit073Ms",elapsed(t));
        try(old;current;BufferedWriter raw=Files.newBufferedWriter(out.resolve("raw.jsonl"),StandardCharsets.UTF_8)){
            for(int i=0;i<3;i++) { Bitmap bitmap=new Bitmap(ImageIO.read(pages.get(i).resolve("原图.png").toFile()));
                old.detect(bitmap);current.detect(bitmap);bitmap.recycle(); }
            Files.writeString(out.resolve("metadata.json"),metadata.toString(2),StandardCharsets.UTF_8);
            int count=0;
            for(int round=0;round<rounds;round++)for(int index=0;index<pages.size();index++) {
                Path page=pages.get(index);t=System.nanoTime();Bitmap image=new Bitmap(ImageIO.read(page.resolve("原图.png").toFile()));double decodeMs=elapsed(t);
                File cache=out.resolve("cache/round-"+round+"-page-"+index).toFile();
                if(cache.exists())throw new IOException("Use a fresh output directory: "+cache);
                JSONObject before,after;boolean reverse=(round+index)%2==1;
                if(reverse){after=detect("0.7.3",current,image,cache);before=detect("0.7.2",old,image,cache);}
                else {before=detect("0.7.2",old,image,cache);after=detect("0.7.3",current,image,cache);}
                String expected=before.getString("resultCanonical");
                if(!expected.equals(after.getString("resultCanonical")))throw new AssertionError("Detector mismatch "+page);
                for(JSONObject row:new JSONObject[]{before,after}) {
                    row.remove("resultCanonical");row.put("scenario","first_visit").put("round",round).put("page",page.getFileName().toString())
                        .put("width",image.getWidth()).put("height",image.getHeight()).put("sourceDecodeMsExcluded",decodeMs).put("newVersionRanFirst",reverse).put("equalRegions",true);
                    write(raw,row);
                }
                // The already executed 0.7.2 detector run is also its repeat-visit detector path: it has no detection cache.
                for(int repeat=0;repeat<3;repeat++) {
                    JSONObject hit=detect("0.7.3",current,image,cache);
                    if(!hit.getBoolean("cacheHit")||!expected.equals(hit.getString("resultCanonical")))throw new AssertionError("Cache mismatch "+page);
                    hit.remove("resultCanonical");hit.put("scenario","repeat_visit").put("round",round).put("repeat",repeat)
                        .put("page",page.getFileName().toString()).put("width",image.getWidth()).put("height",image.getHeight()).put("equalRegions",true);write(raw,hit);
                }
                image.recycle();System.out.println("page "+(++count)+"/"+(rounds*pages.size())+" old="+String.format(Locale.ROOT,"%.1f",before.getDouble("totalMs"))+"ms newMiss="+String.format(Locale.ROOT,"%.1f",after.getDouble("totalMs"))+"ms");
            }
        }
    }
}
