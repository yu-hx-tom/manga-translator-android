package cn.local.manga;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import android.graphics.Rect;
public final class SelectionScopeChecks {
    public static void main(String[] args)throws Exception{
        Rect boxA=new Rect(60,50,95,190),boxB=new Rect(135,50,170,190);
        Region a=new Region("a",boxA,Arrays.asList(boxA),true),b=new Region("b",boxB,Arrays.asList(boxB),true);
        ArrayList<Region> selected=new ArrayList<>(Arrays.asList(a)),all=new ArrayList<>(Arrays.asList(a,b));
        TranslationEngine.PreparedText job=new TranslationEngine.PreparedText(new File("unused-host-metadata"),selected,all);
        if(job.regions.size()!=1||!job.regions.get(0).id.equals("a"))throw new AssertionError("request scope must remain selected only");
        if(job.protectionRegions.size()!=2||!job.protectionRegions.get(1).id.equals("b"))throw new AssertionError("deselected region missing from protection");
        selected.clear();all.clear();if(job.regions.size()!=1||job.protectionRegions.size()!=2)throw new AssertionError("metadata must snapshot UI selection");
        java.awt.image.BufferedImage fixture=javax.imageio.ImageIO.read(new File(args[1]));int w=200,h=240;
        int[] original=fixture.getRGB(0,0,w,h,null,0,w);
        WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.find(original,w,h,new int[][]{{boxA.left,boxA.top,boxA.right,boxA.bottom}},35);
        ArrayList<int[]> foreign=new ArrayList<>();for(Region other:job.protectionRegions)if(!other.id.equals(job.regions.get(0).id))for(Rect line:other.lines)foreign.add(new int[]{line.left,line.top,line.right,line.bottom});
        if(!WhiteBubbleCleaner.touchesForeignInk(original,w,h,mask,foreign.toArray(new int[0][])))throw new AssertionError("deselected region must reach actual production guard");
        Path report=Paths.get(args[0]);String json=Files.readString(report);
        json=json.replace("\"checksPassed\":9","\"checksPassed\":13");
        json=json.replace("\"scope\":","\"deselectedRegionProtected\":true,\"selectedOnlyRequestScope\":true,\"selectionSnapshotStable\":true,\"selectionScopeTest\":\"Actual PreparedText metadata constructor + Region with host Rect adapter, no network\",\"scope\":");
        Files.writeString(report,json);System.out.println(json);
    }
}
