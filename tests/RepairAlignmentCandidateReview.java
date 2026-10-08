package cn.local.manga;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Fixed-domain translation search and two isolated compositor candidates; no production edits or network. */
public final class RepairAlignmentCandidateReview {
    static JSONObject search(int[] original,int[] generated,boolean[] editable,int w,int h){
        ArrayList<Integer> all=new ArrayList<>();for(int y=12;y<h-12;y++)for(int x=12;x<w-12;x++)if(!editable[y*w+x])all.add(y*w+x);
        int stride=Math.max(1,(all.size()+4095)/4096);ArrayList<Integer> samples=new ArrayList<>();for(int i=0;i<all.size();i+=stride)samples.add(all.get(i));
        double baseline=0,best8=Double.POSITIVE_INFINITY,best12=Double.POSITIVE_INFINITY;int x8=0,y8=0,x12=0,y12=0;JSONArray scores=new JSONArray();
        for(int dy=-12;dy<=12;dy++)for(int dx=-12;dx<=12;dx++){long error=0;for(int p:samples)error+=Math.abs(RepairFeatherCandidateReview.gray(original[p])-RepairFeatherCandidateReview.gray(generated[p+dy*w+dx]));double mean=error/(double)samples.size();
            if(dx==0&&dy==0)baseline=mean;if(Math.abs(dx)<=8&&Math.abs(dy)<=8&&mean<best8){best8=mean;x8=dx;y8=dy;}if(mean<best12){best12=mean;x12=dx;y12=dy;}scores.put(new JSONObject().put("dx",dx).put("dy",dy).put("meanAbsoluteLumaDifference",mean));}
        return new JSONObject().put("fixedEligiblePixels",all.size()).put("fixedSampleCount",samples.size()).put("deterministicStride",stride).put("fixedSampleIndices",samples).put("excludedRoiBorderPixels",12).put("score","mean absolute luma difference on one fixed non-editable point set for all 625 offsets")
            .put("baselineMeanDifference",baseline).put("best8",new JSONObject().put("dx",x8).put("dy",y8).put("meanDifference",best8)).put("best12",new JSONObject().put("dx",x12).put("dy",y12).put("meanDifference",best12).put("relativeImprovement",1-best12/baseline)).put("allScores",scores)
            .put("shiftMeaning","Aligned(x,y)=generated(x+dx,y+dy); the image is visually shifted by (-dx,-dy).");
    }
    public static void main(String[] args)throws Exception{
        Path sampleDir=Paths.get(args[0]),out=Paths.get(args[1]);Files.createDirectories(out);String id=sampleDir.getFileName().toString();JSONObject sample=null;
        for(Object item:LiveRepairCompositeReview.json(sampleDir.getParent().resolve("samples.json")).getJSONArray("samples"))if(((JSONObject)item).getString("id").equals(id))sample=(JSONObject)item;if(sample==null)throw new Exception("Missing sample manifest");
        Path originalPath=sampleDir.resolve("input_roi.png"),generatedPath=sampleDir.resolve("生产合成回填验收/模型返回_双线性缩回原尺寸.png");BufferedImage original=ImageIO.read(originalPath.toFile()),generated=ImageIO.read(generatedPath.toFile());int w=original.getWidth(),h=original.getHeight();int[] before=original.getRGB(0,0,w,h,null,0,w),model=generated.getRGB(0,0,w,h,null,0,w);int[][] targets=LiveRepairCompositeReview.boxes(sample.getJSONArray("targetBoxes")),protectedBoxes=LiveRepairCompositeReview.boxes(sample.getJSONArray("protectedBoxes"));boolean[] editable=RepairPixels.editable(w,h,targets,protectedBoxes);
        JSONObject registration=search(before,model,editable,w,h);int dx=registration.getJSONObject("best12").getInt("dx"),dy=registration.getJSONObject("best12").getInt("dy");int[] aligned=model.clone();int invalid=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x,xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>=w||yy>=h){invalid++;if(editable[p])throw new AssertionError("Best offset makes an editable coordinate invalid");xx=Math.max(0,Math.min(w-1,xx));yy=Math.max(0,Math.min(h-1,yy));}aligned[p]=model[yy*w+xx];}
        int[] hard=RepairPixels.composite(before,aligned,w,h,editable),d=RepairFeatherCandidateReview.distance(editable,w,h),feather=hard.clone();for(int p=0;p<before.length;p++)if(editable[p]&&d[p]<=3)feather[p]=RepairFeatherCandidateReview.mix(before[p],hard[p],(d[p]-1)/3f);
        int outside=0;for(int p=0;p<before.length;p++)if(!editable[p]&&(hard[p]!=before[p]||feather[p]!=before[p]))outside++;if(outside!=0)throw new AssertionError("Protected pixels changed");
        ImageIO.write(LiveRepairCompositeReview.image(aligned,w,h),"png",out.resolve("最佳偏移_模型图.png").toFile());ImageIO.write(LiveRepairCompositeReview.image(hard,w,h),"png",out.resolve("最佳偏移_生产硬合成.png").toFile());ImageIO.write(LiveRepairCompositeReview.image(feather,w,h),"png",out.resolve("最佳偏移_3px边圈保留羽化.png").toFile());
        BufferedImage previous=ImageIO.read(sampleDir.resolve("生产合成回填验收/生产保护合成_仅去字.png").toFile());BufferedImage[] views={original,previous,LiveRepairCompositeReview.image(hard,w,h),LiveRepairCompositeReview.image(feather,w,h)};int scale=3;
        BufferedImage comparison=new BufferedImage(w*scale*4+24,h*scale,BufferedImage.TYPE_INT_RGB);Graphics2D g=comparison.createGraphics();g.setColor(new Color(220,220,220));g.fillRect(0,0,comparison.getWidth(),comparison.getHeight());g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);for(int i=0;i<views.length;i++)g.drawImage(views[i],i*(w*scale+8),0,w*scale,h*scale,null);g.dispose();ImageIO.write(comparison,"png",out.resolve("原图_原硬合成_对齐硬合成_对齐羽化.png").toFile());
        JSONArray seamComparisons=new JSONArray();Path reviewPath=sampleDir.getParent().resolve("独立视觉复核.json");
        if(Files.isRegularFile(reviewPath)){JSONArray seamRegions=LiveRepairCompositeReview.json(reviewPath).getJSONObject("productionCompositeReview").getJSONArray("seamRegions");int n=0;
            for(Object item:seamRegions){JSONObject region=(JSONObject)item;JSONArray box=region.getJSONArray("box");int left=box.getInt(0),top=box.getInt(1),right=box.getInt(2),bottom=box.getInt(3),cw=right-left,ch=bottom-top;BufferedImage crop=new BufferedImage(cw*8*4+24,ch*8,BufferedImage.TYPE_INT_RGB);Graphics2D cg=crop.createGraphics();cg.setColor(new Color(220,220,220));cg.fillRect(0,0,crop.getWidth(),crop.getHeight());cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);JSONArray errors=new JSONArray();
                for(int i=0;i<views.length;i++){cg.drawImage(views[i],i*(cw*8+8),0,i*(cw*8+8)+cw*8,ch*8,left,top,right,bottom,null);long error=0;for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)error+=Math.abs(RepairFeatherCandidateReview.gray(original.getRGB(x,y))-RepairFeatherCandidateReview.gray(views[i].getRGB(x,y)));errors.put(error/(double)(cw*ch));}cg.dispose();String filename="已标接缝区域_"+(++n)+"_原图_旧合成_对齐_羽化.png";ImageIO.write(crop,"png",out.resolve(filename).toFile());seamComparisons.put(new JSONObject().put("box",box).put("independentFinding",region.getString("finding")).put("file",filename).put("meanAbsoluteLumaDifferenceFromOriginal",errors));
            }
        }
        JSONObject report=new JSONObject().put("sample",id).put("manifestSample",sample).put("registration",registration).put("invalidAlignedCoordinates",invalid).put("invalidBorderHandling","For the preview/composite input only, unavailable border values clamp to nearest generated pixel. Every editable coordinate remains valid. Registration scoring never uses these border pixels.")
            .put("productionCompositeAccepted",true).put("outsideChangedPixels",outside).put("independentMarkedSeamComparisons",seamComparisons).put("alignedHardBoundary",RepairFeatherCandidateReview.metrics(before,hard,hard,editable,d,w,h,0)).put("alignedFeatherBoundary",RepairFeatherCandidateReview.metrics(before,hard,feather,editable,d,w,h,3))
            .put("originalSha256",LiveRepairCompositeReview.hash(originalPath)).put("generatedSha256",LiveRepairCompositeReview.hash(generatedPath)).put("alignedHardSha256",LiveRepairCompositeReview.hash(out.resolve("最佳偏移_生产硬合成.png"))).put("alignedFeatherSha256",LiveRepairCompositeReview.hash(out.resolve("最佳偏移_3px边圈保留羽化.png")))
            .put("productionChanged",false).put("newApiCalls",0).put("androidRuntimeVerified",false).put("visualReview","pending");Files.writeString(out.resolve("结果.json"),report.toString(2));System.out.println(new JSONObject().put("baseline",registration.getDouble("baselineMeanDifference")).put("best8",registration.getJSONObject("best8")).put("best12",registration.getJSONObject("best12")).put("outsideChangedPixels",outside).toString());
    }
}
