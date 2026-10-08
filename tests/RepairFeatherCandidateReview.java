package cn.local.manga;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Isolated feather candidates, confined to the existing production editable mask. Never changes production. */
public final class RepairFeatherCandidateReview {
    static int gray(int p){return (((p>>>16)&255)*299+((p>>>8)&255)*587+(p&255)*114)/1000;}
    static int[] distance(boolean[] editable,int w,int h){int[] d=new int[editable.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x;d[p]=editable[p]?1+Math.min(x==0?0:d[p-1],y==0?0:d[p-w]):0;}for(int y=h-1;y>=0;y--)for(int x=w-1;x>=0;x--){int p=y*w+x;if(d[p]>0)d[p]=Math.min(d[p],1+Math.min(x==w-1?0:d[p+1],y==h-1?0:d[p+w]));}return d;}
    static int mix(int original,int repaired,float alpha){int out=0;for(int shift:new int[]{24,16,8,0})out|=Math.round((original>>>shift&255)*(1-alpha)+(repaired>>>shift&255)*alpha)<<shift;return out;}
    static JSONObject metrics(int[] original,int[] hard,int[] candidate,boolean[] editable,int[] distance,int w,int h,int radius){
        int outside=0,changedBand=0,changedDeep=0,brightReintroduced=0,pairs=0,maxOriginal=0,maxCandidate=0;long originalJump=0,candidateJump=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x;if(!editable[p]){if(candidate[p]!=original[p])outside++;continue;}
            if(candidate[p]!=hard[p]){if(distance[p]<=radius)changedBand++;else changedDeep++;}
            if(distance[p]<=radius&&gray(original[p])>=180&&gray(original[p])-gray(hard[p])>=35&&gray(candidate[p])-gray(hard[p])>=15)brightReintroduced++;
            for(int q:new int[]{x>0?p-1:-1,x+1<w?p+1:-1,y>0?p-w:-1,y+1<h?p+w:-1})if(q>=0&&!editable[q]){int a=Math.abs(gray(original[p])-gray(original[q])),b=Math.abs(gray(candidate[p])-gray(candidate[q]));originalJump+=a;candidateJump+=b;maxOriginal=Math.max(maxOriginal,a);maxCandidate=Math.max(maxCandidate,b);pairs++;}
        }
        if(outside!=0||changedDeep!=0)throw new AssertionError("Feather candidate escaped its inner band");
        return new JSONObject().put("radius",radius).put("boundaryPixelPairs",pairs).put("originalBoundaryMeanLumaJump",originalJump/(double)Math.max(1,pairs)).put("candidateBoundaryMeanLumaJump",candidateJump/(double)Math.max(1,pairs)).put("originalBoundaryMaxLumaJump",maxOriginal).put("candidateBoundaryMaxLumaJump",maxCandidate).put("changedBandPixelsFromHard",changedBand).put("outsideChanges",outside).put("changesBeyondBandFromHard",changedDeep).put("potentialBrightDetailReintroduced",brightReintroduced).put("brightMetricMeaning","Heuristic only: original >=180, original minus hard >=35, candidate minus hard >=15 inside the feather band. This can include bright artwork; it is not glyph ground truth.");
    }
    static JSONObject alignment(int[] original,int[] generated,boolean[] editable,int w,int h){
        double baseline=0,best=Double.POSITIVE_INFINITY;int bestX=0,bestY=0,count=0;JSONArray scores=new JSONArray();
        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++){long error=0;int points=0;for(int y=3;y<h-3;y++)for(int x=3;x<w-3;x++){int p=y*w+x;if(editable[p])continue;error+=Math.abs(gray(original[p])-gray(generated[(y+dy)*w+x+dx]));points++;}double mean=error/(double)Math.max(1,points);if(dx==0&&dy==0){baseline=mean;count=points;}if(mean<best){best=mean;bestX=dx;bestY=dy;}scores.put(new JSONObject().put("dx",dx).put("dy",dy).put("meanAbsoluteLumaDifference",mean));}
        return new JSONObject().put("fixedExteriorPixels",count).put("shiftMeaning","Compare original(x,y) with generated(x+dx,y+dy), only original non-editable pixels at least3px from ROI border.").put("baselineMeanDifference",baseline).put("bestMeanDifference",best).put("bestDx",bestX).put("bestDy",bestY).put("relativeImprovement",1-best/Math.max(1,baseline)).put("scores",scores).put("candidateApplied",false).put("limitation","A global score over known exterior does not prove alignment of occluded background or remove local redraw drift.");
    }
    public static void main(String[] args)throws Exception{
        Path sampleDir=Paths.get(args[0]),out=Paths.get(args[1]);boolean zeroEdge=args.length>2&&args[2].equals("zero-edge");Files.createDirectories(out);String id=sampleDir.getFileName().toString();JSONObject sample=null;
        for(Object item:LiveRepairCompositeReview.json(sampleDir.getParent().resolve("samples.json")).getJSONArray("samples"))if(((JSONObject)item).getString("id").equals(id))sample=(JSONObject)item;if(sample==null)throw new Exception("Missing evidence sample");
        Path originalPath=sampleDir.resolve("input_roi.png"),hardPath=sampleDir.resolve("生产合成回填验收/生产保护合成_仅去字.png");BufferedImage original=ImageIO.read(originalPath.toFile()),hard=ImageIO.read(hardPath.toFile());int w=original.getWidth(),h=original.getHeight();int[] before=original.getRGB(0,0,w,h,null,0,w),base=hard.getRGB(0,0,w,h,null,0,w);boolean[] editable=RepairPixels.editable(w,h,LiveRepairCompositeReview.boxes(sample.getJSONArray("targetBoxes")),LiveRepairCompositeReview.boxes(sample.getJSONArray("protectedBoxes")));int[] d=distance(editable,w,h);
        JSONArray records=new JSONArray();records.put(metrics(before,base,base,editable,d,w,h,0));BufferedImage[] views=new BufferedImage[4];views[0]=original;views[1]=hard;
        for(int radius=2;radius<=3;radius++){int[] pixels=base.clone();for(int i=0;i<pixels.length;i++)if(editable[i]&&d[i]<=radius)pixels[i]=mix(before[i],base[i],zeroEdge?(d[i]-1)/(float)radius:d[i]/(radius+1f));
            JSONObject record=metrics(before,base,pixels,editable,d,w,h,radius);String name="内侧"+radius+"px羽化_仅测试.png";views[radius]=LiveRepairCompositeReview.image(pixels,w,h);ImageIO.write(views[radius],"png",out.resolve(name).toFile());record.put("file",name).put("sha256",LiveRepairCompositeReview.hash(out.resolve(name)));records.put(record);
            BufferedImage risk=LiveRepairCompositeReview.image(before,w,h);JSONArray riskCoordinates=new JSONArray();for(int p=0;p<before.length;p++)if(d[p]>0&&d[p]<=radius&&gray(before[p])>=180&&gray(before[p])-gray(base[p])>=35&&gray(pixels[p])-gray(base[p])>=15){risk.setRGB(p%w,p/w,0xffff2222);riskCoordinates.put(new JSONArray(new int[]{p%w,p/w}));}ImageIO.write(risk,"png",out.resolve("内侧"+radius+"px恢复高亮像素_原图红标.png").toFile());record.put("potentialBrightDetailCoordinates",riskCoordinates);
        }
        int scale=3;BufferedImage comparison=new BufferedImage(w*scale*4+24,h*scale,BufferedImage.TYPE_INT_RGB);Graphics2D g=comparison.createGraphics();g.setColor(new Color(220,220,220));g.fillRect(0,0,comparison.getWidth(),comparison.getHeight());g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);for(int i=0;i<views.length;i++)g.drawImage(views[i],i*(w*scale+8),0,w*scale,h*scale,null);g.dispose();ImageIO.write(comparison,"png",out.resolve("原图_硬合成_2px_3px羽化.png").toFile());
        JSONArray seamComparisons=new JSONArray();Path reviewPath=sampleDir.getParent().resolve("独立视觉复核.json");
        if(Files.isRegularFile(reviewPath)){JSONArray seamRegions=LiveRepairCompositeReview.json(reviewPath).getJSONObject("productionCompositeReview").getJSONArray("seamRegions");int n=0;
            for(Object item:seamRegions){JSONObject region=(JSONObject)item;JSONArray box=region.getJSONArray("box");int left=box.getInt(0),top=box.getInt(1),right=box.getInt(2),bottom=box.getInt(3),cw=right-left,ch=bottom-top;BufferedImage crop=new BufferedImage(cw*8*4+24,ch*8,BufferedImage.TYPE_INT_RGB);Graphics2D cg=crop.createGraphics();cg.setColor(new Color(220,220,220));cg.fillRect(0,0,crop.getWidth(),crop.getHeight());cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);JSONArray errors=new JSONArray();
                for(int i=0;i<views.length;i++){cg.drawImage(views[i],i*(cw*8+8),0,i*(cw*8+8)+cw*8,ch*8,left,top,right,bottom,null);long error=0;for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)error+=Math.abs(gray(original.getRGB(x,y))-gray(views[i].getRGB(x,y)));errors.put(error/(double)(cw*ch));}cg.dispose();String filename="已标接缝区域_"+(++n)+"_原图_硬合成_2px_3px.png";ImageIO.write(crop,"png",out.resolve(filename).toFile());seamComparisons.put(new JSONObject().put("box",box).put("independentFinding",region.getString("finding")).put("file",filename).put("meanAbsoluteLumaDifferenceFromOriginal",errors));
            }
        }
        BufferedImage generated=ImageIO.read(sampleDir.resolve("生产合成回填验收/模型返回_双线性缩回原尺寸.png").toFile());
        JSONObject report=new JSONObject().put("sample",id).put("manifestSample",sample).put("originalSha256",LiveRepairCompositeReview.hash(originalPath)).put("hardCompositeSha256",LiveRepairCompositeReview.hash(hardPath)).put("translationAlignment",alignment(before,generated.getRGB(0,0,w,h,null,0,w),editable,w,h)).put("zeroModelAlphaAtBoundary",zeroEdge).put("algorithm",zeroEdge?"Inside-only blend: distance is Manhattan distance to non-editable pixels; AI weight=min(1,(distance-1)/radius). Boundary row is exact original; outside pixels untouched.":"Inside-only blend: distance is Manhattan distance to non-editable pixels; AI weight=min(1,distance/(radius+1)); outside pixels untouched.")
            .put("candidates",records).put("independentMarkedSeamComparisons",seamComparisons).put("productionChanged",false).put("newApiCalls",0).put("androidRuntimeVerified",false).put("visualReview","pending").put("limitation","Feathering only blends the narrow boundary; generated texture or geometry drift deeper inside remains unchanged. Blending towards original mathematically reduces pixel error, but is not proof of visual fidelity.");Files.writeString(out.resolve("结果.json"),report.toString(2));System.out.println(report.toString(2));
    }
}
