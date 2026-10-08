package cn.local.manga;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** Durable immutable-file publication. No non-atomic fallback is permitted. */
final class StorageFiles {
    interface Writer { void write(OutputStream out) throws Exception; }
    static void mkdir(File directory) throws IOException {
        if(directory.isDirectory())return;
        File parent=directory.getParentFile();
        if(parent!=null&&!parent.isDirectory())mkdir(parent);
        if(!directory.mkdir()&&!directory.isDirectory())throw new IOException("无法创建存储目录");
        if(parent!=null)syncDirectory(parent);
    }
    static void syncDirectory(File directory) throws IOException {
        java.io.FileDescriptor fd=null;
        if(!directory.isDirectory())throw new IOException("同步目标不是目录");
        try { fd=android.system.Os.open(directory.getPath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(fd); }
        catch(android.system.ErrnoException e){throw new IOException("目录同步失败，未提交存储引用",e);}
        finally { if(fd!=null)try{android.system.Os.close(fd);}catch(android.system.ErrnoException e){throw new IOException("关闭目录失败",e);} }
    }
    static void publish(File temporary,File target)throws IOException {
        if(!temporary.getParentFile().getCanonicalFile().equals(target.getParentFile().getCanonicalFile()))throw new IOException("原子提交必须在同一目录");
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParentFile());
    }
    static void write(File target,Writer writer)throws Exception {
        mkdir(target.getParentFile());File temporary=File.createTempFile(".write-",".tmp",target.getParentFile());
        try {try(FileOutputStream out=new FileOutputStream(temporary)){writer.write(out);out.flush();out.getFD().sync();}publish(temporary,target);}
        finally {Files.deleteIfExists(temporary.toPath());}
    }
    static void copy(File from,File target)throws Exception {
        write(target,out->{try(InputStream in=new FileInputStream(from)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}});
        if(from.length()!=target.length()||!hash(from).equals(hash(target)))throw new IOException("文件复制校验不一致");
    }
    static String hash(File file)throws IOException {
        MessageDigest digest=digest();try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return hex(digest.digest());
    }
    static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new AssertionError(e);}}
    static String hex(byte[] value){StringBuilder out=new StringBuilder(value.length*2);for(byte b:value)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
    static File child(File root,String relative)throws IOException {
        File file=new File(root,relative).getCanonicalFile();String base=root.getCanonicalPath()+File.separator;
        if(!file.getPath().startsWith(base))throw new IOException("存储路径越界");return file;
    }
    /** Relative path of file under root, both canonicalized. On Android /data/user/0 links to /data/data, so a raw getCacheDir() path must never be relativized against a canonical one (1.1.2 bug). */
    static String relativeInside(File root,File file)throws IOException {
        Path base=root.getCanonicalFile().toPath(),actual=file.getCanonicalFile().toPath();
        if(!actual.startsWith(base)||actual.equals(base))throw new IOException("存储路径越界");
        return base.relativize(actual).toString().replace(File.separatorChar,'/');
    }
    /** Resolve directories, but never follow the final disposable view link into the blob store. */
    static File viewFile(File root,File file)throws IOException {
        Path base=root.toPath().toRealPath(),path=file.toPath().toAbsolutePath().normalize();
        Path parent=path.getParent(),existing=parent;
        if(parent==null)throw new IOException("缓存视图路径无效");
        while(existing!=null&&!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(existing==null)throw new IOException("缓存视图路径无效");
        Path resolved=existing.toRealPath().resolve(existing.relativize(parent)).normalize();
        if(!resolved.startsWith(base))throw new IOException("存储路径越界");
        return resolved.resolve(path.getFileName()).toFile();
    }
    static File viewChild(File root,String relative)throws IOException {
        if(relative==null||relative.isEmpty()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":"))throw new IOException("缓存视图路径无效");
        for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("缓存视图路径无效");
        return viewFile(root,new File(root,relative));
    }
    static String relativeView(File root,File file)throws IOException {
        return root.toPath().toRealPath().relativize(viewFile(root,file).toPath()).toString().replace(File.separatorChar,'/');
    }
    /**
     * Disposable view of an immutable blob: hard link, else symbolic link, else verified copy.
     * Android denies hard links to normal apps, so 1.1.2 silently fell back to full copies for every view.
     * Blobs are published read-only, so an accidental in-place write through a link fails instead of corrupting them.
     */
    static void linkOrCopy(File source,File target)throws Exception {
        mkdir(target.getParentFile());File temporary=new File(target.getParentFile(),".link-"+java.util.UUID.randomUUID()+".tmp");
        try{
            try{android.system.Os.link(source.getPath(),temporary.getPath());}
            catch(android.system.ErrnoException noHardLink){
                try{android.system.Os.symlink(source.getCanonicalPath(),temporary.getPath());}
                catch(android.system.ErrnoException noSymlink){copy(source,temporary);}
            }
            publish(temporary,target);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
    /** True when path is a symbolic link resolving to a blob file blobs/xx/&lt;sha256&gt; (inside blobRoot when given). */
    static boolean blobLink(Path path,File blobRoot){
        try{if(!Files.isSymbolicLink(path))return false;Path real=path.toRealPath();String name=real.getFileName().toString();
            if(!name.matches("[0-9a-f]{64}"))return false;Path shard=real.getParent(),store=shard==null?null:shard.getParent();
            if(store==null||!shard.getFileName().toString().equals(name.substring(0,2))||!store.getFileName().toString().equals("blobs"))return false;
            return blobRoot==null||real.startsWith(blobRoot.getCanonicalFile().toPath());}
        catch(IOException broken){return false;}
    }
    private StorageFiles(){}
}
