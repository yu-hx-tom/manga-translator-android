package cn.local.manga;
import java.util.Arrays;
public class OffWhiteFrameChecks {
 static void rect(int[] p,int w,int a,int b,int c,int d,int color){for(int y=b;y<d;y++)for(int x=a;x<c;x++)p[y*w+x]=color;}
 public static void main(String[] args){
  int w=220,h=200;int[] p=new int[w*h];Arrays.fill(p,0xffefefef);
  rect(p,w,20,20,190,24,0xff000000);rect(p,w,20,175,190,180,0xff000000);rect(p,w,20,20,25,180,0xff000000);rect(p,w,185,20,190,180,0xff000000);rect(p,w,186,88,215,95,0xff000000);
  int[] background=p.clone();rect(p,w,60,50,80,145,0xff000000);rect(p,w,120,50,140,145,0xff000000);rect(p,w,70,85,188,95,0xff000000);
  WhiteBubbleCleaner.Mask m=WhiteBubbleCleaner.forText(p,w,h,new int[0][],120,new int[]{55,45,180,150});
  if(!m.whiteBackground||m.backgroundKind!=WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER)throw new AssertionError("off-white connected ink refused");
  boolean[] erase=WhiteBubbleCleaner.rectangleMask(m,w,h,new int[]{55,45,180,150},new int[0][]);int recovered=0,damaged=0,whitePatch=0;
  for(int i=0;i<p.length;i++)if(erase[i]){if(background[i]==0xff000000)damaged++;if(p[i]!=background[i])recovered++;if(m.fillColors==null||m.fillColors[i]!=0xffefefef)whitePatch++;}
  if(recovered<3000||damaged!=0||whitePatch!=0)throw new AssertionError("recovered="+recovered+" damaged="+damaged+" whitePatch="+whitePatch);
  int[] patterned=p.clone();for(int y=24;y<175;y++)for(int x=25;x<185;x++)if(p[y*w+x]==0xffefefef)patterned[y*w+x]=((x/3+y/3)%2==0)?0xffebebeb:0xffffffff;
  WhiteBubbleCleaner.Mask noisy=WhiteBubbleCleaner.forText(patterned,w,h,new int[0][],120,new int[]{55,45,180,150});
  if(noisy.evidence.equals("closed_paper_inner_ink_with_frame_guard"))throw new AssertionError("patterned paper admitted");
  System.out.println("OffWhiteFrameChecks passed: recovered="+recovered+" protectedDamage="+damaged+" whitePatches="+whitePatch+", patterned paper refused");
 }
}
