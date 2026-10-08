package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

public final class CrossRegionChecks {
    static int checks;
    static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        int w=200,h=240;BufferedImage source=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=source.createGraphics();
        g.setColor(Color.GRAY);g.fillRect(0,0,w,h);g.setColor(Color.BLACK);g.fillRect(30,20,170,195);g.setColor(Color.WHITE);g.fillRect(32,22,166,191);g.setColor(Color.BLACK);
        for(int y=55;y<185;y+=25){g.fillRect(66,y,19,15);g.fillRect(141,y,19,15);}g.fillRect(110,65,4,115);g.dispose();
        int[] original=source.getRGB(0,0,w,h,null,0,w),unchanged=original.clone();int[][] a={{60,50,95,190}},b={{135,50,170,190}};
        WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.find(original,w,h,a,35);
        check(mask.whiteBackground,"fixture reproduces accepted same balloon");
        check(!WhiteBubbleCleaner.touchesForeignInk(original,w,h,mask,b),"distant original text is preserved before the foreign guard");
        check(Arrays.equals(original,unchanged),"guard makes no partial pixel change");
        check(!WhiteBubbleCleaner.touchesForeignInk(original,w,h,mask,new int[0][]),"same region has no foreign ink");
        WhiteBubbleCleaner.Mask combined=WhiteBubbleCleaner.find(original,w,h,new int[][]{a[0],b[0]},35);
        check(combined.whiteBackground&&!WhiteBubbleCleaner.touchesForeignInk(original,w,h,combined,new int[0][]),"combined same region still allowed");
        check(WhiteBubbleCleaner.touchesForeignInk(original,w,h,combined,b),"foreign original ink rejected when a candidate includes both paragraphs");
        BubbleLayout.Plan plan=BubbleLayout.plan("安全",combined.interior,w,h,true,35,new int[][]{a[0],b[0]});
        int[][] foreignAtCell={plan.cells[0]};
        check(BubbleLayout.touchesForeignLines(plan,foreignAtCell),"layout guard rejects foreign area even if no original ink there");
        check(!BubbleLayout.touchesForeignLines(plan,new int[0][]),"same region layout allowed");
        check(!BubbleLayout.touchesForeignLines(plan,new int[][]{{-30,-30,0,0}}),"non-overlapping foreign region allowed");
        // The caller always supplies immutable original pixels and ALL other region lines, including prior successes.
        int[] alreadyRendered=original.clone();WhiteBubbleCleaner.apply(alreadyRendered,combined);
        check(WhiteBubbleCleaner.touchesForeignInk(original,w,h,combined,b),"previously rendered foreign region is still protected by original source");
        Path out=Paths.get(args[0]);Files.createDirectories(out);
        String report="{\"checksPassed\":"+checks+",\"guardRejectedForeignInk\":true,\"sameRegionAllowed\":true,\"guardRejectedForeignLayout\":true,\"guardsAreNonMutating\":true,\"previousSuccessProtected\":true,\"scope\":\"Production WhiteBubbleCleaner and BubbleLayout guards with split-balloon fixture; Android render wiring separately javac-checked\"}";
        Files.writeString(out.resolve("cross_region_result.json"),report);System.out.println(report);
    }
}
