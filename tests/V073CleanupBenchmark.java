package cn.local.manga;

import android.graphics.Rect;
import org.json.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Real page pixels, the unchanged 0.7.2 cleanup algorithm, and the actual 0.7.3 disk mask handoff. */
public final class V073CleanupBenchmark {
    static final class Cut {
        int w,h,estimate,index;int[][] lines;int[] pixels;
    }
    static Rect rect(JSONObject b){int x=b.getInt("x"),y=b.getInt("y");return new Rect(x,y,x+b.getInt("width"),y+b.getInt("height"));}
    static List<Cut> cuts(Path page)throws Exception{
        BufferedImage image=ImageIO.read(page.resolve("原图.png").toFile());List<Cut> cuts=new ArrayList<>();
        JSONArray regions=new JSONObject(Files.readString(page.resolve("段落检测.json"))).getJSONArray("regions");
        for(int i=0;i<regions.length();i++){
            JSONObject r=regions.getJSONObject(i);List<Rect> lines=new ArrayList<>();for(Object b:r.getJSONArray("lines"))lines.add(rect((JSONObject)b));
            Region region=new Region(r.getString("id"),rect(r.getJSONObject("box")),lines,r.optBoolean("vertical"),r.has("contextBox")?rect(r.getJSONObject("contextBox")):null);
            Rect roi=RtDetrRegions.renderBounds(region,image.getWidth(),image.getHeight());Cut cut=new Cut();cut.w=roi.right-roi.left;cut.h=roi.bottom-roi.top;cut.index=i;
            if(cut.w<=0||cut.h<=0||(long)cut.w*cut.h>4_000_000)continue;
            List<int[]> local=new ArrayList<>();List<Integer> edges=new ArrayList<>();
            for(Rect b:lines){int x=Math.max(0,b.left-roi.left),y=Math.max(0,b.top-roi.top),right=Math.min(cut.w,b.right-roi.left),bottom=Math.min(cut.h,b.bottom-roi.top);
                if(right>x&&bottom>y){local.add(new int[]{x,y,right,bottom});edges.add(Math.min(right-x,bottom-y));}}
            if(edges.isEmpty())continue;Collections.sort(edges);cut.estimate=edges.get(edges.size()/2);cut.lines=local.toArray(new int[0][]);
            cut.pixels=image.getRGB(roi.left,roi.top,cut.w,cut.h,null,0,cut.w);cuts.add(cut);
        }
        image.flush();return cuts;
    }
    static long signature(WhiteBubbleCleaner.Mask mask){
        long result=17;result=31*result+Arrays.hashCode(mask.erase);result=31*result+Arrays.hashCode(mask.interior);result=31*result+Arrays.hashCode(mask.fillColors);result=31*result+Arrays.hashCode(mask.glyphCandidate);
        return 31*result+(mask.whiteBackground?1:0)+(mask.texturedBackground?2:0)+mask.pixels;
    }
    static double ms(long start){return(System.nanoTime()-start)/1e6;}
    static JSONObject oldRun(List<Cut> cuts,List<Long> signatures){
        double total=0;for(Cut c:cuts){long at=System.nanoTime();WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(c.pixels,c.w,c.h,c.lines,c.estimate);total+=ms(at);signatures.add(signature(mask));}
        return new JSONObject().put("replyTailMaskMs",total).put("allMaskWorkMs",total);
    }
    static JSONObject newRun(List<Cut> cuts,List<Long> signatures,Path output)throws Exception{
        Path directory=Files.createTempDirectory(output,"mask-job-");CleanupPlan cache=new CleanupPlan(directory.toFile());
        double prepare=0,tail=0;int eligible=0,hits=0;long written=0;
        try{
            for(Cut c:cuts){if((long)c.w*c.h>CleanupPlan.MAX_PIXELS)continue;eligible++;long at=System.nanoTime();
                WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(c.pixels,c.w,c.h,c.lines,c.estimate);cache.save(c.index,c.w,c.h,mask,()->false);prepare+=ms(at);}
            try(var files=Files.list(directory)){for(Path f:files.toList())written+=Files.size(f);}
            cache.startRendering();
            for(Cut c:cuts){long at=System.nanoTime();WhiteBubbleCleaner.Mask mask=cache.load(c.index,c.w,c.h,()->false);if(mask==null)mask=WhiteBubbleCleaner.forText(c.pixels,c.w,c.h,c.lines,c.estimate);else hits++;tail+=ms(at);signatures.add(signature(mask));}
        }finally{cache.close();}
        return new JSONObject().put("precomputeAndWriteMs",prepare).put("replyTailMaskMs",tail).put("allMaskWorkMs",prepare+tail).put("eligible",eligible).put("hits",hits).put("maskBytes",written);
    }
    static JSONObject stats(List<Double> values){List<Double> sorted=new ArrayList<>(values);Collections.sort(sorted);double sum=sorted.stream().mapToDouble(v->v).sum();int n=sorted.size();return new JSONObject().put("count",n).put("mean",sum/n).put("median",n%2==1?sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2).put("p95",sorted.get(Math.min(n-1,(int)Math.ceil(n*.95)-1))).put("total",sum);}
    public static void main(String[] args)throws Exception{
        Path input=Paths.get(args[0]),output=Paths.get(args[1]),source=Paths.get(args[2]);Files.createDirectories(output);
        JSONObject published=new JSONObject(Files.readString(Paths.get(args[3]))),hashes=published.getJSONObject("sourceSha256");
        JSONObject verified=new JSONObject();for(String file:new String[]{"WhiteBubbleCleaner.java","GrayGlyphRepair.java","RtDetrRegions.java"}){String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.resolve(file))));if(!hash.equals(hashes.getString(file)))throw new AssertionError("0.7.2 source mismatch: "+file);verified.put(file,hash);}
        List<Path> pages;try(var dirs=Files.list(input)){pages=dirs.filter(Files::isDirectory).sorted().toList();}
        for(Path p:pages.subList(0,Math.min(3,pages.size()))){List<Cut> cuts=cuts(p);oldRun(cuts,new ArrayList<>());newRun(cuts,new ArrayList<>(),output);}
        JSONArray samples=new JSONArray();List<Double> old=new ArrayList<>(),tail=new ArrayList<>(),prep=new ArrayList<>(),total=new ArrayList<>();int regions=0,hits=0;long disk=0;
        for(int round=0;round<3;round++)for(int page=0;page<pages.size();page++){
            List<Cut> cuts=cuts(pages.get(page));List<Long> expected=new ArrayList<>(),actual=new ArrayList<>();JSONObject a,b;
            if((round+page)%2==0){a=oldRun(cuts,expected);b=newRun(cuts,actual,output);}else{b=newRun(cuts,actual,output);a=oldRun(cuts,expected);}
            if(!expected.equals(actual))throw new AssertionError("mask mismatch "+pages.get(page));
            JSONObject sample=new JSONObject().put("round",round+1).put("page",pages.get(page).getFileName().toString()).put("regions",cuts.size()).put("old072",a).put("new073",b);samples.put(sample);
            old.add(a.getDouble("replyTailMaskMs"));tail.add(b.getDouble("replyTailMaskMs"));prep.add(b.getDouble("precomputeAndWriteMs"));total.add(b.getDouble("allMaskWorkMs"));regions+=cuts.size();hits+=b.getInt("hits");disk+=b.getLong("maskBytes");
            System.out.printf(Locale.ROOT,"round %d page %d: old %.2f ms, ready %.2f ms, new total %.2f ms%n",round+1,page+1,a.getDouble("replyTailMaskMs"),b.getDouble("replyTailMaskMs"),b.getDouble("allMaskWorkMs"));
        }
        JSONObject result=new JSONObject().put("pages",pages.size()).put("rounds",3).put("samples",samples).put("sourceMatches072Release",verified).put("regionsAcrossRounds",regions).put("cacheHitsAcrossRounds",hits).put("maskBytesAcrossRounds",disk)
            .put("old072ReplyTailMaskMs",stats(old)).put("new073ReadyReplyTailMaskMs",stats(tail)).put("new073PrecomputeMs",stats(prep)).put("new073AllMaskWorkMs",stats(total))
            .put("maskDecisionsAndArraysEqual",true).put("java",System.getProperty("java.runtime.version")).put("processors",Runtime.getRuntime().availableProcessors()).put("paidApiUsed",false).put("androidRuntimeVerified",false)
            .put("scope","Actual unchanged 0.7.2 WhiteBubbleCleaner on real page crops versus actual 0.7.3 CleanupPlan save/reload. Pixel decoding excluded equally. New reply tail assumes precompute has finished before the reply; not an end-to-end phone speedup. ROI geometry uses production renderBounds. Fonts, WebView, layout, API waits and model inference excluded.");
        Files.writeString(output.resolve("cleanup_benchmark.json"),result.toString(2));System.out.println(result.toString(2).substring(0,Math.min(200,result.toString(2).length())));
    }
}
