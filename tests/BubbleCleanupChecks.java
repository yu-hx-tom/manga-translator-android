package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;
public final class BubbleCleanupChecks {
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
        ok(cropped.whiteBackground&&cropResidual==0,"same algorithm works inside the actual padded detector crop");
        int[] borderCrop=original.getRGB(cropX,cropY,cropW,cropH,null,0,cropW);for(int y=0;y<cropH;y++)for(int x=65;x<69;x++)borderCrop[y*cropW+x]=0xff000000;
        WhiteBubbleCleaner.Mask nearBorder=WhiteBubbleCleaner.find(borderCrop,cropW,cropH,new int[][]{{20,20,51,274}},28);WhiteBubbleCleaner.apply(borderCrop,nearBorder);int borderDamage=0;for(int y=0;y<cropH;y++)for(int x=65;x<69;x++)if(borderCrop[y*cropW+x]!=0xff000000)borderDamage++;
        ok(nearBorder.whiteBackground&&borderDamage==0,"adjacent bubble boundary remains protected during cleanup");
        int[] colored=before.clone();for(int i=0;i<colored.length;i++)if(colored[i]==0xffffffff)colored[i]=0xffdfbfa0;
        WhiteBubbleCleaner.Mask complex=WhiteBubbleCleaner.find(colored,w,h,lines,28);ok(!complex.whiteBackground&&complex.pixels==0,"colored background rejected rather than whitened");
        BufferedImage previewMask=image(before,w,h);for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(mask.erase[y*w+x])previewMask.setRGB(x,y,0xffff4040);
        BufferedImage cleaned=image(after,w,h),translated=image(after,w,h);g=graphics(translated);g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,28));String zh="你好我的朋友";for(int i=0;i<zh.length();i++)g.drawString(zh.substring(i,i+1),182,106+i*34);g.dispose();
        ImageIO.write(original,"png",out.resolve("原图.png").toFile());ImageIO.write(previewMask,"png",out.resolve("清除mask.png").toFile());ImageIO.write(cleaned,"png",out.resolve("清除后.png").toFile());ImageIO.write(translated,"png",out.resolve("译图_桌面排字示意.png").toFile());
        BufferedImage sheet=new BufferedImage(w*4,h+44,BufferedImage.TYPE_INT_RGB);g=graphics(sheet);g.setColor(new Color(0xeaf0ec));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());String[] names={"原图（合成安全样例）","实际 Java 清除 mask","清除后：边线保留","中文排字示意"};BufferedImage[] columns={original,previewMask,cleaned,translated};g.setFont(new Font("Microsoft YaHei",Font.PLAIN,18));for(int i=0;i<4;i++){g.setColor(Color.BLACK);g.drawString(names[i],i*w+12,27);g.drawImage(columns[i],i*w,44,null);}g.dispose();ImageIO.write(sheet,"png",out.resolve("白底气泡对照.png").toFile());
        String report="{\"checksPassed\":"+checks+",\"targetInkPixels\":"+darkTargets+",\"residualDarkTargetPixels\":"+remaining+",\"changedProtectedPixels\":"+protectedChanges+",\"changedOutsideEnvelopePixels\":"+outside+",\"scope\":\"Production WhiteBubbleCleaner Java algorithm; synthetic white bubble with actual Japanese font/ruby/punctuation; desktop Chinese typesetting illustration, not Android screenshot\"}";Files.writeString(out.resolve("cleanup_result.json"),report);System.out.println(report);
    }
}
