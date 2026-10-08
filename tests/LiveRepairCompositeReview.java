package cn.local.manga;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Read-only use of a recorded live result: production pixel compositor and layout, desktop glyphs only. */
public final class LiveRepairCompositeReview {
    static JSONObject json(Path file)throws Exception{String s=Files.readString(file);return new JSONObject(s.startsWith("\ufeff")?s.substring(1):s);}
    static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    static int[][] boxes(JSONArray a){int[][] out=new int[a.length()][4];for(int i=0;i<out.length;i++)for(int j=0;j<4;j++)out[i][j]=a.getJSONArray(i).getInt(j);return out;}
    static BufferedImage image(int[] pixels,int w,int h){BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);out.setRGB(0,0,w,h,pixels,0,w);return out;}
    static void draw(BufferedImage image,NearbyTextLayout.Plan plan)throws Exception{
        Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font base=new Font("Microsoft YaHei",Font.PLAIN,1).deriveFont(plan.font),symbols=new Font("Segoe UI Symbol",Font.PLAIN,1).deriveFont(plan.font);
        for(int i=0;i<plan.points.length;i++){
            int code=plan.points[i];Font font=base.canDisplay(code)?base:symbols;if(!font.canDisplay(code))throw new Exception("Desktop preview font missing U+"+Integer.toHexString(code));
            Graphics2D cell=(Graphics2D)g.create();cell.setFont(font);String character=new String(Character.toChars(code));FontMetrics fm=cell.getFontMetrics();GlyphVector glyph=font.createGlyphVector(cell.getFontRenderContext(),character);
            float x=plan.cellLeft(i),y=plan.cellTop(i),width=(float)glyph.getLogicalBounds().getBounds2D().getWidth();float scale=Math.min(1f,plan.step*.78f/Math.max(1f,Math.max(width,fm.getAscent()+fm.getDescent())));
            cell.clip(new Rectangle2D.Float(x,y,Math.min(plan.box[2],x+plan.step)-x,Math.min(plan.box[3],y+plan.step)-y));cell.translate(x+plan.step/2,y+plan.step/2);cell.scale(scale,scale);
            Shape outline=glyph.getOutline(-width/2f,(fm.getAscent()-fm.getDescent())/2f);cell.setStroke(new BasicStroke(Math.max(.1f,plan.font*.14f),BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND));cell.setColor(Color.WHITE);cell.draw(outline);cell.setColor(Color.BLACK);cell.fill(outline);cell.dispose();
        }
        g.dispose();
    }
    public static void main(String[] args)throws Exception{
        Path evidence=Paths.get(args[0]),returnedPath=Paths.get(args[2]),project=Paths.get(args[3]),cacheRoot=Paths.get(args[4]),out=Paths.get(args[5]);String id=args[1];Files.createDirectories(out);
        JSONObject sample=null;for(Object item:json(evidence.resolve("samples.json")).getJSONArray("samples"))if(((JSONObject)item).getString("id").equals(id))sample=(JSONObject)item;if(sample==null)throw new Exception("Sample not in evidence manifest");
        Path sampleDir=evidence.resolve(id),inputPath=sampleDir.resolve("input_roi.png"),requestPath=sampleDir.resolve("request_result.json");JSONObject request=json(requestPath);
        if(!request.optBoolean("success"))throw new Exception("The recorded live attempt did not return a successful image");
        BufferedImage original=ImageIO.read(inputPath.toFile()),returned=ImageIO.read(returnedPath.toFile());if(original==null||returned==null)throw new Exception("ImageIO cannot decode recorded input or result");int w=original.getWidth(),h=original.getHeight();
        if(!hash(inputPath).equals(request.getString("inputSha256"))||!hash(returnedPath).equals(request.getString("returnedSha256")))throw new Exception("Recorded image hashes do not match the live result evidence");
        int[][] targets=boxes(sample.getJSONArray("targetBoxes")),protectedBoxes=boxes(sample.getJSONArray("protectedBoxes"));ImageCleanup.prompt(w,h,targets,protectedBoxes);ImageCleanup.validateReturnedSize(returned.getWidth(),returned.getHeight(),w,h);
        BufferedImage resized=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D resize=resized.createGraphics();resize.setComposite(AlphaComposite.Src);resize.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);resize.drawImage(returned,0,0,w,h,null);resize.dispose();
        int page=Integer.parseInt(id.substring(1,id.indexOf('_')));String regionId=id.substring(id.indexOf('_')+1);Path pageDir=cacheRoot.resolve("逐页").resolve(String.format("第%02d页",page)),translationPath=pageDir.resolve("真实译文.json"),detectionPath=pageDir.resolve("段落检测.json");JSONObject translation=null,region=null;
        for(Object value:json(translationPath).getJSONArray("translations"))if(((JSONObject)value).getString("id").equals(regionId))translation=(JSONObject)value;
        for(Object value:json(detectionPath).getJSONArray("regions"))if(((JSONObject)value).getString("id").equals(regionId))region=(JSONObject)value;
        if(translation==null||region==null||translation.optBoolean("skip"))throw new Exception("Recorded sample lacks its original direction or cached real translation");
        String text=translation.getString("zh");boolean vertical=region.getBoolean("vertical");BufferedImage sourcePage=ImageIO.read(pageDir.resolve("原图.png").toFile());int pageWidth=sourcePage.getWidth(),pageHeight=sourcePage.getHeight();JSONObject regionBox=region.getJSONObject("box");int[] absoluteAnchor={regionBox.getInt("x"),regionBox.getInt("y"),regionBox.getInt("x")+regionBox.getInt("width"),regionBox.getInt("y")+regionBox.getInt("height")};JSONArray sourceCrop=sample.getJSONArray("sourceCrop");int cropLeft=sourceCrop.getInt(0),cropTop=sourceCrop.getInt(1);
        if(sourceCrop.getInt(2)-cropLeft!=w||sourceCrop.getInt(3)-cropTop!=h||cropLeft<0||cropTop<0||cropLeft+w>pageWidth||cropTop+h>pageHeight)throw new Exception("Recorded source crop does not fit the original page exactly");
        for(int i=0;i<4;i++)if(absoluteAnchor[i]-(i%2==0?cropLeft:cropTop)!=targets[0][i])throw new Exception("Original paragraph anchor differs from recorded live target");
        int[] before=original.getRGB(0,0,w,h,null,0,w),after=resized.getRGB(0,0,w,h,null,0,w),composited=before.clone(),finalPixels=before.clone();
        Font previewFont=new Font("Microsoft YaHei",Font.PLAIN,28);int missingAt=previewFont.canDisplayUpTo(text);JSONObject fontCoverage=new JSONObject().put("family",previewFont.getFamily()).put("fontName",previewFont.getFontName()).put("canDisplayUpToCachedText",missingAt).put("noMissingGlyphs",missingAt==-1);
        BufferedImage fontReference=new BufferedImage(Math.max(460,text.length()*30+24),76,BufferedImage.TYPE_INT_RGB);Graphics2D fg=fontReference.createGraphics();fg.setColor(Color.WHITE);fg.fillRect(0,0,fontReference.getWidth(),fontReference.getHeight());fg.setFont(previewFont);fg.setColor(Color.BLACK);fg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);fg.drawString(text,12,46);fg.dispose();ImageIO.write(fontReference,"png",out.resolve("字体覆盖核验_28px参考.png").toFile());
        boolean[] editable=RepairPixels.editable(w,h,targets,protectedBoxes);int editableCount=0,outsideChangedByModel=0,insideChangedByModel=0;long outsideAbsoluteError=0;
        for(int p=0;p<before.length;p++){int delta=(Math.abs((before[p]>>16&255)-(after[p]>>16&255))+Math.abs((before[p]>>8&255)-(after[p]>>8&255))+Math.abs((before[p]&255)-(after[p]&255)))/3;if(editable[p]){editableCount++;if(delta>8)insideChangedByModel++;}else{outsideAbsoluteError+=delta;if(before[p]!=after[p])outsideChangedByModel++;}}
        JSONObject report=new JSONObject().put("sample",id).put("manifestSample",sample).put("inputWidth",w).put("inputHeight",h).put("returnedWidth",returned.getWidth()).put("returnedHeight",returned.getHeight()).put("returnedSizeDiffers",returned.getWidth()!=w||returned.getHeight()!=h)
            .put("resizeMethod","Desktop AWT bilinear to exact input dimensions; Android decoder subsampling and Bitmap.createScaledBitmap pixels are not verified.")
            .put("inputSha256",hash(inputPath)).put("returnedSha256",hash(returnedPath)).put("requestResultSha256",hash(requestPath)).put("translationFileSha256",hash(translationPath)).put("detectionFileSha256",hash(detectionPath))
            .put("cachedRealTranslation",text).put("fontCoverage",fontCoverage).put("originalVertical",vertical).put("sourcePageWidth",pageWidth).put("sourcePageHeight",pageHeight).put("absoluteOriginalAnchor",new JSONArray(absoluteAnchor)).put("productionPixelMethods","RepairPixels.editable / RepairPixels.composite").put("productionLayoutMethod","NearbyTextLayout.forCrop")
            .put("editablePixels",editableCount).put("outsidePixels",before.length-editableCount).put("modelChangedOutsidePixelsBeforeComposite",outsideChangedByModel).put("modelChangedInsidePixelsOverDelta8",insideChangedByModel).put("modelOutsideMeanDelta",outsideAbsoluteError/(double)Math.max(1,before.length-editableCount))
            .put("newApiCalls",0).put("historicalApplicationRequestAttempts",2).put("usesRecordedLiveResponse",true).put("androidCanvasVerified",false).put("visualReview","pending");
        boolean compositeAccepted=false,layoutAccepted=false;
        try{
            composited=RepairPixels.composite(before,after,w,h,editable);compositeAccepted=true;
            NearbyTextLayout.Plan plan=NearbyTextLayout.forCrop(pageWidth,pageHeight,absoluteAnchor,cropLeft,cropTop,vertical,text);
            JSONArray cells=new JSONArray();for(int i=0;i<plan.points.length;i++){
                float x=plan.cellLeft(i),y=plan.cellTop(i);cells.put(new JSONArray(new float[]{x,y,x+plan.step,y+plan.step}));
                for(int[] box:protectedBoxes)if(x<box[2]+2&&x+plan.step>box[0]-2&&y<box[3]+2&&y+plan.step>box[1]-2)throw new Exception("修图译文会覆盖其他段，保留原图");
            }
            BufferedImage staged=image(composited,w,h);draw(staged,plan);int[] stagedPixels=staged.getRGB(0,0,w,h,null,0,w);int[] target=targets[0];
            for(int y=Math.max(0,target[1]);y<Math.min(h,target[3]);y++)for(int x=Math.max(0,target[0]);x<Math.min(w,target[2]);x++)if(editable[y*w+x])finalPixels[y*w+x]=stagedPixels[y*w+x];
            layoutAccepted=true;report.put("font",plan.font).put("columns",plan.columns).put("glyphCount",plan.points.length).put("cells",cells).put("protectedLayoutCheckPassed",true);
        }catch(Exception rejected){report.put("rejectionReason",rejected.getMessage()).put("rejectedStage",compositeAccepted?"layout_or_desktop_font":"production_composite");}
        int outsideComposite=0,outsideFinal=0;for(int p=0;p<before.length;p++)if(!editable[p]){if(before[p]!=composited[p])outsideComposite++;if(before[p]!=finalPixels[p])outsideFinal++;}
        if(outsideComposite!=0||outsideFinal!=0)throw new AssertionError("Protected or exterior pixels changed after the compositor");
        report.put("compositeAccepted",compositeAccepted).put("layoutAccepted",layoutAccepted).put("outsideChangedAfterComposite",outsideComposite).put("outsideChangedAfterFinalText",outsideFinal).put("outsidePixelEqualityPassed",true);
        ImageIO.write(original,"png",out.resolve("原图ROI.png").toFile());ImageIO.write(resized,"png",out.resolve("模型返回_双线性缩回原尺寸.png").toFile());ImageIO.write(image(composited,w,h),"png",out.resolve("生产保护合成_仅去字.png").toFile());ImageIO.write(image(finalPixels,w,h),"png",out.resolve("中文回填_桌面预览.png").toFile());
        int scale=Math.max(1,Math.min(4,900/h));BufferedImage comparison=new BufferedImage(w*scale*4+24,h*scale,BufferedImage.TYPE_INT_RGB);Graphics2D g=comparison.createGraphics();g.setColor(new Color(220,220,220));g.fillRect(0,0,comparison.getWidth(),comparison.getHeight());g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        BufferedImage[] views={original,resized,image(composited,w,h),image(finalPixels,w,h)};for(int i=0;i<views.length;i++)g.drawImage(views[i],i*(w*scale+8),0,w*scale,h*scale,null);g.dispose();ImageIO.write(comparison,"png",out.resolve("原图_模型返回_保护合成_中文回填.png").toFile());
        int[] sourcePagePixels=sourcePage.getRGB(0,0,pageWidth,pageHeight,null,0,pageWidth),fullPixels=sourcePagePixels.clone();
        for(int y=0;y<h;y++)System.arraycopy(finalPixels,y*w,fullPixels,(cropTop+y)*pageWidth+cropLeft,w);
        int pageOutside=0,pageOutsideChanged=0;for(int y=0;y<pageHeight;y++)for(int x=0;x<pageWidth;x++)if(x<cropLeft||x>=cropLeft+w||y<cropTop||y>=cropTop+h){pageOutside++;if(sourcePagePixels[y*pageWidth+x]!=fullPixels[y*pageWidth+x])pageOutsideChanged++;}
        if(pageOutsideChanged!=0)throw new AssertionError("Full-page pixels outside sourceCrop changed");
        ImageIO.write(image(fullPixels,pageWidth,pageHeight),"png",out.resolve("整页回填_桌面预览.png").toFile());
        int cl=Math.max(0,cropLeft-80),ct=Math.max(0,cropTop-80),cr=Math.min(pageWidth,cropLeft+w+80),cb=Math.min(pageHeight,cropTop+h+80),cw=cr-cl,ch=cb-ct;
        BufferedImage context=new BufferedImage(cw*3+16,ch,BufferedImage.TYPE_INT_RGB);Graphics2D cg=context.createGraphics();cg.setColor(new Color(220,220,220));cg.fillRect(0,0,context.getWidth(),ch);BufferedImage[] contextRois={original,image(composited,w,h),image(finalPixels,w,h)};
        for(int i=0;i<3;i++){int x=i*(cw+8);cg.drawImage(sourcePage,x,0,x+cw,ch,cl,ct,cr,cb,null);cg.drawImage(contextRois[i],x+cropLeft-cl,cropTop-ct,null);}cg.dispose();ImageIO.write(context,"png",out.resolve("局部上下文对照_1x.png").toFile());
        report.put("sourceCropOnPage",sourceCrop).put("sourcePageSha256",hash(pageDir.resolve("原图.png"))).put("wholePageWidth",pageWidth).put("wholePageHeight",pageHeight).put("wholePageOutsideCropPixels",pageOutside).put("wholePageOutsideCropChangedPixels",pageOutsideChanged).put("fullPageFileSha256",hash(out.resolve("整页回填_桌面预览.png"))).put("contextBounds",new JSONArray(new int[]{cl,ct,cr,cb})).put("contextPreviewScale",1).put("fullPageScope","Only the recorded sample ROI is composited into the original page; the rest of this page is unmodified and untranslated.");
        JSONObject hashes=new JSONObject();for(String name:new String[]{"RepairPixels","ImageCleanup","NearbyTextLayout","TranslationEngine"})hashes.put(name,hash(project.resolve("app/src/main/java/cn/local/manga/"+name+".java")));report.put("productionSourceSha256",hashes).put("compositeFileSha256",hash(out.resolve("生产保护合成_仅去字.png"))).put("previewFileSha256",hash(out.resolve("中文回填_桌面预览.png")));
        Files.writeString(out.resolve("结果.json"),report.toString(2));System.out.println("LIVE COMPOSITE REVIEW: composite="+compositeAccepted+", layout="+layoutAccepted+", protected/exterior changes="+outsideFinal+(report.has("rejectionReason")?", rejection="+report.getString("rejectionReason"):""));
    }
}
