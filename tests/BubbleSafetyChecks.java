package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;
public final class BubbleSafetyChecks {
    static int checks;
    static void ok(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static Graphics2D graphics(BufferedImage image){Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);return g;}
    static BufferedImage image(int[] pixels,int w,int h){BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,w,h,pixels,0,w);return image;}
    public static void main(String[] args)throws Exception{
        Path out=Paths.get(args.length>0?args[0]:"bubble-proof");Files.createDirectories(out);int w=400,h=440;
        BufferedImage original=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D g=graphics(original);g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setColor(Color.BLACK);g.setStroke(new BasicStroke(5));g.drawOval(55,20,280,390);
        // Safe synthetic non-text illustration, outside the dialogue envelope.
        g.fillOval(350,50,30,30);g.drawLine(365,80,365,130);g.drawLine(365,90,342,115);g.drawLine(365,90,388,115);g.dispose();
        int[] background=original.getRGB(0,0,w,h,null,0,w);g=graphics(original);g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,28));
        String text="こんにちは友達";for(int i=0;i<text.length();i++)g.drawString(text.substring(i,i+1),180,91+i*31);
        g.setFont(new Font("Microsoft YaHei",Font.PLAIN,10));for(int i=0;i<7;i++)g.drawString("あ",213,87+i*31);
        g.fillOval(207,332,4,4);g.fillRect(175,63,3,3);g.dispose();
        int[] before=original.getRGB(0,0,w,h,null,0,w);int[][] lines={{179,67,210,321}};
        WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.find(before,w,h,lines,28);int[] after=before.clone();WhiteBubbleCleaner.apply(after,mask);
        ok(mask.whiteBackground,"closed white bubble recognized");ok(mask.pixels>100,"cleanup mask includes foreground ink");
        int darkTargets=0,remaining=0,protectedChanges=0,outside=0,ruby=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;
            boolean target=background[i]!=before[i]&&(before[i]&255)<235;
            if(target){darkTargets++;if((after[i]&255)<245)remaining++;if(x>=213)ruby++;}
            if(background[i]==before[i]&&background[i]!=0xffffffff&&after[i]!=before[i])protectedChanges++;
            if((x<158||x>231||y<45||y>343)&&after[i]!=before[i])outside++;
        }
        ok(darkTargets>500&&ruby>20,"fixture contains actual Japanese glyphs plus small furigana");ok(remaining==0,"main Japanese text, furigana and punctuation removed");ok(protectedChanges==0,"bubble boundary and illustration pixels are unchanged");ok(outside==0,"no changes outside expanded dialogue envelope");
        int cropX=159,cropY=47,cropW=72,cropH=297;int[] crop=original.getRGB(cropX,cropY,cropW,cropH,null,0,cropW);
        WhiteBubbleCleaner.Mask cropped=WhiteBubbleCleaner.find(crop,cropW,cropH,new int[][]{{20,20,51,274}},28);WhiteBubbleCleaner.apply(crop,cropped);
        int cropResidual=0;for(int y=0;y<cropH;y++)for(int x=0;x<cropW;x++){int source=(y+cropY)*w+x+cropX;if(background[source]!=before[source]&&(before[source]&255)<235&&(crop[y*cropW+x]&255)<245)cropResidual++;}
        ok(!cropped.whiteBackground&&cropped.pixels==0,"boundary missing from tight ROI must reject and preserve source");
        int[] borderCrop=original.getRGB(cropX,cropY,cropW,cropH,null,0,cropW);for(int y=0;y<cropH;y++)for(int x=65;x<69;x++)borderCrop[y*cropW+x]=0xff000000;
        WhiteBubbleCleaner.Mask nearBorder=WhiteBubbleCleaner.find(borderCrop,cropW,cropH,new int[][]{{20,20,51,274}},28);WhiteBubbleCleaner.apply(borderCrop,nearBorder);int borderDamage=0;for(int y=0;y<cropH;y++)for(int x=65;x<69;x++)if(borderCrop[y*cropW+x]!=0xff000000)borderDamage++;
        ok(!nearBorder.whiteBackground&&borderDamage==0,"open boundary rejected without damaging border");
        int[] colored=before.clone();for(int i=0;i<colored.length;i++)if(colored[i]==0xffffffff)colored[i]=0xffdfbfa0;
        WhiteBubbleCleaner.Mask complex=WhiteBubbleCleaner.find(colored,w,h,lines,28);ok(!complex.whiteBackground&&complex.pixels==0,"colored background rejected rather than whitened");
        boolean[] overlay=WhiteBubbleCleaner.complexTextArea(colored,w,h,lines);
        ok(overlay!=null,"colored caption retains bounded overlay support");
        BubbleLayout.Plan plan=BubbleLayout.plan("你好朋友",overlay,w,h,true,28,lines);
        int escaped=0;for(int[] cell:plan.cells)for(int y=cell[1];y<cell[3];y++)for(int x=cell[0];x<cell[2];x++)if(!overlay[y*w+x])escaped++;
        ok(escaped==0,"complex overlay stays inside detection geometry");
        ok(WhiteBubbleCleaner.complexTextArea(before,w,h,lines)==null,"white exterior is never accepted as complex overlay");
        ImageIO.write(original,"png",out.resolve("合成原图.png").toFile());
        BubbleLayoutChecks.review(out.resolve("合成原图.png"),out,"合成生产布局",lines,"你好我的朋友",true,true);        String report="{\"checksPassed\":"+checks+",\"targetInkPixels\":"+darkTargets+",\"residualDarkTargetPixels\":"+remaining+",\"changedProtectedPixels\":"+protectedChanges+",\"changedOutsideEnvelopePixels\":"+outside+",\"scope\":\"Production WhiteBubbleCleaner Java algorithm; synthetic white bubble with actual Japanese font/ruby/punctuation; production BubbleLayout cell coordinates with desktop font, not Android screenshot\"}";Files.writeString(out.resolve("cleanup_result.json"),report);System.out.println(report);
    }
}
