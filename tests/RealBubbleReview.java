package cn.local.manga;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;
public final class RealBubbleReview {
    static void render(Path source,Path out,String name,int[][] lines,String chinese)throws Exception{
        BufferedImage page=ImageIO.read(source.toFile());int left=page.getWidth(),top=page.getHeight(),right=0,bottom=0;int[] widths=new int[lines.length];
        for(int i=0;i<lines.length;i++){int[] b=lines[i];left=Math.min(left,b[0]);top=Math.min(top,b[1]);right=Math.max(right,b[2]);bottom=Math.max(bottom,b[3]);widths[i]=Math.min(b[2]-b[0],b[3]-b[1]);}Arrays.sort(widths);int estimate=widths[widths.length/2],padding=Math.max(3,(int)Math.ceil(estimate*.7));
        int x=Math.max(0,left-padding),y=Math.max(0,top-padding),w=Math.min(page.getWidth(),right+padding)-x,h=Math.min(page.getHeight(),bottom+padding)-y;int[][] local=new int[lines.length][4];for(int i=0;i<lines.length;i++)local[i]=new int[]{lines[i][0]-x,lines[i][1]-y,lines[i][2]-x,lines[i][3]-y};
        int[] original=page.getRGB(x,y,w,h,null,0,w),cleaned=original.clone();WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.find(original,w,h,local,estimate);WhiteBubbleCleaner.apply(cleaned,mask);
        BufferedImage before=BubbleCleanupChecks.image(original,w,h),marked=BubbleCleanupChecks.image(original,w,h),after=BubbleCleanupChecks.image(cleaned,w,h),translated=BubbleCleanupChecks.image(cleaned,w,h);
        for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++)if(mask.erase[yy*w+xx])marked.setRGB(xx,yy,0xffff4040);
        Graphics2D g=BubbleCleanupChecks.graphics(translated);int size=Math.max(7,Math.min(estimate,(bottom-top)/Math.min(8,chinese.length())));g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,size));int rows=Math.max(1,(bottom-top)/(size+1));for(int i=0;i<chinese.length();i++){int column=i/rows,row=i%rows;g.drawString(chinese.substring(i,i+1),right-x-size-column*(size+2),top-y+size+row*(size+1));}g.dispose();
        int scale=4,cellW=Math.max(180,w*scale),cellH=h*scale+50;BufferedImage montage=new BufferedImage(cellW*4,cellH,BufferedImage.TYPE_INT_RGB);g=BubbleCleanupChecks.graphics(montage);g.setColor(new Color(0xeaf0ec));g.fillRect(0,0,montage.getWidth(),montage.getHeight());g.setFont(new Font("Microsoft YaHei",Font.PLAIN,18));String[] labels={"真实原裁片","生产 Java mask","清除后","中文排字示意"};BufferedImage[] parts={before,marked,after,translated};for(int i=0;i<4;i++){g.setColor(Color.BLACK);g.drawString(labels[i],i*cellW+8,26);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(parts[i],i*cellW,48,w*scale,h*scale,null);}g.dispose();
        Files.createDirectories(out);ImageIO.write(before,"png",out.resolve(name+"_原图.png").toFile());ImageIO.write(marked,"png",out.resolve(name+"_mask.png").toFile());ImageIO.write(after,"png",out.resolve(name+"_清除后.png").toFile());ImageIO.write(translated,"png",out.resolve(name+"_译图示意.png").toFile());ImageIO.write(montage,"png",out.resolve(name+"_四栏对照.png").toFile());
        String report="{\"sample\":\""+name+"\",\"crop\":["+x+","+y+","+(x+w)+","+(y+h)+"],\"whiteBackground\":"+mask.whiteBackground+",\"removedPixels\":"+mask.pixels+",\"sourceDetection\":\"existing 测试结果/坐标.json line boxes, new production padding formula\",\"scope\":\"actual production cleanup only; Chinese desktop font illustration, no paid API or Android screenshot\"}";Files.writeString(out.resolve(name+"_记录.json"),report);System.out.println(report);
    }
    public static void main(String[] args)throws Exception{
        Path source=Paths.get(args[0]),out=Paths.get(args[1]);
        render(source.resolve("样本01.png"),out,"样本01_欢呼气泡",new int[][]{{224,326,242,357},{211,326,229,387}},"勇者利乌斯！");
        render(source.resolve("样本02.png"),out,"样本02_对白气泡",new int[][]{{365,37,384,116},{351,39,369,138}},"你回来了，勇者利乌斯！");
    }
}
