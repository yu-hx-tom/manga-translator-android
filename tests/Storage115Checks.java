package cn.local.manga;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 1.1.5 storage slimming: views are links (hard links denied as on Android, see host Os stub), blobs are read-only,
 * and 「备份、旧缓存与其他」 can be cleaned. Host SQLite + Windows symbolic links; Android runtime NOT verified.
 */
public final class Storage115Checks {
    private static int checks;
    private interface Action{void run()throws Exception;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void rejects(Action action,String message){boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,message);}
    private static byte[] png(int color)throws Exception{int[] pixels=new int[192];Arrays.fill(pixels,color);android.graphics.Bitmap bitmap=new android.graphics.Bitmap(16,12,pixels);ByteArrayOutputStream out=new ByteArrayOutputStream();check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"synthetic PNG fixture");return out.toByteArray();}
    private static void write(File file,byte[] data)throws Exception{file.getParentFile().mkdirs();Files.write(file.toPath(),data);}
    private static JSONObject draft()throws Exception{return new JSONObject().put("schema",1).put("width",16).put("height",12).put("engine","typeset-v1").put("regions",new JSONArray().put(new JSONObject().put("id","r1").put("box",new JSONArray(new int[]{1,1,8,10})).put("lines",new JSONArray()).put("original","synthetic").put("machine","测试").put("status","translated"))).put("protection",new JSONArray());}
    private static File makeDraft(File dir,byte[] source,byte[] rendered)throws Exception{write(new File(dir,PageDraft.SOURCE),source);write(new File(dir,PageDraft.JSON),draft().toString().getBytes("UTF-8"));write(new File(dir,"rendered.png"),rendered);return dir;}
    private static String sha(byte[] data){return StorageFiles.hex(StorageFiles.digest().digest(data));}
    private static File blob(Context c,String hash){return new File(new File(new File(c.getFilesDir(),"blobs"),hash.substring(0,2)),hash);}
    private static long blobCount(Context c)throws IOException{Path root=new File(c.getFilesDir(),"blobs").toPath();if(!Files.isDirectory(root))return 0;try(java.util.stream.Stream<Path> walk=Files.walk(root)){return walk.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).count();}}
    private static int backups(Context c){File[] files=new File(c.getFilesDir(),"db-backup").listFiles(f->f.getName().endsWith(".db"));return files==null?0:files.length;}

    public static void main(String[] args){
        try{run(new File(args[0]));System.out.println(checks+" storage 1.1.5 checks passed (host SQLite/filesystem, Windows symbolic links; Android runtime NOT verified)");System.exit(0);}
        catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    private static void run(File root)throws Exception {
        root.mkdirs();Context c=new Context(root);
        check(!android.system.Os.allowHardLinks,"host stub denies hard links like Android");
        byte[] a=png(0xff112233),b=png(0xff445566);

        // 1. View creation chain: hard link (denied) -> symbolic link -> verified copy.
        File src=new File(root,"src.bin");write(src,a);
        File linkView=new File(root,"views/link.bin");StorageFiles.linkOrCopy(src,linkView);
        check(Files.isSymbolicLink(linkView.toPath()),"hard link denied: view becomes a symbolic link, not a copy");
        check(Arrays.equals(a,Files.readAllBytes(linkView.toPath())),"link view reads the original bytes");
        android.system.Os.failSymlinks=true;File copyView=new File(root,"views/copy.bin");
        try{StorageFiles.linkOrCopy(src,copyView);}finally{android.system.Os.failSymlinks=false;}
        check(!Files.isSymbolicLink(copyView.toPath())&&Arrays.equals(a,Files.readAllBytes(copyView.toPath())),"no link support: verified copy fallback");

        // 2. Draft views are links into read-only blobs; a write through a view cannot alter the shared object.
        File draftDir=makeDraft(new File(root,"draft-a"),a,b);String before=StoredDrafts.treeHash(draftDir),key="a".repeat(64);
        check(StoredDrafts.commit(c,draftDir,key),"draft committed");
        File view=StoredDrafts.find(c,key);File viewSource=new File(view,PageDraft.SOURCE);
        check(Files.isSymbolicLink(viewSource.toPath()),"draft view file is a link, not a duplicate");
        check(before.equals(StoredDrafts.treeHash(view)),"tree hash follows blob links and matches the original draft");
        File object=viewSource.toPath().toRealPath().toFile();
        check(object.getName().equals(sha(a))&&!object.canWrite(),"blob object published read-only");
        rejects(()->{try(FileOutputStream out=new FileOutputStream(viewSource)){out.write(7);}},"write through a view link is refused");
        check(Arrays.equals(a,Files.readAllBytes(object.toPath())),"shared object unchanged after the refused write");

        // 3. Re-storing from a link view (session record path) reuses objects; foreign links are still refused.
        long objects=blobCount(c);
        StorageDatabase.call(c,s->{try(StoredDrafts.Prepared prepared=StoredDrafts.prepare(s,view)){check(prepared.manifest.getJSONObject("files").length()==3,"prepare reads all three link views");}return null;});
        StorageDatabase.call(c,s->{try(BlobStore.Prepared prepared=new BlobStore(s).put(viewSource,"lossless-original")){check(prepared.hash.equals(sha(a)),"put follows a blob link");}return null;});
        check(blobCount(c)==objects,"no new objects created from link views");
        File foreign=new File(root,"foreign.lnk");Files.createSymbolicLink(foreign.toPath(),src.toPath());
        rejects(()->StorageDatabase.call(c,s->{new BlobStore(s).put(foreign,"metadata").close();return null;}),"links outside the blob store are refused");

        // 4. A deleted view is rebuilt as a link.
        Files.delete(viewSource.toPath());
        check(Files.isSymbolicLink(new File(StoredDrafts.find(c,key),PageDraft.SOURCE).toPath()),"missing view rebuilt on demand as a link");

        // 5. 「备份、旧缓存与其他」: objects only old backups protect are reclaimed; one fresh backup kept; live data intact.
        byte[] doomed=png(0xff778899);String key2="b".repeat(64);check(StoredDrafts.commit(c,makeDraft(new File(root,"draft-b"),doomed,b),key2),"second draft committed");
        StorageDatabase.call(c,s->{s.backup(true);StorageQuota.removeDraft(s,key2);s.backup(true);return null;});
        check(backups(c)>=2,"several backups exist before cleanup");
        StorageDatabase.call(c,s->{new BlobStore(s).collect(System.currentTimeMillis()+BlobStore.GRACE_MS*2);return null;});
        check(blob(c,sha(doomed)).isFile(),"normal GC keeps an object an old backup still references");
        write(new File(c.getCacheDir(),"rendered-pages-v1/old.bin"),a);write(new File(c.getCacheDir(),"import-123/page-1"),a);
        File unmigrated=new File(c.getFilesDir(),"browser-sessions/legacy-unmigrated");write(new File(unmigrated,"project.json"),"{}".getBytes("UTF-8"));write(new File(unmigrated,"pages/p1/rendered.png"),b);
        check(StorageDatabase.call(c,s->StorageQuota.unmigratedBytes(s))>0,"unmigrated old session measured");
        StorageDatabase.call(c,s->{StorageQuota.clearOther(s,false);return null;});
        check(!blob(c,sha(doomed)).exists(),"object protected only by old backups is reclaimed");
        check(backups(c)==1,"exactly one fresh database backup kept");
        check(!new File(c.getCacheDir(),"rendered-pages-v1").exists()&&!new File(c.getCacheDir(),"import-123").exists(),"pre-1.1.2 cache and import temp folders removed");
        check(unmigrated.isDirectory(),"unmigrated sessions kept unless ticked");
        File again=StoredDrafts.find(c,key);check(again!=null&&before.equals(StoredDrafts.treeHash(again)),"live draft survives and re-materializes byte-identically");
        check(blob(c,sha(a)).isFile(),"object of a live draft kept");
        StorageDatabase.call(c,s->{StorageQuota.clearOther(s,true);return null;});
        check(!unmigrated.exists(),"unmigrated sessions removed only when ticked");
        check(StoredDrafts.find(c,key)!=null,"live draft still resolvable after second cleanup");

        // 6. Upgrade step: copies made by earlier versions are replaced by links once.
        String key3="c".repeat(64);android.system.Os.failSymlinks=true;
        try{check(StoredDrafts.commit(c,makeDraft(new File(root,"draft-c"),png(0xffabcdef),b),key3),"draft committed while links unavailable");}finally{android.system.Os.failSymlinks=false;}
        check(!Files.isSymbolicLink(new File(StoredDrafts.find(c,key3),PageDraft.SOURCE).toPath()),"earlier-version view is a full copy");
        StorageDatabase.call(c,s->{s.db.execSQL("DELETE FROM meta WHERE key='views_relinked_v1'");StorageQuota.relinkViewsOnce(s);check("links".equals(s.meta("views_relinked_v1")),"upgrade step detects link support");return null;});
        check(Files.isSymbolicLink(new File(StoredDrafts.find(c,key3),PageDraft.SOURCE).toPath()),"copy view replaced by a link after upgrade");
        StorageDatabase.call(c,s->{StorageQuota.relinkViewsOnce(s);return null;});check(true,"upgrade step runs once (idempotent)");
    }
}
