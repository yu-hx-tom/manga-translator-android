package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
public class LargeHandwritingChecks {
 static int checks;
 static void ok(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  int w=220,h=230;BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
  g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setColor(Color.BLACK);g.setStroke(new BasicStroke(4));
  g.drawPolygon(new int[]{30,145,175,170,150,32,24},new int[]{25,18,50,180,205,192,120},7);
  int[] background=image.getRGB(0,0,w,h,null,0,w);
  g.fillRect(40,50,27,125);g.fillRect(138,50,27,125);g.fillRect(60,92,87,31);g.dispose();
  int[] source=image.getRGB(0,0,w,h,null,0,w);int[][] lines={{39,49,166,176}};int[] box={39,49,166,176};
  WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(source,w,h,lines,127,box);
  ok(mask.whiteBackground&&mask.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,"large dense handwriting near irregular outline accepted");
  boolean[] erase=WhiteBubbleCleaner.rectangleMask(mask,w,h,box,new int[0][]);int target=0,missed=0,damage=0;
  for(int i=0;i<source.length;i++){if(source[i]!=background[i]){target++;if(!erase[i])missed++;}if(background[i]!=0xffffffff&&erase[i])damage++;}
  ok(target>8000,"fixture includes large dense connected strokes");
  ok(missed==0,"all large handwritten strokes recovered");ok(damage==0,"irregular outline protected");
  // Same glyph physically joined to the frame: never classify the frame as a glyph.
  int[] attached=source.clone();for(int y=58;y<62;y++)for(int x=160;x<177;x++)attached[y*w+x]=0xff000000;
  WhiteBubbleCleaner.Mask joined=WhiteBubbleCleaner.forText(attached,w,h,lines,127,box);
  boolean[] joinedErase=WhiteBubbleCleaner.rectangleMask(joined,w,h,box,new int[0][]);
  for(int i=0;i<background.length;i++)if(background[i]!=0xffffffff)ok(!joinedErase[i],"joined frame preserved");
  int[] color=source.clone();for(int i=0;i<color.length;i++)if(color[i]==0xffffffff)color[i]=0xffd0b080;
  ok(!WhiteBubbleCleaner.forText(color,w,h,lines,127,box).whiteBackground,"color paper refused");
  int[] snap=source.clone();WhiteBubbleCleaner.forText(source,w,h,lines,127,box);ok(Arrays.equals(snap,source),"source immutable");
  System.out.println("LargeHandwritingChecks: "+checks+" assertions passed, targetInk="+target+", residual="+missed+", frameDamage="+damage);
 }
}
