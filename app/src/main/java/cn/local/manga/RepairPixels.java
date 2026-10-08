package cn.local.manga;

/** Keeps all pixels outside the requested text areas, including other paragraphs, byte-for-byte unchanged. */
final class RepairPixels {
    static final class Normalized {
        final int[] pixels;final String method="verified_center_padding_crop";final double[] sourceRect;
        final double fullOutsideMeanDelta,normalizedOutsideMeanDelta,paddingAgreement;final int contextPixels,matchedContextBins;
        Normalized(int[] pixels,double[] rect,double full,double normalized,double padding,int context,int matched){this.pixels=pixels;sourceRect=rect;fullOutsideMeanDelta=full;normalizedOutsideMeanDelta=normalized;paddingAgreement=padding;contextPixels=context;matchedContextBins=matched;}
    }
    /** Recover one centered, padded return only when distributed original context proves its alignment.
     * The ordinary caller-provided resize remains byte exact; null means keep that existing path. */
    static Normalized tryRecoverPadding(int[] original,int[] fullResize,int width,int height,boolean[] editable,int[] returned,int returnedWidth,int returnedHeight)throws Exception{
        ImageCleanup.validateReturnedSize(returnedWidth,returnedHeight,width,height);int size=width*height;
        if(original.length!=size||fullResize.length!=size||editable.length!=size||returned.length!=(long)returnedWidth*returnedHeight)throw new Exception("修图画布适配像素数量不匹配");
        long fullError=0;int context=0;for(int i=0;i<size;i++)if(!editable[i]){context++;fullError+=pixelDelta(original[i],fullResize[i]);}
        if(context<Math.max(128,size/20)||fullError<=context*45L)return null;
        double scale=Math.min(returnedWidth/(double)width,returnedHeight/(double)height),cw=width*scale,ch=height*scale,left=(returnedWidth-cw)/2,top=(returnedHeight-ch)/2;
        boolean sides=left>top;double pad=sides?left:top;
        if(pad<Math.max(3,(sides?returnedWidth:returnedHeight)*.04))return null;
        double[] rect={left,top,left+cw,top+ch};double padding=paddingAgreement(returned,returnedWidth,returnedHeight,rect,sides);if(padding<.98)return null;
        int[] candidate=resizeRect(returned,returnedWidth,returnedHeight,width,height,rect);long error=0;int[] counts=new int[8],edges=new int[8];long[] sums=new long[8],squares=new long[8],errors=new long[8],oldErrors=new long[8];boolean vertical=height>=width;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int i=y*width+x;if(editable[i])continue;int bin=vertical?Math.min(3,y*4/height)*2+Math.min(1,x*2/width):Math.min(3,x*4/width)*2+Math.min(1,y*2/height);int value=gray(original[i]),delta=pixelDelta(original[i],candidate[i]);counts[bin]++;sums[bin]+=value;squares[bin]+=(long)value*value;errors[bin]+=delta;oldErrors[bin]+=pixelDelta(original[i],fullResize[i]);error+=delta;
            if(x>0&&!editable[i-1]&&Math.abs(value-gray(original[i-1]))>=30)edges[bin]++;else if(y>0&&!editable[i-width]&&Math.abs(value-gray(original[i-width]))>=30)edges[bin]++;}
        if(error>context*30L||fullError-error<context*20L||error>fullError*.6)return null;
        int matched=0,textured=0,minimum=Math.max(16,Math.min(256,size/512));
        for(int i=0;i<8;i++){if(counts[i]<minimum||errors[i]>counts[i]*60L||errors[i]>oldErrors[i]+counts[i]*2L)return null;double mean=sums[i]/(double)counts[i],variance=squares[i]/(double)counts[i]-mean*mean;if(variance<144)return null;if(edges[i]>=Math.max(4,counts[i]/500))textured++;if(errors[i]<=counts[i]*45L)matched++;}
        if(matched<7||textured<6)return null;
        return new Normalized(candidate,rect,fullError/(double)context,error/(double)context,padding,context,matched);
    }
    private static int gray(int color){return ((color>>16&255)+(color>>8&255)+(color&255))/3;}
    private static int pixelDelta(int a,int b){return (Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255)))/3;}
    private static double paddingAgreement(int[] pixels,int w,int h,double[] rect,boolean sides){
        int low=(int)Math.floor(sides?rect[0]:rect[1])-2,high=(int)Math.ceil(sides?rect[2]:rect[3])+2,span=sides?w:h;if(low<1||high>=span)return 0;
        int[] histogram=new int[256],counts=new int[8],matches=new int[8];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int cross=sides?x:y;if(cross<low||cross>=high)histogram[gray(pixels[y*w+x])]++;}
        int color=0;for(int i=1;i<256;i++)if(histogram[i]>histogram[color])color=i;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int cross=sides?x:y;if(cross>=low&&cross<high)continue;int bin=(sides?Math.min(3,y*4/h):Math.min(3,x*4/w))*2+(cross<low?0:1),p=pixels[y*w+x];counts[bin]++;if((p>>>24)>=250&&Math.abs((p>>16&255)-color)<=8&&Math.abs((p>>8&255)-color)<=8&&Math.abs((p&255)-color)<=8)matches[bin]++;}
        double agreement=1;for(int i=0;i<8;i++){if(counts[i]<4)return 0;agreement=Math.min(agreement,matches[i]/(double)counts[i]);}return agreement;
    }
    /** Pixel-center bilinear mapping is shared by Android and host recovery; no offset search. */
    private static int[] resizeRect(int[] source,int sw,int sh,int w,int h,double[] r){
        int[] output=new int[w*h];for(int y=0;y<h;y++){double sy=Math.max(0,Math.min(sh-1,r[1]+(y+.5)*(r[3]-r[1])/h-.5));int y0=(int)sy,y1=Math.min(sh-1,y0+1);double fy=sy-y0;for(int x=0;x<w;x++){double sx=Math.max(0,Math.min(sw-1,r[0]+(x+.5)*(r[2]-r[0])/w-.5));int x0=(int)sx,x1=Math.min(sw-1,x0+1);double fx=sx-x0;int a=source[y0*sw+x0],b=source[y0*sw+x1],c=source[y1*sw+x0],d=source[y1*sw+x1],pixel=0;for(int shift=0;shift<32;shift+=8){double upper=(a>>>shift&255)*(1-fx)+(b>>>shift&255)*fx,lower=(c>>>shift&255)*(1-fx)+(d>>>shift&255)*fx;pixel|=((int)Math.round(upper*(1-fy)+lower*fy))<<shift;}output[y*w+x]=pixel;}}return output;
    }
    static boolean[] editable(int width,int height,int[][] targets,int[][] protectedBoxes){
        if(width<1||height<1||(long)width*height>ImageCleanup.MAX_INPUT_PIXELS)throw new IllegalArgumentException("Invalid repair area");
        boolean[] mask=new boolean[width*height];
        for(int[] box:targets)paint(mask,width,height,box,0,true);
        for(int[] box:protectedBoxes)paint(mask,width,height,box,2,false);
        return mask;
    }
    private static void paint(boolean[] mask,int width,int height,int[] box,int margin,boolean value){
        int left=Math.max(0,box[0]-margin),right=Math.min(width,box[2]+margin);
        for(int y=Math.max(0,box[1]-margin);y<Math.min(height,box[3]+margin);y++)
            if(right>left)java.util.Arrays.fill(mask,y*width+left,y*width+right,value);
    }
    static int[] composite(int[] original,int[] edited,int width,int height,boolean[] editable)throws Exception{
        if(width<1||height<1||(long)width*height>ImageCleanup.MAX_INPUT_PIXELS)throw new Exception("修复区域尺寸无效或超过 400 万像素");
        int n=width*height;
        if(original.length!=n||edited.length!=n||editable.length!=n)throw new Exception("修复图像素数量不匹配");
        long exteriorError=0;int outside=0,inside=0,changed=0;
        for(int i=0;i<n;i++){
            int delta=(Math.abs((original[i]>>16&255)-(edited[i]>>16&255))+Math.abs((original[i]>>8&255)-(edited[i]>>8&255))+Math.abs((original[i]&255)-(edited[i]&255)))/3;
            if(editable[i]){inside++;if((edited[i]>>>24)<250)throw new Exception("修复图目标文字区域透明，保留原图");if(delta>8)changed++;}
            else{outside++;exteriorError+=delta;}
        }
        if(inside==0)throw new Exception("修复区域与其他段落重叠，保留原图");
        if(changed<Math.min(8,inside))throw new Exception("修图未对目标文字作出有效修改，保留原图");
        if(outside>32&&exteriorError>outside*45L)throw new Exception("修图改变了周围构图或背景，未采用此结果");
        // Bounded distance uses one extra byte per pixel: first inner ring stays original,
        // second ring blends halfway, and the third ring onward uses the model pixels.
        byte[] distance=new byte[n];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=y*width+x;
            if(editable[p])distance[p]=(byte)Math.min(3,1+Math.min(x==0?0:distance[p-1],y==0?0:distance[p-width]));
        }
        for(int y=height-1;y>=0;y--)for(int x=width-1;x>=0;x--){int p=y*width+x;
            if(distance[p]>0)distance[p]=(byte)Math.min(distance[p],1+Math.min(x==width-1?0:distance[p+1],y==height-1?0:distance[p+width]));
        }
        int[] result=original.clone();
        int effective=0;
        for(int i=0;i<n;i++)if(distance[i]>1){
            if(distance[i]>=3)result[i]=edited[i];
            else{int blended=0;for(int shift=0;shift<32;shift+=8)blended|=(((original[i]>>>shift&255)+(edited[i]>>>shift&255)+1)/2)<<shift;result[i]=blended;}
            int delta=(Math.abs((original[i]>>16&255)-(result[i]>>16&255))+Math.abs((original[i]>>8&255)-(result[i]>>8&255))+Math.abs((original[i]&255)-(result[i]&255)))/3;
            if(delta>8)effective++;
        }
        if(effective<Math.min(8,inside))throw new Exception("边缘融合后未对目标文字作出有效修改，保留原图");
        return result;
    }
}
