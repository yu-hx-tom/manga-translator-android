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

/** Cached real translations and production geometry, with desktop-only glyph rasterization. No network. */
public final class V090PlainRenderingChecks {
    static JSONObject render(BufferedImage source,List<Region> regions,Region region,String text,Path out)throws Exception{
        JSONObject result=new JSONObject().put("id",region.id).put("zh",text).put("glyphCount",text.replaceAll("\\s+","").codePointCount(0,text.replaceAll("\\s+","").length()));
        Rect roi=RtDetrRegions.renderBounds(region,source.getWidth(),source.getHeight());int w=roi.right-roi.left,h=roi.bottom-roi.top;
        int[] original=source.getRGB(roi.left,roi.top,w,h,null,0,w),cleaned=original.clone();BufferedImage before=V090MaskAudit.image(original,w,h),after=V090MaskAudit.image(original,w,h),filled=V090MaskAudit.image(original,w,h);
        result.put("roi",new JSONArray(new int[]{roi.left,roi.top,roi.right,roi.bottom}));
        String failure=null;BubbleLayout.Plan plan=null;boolean[] safe=null;WhiteBubbleCleaner.Mask mask=null;
        if(region.lines.isEmpty())failure="no_reliable_anchors";
        else{
            int[][] lines=new int[region.lines.size()][4];int[] sizes=new int[lines.length];
            for(int i=0;i<lines.length;i++){Rect b=region.lines.get(i);lines[i]=new int[]{b.left-roi.left,b.top-roi.top,b.right-roi.left,b.bottom-roi.top};sizes[i]=Math.min(b.right-b.left,b.bottom-b.top);}Arrays.sort(sizes);int estimate=sizes[sizes.length/2];
            mask=WhiteBubbleCleaner.forText(original,w,h,lines,estimate);int[][] foreign=RtDetrRegions.foreignLines(regions,region.id,roi.left,roi.top);
            boolean[] erase=WhiteBubbleCleaner.excludeForeign(mask.erase,w,h,foreign);int erased=0;for(boolean p:erase)if(p)erased++;
            result.put("maskValidBeforeForeignExclusion",mask.whiteBackground).put("maskPixelsBeforeForeignExclusion",mask.pixels).put("maskPixelsAfterForeignExclusion",erased).put("kind",mask.backgroundKind.name()).put("evidence",mask.evidence).put("lines",new JSONArray(lines)).put("estimate",estimate);
            mask=new WhiteBubbleCleaner.Mask(erase,mask.interior,mask.whiteBackground,erased,mask.fillColors,mask.texturedBackground,mask.glyphCandidate,mask.backgroundKind,mask.evidence);
            if(WhiteBubbleCleaner.touchesForeignInk(original,w,h,mask,foreign))failure="foreign_ink";
            else if(!mask.whiteBackground||mask.backgroundKind!=WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER)failure="unconfirmed_plain_cleanup";
            else if(mask.pixels==0)failure="all_ink_protected";
            else{
                safe=WhiteBubbleCleaner.excludeForeign(mask.interior,w,h,foreign);
                try{plan=BubbleLayout.plan(text,safe,w,h,region.vertical,estimate,lines);}
                catch(Exception e){failure="layout_fit";result.put("error",e.getMessage());}
                if(plan!=null){
                    if(BubbleLayout.touchesForeignLines(plan,foreign))throw new AssertionError("Foreign paragraph touched: "+region.id);
                    for(int[] cell:plan.cells)for(int y=cell[1];y<cell[3];y++)for(int x=cell[0];x<cell[2];x++)if(!safe[y*w+x])throw new AssertionError("Layout escaped safe interior");
                    WhiteBubbleCleaner.apply(cleaned,mask);after=V090MaskAudit.image(cleaned,w,h);filled=V090MaskAudit.image(cleaned,w,h);
                    for(int p=0;p<original.length;p++)if(cleaned[p]!=original[p]&&!mask.erase[p])throw new AssertionError("Cleanup escaped glyph mask");
                    Graphics2D g=filled.createGraphics();Font primary=new Font("Microsoft YaHei",Font.PLAIN,plan.font),symbols=new Font("Segoe UI Symbol",Font.PLAIN,plan.font);g.setColor(Color.BLACK);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    for(int i=0;i<plan.cells.length;i++){int[] cell=plan.cells[i];String glyph=new String(Character.toChars(plan.codepoints[i]));Font selected=primary.canDisplay(plan.codepoints[i])?primary:symbols;if(!selected.canDisplay(plan.codepoints[i]))throw new AssertionError("Desktop preview font missing U+"+Integer.toHexString(plan.codepoints[i]));g.setFont(selected);FontMetrics metrics=g.getFontMetrics();Shape clip=g.getClip();g.clipRect(cell[0],cell[1],cell[2]-cell[0],cell[3]-cell[1]);g.drawString(glyph,(cell[0]+cell[2]-metrics.stringWidth(glyph))/2,(cell[1]+cell[3]-metrics.getHeight())/2+metrics.getAscent());g.setClip(clip);}g.dispose();
                    result.put("font",plan.font).put("vertical",region.vertical).put("cells",new JSONArray(plan.cells)).put("safeCells",true).put("foreignInkAndCellsProtected",true);
                }
            }
        }
        result.put("status",failure==null?"cleaned_layout":failure);
        Files.createDirectories(out);ImageIO.write(before,"png",out.resolve("原图.png").toFile());ImageIO.write(after,"png",out.resolve("仅去字.png").toFile());ImageIO.write(filled,"png",out.resolve("黑字回填_桌面预览.png").toFile());
        BufferedImage panel=new BufferedImage(w*3+12,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=panel.createGraphics();g.setColor(new Color(220,220,220));g.fillRect(0,0,panel.getWidth(),h);g.drawImage(before,0,0,null);g.drawImage(after,w+6,0,null);g.drawImage(filled,w*2+12,0,null);g.dispose();ImageIO.write(panel,"png",out.resolve("原图_去字_回填.png").toFile());
        return result;
    }
    public static void main(String[] args)throws Exception{
        Path input=Paths.get(args[0]),labels=Paths.get(args[1]),out=Paths.get(args[2]),project=Paths.get(args[3]);Files.createDirectories(out);
        JSONArray samples=new JSONArray(),failures=new JSONArray();int passed=0,total=0;StringBuilder html=new StringBuilder("<!doctype html><meta charset='utf-8'><title>普通白底真实译文回填</title><style>body{font:16px system-ui;background:#eee}img{max-width:100%;border:1px solid #aaa}</style><h1>普通白底真实译文回填</h1><p>从左到右：原图、仅去字、黑字回填。使用已有真实缓存译文和生产几何算法；字形由 Windows Microsoft YaHei 绘制，未验证 Android Canvas。</p>");
        for(Object raw:new JSONArray(Files.readString(labels))){JSONObject label=(JSONObject)raw;if(!label.getString("category").equals("plain_white"))continue;total++;
            String key=label.getString("id"),id=key.substring(key.indexOf('_')+1);Path dir=Paths.get(label.getString("source")).getParent();BufferedImage image=ImageIO.read(dir.resolve("原图.png").toFile());List<Region> regions=V090MaskAudit.predict(image,new JSONObject(Files.readString(dir.resolve("检测原始结果.json"))));Region region=null;for(Region candidate:regions)if(candidate.id.equals(id))region=candidate;if(region==null)throw new AssertionError("Missing region "+key);
            JSONObject translation=null;for(Object item:new JSONObject(Files.readString(dir.resolve("真实译文.json"))).getJSONArray("translations"))if(((JSONObject)item).getString("id").equals(id))translation=(JSONObject)item;if(translation==null||translation.optBoolean("skip"))throw new AssertionError("Missing real translation "+key);
            JSONObject result=render(image,regions,region,translation.getString("zh"),out.resolve(key)).put("sample",key).put("source",dir.resolve("原图.png").toString()).put("visualCategory","plain_white");samples.put(result);
            if(result.getString("status").equals("cleaned_layout"))passed++;else failures.put(new JSONObject().put("id",key).put("status",result.getString("status")));
            html.append("<h2>").append(key).append(" — ").append(result.getString("status")).append("</h2><img src='").append(key).append("/原图_去字_回填.png'>");image.flush();
        }
        JSONObject hashes=new JSONObject();for(String name:new String[]{"WhiteBubbleCleaner","RtDetrRegions","BubbleLayout"})hashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(project.resolve("app/src/main/java/cn/local/manga/"+name+".java")))));
        JSONObject report=new JSONObject().put("total",total).put("accepted",passed).put("failed",total-passed).put("failures",failures).put("cachedRealTranslations",true).put("paidApiUsed",false).put("productionMaskAndLayout",true).put("engineForeignExclusionMirrored",true).put("androidCanvasVerified",false).put("sourceSha256",hashes).put("samples",samples);
        Files.writeString(out.resolve("结果.json"),report.toString(2));Files.writeString(out.resolve("对照.html"),html.toString());System.out.println("PLAIN REAL RENDER: "+passed+"/"+total+"; failures="+failures);
    }
}
