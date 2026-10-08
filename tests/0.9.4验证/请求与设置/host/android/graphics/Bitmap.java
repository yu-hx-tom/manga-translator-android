package android.graphics;
/** Exact fixture pixels and PNG upload encoding; no Android Canvas or rendering acceptance. */
public final class Bitmap {
    public enum Config {ARGB_8888}
    public enum CompressFormat {PNG}
    private final int width,height;
    private final int[] pixels;
    private boolean recycled;
    public Bitmap(int width,int height,int[] pixels){this.width=width;this.height=height;this.pixels=pixels.clone();}
    public int getWidth(){return width;}
    public int getHeight(){return height;}
    public void getPixels(int[] target,int offset,int stride,int x,int y,int count,int rows){if(recycled)throw new IllegalStateException("recycled");for(int row=0;row<rows;row++)System.arraycopy(pixels,(y+row)*width+x,target,offset+row*stride,count);}
    public boolean compress(CompressFormat format,int quality,java.io.OutputStream output){
        if(recycled)throw new IllegalStateException("recycled");
        try{java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);return javax.imageio.ImageIO.write(image,"png",output);}catch(java.io.IOException failure){return false;}
    }
    public void recycle(){recycled=true;}
    public boolean isRecycled(){return recycled;}
}
