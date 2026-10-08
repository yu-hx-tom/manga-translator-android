package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

public class BubbleLayoutChecks {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void review(Path file,Path out,String name,int[][] lines,String text,boolean vertical,boolean full)throws Exception{
        BufferedImage page=ImageIO.read(file.toFile());int left=page.getWidth(),top=page.getHeight(),right=0,bottom=0;int[] widths=new int[lines.length];
        for(int i=0;i<lines.length;i++){int[] r=lines[i];left=Math.min(left,r[0]);top=Math.min(top,r[1]);right=Math.max(right,r[2]);bottom=Math.max(bottom,r[3]);widths[i]=Math.min(r[2]-r[0],r[3]-r[1]);}
        Arrays.sort(widths);int estimate=widths[widths.length/2],pad=estimate*3;
        int x=full?0:Math.max(0,left-pad),y=full?0:Math.max(0,top-pad),w=(full?page.getWidth():Math.min(page.getWidth(),right+pad))-x,h=(full?page.getHeight():Math.min(page.getHeight(),bottom+pad))-y;
        int[][] local=new int[lines.length][4];for(int i=0;i<lines.length;i++)local[i]=new int[]{lines[i][0]-x,lines[i][1]-y,lines[i][2]-x,lines[i][3]-y};
        int[] original=page.getRGB(x,y,w,h,null,0,w),clean=original.clone();WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.find(original,w,h,local,estimate);
        check(mask.whiteBackground,name+" enclosed interior");BubbleLayout.Plan plan=BubbleLayout.plan(text,mask.interior,w,h,vertical,estimate,local);
        int outside=0;for(int[] cell:plan.cells)for(int yy=cell[1];yy<cell[3];yy++)for(int xx=cell[0];xx<cell[2];xx++)if(!mask.interior[yy*w+xx])outside++;
        check(outside==0,name+" all production cells within true interior");WhiteBubbleCleaner.apply(clean,mask);
        BufferedImage before=image(original,w,h),marked=image(original,w,h),after=image(clean,w,h),translated=image(clean,w,h);
        for(int p=0;p<original.length;p++)if(mask.erase[p])marked.setRGB(p%w,p/w,0xffff4040);
        Graphics2D g=translated.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,plan.font));g.setColor(Color.BLACK);FontMetrics fm=g.getFontMetrics();
        for(int i=0;i<plan.cells.length;i++){int[] cell=plan.cells[i];String glyph=new String(Character.toChars(plan.codepoints[i]));g.setClip(cell[0],cell[1],cell[2]-cell[0],cell[3]-cell[1]);g.drawString(glyph,(cell[0]+cell[2]-fm.stringWidth(glyph))/2f,(cell[1]+cell[3]+fm.getAscent()-fm.getDescent())/2f);}g.dispose();
        int outsideChanges=0;for(int p=0;p<original.length;p++)if(!mask.interior[p]&&translated.getRGB(p%w,p/w)!=original[p])outsideChanges++;check(outsideChanges==0,name+" no outside changes");
        int scale=2,cellW=w*scale,cellH=h*scale+40;BufferedImage montage=new BufferedImage(cellW*4,cellH,BufferedImage.TYPE_INT_RGB);g=montage.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,montage.getWidth(),montage.getHeight());g.setFont(new Font("Microsoft YaHei",Font.PLAIN,18));BufferedImage[] images={before,marked,after,translated};String[] titles={"输入原图","生产去字 mask","清除后","生产布局坐标 / 桌面字形"};for(int i=0;i<4;i++){g.setColor(Color.BLACK);g.drawString(titles[i],i*cellW+4,25);g.drawImage(images[i],i*cellW,40,cellW,h*scale,null);}g.dispose();Files.createDirectories(out);ImageIO.write(montage,"png",out.resolve(name+"_四栏.png").toFile());ImageIO.write(translated,"png",out.resolve(name+"_布局.png").toFile());
        String result="{\"sample\":\""+name+"\",\"removed\":"+mask.pixels+",\"font\":"+plan.font+",\"cellsOutside\":"+outside+",\"outsideChanges\":"+outsideChanges+",\"cells\":"+Arrays.deepToString(plan.cells)+"}";Files.writeString(out.resolve(name+".json"),result);System.out.println(result);
        try{BubbleLayout.plan("中".repeat(1000),mask.interior,w,h,vertical,estimate,local);throw new AssertionError("long text accepted");}catch(Exception expected){checks++;}
    }
    static BufferedImage image(int[] p,int w,int h){BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,w,h,p,0,w);return b;}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]),out=Paths.get(args[2]);
        review(Paths.get(args[1]),out,"用户相连气泡",new int[][]{{136,52,178,150},{57,101,103,259},{142,166,173,207}},"锵锵！……开玩笑的啦。",true,true);
        review(root.resolve("样本01.png"),out,"样本01",new int[][]{{224,326,242,357},{211,326,229,387}},"勇者利乌斯！",true,false);
        review(root.resolve("样本02.png"),out,"样本02",new int[][]{{365,37,384,116},{351,39,369,138}},"你回来了，勇者利乌斯！",true,false);
        int[] open=new int[10000];Arrays.fill(open,0xffffffff);for(int y=35;y<60;y++)for(int x=45;x<52;x++)open[y*100+x]=0xff000000;
        check(!WhiteBubbleCleaner.find(open,100,100,new int[][]{{43,32,55,65}},12).whiteBackground,"exterior white/open bubble rejected");
        Files.writeString(out.resolve("checks.json"),"{\"checks\":"+checks+",\"status\":\"passed\",\"layout\":\"actual production BubbleLayout; desktop font rasterization, not Android screenshot\"}");System.out.println("checks="+checks);
    }
}
