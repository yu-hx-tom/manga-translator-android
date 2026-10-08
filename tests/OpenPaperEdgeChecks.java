package cn.local.manga;
import java.util.Arrays;
public class OpenPaperEdgeChecks {
 static int checks;
 static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static void rect(int[] p,int w,int x1,int y1,int x2,int y2,int c){for(int y=y1;y<y2;y++)for(int x=x1;x<x2;x++)p[y*w+x]=c;}
 public static void main(String[] args){
  int w=120,h=150;int[] pixels=new int[w*h];Arrays.fill(pixels,0xffffffff);
  for(int y=35;y<105;y+=25)rect(pixels,w,44,y,67,y+4,0xff000000);
  int[] clean=pixels.clone();int[][] lines={{45,32,61,105}};
  WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(pixels,w,h,lines,24,new int[]{45,32,61,105});
  ok(mask.whiteBackground&&mask.backgroundKind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,"clipped complete strokes on open white paper recovered");
  WhiteBubbleCleaner.apply(clean,mask);
  for(int i=0;i<pixels.length;i++)if(pixels[i]==0xff000000)ok(clean[i]==0xffffffff,"all clipped strokes removed");
  int[] protectedImage=pixels.clone();rect(protectedImage,w,38,45,41,49,0xff000000);rect(protectedImage,w,68,20,71,120,0xff000000);
  WhiteBubbleCleaner.Mask guarded=WhiteBubbleCleaner.forText(protectedImage,w,h,lines,24);
  int[] guardedOut=protectedImage.clone();WhiteBubbleCleaner.apply(guardedOut,guarded);
  ok(guarded.whiteBackground,"separate glyphs remain recoverable with nearby art");
  for(int i=0;i<pixels.length;i++)if(pixels[i]!=protectedImage[i])ok(guardedOut[i]==protectedImage[i],"unanchored mark and connected art protected");
  int[] attached=pixels.clone();rect(attached,w,44,0,46,110,0xff000000);
  WhiteBubbleCleaner.Mask connected=WhiteBubbleCleaner.forText(attached,w,h,lines,24);
  for(int y=0;y<110;y++)ok(!connected.erase[y*w+44],"ink connected outside ROI retained");
  int[] colored=pixels.clone();for(int i=0;i<colored.length;i++)if(colored[i]==0xffffffff)colored[i]=0xffdfbfa0;
  ok(!WhiteBubbleCleaner.forText(colored,w,h,lines,24).whiteBackground,"colored paper refused");
  int[] original=pixels.clone();WhiteBubbleCleaner.forText(pixels,w,h,lines,24);ok(Arrays.equals(original,pixels),"source immutable");
  boolean[] foreign=WhiteBubbleCleaner.rectangleMask(mask,w,h,new int[]{45,32,61,105},new int[][]{{60,30,68,108}});
  for(int y=28;y<110;y++)for(int x=58;x<70;x++)ok(!foreign[y*w+x],"foreign paragraph protected");
  System.out.println("OpenPaperEdgeChecks: "+checks+" checks passed");
 }
}
