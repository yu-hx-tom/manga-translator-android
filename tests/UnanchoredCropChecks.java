package cn.local.manga;
import android.graphics.Rect;
import java.util.List;
public class UnanchoredCropChecks {
 public static void main(String[] args){
  Region r=new Region("x",new Rect(70,50,130,150),List.of(),true,new Rect(20,10,180,190));
  Rect roi=RtDetrRegions.renderBounds(r,220,210);
  if(roi.left>=20||roi.top>=10||roi.right<=180||roi.bottom<=190)throw new AssertionError("balloon plus exterior not included");
  if(roi.left<0||roi.top<0||roi.right>220||roi.bottom>210)throw new AssertionError("out of image");
  if(r.box.left!=70||r.box.right!=130)throw new AssertionError("source rectangle changed");
  Region noBubble=new Region("y",new Rect(1,2,40,45),List.of(),true,null);Rect edge=RtDetrRegions.renderBounds(noBubble,60,70);
  if(edge.left!=0||edge.top!=0||edge.right<=40||edge.bottom<=45)throw new AssertionError("fallback context and clipping");
  System.out.println("UnanchoredCropChecks passed");
 }
}
