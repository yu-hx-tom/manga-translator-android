package cn.local.manga;

import android.graphics.Rect;
import org.json.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Saved predictions plus manually inspected glyph interiors; no inferred labels or paid API. */
public final class V090AnchorChecks {
    static int checks;static void ok(boolean condition,String reason){if(!condition)throw new AssertionError(reason);checks++;}
    static JSONArray boxes(List<Rect> values){return V090AnchorDiagnostic.boxes(values);}
    static List<Region> predict(BufferedImage image,JSONObject input,boolean before){
        JSONArray raw=input.getJSONArray("boxes"),types=input.getJSONArray("box_types"),conf=input.getJSONArray("scores");int count=raw.length();
        long[] labels=new long[count];float[][] bounds=new float[count][4];float[] scores=new float[count];
        for(int i=0;i<count;i++){String type=types.getString(i);labels[i]=type.equals("bubble")?0:type.equals("text_bubble")?1:type.equals("text_free")?2:-1;scores[i]=conf.getFloat(i);for(int k=0;k<4;k++)bounds[i][k]=raw.getJSONArray(i).getFloat(k);}
        int w=image.getWidth(),h=image.getHeight();int[] rgb=image.getRGB(0,0,w,h,null,0,w);
        return before?BeforeRtDetrRegions.fromPredictions(w,h,rgb,labels,bounds,scores):RtDetrRegions.fromPredictions(w,h,rgb,labels,bounds,scores);
    }
    static boolean inside(List<Rect> lines,int x,int y){for(Rect r:lines)if(x>=r.left&&x<r.right&&y>=r.top&&y<r.bottom)return true;return false;}
    static double coverage(BufferedImage image,List<Rect> lines,int[][] regions){int dark=0,covered=0;for(int[] box:regions)for(int y=box[1];y<box[3];y++)for(int x=box[0];x<box[2];x++){
        int c=image.getRGB(x,y),v=(((c>>>16)&255)*299+((c>>>8)&255)*587+(c&255)*114)/1000;if(v<160){dark++;if(inside(lines,x,y))covered++;}}
        if(dark==0)throw new AssertionError("manual reference contains no ink");return(double)covered/dark;
    }
    static Map<String,int[][]> reference(){Map<String,int[][]> result=new LinkedHashMap<>();
        result.put("P04_rt_5",new int[][]{{780,158,802,219}});result.put("P05_rt_8",new int[][]{{111,483,162,594}});
        result.put("P07_rt_8",new int[][]{{164,627,180,745},{191,627,209,725}});
        result.put("P11_rt_12",new int[][]{{123,1207,151,1259}});result.put("P13_rt_4",new int[][]{{435,66,456,182}});
        result.put("P14_rt_4",new int[][]{{110,484,129,576}});result.put("P18_rt_2",new int[][]{{423,54,442,117},{392,53,412,207}});
        result.put("P18_rt_14",new int[][]{{102,1313,125,1388},{72,1314,94,1468}});result.put("P19_rt_2",new int[][]{{100,499,134,572}});
        result.put("P19_rt_11",new int[][]{{542,1250,565,1335},{578,1258,595,1319}});
        result.put("P24_rt_1",new int[][]{{641,25,663,164}});result.put("P25_rt_7",new int[][]{{287,515,307,592}});
        result.put("P28_rt_1",new int[][]{{948,31,963,114},{978,31,993,92},{966,116,977,132}});return result;
    }
    static void synthetic(){
        BufferedImage image=new BufferedImage(120,230,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,120,230);g.setColor(Color.BLACK);
        for(int y=35;y<170;y+=22){g.fillRect(40,y,12,3);g.fillRect(40,y+5,3,7);}
        for(int y=28;y<210;y+=6)g.fillRect(70,y,3,3);g.dispose();
        JSONObject prediction=new JSONObject().put("boxes",new JSONArray().put(new JSONArray(new int[]{30,20,90,218}))).put("box_types",new JSONArray().put("text_bubble")).put("scores",new JSONArray().put(.95));
        List<Region> before=predict(image,prediction,true),after=predict(image,prediction,false);
        ok(before.size()==1&&after.size()==1,"fragmented glyph fixture preserves paragraph identity");
        int[][] glyphs={{40,35,52,170}};
        ok(coverage(image,after.get(0).lines,glyphs)>.98,"many punctuation components do not suppress fragmented body column");
        ok(coverage(image,before.get(0).lines,glyphs)<.5,"synthetic fragmented-column test exposes the original failure");
    }
    static void visual(Path out,String key,BufferedImage image,Region before,Region after,int[][] bodies)throws Exception{
        Rect b=after.box;int left=Math.max(0,b.left-15),top=Math.max(0,b.top-15),right=Math.min(image.getWidth(),b.right+15),bottom=Math.min(image.getHeight(),b.bottom+15),w=right-left,h=bottom-top;
        BufferedImage result=new BufferedImage(w*3,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=result.createGraphics();
        for(int n=0;n<3;n++)g.drawImage(image,n*w,0,(n+1)*w,h,left,top,right,bottom,null);
        g.setColor(Color.RED);for(Rect r:before.lines)g.drawRect(w+r.left-left,r.top-top,r.right-r.left,r.bottom-r.top);
        g.setColor(new Color(0,180,70));for(Rect r:after.lines)g.drawRect(w*2+r.left-left,r.top-top,r.right-r.left,r.bottom-r.top);
        g.dispose();ImageIO.write(result,"png",out.resolve(key+"_原图_旧锚框_新锚框.png").toFile());
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]),out=Paths.get(args[1]);String sourceHash=args.length>2?hash(Paths.get(args[2])):"not_recorded";
        Files.createDirectories(out);synthetic();Map<String,int[][]> reference=reference();JSONArray records=new JSONArray(),restored=new JSONArray();int paragraphs=0,changed=0,emptyBefore=0,emptyAfter=0;
        for(int page=1;page<=30;page++){
            Path folder=root.resolve(String.format("逐页/第%02d页",page));BufferedImage image=ImageIO.read(folder.resolve("原图.png").toFile());JSONObject raw=new JSONObject(Files.readString(folder.resolve("检测原始结果.json")));
            List<Region> before=predict(image,raw,true),after=predict(image,raw,false);ok(before.size()==after.size(),"saved prediction paragraph count unchanged on page "+page);
            for(int i=0;i<after.size();i++){
                Region a=before.get(i),b=after.get(i);String key=String.format("P%02d_",page)+b.id;paragraphs++;
                ok(a.id.equals(b.id)&&boxes(List.of(a.box)).toString().equals(boxes(List.of(b.box)).toString())&&a.vertical==b.vertical,"detected paragraph coordinates and reading direction unchanged "+key);
                ok(Objects.equals(a.contextBox==null?null:boxes(List.of(a.contextBox)).toString(),b.contextBox==null?null:boxes(List.of(b.contextBox)).toString()),"bubble context unchanged "+key);
                if(a.lines.isEmpty())emptyBefore++;if(b.lines.isEmpty())emptyAfter++;
                ok(a.lines.isEmpty()||!b.lines.isEmpty(),"previous nonempty anchors never become empty "+key);
                boolean different=!boxes(a.lines).toString().equals(boxes(b.lines).toString());if(different)changed++;
                for(Rect line:b.lines)ok(line.left>=0&&line.top>=0&&line.right<=image.getWidth()&&line.bottom<=image.getHeight()&&line.right>line.left&&line.bottom>line.top,"anchor is a valid bounded pixel area "+key);
                JSONObject item=new JSONObject().put("id",key).put("target",boxes(List.of(b.box)).get(0)).put("before",boxes(a.lines)).put("after",boxes(b.lines));
                if(reference.containsKey(key)){
                    int[][] bodies=reference.get(key);double oldCoverage=coverage(image,a.lines,bodies),newCoverage=coverage(image,b.lines,bodies);
                    ok(newCoverage>=.95,"manually inspected body ink restored "+key);ok(newCoverage>oldCoverage+.25,"real-case anchor regression exposes old omission "+key);
                    item.put("manualBodyReference",new JSONArray(bodies)).put("bodyCoverageBefore",oldCoverage).put("bodyCoverageAfter",newCoverage);restored.put(key);visual(out,key,image,a,b,bodies);
                }
                if(key.equals("P05_rt_8")||key.equals("P14_rt_4")){
                    int[][] protect=key.equals("P05_rt_8")?new int[][]{{172,466,181,605}}:new int[][]{{92,463,98,593}};
                    ok(coverage(image,b.lines,protect)==0,"side balloon border does not become a body anchor "+key);item.put("protectedBorderReference",new JSONArray(protect));
                }
                records.put(item);
            }
        }
        ok(restored.length()==reference.size(),"every manual source reference matched current predictions");
        if(args.length>2)ok(sourceHash.equals(hash(Paths.get(args[2]))),"production anchor source unchanged during regression");
        JSONObject result=new JSONObject().put("passed",true).put("checks",checks).put("pages",30).put("paragraphs",paragraphs).put("changedAnchors",changed).put("emptyBefore",emptyBefore).put("emptyAfter",emptyAfter).put("restoredManualCases",restored)
            .put("productionSourceSha256",sourceHash).put("paidApiUsed",false).put("modelInferencePerformed",false).put("segmentationGroundTruth",false).put("scope","Saved real model boxes; production anchor extraction. Body interior reference areas manually reviewed; not full OCR recall or erasure-quality acceptance.").put("regions",records);
        Files.writeString(out.resolve("锚框回归.json"),result.toString(2));System.out.println("V090AnchorChecks: "+checks+" checks passed; paragraphs="+paragraphs+", changed="+changed+", restored="+restored.length());
    }
    static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
