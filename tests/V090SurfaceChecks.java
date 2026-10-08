package cn.local.manga;

import android.graphics.Rect;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.*;

/** Classification evidence and zero-erasure contract, with independently labeled real samples. */
public final class V090SurfaceChecks {
    static int checks;
    static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);checks++;}
    static Rect rect(JSONArray a){return new Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3));}
    static BufferedImage fixture(Color background){BufferedImage image=new BufferedImage(160,220,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(background);g.fillRect(0,0,160,220);g.setColor(Color.WHITE);g.setFont(new Font("Dialog",Font.BOLD,18));for(int y=35;y<200;y+=32)g.drawString("TEXT",30,y);g.dispose();return image;}
    static WhiteBubbleCleaner.Mask classify(BufferedImage b){return WhiteBubbleCleaner.classifyOnly(b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()),b.getWidth(),b.getHeight(),new int[][]{{0,0,b.getWidth(),b.getHeight()}});}
    static void synthetic(){
        BufferedImage black=fixture(Color.BLACK);WhiteBubbleCleaner.Mask dark=classify(black);
        check(dark.backgroundKind==WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART,"white text on black receives an affirmative background classification");
        int[] before=black.getRGB(0,0,160,220,null,0,160),after=before.clone();WhiteBubbleCleaner.apply(after,dark);
        check(!dark.whiteBackground&&dark.pixels==0&&Arrays.equals(before,after),"classification-only never authorizes or changes a pixel");
        check(classify(fixture(new Color(40,85,160))).backgroundKind==WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART,"colored image background is recognized from target text area");
        BufferedImage mixed=fixture(new Color(100,100,100));Graphics2D g=mixed.createGraphics();g.setColor(Color.WHITE);g.fillOval(85,25,65,115);g.dispose();
        check(classify(mixed).backgroundKind==WhiteBubbleCleaner.BackgroundKind.UNCERTAIN,"paragraph containing a large white compartment and dark background remains mixed");
        int[] white=new int[160*220];Arrays.fill(white,0xffffffff);for(int y=15;y<205;y++)for(int x=15;x<145;x++)if(x<17||x>=143||y<17||y>=203)white[y*160+x]=0xff000000;
        for(int y=45;y<185;y+=30)for(int yy=y;yy<y+14;yy++)for(int x=65;x<80;x++)if(x<68||x>77||yy<y+3||yy>y+11)white[yy*160+x]=0xff000000;
        WhiteBubbleCleaner.Mask paper=WhiteBubbleCleaner.forText(white,160,220,new int[][]{{60,38,85,198}},24);
        check(paper.whiteBackground&&paper.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,"closed white paper plus confirmed glyphs permits local cleanup");
        for(int y=55;y<80;y++)for(int x=115;x<124;x++)white[y*160+x]=0xff000000;
        WhiteBubbleCleaner.Mask adjacent=WhiteBubbleCleaner.forText(white,160,220,new int[][]{{60,38,85,198}},24);
        check(adjacent.whiteBackground,"a separate unrecognized mark inside the balloon does not prevent cleanup of confirmed text");
        for(int y=55;y<80;y++)for(int x=115;x<124;x++)if(adjacent.erase[y*160+x])throw new AssertionError("undetected neighboring lettering or art was erased");
        checks++;
        int[] partial=new int[100*160];Arrays.fill(partial,0xffffffff);
        for(int y=0;y<160;y++)for(int x=42;x<54;x++)partial[y*100+x]=0xff000000;
        for(int y=35;y<45;y++)for(int x=60;x<65;x++)partial[y*100+x]=0xff000000;
        WhiteBubbleCleaner.Mask incomplete=WhiteBubbleCleaner.forText(partial,100,160,new int[][]{{20,20,72,140}},20);
        check(!incomplete.whiteBackground&&incomplete.pixels==0,"a removable tiny fragment must not hide the unremoved connected main stroke");
        int[] noInk=new int[20*20];Arrays.fill(noInk,0xffffffff);WhiteBubbleCleaner.Mask blank=WhiteBubbleCleaner.classifyOnly(noInk,20,20,new int[0][]);
        check(blank.backgroundKind==WhiteBubbleCleaner.BackgroundKind.UNCERTAIN&&blank.pixels==0,"empty target scope stays uncertain");
        boolean invalid=false;try{WhiteBubbleCleaner.classifyOnly(new int[1],2,2,new int[][]{{0,0,2,2}});}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"classification validates pixel geometry");
    }
    public static void main(String[] args)throws Exception{
        Path benchmark=Paths.get(args[0]),out=Paths.get(args[1]);Files.createDirectories(out);synthetic();JSONArray results=new JSONArray();
        int plainCorrect=0,plainTotal=0,backgroundCorrect=0,backgroundTotal=0,mixedConservative=0,mixedTotal=0;
        for(Object raw:new JSONObject(Files.readString(benchmark)).getJSONArray("samples")){
            JSONObject item=(JSONObject)raw;BufferedImage image=ImageIO.read(Paths.get(item.getString("source")).toFile());String id=item.getString("id"),regionId=item.getString("regionId");Region region=null;
            Path prediction=id.startsWith("P")?Paths.get(item.getString("source")).resolveSibling("检测原始结果.json")
                :Paths.get("E:/codexwork/漫画检测模型评测_20260929/results/rtdetr_legacy_r50_int8_t4",String.format("page%02d.json",item.getInt("page")));
            if(Files.isRegularFile(prediction))for(Region candidate:V090MaskAudit.predict(image,new JSONObject(Files.readString(prediction))))if(candidate.id.equals(regionId)){region=candidate;break;}
            if(region==null){List<Rect> lines=new ArrayList<>();for(Object line:item.getJSONArray("lines"))lines.add(rect((JSONArray)line));Rect target=rect(item.getJSONArray("targetBox"));region=new Region(regionId,target,lines,target.bottom-target.top>target.right-target.left);}
            Rect box=region.box;int bw=box.right-box.left,bh=box.bottom-box.top;WhiteBubbleCleaner.Mask surface=WhiteBubbleCleaner.classifyOnly(image.getRGB(box.left,box.top,bw,bh,null,0,bw),bw,bh,new int[][]{{0,0,bw,bh}}),mask=null;
            if(!region.lines.isEmpty()){
                Rect roi=RtDetrRegions.renderBounds(region,image.getWidth(),image.getHeight());int w=roi.right-roi.left,h=roi.bottom-roi.top;int[][] lines=new int[region.lines.size()][4];int[] sizes=new int[lines.length];
                for(int i=0;i<lines.length;i++){Rect line=region.lines.get(i);lines[i]=new int[]{line.left-roi.left,line.top-roi.top,line.right-roi.left,line.bottom-roi.top};sizes[i]=Math.min(line.right-line.left,line.bottom-line.top);}Arrays.sort(sizes);
                mask=WhiteBubbleCleaner.forText(image.getRGB(roi.left,roi.top,w,h,null,0,w),w,h,lines,sizes[sizes.length/2]);
            }
            WhiteBubbleCleaner.Mask selected=mask!=null&&mask.backgroundKind!=WhiteBubbleCleaner.BackgroundKind.UNCERTAIN?mask:surface;
            String category=item.getString("category");if(category.equals("plain_white")){plainTotal++;if(selected.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER&&mask!=null&&mask.whiteBackground)plainCorrect++;}
            else if(category.equals("background_text")){backgroundTotal++;if(selected.backgroundKind==WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART)backgroundCorrect++;check(selected.backgroundKind!=WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,id+" background text must never become plain paper");}
            else{mixedTotal++;if(selected.backgroundKind==WhiteBubbleCleaner.BackgroundKind.UNCERTAIN||(selected.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER&&mask!=null&&mask.whiteBackground))mixedConservative++;}
            check(surface.pixels==0&&!surface.whiteBackground,id+" source-box classification has no erasure mask");
            results.put(new JSONObject().put("id",id).put("visualCategory",category).put("kind",selected.backgroundKind.name()).put("evidence",selected.evidence)
                .put("maskValid",mask!=null&&mask.whiteBackground).put("maskPixels",mask==null?0:mask.pixels).put("surfaceOnlyKind",surface.backgroundKind.name())
                .put("mixedLabelMeaning","Whole paragraph context can be mixed while an independently verified glyph mask stays inside white paper; mixed labels alone are not pixel-level failure ground truth."));
            image.flush();
        }
        JSONObject report=new JSONObject().put("checksPassed",checks).put("plainLocal",plainCorrect).put("plainTotal",plainTotal).put("backgroundIdentified",backgroundCorrect).put("backgroundTotal",backgroundTotal)
            .put("mixedConservativeOrVerifiedLocal",mixedConservative).put("mixedTotal",mixedTotal).put("classificationVisualGroundTruth",true).put("pixelSegmentationGroundTruth",false).put("paidApiUsed",false).put("samples",results);
        Files.writeString(out.resolve("分类结果.json"),report.toString(2));System.out.println(report.toString(2));
    }
}
