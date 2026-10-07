package cn.local.manga;

import java.io.*;
import java.util.zip.*;

/** Persist verified cleanup/layout geometry once, so editing never reruns cleanup. */
final class BubbleStore {
    static void write(File file,Typesetter.Bubble b)throws IOException{
        try(DataOutputStream o=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(file)))){
            o.writeInt(1);for(int n:new int[]{b.left,b.top,b.w,b.h,b.estimate})o.writeInt(n);o.writeBoolean(b.solid);o.writeBoolean(b.vertical);
            bools(o,b.erase);bools(o,b.layoutArea);o.writeBoolean(b.fillColors!=null);if(b.fillColors!=null)for(int n:b.fillColors)o.writeInt(n);lines(o,b.localLines);lines(o,b.foreignLines);
        }
    }
    static Typesetter.Bubble read(File file)throws IOException{
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(new FileInputStream(file)))){
            if(in.readInt()!=1)throw new IOException("排版数据版本不符");int l=in.readInt(),t=in.readInt(),w=in.readInt(),h=in.readInt(),e=in.readInt();if(w<1||h<1||(long)w*h>4000000)throw new IOException("排版尺寸无效");
            boolean solid=in.readBoolean(),vertical=in.readBoolean();boolean[] erase=bools(in,w*h),layout=bools(in,w*h);int[] colors=null;if(in.readBoolean()){colors=new int[w*h];for(int i=0;i<colors.length;i++)colors[i]=in.readInt();}
            return new Typesetter.Bubble(l,t,w,h,erase,colors,layout,e,lines(in),lines(in),solid,vertical);
        }
    }
    private static void bools(DataOutputStream out,boolean[] values)throws IOException{for(boolean b:values)out.writeBoolean(b);}
    private static boolean[] bools(DataInputStream in,int n)throws IOException{boolean[] a=new boolean[n];for(int i=0;i<n;i++)a[i]=in.readBoolean();return a;}
    private static void lines(DataOutputStream out,int[][] lines)throws IOException{out.writeInt(lines.length);for(int[] line:lines)for(int v:line)out.writeInt(v);}
    private static int[][] lines(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>20000)throw new IOException("排版数据损坏");int[][] a=new int[n][4];for(int[] line:a)for(int i=0;i<4;i++)line[i]=in.readInt();return a;}
}
