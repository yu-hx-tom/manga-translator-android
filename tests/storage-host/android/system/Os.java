package android.system;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Windows cannot model Android directory-fsync/power-loss semantics; that boundary is explicit. */
public class Os {
    public static boolean failSync,failLinks;private static final Set<String> linked=new HashSet<>();
    public static FileDescriptor open(String path,int flags,int mode)throws ErrnoException{if(!new File(path).isDirectory())throw new ErrnoException("not a directory",20);return new FileDescriptor();}
    public static void fsync(FileDescriptor fd)throws ErrnoException{if(failSync)throw new ErrnoException("injected sync failure",5);}
    public static void close(FileDescriptor fd)throws ErrnoException{}
    /** Like Android, where SELinux denies hard links to normal apps (1.1.2 assumed otherwise). Opt in only to model other systems. */
    public static boolean allowHardLinks;
    public static void link(String from,String to)throws ErrnoException{if(!allowHardLinks)throw new ErrnoException("link denied as on Android (SELinux)",13);if(failLinks)throw new ErrnoException("injected link failure",18);try{Files.createLink(Path.of(to),Path.of(from));linked.add(to);linked.add(from);}catch(IOException e){throw new ErrnoException(e.toString(),1);}}
    public static boolean failSymlinks;
    public static void symlink(String target,String link)throws ErrnoException{if(failSymlinks)throw new ErrnoException("injected symlink failure",13);try{Files.createSymbolicLink(Path.of(link),Path.of(target));}catch(IOException e){throw new ErrnoException(e.toString(),1);}}
    public static StructStat stat(String path)throws ErrnoException{StructStat out=new StructStat();out.st_nlink=linked.contains(path)?2:1;return out;}
}
