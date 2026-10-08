package cn.local.manga;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Explicit cache allowlist. Never traverse projects, browsing-library, preferences or diagnostics. */
final class CacheStorage {
    static final String[] LABELS={"翻译图片、译文与检测缓存","网页会话与翻译草稿","导入与翻译临时文件","旧版 RT-DETR 模型副本"};
    private static int users;
    private static boolean cleaning;
    static synchronized boolean beginUse(){if(cleaning)return false;users++;return true;}
    static synchronized void endUse(){if(users<=0)throw new IllegalStateException("Unbalanced cache use");users--;if(users==0)StorageDatabase.dispatch(s->{StorageQuota.maintain(s,true);return null;});}
    static synchronized boolean beginClear(){if(cleaning||users!=0)return false;cleaning=true;return true;}
    static synchronized void endClear(){cleaning=false;}

    static List<File> targets(File cache,File files,int group){
        List<File> out=new ArrayList<>();
        if(group==0)for(String name:new String[]{"browser-pages","browser-originals-v1","rendered-pages-v1","translations","detections-v1"})out.add(new File(cache,name));
        else if(group==1){out.add(new File(files,"browser-sessions"));out.add(new File(files,"page-drafts"));}
        else if(group==2){out.add(new File(cache,"text-jobs"));File[] children=cache.listFiles();if(children!=null)for(File child:children)if(child.getName().matches("(?:session-import-|import-)[0-9a-fA-F-]{36}"))out.add(child);}
        else if(group==3)out.add(new File(files,"models/rtdetr"));
        else throw new IllegalArgumentException("Unknown cache group");
        return out;
    }
    static long size(File file)throws IOException{
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return 0;
        long[] total={0};Files.walkFileTree(file.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs){if(attrs.isRegularFile())total[0]+=attrs.size();return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFileFailed(Path path,IOException error)throws IOException{if(error instanceof NoSuchFileException)return FileVisitResult.CONTINUE;throw error;}
        });return total[0];
    }
    static long[] sizes(File cache,File files)throws IOException{
        long[] out=new long[LABELS.length];for(int i=0;i<out.length;i++)for(File f:targets(cache,files,i))if(safeTarget(cache,files,f))out[i]+=size(f);return out;
    }
    private static boolean safeTarget(File cache,File files,File target)throws IOException{
        // Context's data root may itself be an Android /data/user/0 alias. Resolve that
        // trusted root, but reject redirects beneath it (including a models/ symlink).
        Path p=target.getAbsoluteFile().toPath().normalize();
        for(File base:new File[]{cache,files}){
            Path absolute=base.getAbsoluteFile().toPath().normalize();
            if(p.startsWith(absolute)&&!p.equals(absolute)){
                if(!Files.exists(absolute))return false;
                Path existing=p;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
                return existing.toRealPath().equals(absolute.toRealPath().resolve(absolute.relativize(existing)));
            }
        }
        return false;
    }
    static final class Result { long bytes;int failures; }
    static Result clear(File cache,File files,boolean[] selected){
        Result result=new Result();
        for(int i=0;i<LABELS.length;i++)if(i<selected.length&&selected[i])for(File target:targets(cache,files,i)){
            try{
                if(!safeTarget(cache,files,target)){result.failures++;continue;}
                if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))continue;
                Files.walkFileTree(target.toPath(),new SimpleFileVisitor<Path>(){
                    @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs){try{if(Files.deleteIfExists(path)&&attrs.isRegularFile())result.bytes+=attrs.size();}catch(IOException e){result.failures++;}return FileVisitResult.CONTINUE;}
                    @Override public FileVisitResult visitFileFailed(Path path,IOException e){if(!(e instanceof NoSuchFileException))result.failures++;return FileVisitResult.CONTINUE;}
                    @Override public FileVisitResult postVisitDirectory(Path path,IOException e){try{if(e!=null)result.failures++;Files.deleteIfExists(path);}catch(IOException failure){result.failures++;}return FileVisitResult.CONTINUE;}
                });
            }catch(IOException e){result.failures++;}
        }
        return result;
    }
}
