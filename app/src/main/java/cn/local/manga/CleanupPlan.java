package cn.local.manga;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Optional per-job cleanup masks. A cache miss always leaves the synchronous renderer available. */
final class CleanupPlan implements AutoCloseable {
    static final int MAX_PIXELS=1_000_000;
    private static final int MAGIC=0x4d434d32;
    private static final long MAX_BYTES=24L*1024*1024;
    private final File directory;
    private volatile boolean stopped,closed;
    private long bytes;
    private int prepared,refused,hits,misses;

    CleanupPlan(File directory){this.directory=directory;}
    boolean stopped(){return stopped||Thread.currentThread().isInterrupted();}
    void startRendering(){stopped=true;}
    synchronized int prepared(){return prepared;}
    synchronized int refused(){return refused;}
    synchronized int hits(){return hits;}
    synchronized int misses(){return misses;}
    synchronized boolean prepared(int index){return !closed&&file(index).isFile();}
    private File file(int index){return new File(directory,fileName(index));}

    // Only a bounded region commit holds the lock. Pixel computation never blocks rendering or close().
    synchronized boolean save(int index,int width,int height,WhiteBubbleCleaner.Mask mask,BooleanSupplier cancelled)throws IOException {
        if(stopped()||closed||cancelled.getAsBoolean())return false;
        int n=validSize(width,height);
        if(mask.erase.length!=n||mask.interior.length!=n||(mask.fillColors!=null&&mask.fillColors.length!=n)||(mask.glyphCandidate!=null&&mask.glyphCandidate.length!=n))throw new IOException("Invalid cleanup mask");
        File target=file(index),temporary=new File(target.getPath()+".tmp");
        if(target.isFile()||!directory.isDirectory()||bytes>=MAX_BYTES)return false;
        try {
            CRC32 checksum=new CRC32();
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new CheckedOutputStream(new FileOutputStream(temporary),checksum)))){
                out.writeInt(MAGIC);out.writeInt(width);out.writeInt(height);
                out.writeBoolean(mask.whiteBackground);out.writeBoolean(mask.texturedBackground);out.writeInt(mask.pixels);
                out.writeUTF(mask.backgroundKind.name());out.writeUTF(mask.evidence);
                writeBits(out,mask.erase,cancelled);writeBits(out,mask.interior,cancelled);
                out.writeBoolean(mask.fillColors!=null);
                if(mask.fillColors!=null)for(int i=0;i<n;i++){if((i&4095)==0)check(cancelled);out.writeInt(mask.fillColors[i]);}
                out.writeBoolean(mask.glyphCandidate!=null);
                if(mask.glyphCandidate!=null)writeBits(out,mask.glyphCandidate,cancelled);
                out.flush();out.writeLong(checksum.getValue());
            }
            if(stopped()||cancelled.getAsBoolean()||temporary.length()+bytes>MAX_BYTES)return false;
            try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),target.toPath());}
            bytes+=target.length();prepared++;if(!mask.whiteBackground)refused++;return true;
        }finally{temporary.delete();}
    }

    synchronized WhiteBubbleCleaner.Mask load(int index,int width,int height,BooleanSupplier cancelled){
        if(closed){misses++;return null;}
        WhiteBubbleCleaner.Mask mask=read(file(index),width,height,cancelled);
        if(mask==null)misses++;else hits++;
        return mask;
    }
    /** Mask files keep this name when a page draft adopts them (see PageDraftStore). */
    static String fileName(int index){if(index<0)throw new IllegalArgumentException();return "cleanup-"+index+".bin";}
    /** Reads one saved mask; null when missing, stale, damaged or of another size. */
    static WhiteBubbleCleaner.Mask read(File target,int width,int height,BooleanSupplier cancelled){
        try {
            int n=validSize(width,height);long size=target.length();
            if(!target.isFile()||size<28||size>256L+((n+7L)/8)*3+n*4L)throw new IOException("Missing cleanup mask");
            CRC32 checksum=new CRC32();
            try(DataInputStream in=new DataInputStream(new CheckedInputStream(new BufferedInputStream(new FileInputStream(target)),checksum))){
                if(in.readInt()!=MAGIC||in.readInt()!=width||in.readInt()!=height)throw new IOException("Stale cleanup mask");
                boolean white=in.readBoolean(),textured=in.readBoolean();int pixels=in.readInt();
                WhiteBubbleCleaner.BackgroundKind kind=WhiteBubbleCleaner.BackgroundKind.valueOf(in.readUTF());String evidence=in.readUTF();
                if(pixels<0||pixels>n)throw new IOException("Invalid cleanup count");
                boolean[] erase=readBits(in,n,cancelled),interior=readBits(in,n,cancelled);int[] fill=null;
                if(in.readBoolean()){fill=new int[n];for(int i=0;i<n;i++){if((i&4095)==0)check(cancelled);fill[i]=in.readInt();}}
                boolean[] glyph=in.readBoolean()?readBits(in,n,cancelled):null;
                long expected=checksum.getValue();if(in.readLong()!=expected||in.read()!=-1)throw new IOException("Damaged cleanup mask");
                return new WhiteBubbleCleaner.Mask(erase,interior,white,pixels,fill,textured,glyph,kind,evidence);
            }
        }catch(CancellationException cancelledRead){throw cancelledRead;}
        catch(IOException|IllegalArgumentException invalid){return null;}
    }

    private static int validSize(int width,int height)throws IOException {
        long n=(long)width*height;if(width<1||height<1||n>MAX_PIXELS)throw new IOException("Cleanup precompute size limit");return (int)n;
    }
    private static void writeBits(DataOutputStream out,boolean[] bits,BooleanSupplier cancelled)throws IOException {
        for(int i=0;i<bits.length;i+=8){if((i&4095)==0)check(cancelled);int value=0;for(int b=0;b<8&&i+b<bits.length;b++)if(bits[i+b])value|=1<<b;out.writeByte(value);}
    }
    private static boolean[] readBits(DataInputStream in,int count,BooleanSupplier cancelled)throws IOException {
        boolean[] bits=new boolean[count];for(int i=0;i<count;i+=8){if((i&4095)==0)check(cancelled);int value=in.readUnsignedByte();for(int b=0;b<8&&i+b<count;b++)bits[i+b]=(value&(1<<b))!=0;}return bits;
    }
    private static void check(BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new CancellationException("已取消");}
    @Override public synchronized void close(){
        stopped=true;closed=true;
        File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();directory.delete();
    }
}
