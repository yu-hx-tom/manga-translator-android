package cn.local.manga;

import android.graphics.Rect;
import org.json.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Offline audit of the 30 original pages with their saved detector anchors; never calls an API. */
public final class V090MaskAudit {
    static Rect rect(JSONObject b){int x=b.getInt("x"),y=b.getInt("y");return new Rect(x,y,x+b.getInt("width"),y+b.getInt("height"));}
    static int gray(int c){return (((c>>>16)&255)*299+((c>>>8)&255)*587+(c&255)*114)/1000;}
    static boolean neutral(int c){int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;return Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=20;}
    static JSONObject statistics(int[] pixels,int w,int h,int[][] lines){
        boolean[] scope=new boolean[pixels.length];int count=0,dark160=0,dark225=0,white225=0,white245=0,colored=0,pairs=0,edges=0;
        for(int[] r:lines)for(int y=Math.max(0,r[1]);y<Math.min(h,r[3]);y++)for(int x=Math.max(0,r[0]);x<Math.min(w,r[2]);x++){
            int p=y*w+x;if(scope[p])continue;scope[p]=true;count++;int v=gray(pixels[p]);
            if(v<160)dark160++;if(v<225)dark225++;if(v>=225)white225++;if(v>=245)white245++;if(!neutral(pixels[p]))colored++;
        }
        for(int y=0;y<h-1;y++)for(int x=0;x<w-1;x++){int p=y*w+x;if(!scope[p]||gray(pixels[p])<160)continue;
            for(int q:new int[]{p+1,p+w})if(scope[q]&&gray(pixels[q])>=160){pairs++;if(Math.abs(gray(pixels[p])-gray(pixels[q]))>18)edges++;}}
        return new JSONObject().put("scopePixels",count).put("white225",(double)white225/Math.max(1,count)).put("white245",(double)white245/Math.max(1,count))
            .put("dark160",dark160).put("dark225",dark225).put("colored",(double)colored/Math.max(1,count)).put("paperEdgeRatio",(double)edges/Math.max(1,pairs));
    }
    static BufferedImage image(int[] p,int w,int h){BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,w,h,p,0,w);return b;}
    static List<Region> predict(BufferedImage image,JSONObject prediction){
        JSONArray raw=prediction.getJSONArray("boxes"),types=prediction.getJSONArray("box_types"),confidence=prediction.getJSONArray("scores");
        long[] labels=new long[raw.length()];float[][] boxes=new float[raw.length()][4];float[] scores=new float[raw.length()];
        for(int i=0;i<raw.length();i++){String type=types.getString(i);labels[i]=type.equals("bubble")?0:type.equals("text_bubble")?1:type.equals("text_free")?2:-1;scores[i]=confidence.getFloat(i);for(int k=0;k<4;k++)boxes[i][k]=raw.getJSONArray(i).getFloat(k);}
        int w=image.getWidth(),h=image.getHeight();return RtDetrRegions.fromPredictions(w,h,image.getRGB(0,0,w,h,null,0,w),labels,boxes,scores);
    }
    static JSONObject optionalKind(WhiteBubbleCleaner.Mask mask){JSONObject result=new JSONObject();for(String name:new String[]{"backgroundKind","evidence"})try{var f=mask.getClass().getDeclaredField(name);f.setAccessible(true);result.put(name,String.valueOf(f.get(mask)));}catch(Exception ignored){}return result;}
    static JSONArray recovery(int[] pixels,int w,int h,int[][] lines,WhiteBubbleCleaner.Mask mask){JSONArray a=new JSONArray();
        for(int[] r:lines){int iw=Math.max(1,(r[2]-r[0])/5),ih=Math.max(1,(r[3]-r[1])/10),dark=0,removed=0;
            for(int y=Math.max(0,r[1]+ih);y<Math.min(h,r[3]-ih);y++)for(int x=Math.max(0,r[0]+iw);x<Math.min(w,r[2]-iw);x++){int p=y*w+x;if(bright(pixels[p])<160){dark++;if(mask.erase[p])removed++;}}
            a.put(new JSONObject().put("dark",dark).put("removed",removed));}return a;}
    static int bright(int c){return gray(c);}
    static void writeVisual(Path out,String key,int[] pixels,int w,int h,int[][] lines,WhiteBubbleCleaner.Mask mask,BeforeWhiteBubbleCleaner.Mask before)throws Exception{
        BufferedImage original=image(pixels,w,h),annotated=image(pixels,w,h),overlay=image(pixels,w,h);int[] clean=pixels.clone();WhiteBubbleCleaner.apply(clean,mask);
        Graphics2D a=annotated.createGraphics();a.setColor(Color.GREEN);a.setStroke(new BasicStroke(1));for(int[] r:lines)a.drawRect(r[0],r[1],r[2]-r[0],r[3]-r[1]);a.dispose();
        for(int p=0;p<pixels.length;p++)if(mask.erase[p])overlay.setRGB(p%w,p/w,0xffee3344);else if(before.erase[p])overlay.setRGB(p%w,p/w,0xffffcc33);
        Files.createDirectories(out.resolve(key));ImageIO.write(original,"png",out.resolve(key+"/before.png").toFile());ImageIO.write(annotated,"png",out.resolve(key+"/anchors.png").toFile());
        ImageIO.write(overlay,"png",out.resolve(key+"/mask.png").toFile());ImageIO.write(image(clean,w,h),"png",out.resolve(key+"/after.png").toFile());
    }
    public static void main(String[] args)throws Exception{
        Path input=Paths.get(args[0]),out=Paths.get(args[1]);Files.createDirectories(out);Path cuts=out.resolve("裁切");Files.createDirectories(cuts);
        Set<String> fallback=new HashSet<>();Map<String,String> oldFailure=new HashMap<>();
        for(Object raw:new JSONArray(Files.readString(input.resolve("兜底位置清单.json")))){JSONObject f=(JSONObject)raw;String key=String.format("p%02d_",f.getInt("page"))+f.getString("id");fallback.add(key);oldFailure.put(key,f.optString("normalFailure"));}
        JSONArray records=new JSONArray();int total=0,baselineValid=0,newValid=0,gained=0,lost=0,foreignHits=0;long started=System.nanoTime();
        var strict=BeforeWhiteBubbleCleaner.class.getDeclaredMethod("findAtThreshold",int[].class,int.class,int.class,int[][].class,int.class,int.class);strict.setAccessible(true);
        var local=BeforeWhiteBubbleCleaner.class.getDeclaredMethod("findLocalWhiteText",int[].class,int.class,int.class,int[][].class,int.class,BeforeWhiteBubbleCleaner.Mask.class);local.setAccessible(true);
        StringBuilder html=new StringBuilder("<!doctype html><meta charset='utf-8'><title>0.9 去字审查</title><style>body{font:16px sans-serif}section{display:flex;gap:5px}img{max-width:31%;image-rendering:auto;object-fit:contain;max-height:600px;border:1px solid #bbb}</style><h1>原图与锚框／掩膜／去字结果</h1>");
        for(int page=1;page<=30;page++){
            Path dir=input.resolve("逐页").resolve(String.format("第%02d页",page));BufferedImage source=ImageIO.read(dir.resolve("原图.png").toFile());List<Region> regions=new ArrayList<>();
            for(Object raw:new JSONObject(Files.readString(dir.resolve("段落检测.json"))).getJSONArray("regions")){
                JSONObject r=(JSONObject)raw;List<Rect> lines=new ArrayList<>();for(Object item:r.getJSONArray("lines"))lines.add(rect((JSONObject)item));
                regions.add(new Region(r.getString("id"),rect(r.getJSONObject("box")),lines,r.optBoolean("vertical"),r.has("contextBox")?rect(r.getJSONObject("contextBox")):null));
            }
            if(args.length>2&&args[2].equals("predict"))regions=predict(source,new JSONObject(Files.readString(dir.resolve("检测原始结果.json"))));
            for(Region region:regions){
                String key=String.format("p%02d_",page)+region.id;JSONObject record=new JSONObject().put("key",key).put("page",page).put("id",region.id).put("wasFallback",fallback.contains(key)).put("oldFailure",oldFailure.getOrDefault(key,""));total++;
                int bw=region.box.right-region.box.left,bh=region.box.bottom-region.box.top;
                if(bw>0&&bh>0){WhiteBubbleCleaner.Mask surface=WhiteBubbleCleaner.classifyOnly(source.getRGB(region.box.left,region.box.top,bw,bh,null,0,bw),bw,bh,new int[][]{{0,0,bw,bh}});record.put("surfaceClassification",optionalKind(surface));}
                if(region.lines.isEmpty()){record.put("noLines",true);records.put(record);continue;}
                Rect roi=RtDetrRegions.renderBounds(region,source.getWidth(),source.getHeight());int w=roi.right-roi.left,h=roi.bottom-roi.top;int[][] lines=new int[region.lines.size()][4];int[] sizes=new int[lines.length];
                for(int i=0;i<lines.length;i++){Rect b=region.lines.get(i);lines[i]=new int[]{b.left-roi.left,b.top-roi.top,b.right-roi.left,b.bottom-roi.top};sizes[i]=Math.min(b.right-b.left,b.bottom-b.top);}Arrays.sort(sizes);int estimate=sizes[sizes.length/2];
                int[] pixels=source.getRGB(roi.left,roi.top,w,h,null,0,w);BeforeWhiteBubbleCleaner.Mask before=BeforeWhiteBubbleCleaner.forText(pixels,w,h,lines,estimate);
                WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(pixels,w,h,lines,estimate);int[][] foreign=RtDetrRegions.foreignLines(regions,region.id,roi.left,roi.top);
                boolean touches=WhiteBubbleCleaner.touchesForeignInk(pixels,w,h,mask,foreign);if(touches)foreignHits++;
                boolean[] protectedErase=WhiteBubbleCleaner.excludeForeign(mask.erase,w,h,foreign);int protectedPixels=0;for(boolean p:protectedErase)if(p)protectedPixels++;
                WhiteBubbleCleaner.Mask protectedMask=new WhiteBubbleCleaner.Mask(protectedErase,mask.interior,mask.whiteBackground,protectedPixels,mask.fillColors,mask.texturedBackground,mask.glyphCandidate,mask.backgroundKind,mask.evidence);
                boolean protectedTouches=WhiteBubbleCleaner.touchesForeignInk(pixels,w,h,protectedMask,foreign);
                if(before.whiteBackground)baselineValid++;if(mask.whiteBackground)newValid++;if(!before.whiteBackground&&mask.whiteBackground)gained++;if(before.whiteBackground&&!mask.whiteBackground)lost++;
                record.put("roi",new JSONObject().put("x",roi.left).put("y",roi.top).put("width",w).put("height",h)).put("estimate",estimate).put("lines",new JSONArray(lines))
                    .put("beforeValid",before.whiteBackground).put("beforeTextured",before.texturedBackground).put("afterValid",mask.whiteBackground).put("afterTextured",mask.texturedBackground)
                    .put("beforePixels",before.pixels).put("afterPixels",mask.pixels).put("touchesForeign",touches).put("protectedMaskPixels",protectedPixels).put("protectedTouchesForeign",protectedTouches)
                    .put("integratedLocalCandidate",mask.whiteBackground&&mask.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER&&protectedPixels>0&&!protectedTouches)
                    .put("metrics",statistics(pixels,w,h,lines)).put("classification",optionalKind(mask)).put("recovery",recovery(pixels,w,h,lines,mask));
                if(fallback.contains(key)||!before.whiteBackground){
                    BeforeWhiteBubbleCleaner.Mask white=(BeforeWhiteBubbleCleaner.Mask)strict.invoke(null,pixels,w,h,lines,estimate,225);
                    record.put("strictDiagnostic",BeforeWhiteBubbleCleaner.diagnostic);
                    BeforeWhiteBubbleCleaner.Mask gray=BeforeWhiteBubbleCleaner.find(pixels,w,h,lines,estimate);
                    record.put("paperDiagnostic",BeforeWhiteBubbleCleaner.diagnostic);
                    BeforeWhiteBubbleCleaner.Mask open=(BeforeWhiteBubbleCleaner.Mask)local.invoke(null,pixels,w,h,lines,estimate,gray);
                    record.put("openDiagnostic",BeforeWhiteBubbleCleaner.diagnostic);
                    record.put("strictWhite",white.whiteBackground).put("paperPass",gray.whiteBackground).put("paperTextured",gray.texturedBackground).put("openWhite",open.whiteBackground);
                }
                if(fallback.contains(key)||before.whiteBackground!=mask.whiteBackground||touches){
                    writeVisual(cuts,key,pixels,w,h,lines,mask,before);html.append("<h2>").append(key).append(" — ").append(before.whiteBackground).append(" → ").append(mask.whiteBackground).append(" ").append(optionalKind(mask)).append("</h2><section><img src='裁切/").append(key).append("/anchors.png'><img src='裁切/").append(key).append("/mask.png'><img src='裁切/").append(key).append("/after.png'></section>");
                }
                records.put(record);
            }
            source.flush();System.out.println("page "+page+" audited");
        }
        JSONObject result=new JSONObject().put("total",total).put("baselineValid",baselineValid).put("newValid",newValid).put("gained",gained).put("lost",lost).put("foreignInkHits",foreignHits)
            .put("elapsedMs",(System.nanoTime()-started)/1e6).put("paidApiUsed",false).put("regions",records);
        Files.writeString(out.resolve("结果.json"),result.toString(2));Files.writeString(out.resolve("对照.html"),html.toString());System.out.println(result.toString().substring(0,Math.min(300,result.toString().length())));
    }
}
