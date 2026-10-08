package cn.local.manga;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Open white caption next to connected artwork: erase glyphs, reserve art, preserve alpha. */
public final class GlyphIsolationChecks {
    static int checks;
    static void ok(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    public static void main(String[] args)throws Exception{
        int w=130,h=200;int[] source=new int[w*h];Arrays.fill(source,0xffffffff);
        // Hair/outline enters the analyzed strip but is connected to the crop exterior.
        for(int y=18;y<184;y++)for(int x=42;x<45;x++)source[y*w+x]=0xff000000;
        for(int x=0;x<45;x++)source[90*w+x]=0xff000000;
        boolean[] target=new boolean[w*h];for(int y=30;y<170;y+=22)for(int yy=y;yy<y+12;yy++)for(int x=61;x<72;x++)if(x<63||x>69||yy<y+2||yy>y+9){target[yy*w+x]=true;source[yy*w+x]=0xff000000;}
        int[][] lines={{44,24,79,178}};WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.findPreferWhitePaper(source,w,h,lines,22);
        ok(mask.whiteBackground,"open white caption obtains glyph-local cleanup");int[] cleaned=source.clone();WhiteBubbleCleaner.apply(cleaned,mask);int residual=0,artChanged=0,outsideChanged=0;
        for(int p=0;p<source.length;p++){if(target[p]&&cleaned[p]!=0xffffffff)residual++;if(!target[p]&&source[p]==0xff000000&&cleaned[p]!=source[p])artChanged++;if(cleaned[p]!=source[p]&&!mask.erase[p])outsideChanged++;}
        ok(residual==0,"all synthetic original glyph strokes cleared");ok(artChanged==0,"connected hair and border preserved");ok(outsideChanged==0,"cleanup affects only explicit pixel mask");
        String translation="中".repeat(24);BubbleLayout.Plan plan=BubbleLayout.plan(translation,mask.interior,w,h,true,22,lines);ok(plan.cells.length==24,"complete translation fits the production plan without a legacy capacity hint");
        for(int[] cell:plan.cells)for(int y=cell[1];y<cell[3];y++)for(int x=cell[0];x<cell[2];x++)if(!mask.interior[y*w+x])throw new AssertionError("unsafe cell");checks++;
        BufferedImage transparent=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D g=transparent.createGraphics();g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,plan.font));g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for(int i=0;i<plan.cells.length;i++){int[] c=plan.cells[i];Shape clip=g.getClip();g.clipRect(c[0],c[1],c[2]-c[0],c[3]-c[1]);g.drawString("中",c[0],c[1]+plan.font);g.setClip(clip);}g.dispose();int alphaZero=0,alphaInk=0;for(int p:transparent.getRGB(0,0,w,h,null,0,w)){if((p>>>24)==0)alphaZero++;else alphaInk++;}
        ok(alphaZero>w*h*.5&&alphaInk>0,"Chinese layer contains transparent background and visible ink");
        int[] gray=source.clone();for(int p=0;p<gray.length;p++)if(gray[p]==0xffffffff)gray[p]=0xffbcbcbc;ok(!WhiteBubbleCleaner.findPreferWhitePaper(gray,w,h,lines,22).whiteBackground,"open gray paper does not gain fabricated closure");
        int[] colored=source.clone();colored[50*w+50]=0xffff0000;ok(!WhiteBubbleCleaner.findPreferWhitePaper(colored,w,h,lines,22).whiteBackground,"colored artwork inside fallback scope prevents cleanup");
        int[] blank=new int[w*h];Arrays.fill(blank,0xffffffff);ok(!WhiteBubbleCleaner.findPreferWhitePaper(blank,w,h,lines,22).whiteBackground,"blank page has no invented removable glyphs");
        Path out=Paths.get(args[0]);Files.createDirectories(out);BufferedImage before=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);before.setRGB(0,0,w,h,source,0,w);BufferedImage after=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);after.setRGB(0,0,w,h,cleaned,0,w);ImageIO.write(before,"png",out.resolve("开放白底原字与画线.png").toFile());ImageIO.write(after,"png",out.resolve("仅去字保留画线.png").toFile());ImageIO.write(transparent,"png",out.resolve("透明竖排中文层.png").toFile());
        System.out.println("GlyphIsolationChecks: "+checks+" checks passed; glyph mask, art guard, full translation layout and alpha");
    }
}
