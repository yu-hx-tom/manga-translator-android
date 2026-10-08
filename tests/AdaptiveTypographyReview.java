package cn.local.manga;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.*;

/** Feedback-ROI typography comparison. Uses one frozen cleanup basis for both font versions; no API. */
public final class AdaptiveTypographyReview {
    static JSONObject read(Path p)throws Exception{return new JSONObject(Files.readString(p).replaceFirst("^\\ufeff",""));}
    static int[] ints(JSONArray a){int[] r=new int[a.length()];for(int i=0;i<r.length;i++)r[i]=a.getInt(i);return r;}
    static int[][] boxes(JSONArray a){int[][] r=new int[a.length()][];for(int i=0;i<r.length;i++)r[i]=ints(a.getJSONArray(i));return r;}
    static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static BufferedImage image(int[] p,int w,int h){BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,w,h,p,0,w);return b;}
    static BufferedImage copy(BufferedImage b){return image(b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()),b.getWidth(),b.getHeight());}
    static void draw(Graphics2D g,float size,float step,int[] points,float[][] cells,boolean outlined,Color color){
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font primary=new Font("Microsoft YaHei",Font.PLAIN,1).deriveFont(size),symbols=new Font("Segoe UI Symbol",Font.PLAIN,1).deriveFont(size);
        for(int i=0;i<points.length;i++){Graphics2D cell=(Graphics2D)g.create();Font font=primary.canDisplay(points[i])?primary:symbols;if(!font.canDisplay(points[i]))throw new AssertionError("Missing preview glyph U+"+Integer.toHexString(points[i]));cell.setFont(font);FontMetrics fm=cell.getFontMetrics();float x=cells[i][0],y=cells[i][1];cell.clip(new Rectangle2D.Float(x,y,step,step));
            String glyph=new String(Character.toChars(points[i]));float scale=outlined?Math.min(1f,step*.78f/Math.max(1f,Math.max(fm.stringWidth(glyph),fm.getAscent()+fm.getDescent()))):1f;
            cell.translate(x+step/2,y+step/2);cell.scale(scale,scale);float baseline=(fm.getAscent()-fm.getDescent())/2f;
            Shape outline=cell.getFont().createGlyphVector(cell.getFontRenderContext(),glyph).getOutline(-fm.stringWidth(glyph)/2f,baseline);
            if(outlined){cell.setColor(Color.WHITE);cell.setStroke(new BasicStroke(Math.max(.1f,size*.14f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));cell.draw(outline);}
            cell.setColor(color);cell.fill(outline);cell.dispose();}
    }
    static float[][] cells(BubbleLayout.Plan p){float[][] r=new float[p.cells.length][2];for(int i=0;i<r.length;i++){r[i][0]=p.cells[i][0];r[i][1]=p.cells[i][1];}return r;}
    static float[][] cells(BeforeBubbleLayout.Plan p){float[][] r=new float[p.cells.length][2];for(int i=0;i<r.length;i++){r[i][0]=p.cells[i][0];r[i][1]=p.cells[i][1];}return r;}
    static float[][] cells(NearbyTextLayout.Plan p){float[][] r=new float[p.points.length][2];for(int i=0;i<r.length;i++){r[i][0]=p.cellLeft(i);r[i][1]=p.cellTop(i);}return r;}
    static float[][] cells(BeforeNearbyTextLayout.Plan p){float[][] r=new float[p.points.length][2];for(int i=0;i<r.length;i++){r[i][0]=p.cellLeft(i);r[i][1]=p.cellTop(i);}return r;}
    static void drawMixed(Graphics2D g,NearbyTextLayout.Plan p,Color color){for(int i=0;i<p.points.length;i++)draw(g,p.cellFont(i),p.cellStep(i),new int[]{p.points[i]},new float[][]{{p.cellLeft(i),p.cellTop(i)}},true,color);}
    static void drawMixed(Graphics2D g,BubbleLayout.Plan p,Color color){
        for(int i=0;i<p.codepoints.length;i++){float size=p.cellFont(i);int[] c=p.cells[i];Font primary=new Font("Microsoft YaHei",Font.PLAIN,1).deriveFont(size),symbols=new Font("Segoe UI Symbol",Font.PLAIN,1).deriveFont(size),font=primary.canDisplay(p.codepoints[i])?primary:symbols;
            Graphics2D cell=(Graphics2D)g.create();cell.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);java.awt.font.GlyphVector glyph=font.createGlyphVector(cell.getFontRenderContext(),new String(Character.toChars(p.codepoints[i])));Rectangle2D bounds=glyph.getVisualBounds();float step=p.cellStep(i),scale=(float)Math.min(1,Math.min(Math.max(1,step-2)/Math.max(1,bounds.getWidth()),Math.max(1,step-2)/Math.max(1,bounds.getHeight())));
            cell.clipRect(c[0],c[1],c[2]-c[0],c[3]-c[1]);cell.translate((c[0]+c[2])/2.0,(c[1]+c[3])/2.0);cell.scale(scale,scale);cell.translate(-bounds.getCenterX(),-bounds.getCenterY());cell.setColor(color);cell.fill(glyph.getOutline());cell.dispose();}
    }
    static List<int[]> foreign(JSONObject page,String id,int left,int top){List<int[]> lines=new ArrayList<>();for(Object raw:page.getJSONArray("regions")){JSONObject r=(JSONObject)raw;if(r.getString("id").equals(id))continue;
        int[][] bs=r.getJSONArray("lines").isEmpty()?new int[][]{ints(r.getJSONArray("box"))}:boxes(r.getJSONArray("lines"));for(int[] b:bs)lines.add(new int[]{b[0]-left,b[1]-top,b[2]-left,b[3]-top});}return lines;}
    static void write(Path p,JSONObject value)throws Exception{Files.createDirectories(p.getParent());Files.writeString(p,value.toString(2));}
    public static void main(String[] args)throws Exception{
        Path source=Paths.get(args[0]),out=Paths.get(args[1]),project=Paths.get(args[2]);Files.createDirectories(out);
        Map<String,JSONObject> manifest=new HashMap<>();for(Object raw:read(source.resolve("cleanup_manifest.json")).getJSONArray("regions")){JSONObject r=(JSONObject)raw;manifest.put(r.getString("id"),r);}
        Set<String> samples=new LinkedHashSet<>(Arrays.asList("P06_rt_1","P07_rt_11","P07_rt_13","P13_rt_8","P14_rt_4","P14_rt_6","P15_rt_4","P15_rt_6","P15_rt_7","P16_rt_2","P16_rt_4"));
        JSONArray rows=new JSONArray();int cellsChecked=0;
        for(Object pageRaw:read(source.resolve("translation_plan.json")).getJSONArray("pages")){JSONObject page=(JSONObject)pageRaw;BufferedImage original=null;
            for(Object rowRaw:page.getJSONArray("regions")){JSONObject row=(JSONObject)rowRaw;String id=row.getString("id");if(!samples.contains(id))continue;if(original==null)original=ImageIO.read(Paths.get(page.getString("sourcePage")).toFile());
                String text=row.getString("zh");boolean local=row.getString("route").equals("local_white"),red=id.equals("P15_rt_7");JSONObject api=manifest.get(id);
                int[] crop=ints((local?row:api).getJSONArray(local?"renderRoi":"sourceCrop"));int w=crop[2]-crop[0],h=crop[3]-crop[1];
                int[] pixels=original.getRGB(crop[0],crop[1],w,h,null,0,w);BufferedImage before=image(pixels,w,h),basis;
                float oldFont,newFont,oldStep,newStep;float[][] oldCells,newCells;int[] points;boolean[] safe=null;NearbyTextLayout.Plan currentNearby=null;BubbleLayout.Plan currentBubble=null;
                if(local){int[][] lines=boxes(row.getJSONArray("clippedLines"));int estimate=row.getInt("estimate");BeforeWhiteBubbleCleaner.Mask mask=BeforeWhiteBubbleCleaner.forText(pixels,w,h,lines,estimate);
                    int[][] others=foreign(page,id,crop[0],crop[1]).toArray(new int[0][]);safe=BeforeWhiteBubbleCleaner.excludeForeign(mask.interior,w,h,others);
                    boolean[] erase=BeforeWhiteBubbleCleaner.excludeForeign(mask.erase,w,h,others);int[] fill=mask.fillColors;
                    if(id.startsWith("P16_")){int[] paragraph=ints(row.getJSONArray("box"));paragraph[0]-=crop[0];paragraph[2]-=crop[0];paragraph[1]-=crop[1];paragraph[3]-=crop[1];WhiteBubbleCleaner.Mask production=WhiteBubbleCleaner.forText(pixels,w,h,lines,estimate,paragraph);
                        safe=WhiteBubbleCleaner.excludeForeign(production.interior,w,h,others);erase=WhiteBubbleCleaner.excludeForeign(production.erase,w,h,others);fill=production.fillColors;}
                    int[] clean=pixels.clone();for(int p=0;p<clean.length;p++)if(erase[p])clean[p]=fill==null?0xffffffff:fill[p];basis=image(clean,w,h);
                    BeforeBubbleLayout.Plan old=BeforeBubbleLayout.plan(text,safe,w,h,row.getBoolean("vertical"),estimate,lines);BubbleLayout.Plan current=BubbleLayout.plan(text,safe,w,h,row.getBoolean("vertical"),estimate,lines);
                    currentBubble=current;oldFont=old.font;newFont=current.font;oldStep=old.step;newStep=current.step;oldCells=cells(old);newCells=cells(current);points=current.codepoints;
                    if(id.equals("P16_rt_4")){if(points[0]!='啊'||points[1]!='，'||current.cells[0][1]>=133||current.cells[1][1]>=133)throw new AssertionError(id+" lost opening utterance in upper lobe");for(int i=2;i<points.length;i++)if(current.cells[i][1]<133)throw new AssertionError(id+" placed sentence ending above its opening");}
                    if(id.equals("P16_rt_2")){boolean lowerStarted=false;for(int[] cell:current.cells){if(cell[1]>=180)lowerStarted=true;else if(lowerStarted)throw new AssertionError(id+" reversed stacked source groups");}if(!lowerStarted||current.cells[0][1]>=180)throw new AssertionError(id+" missing expected stacked reading order");}
                    for(int[] c:current.cells)for(int yy=c[1];yy<c[3];yy++)for(int xx=c[0];xx<c[2];xx++)if(!safe[yy*w+xx])throw new AssertionError(id+" escaped safe bubble");
                }else{basis=red?copy(before):ImageIO.read(source.resolve("requests/"+id+"/合成审查/生产保护合成_仅去字.png").toFile());
                    BeforeNearbyTextLayout.Plan old=BeforeNearbyTextLayout.forCrop(page.getInt("width"),page.getInt("height"),ints(row.getJSONArray("box")),crop[0],crop[1],row.getBoolean("vertical"),text);
                    NearbyTextLayout.Plan current=NearbyTextLayout.forCrop(page.getInt("width"),page.getInt("height"),ints(row.getJSONArray("box")),crop[0],crop[1],row.getBoolean("vertical"),text,boxes(row.getJSONArray("lines")));
                    currentNearby=current;
                    oldFont=old.font;newFont=current.font;oldStep=old.step;newStep=current.step;oldCells=cells(old);newCells=cells(current);points=current.points;
                    for(int i=0;i<points.length;i++)if(newCells[i][0]<current.box[0]-.001||newCells[i][1]<current.box[1]-.001||newCells[i][0]+current.cellStep(i)>current.box[2]+.001||newCells[i][1]+current.cellStep(i)>current.box[3]+.001)throw new AssertionError(id+" escaped original target");
                }
                BufferedImage oldImage=copy(basis),newImage=copy(basis);Graphics2D og=oldImage.createGraphics(),ng=newImage.createGraphics();
                if(!local){int[] b=ints(row.getJSONArray("box"));Area clip=new Area(new Rectangle(b[0]-crop[0],b[1]-crop[1],b[2]-b[0],b[3]-b[1]));for(int[] p:boxes(api.getJSONArray("protectedBoxes")))clip.subtract(new Area(new Rectangle(p[0]-2,p[1]-2,p[2]-p[0]+4,p[3]-p[1]+4)));og.clip(clip);ng.clip(clip);}
                draw(og,oldFont,oldStep,points,oldCells,!local,red?Color.RED:Color.BLACK);if(currentNearby!=null)drawMixed(ng,currentNearby,red?Color.RED:Color.BLACK);else drawMixed(ng,currentBubble,Color.BLACK);og.dispose();ng.dispose();cellsChecked+=points.length;
                int outside=0;int[] b=ints(row.getJSONArray("box"));for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean allowed=local?safe[y*w+x]:x+crop[0]>=b[0]&&x+crop[0]<b[2]&&y+crop[1]>=b[1]&&y+crop[1]<b[3];if(!allowed&&newImage.getRGB(x,y)!=basis.getRGB(x,y))outside++;}if(outside!=0)throw new AssertionError(id+" changed outside text safety area");
                int viewL=Math.max(0,b[0]-crop[0]-35),viewT=Math.max(0,b[1]-crop[1]-35),viewR=Math.min(w,b[2]-crop[0]+35),viewB=Math.min(h,b[3]-crop[1]+35),vw=viewR-viewL,vh=viewB-viewT;
                BufferedImage montage=new BufferedImage(vw*3,Math.max(80,vh+46),BufferedImage.TYPE_INT_RGB);Graphics2D g=montage.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,montage.getWidth(),montage.getHeight());g.setFont(new Font("Microsoft YaHei",Font.PLAIN,15));
                BufferedImage[] imgs={before,oldImage,newImage};String[] titles={id+" 原图","原排版 "+String.format(Locale.ROOT,"%.1f",oldFont)+"px","自适应 "+String.format(Locale.ROOT,"%.1f",newFont)+"px"};
                for(int i=0;i<3;i++){g.setColor(Color.BLACK);g.drawString(titles[i],i*vw+5,25);g.drawImage(imgs[i],i*vw,46,(i+1)*vw,46+vh,viewL,viewT,viewR,viewB,null);}g.dispose();
                Path dir=out.resolve(id);Files.createDirectories(dir);ImageIO.write(montage,"png",dir.resolve("原图_旧字号_自适应字号.png").toFile());ImageIO.write(newImage,"png",dir.resolve("新排版ROI.png").toFile());
                JSONObject entry=new JSONObject().put("id",id).put("oldFont",oldFont).put("newFont",newFont).put("sourceEstimate",row.optInt("estimate")).put("characters",points.length).put("cells",new JSONArray(newCells)).put("allCellsSafe",true).put("outsideSafetyChangedPixels",outside).put("color",red?"red_fallback":"black").put("sameFrozenCleanupBasis",true).put("cleanupSource",local?"pre_v092_local_cleanup":"v091_actual_api_composite_or_preserved_original").put("comparisonFile",id+"/原图_旧字号_自适应字号.png").put("sourcePageSha256",sha(Paths.get(page.getString("sourcePage"))));
                if(id.startsWith("P16_"))entry.put("cleanupSource","current_v092_production_cleanup");
                if(currentNearby!=null){JSONArray fonts=new JSONArray();for(int i=0;i<points.length;i++)fonts.put(currentNearby.cellFont(i));entry.put("fontsByGlyph",fonts).put("sourceGrouped",currentNearby.sourceGrouped);}
                if(currentBubble!=null){JSONArray fonts=new JSONArray(),steps=new JSONArray();for(int i=0;i<points.length;i++){fonts.put(currentBubble.cellFont(i));steps.put(currentBubble.cellStep(i));}entry.put("fontsByGlyph",fonts).put("stepsByGlyph",steps);}
                write(dir.resolve("结果.json"),entry);rows.put(entry);System.out.println(id+" "+oldFont+" -> "+newFont);
            }if(original!=null)original.flush();
        }
        if(rows.length()!=samples.size())throw new AssertionError("Missing real feedback sample");JSONObject hashes=new JSONObject();for(String name:new String[]{"BubbleLayout","NearbyTextLayout"})hashes.put(name,sha(project.resolve("app/src/main/java/cn/local/manga/"+name+".java")));
        write(out.resolve("真实反馈字体对照.json"),new JSONObject().put("samples",rows).put("sampleCount",rows.length()).put("cellsChecked",cellsChecked).put("allCellsSafe",true).put("networkCalls",0).put("androidCanvasVerified",false).put("productionSourceSha256",hashes).put("scope","Production layout with desktop glyph rendering; identical frozen cleanup basis isolates typography from the separately repaired erasure"));
    }
}
