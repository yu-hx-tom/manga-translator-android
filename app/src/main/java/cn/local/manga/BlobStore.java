package cn.local.manga;

import android.database.Cursor;
import java.io.*;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;

/** Immutable byte-addressed files. A prepared object is pinned until its owner transaction commits. */
final class BlobStore {
    static final long GRACE_MS=10*60*1000L,PENDING_MS=60*60*1000L;
    static final Set<String> KINDS=new HashSet<>(Arrays.asList("lossless-original","lossless-layer","lossless-rendered","encoded-original","lossy-display","mask","metadata"));
    final StorageDatabase store;final File root;
    BlobStore(StorageDatabase store){this.store=store;root=new File(store.files,"blobs");}
    File file(String hash)throws IOException{if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("文件库标识无效");return new File(new File(root,hash.substring(0,2)),hash);}
    final class Prepared implements AutoCloseable {
        final String hash,kind,mime,token;final long bytes;final AutoCloseable lease;boolean committed,closed;
        Prepared(String hash,String kind,String mime,String token,long bytes,AutoCloseable lease){this.hash=hash;this.kind=kind;this.mime=mime;this.token=token;this.bytes=bytes;this.lease=lease;}
        /** Must be inside the SAME transaction as the owner's metadata. */
        void reference(String ownerType,String ownerId,String role)throws Exception {
            StorageDatabase.requireWorker();if(!store.db.inTransaction())throw new IllegalStateException("文件引用需要所有者事务");
            File content=file(hash);if(!content.isFile()||content.length()!=bytes)throw new IOException("文件发布未完成，拒绝登记引用");
            long now=System.currentTimeMillis();
            store.db.execSQL("INSERT OR IGNORE INTO blob(hash,size,mime,created_at,last_access_at) VALUES(?,?,?,?,?)",new Object[]{hash,bytes,mime,now,now});
            if(store.number("SELECT size FROM blob WHERE hash=?",hash)!=bytes)throw new IOException("文件库大小不一致");
            store.db.execSQL("UPDATE blob SET missing=0,last_access_at=? WHERE hash=?",new Object[]{now,hash});
            store.db.execSQL("DELETE FROM blob_gc WHERE hash=?",new Object[]{hash});
            store.db.execSQL("INSERT OR REPLACE INTO blob_ref(owner_type,owner_id,role,hash,kind) VALUES(?,?,?,?,?)",new Object[]{ownerType,ownerId,role,hash,kind});
            store.db.execSQL("DELETE FROM blob_pending WHERE token=?",new Object[]{token});
        }
        public void close()throws Exception{if(closed)return;closed=true;lease.close();}
    }
    Prepared put(File given,String kind)throws Exception {
        // 1.1.5 views are symbolic links into this store; read the object itself. Any other link is still refused.
        final File source=StorageFiles.blobLink(given.toPath(),root)?given.toPath().toRealPath().toFile():given;
        if(!source.isFile()||Files.isSymbolicLink(source.toPath()))throw new IOException("共享文件源无效");
        long bytes=source.length(),stamp=source.lastModified();
        Prepared saved=put(out->{try(InputStream in=new BufferedInputStream(new FileInputStream(source))){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}},kind,mime(source.getName()));
        if(source.length()!=bytes||source.lastModified()!=stamp||saved.bytes!=bytes){saved.close();throw new IOException("复制期间源文件已变化");}return saved;
    }
    Prepared put(byte[] bytes,String kind,String mime)throws Exception {return put(out->out.write(bytes),kind,mime);}
    Prepared put(StorageFiles.Writer writer,String kind,String mime)throws Exception {
        StorageDatabase.requireWorker();if(!KINDS.contains(kind))throw new IOException("未知的文件用途");
        StorageFiles.mkdir(root);File incoming=new File(root,".incoming");StorageFiles.mkdir(incoming);
        String token=UUID.randomUUID().toString();File temporary=new File(incoming,token+".tmp");
        MessageDigest digest=StorageFiles.digest();AutoCloseable lease=null;
        try{
            try(FileOutputStream file=new FileOutputStream(temporary);DigestOutputStream out=new DigestOutputStream(file,digest)){writer.write(out);out.flush();file.getFD().sync();}
            String hash=StorageFiles.hex(digest.digest());long size=temporary.length();File destination=file(hash);lease=store.lease(hash);
            store.db.execSQL("INSERT INTO blob_pending(token,hash,created_at,purpose) VALUES(?,?,?,?)",new Object[]{token,hash,System.currentTimeMillis(),kind});
            if(destination.exists()){
                if(destination.length()!=size||!hash.equals(StorageFiles.hash(destination)))throw new IOException("已有共享文件校验失败，停止覆盖");
                Files.delete(temporary.toPath());
            }else{
                StorageFiles.mkdir(destination.getParentFile());File staged=new File(destination.getParentFile(),"."+token+".tmp");
                Files.move(temporary.toPath(),staged.toPath(),StandardCopyOption.ATOMIC_MOVE);StorageFiles.syncDirectory(incoming);StorageFiles.syncDirectory(staged.getParentFile());
                // Read-only before publication: a write through a view link must fail rather than alter a shared object.
                staged.setWritable(false,false);StorageFiles.publish(staged,destination);
            }
            return new Prepared(hash,kind,mime,token,size,lease);
        }catch(Exception e){if(lease!=null)lease.close();throw e;}
        finally{Files.deleteIfExists(temporary.toPath());}
    }
    /** Reference ownership already pins this file. Callers needing it across owner mutations also acquire a lease. */
    File resolve(String ownerType,String ownerId,String role,String...allowedKinds)throws Exception {
        StorageDatabase.requireWorker();
        try(Cursor c=store.db.rawQuery("SELECT r.hash,r.kind,b.size,b.missing FROM blob_ref r JOIN blob b ON b.hash=r.hash WHERE r.owner_type=? AND r.owner_id=? AND r.role=?",new String[]{ownerType,ownerId,role})){
            if(!c.moveToFirst())return null;String hash=c.getString(0),kind=c.getString(1);
            if(allowedKinds.length>0&&!Arrays.asList(allowedKinds).contains(kind))throw new IOException("共享文件用途不匹配，拒绝有损/无损混用");
            File file=file(hash);if(c.getInt(3)!=0||!file.isFile()||file.length()!=c.getLong(2))throw new IOException("共享文件缺失，原引用已保留");
            store.db.execSQL("UPDATE blob SET last_access_at=? WHERE hash=?",new Object[]{System.currentTimeMillis(),hash});return file;
        }
    }
    void releaseOwner(String type,String id){if(!store.db.inTransaction())throw new IllegalStateException("解除引用需要事务");store.db.execSQL("DELETE FROM blob_ref WHERE owner_type=? AND owner_id=?",new Object[]{type,id});}
    /** Two-phase GC is serialized with put/reference; persistent tombstones survive failed deletes. */
    long collect(long now)throws Exception {return collect(now,GRACE_MS);}
    /** grace below GRACE_MS is only for explicit user cleanup under CacheStorage.beginClear (no task can hold a new object). */
    long collect(long now,long grace)throws Exception {
        StorageDatabase.requireWorker();
        if(!store.backupProtectionReady)return 0;
        store.db.execSQL("DELETE FROM blob_pending WHERE created_at<?",new Object[]{now-PENDING_MS});
        List<String> eligible=new ArrayList<>();
        try(Cursor c=store.db.rawQuery("SELECT hash FROM blob b WHERE last_access_at<? AND NOT EXISTS(SELECT 1 FROM blob_ref r WHERE r.hash=b.hash) AND NOT EXISTS(SELECT 1 FROM blob_pending p WHERE p.hash=b.hash) AND NOT EXISTS(SELECT 1 FROM backup_ref r WHERE r.hash=b.hash)",new String[]{Long.toString(now-grace)})){while(c.moveToNext())if(!store.leased(c.getString(0)))eligible.add(c.getString(0));}
        store.db.beginTransaction();try{for(String hash:eligible){store.db.execSQL("INSERT OR IGNORE INTO blob_gc(hash,queued_at) VALUES(?,?)",new Object[]{hash,now});store.db.execSQL("DELETE FROM blob WHERE hash=?",new Object[]{hash});}store.db.setTransactionSuccessful();}finally{store.db.endTransaction();}
        long freed=0;List<String> queued=new ArrayList<>();try(Cursor c=store.db.rawQuery("SELECT hash FROM blob_gc",null)){while(c.moveToNext())queued.add(c.getString(0));}
        for(String hash:queued){if(store.leased(hash)||store.number("SELECT count(*) FROM blob_ref WHERE hash=?",hash)>0||store.number("SELECT count(*) FROM blob_pending WHERE hash=?",hash)>0||store.number("SELECT count(*) FROM backup_ref WHERE hash=?",hash)>0)continue;
            File file=file(hash);long bytes=file.length();if(file.exists())file.setWritable(true,false);if(!file.exists()||file.delete()){if(file.getParentFile().isDirectory())StorageFiles.syncDirectory(file.getParentFile());store.db.execSQL("DELETE FROM blob_gc WHERE hash=?",new Object[]{hash});freed+=bytes;}}
        return freed;
    }
    /** Daily disk/SQL reconciliation never drops a missing object's references. */
    void reconcile(boolean force)throws Exception {
        StorageDatabase.requireWorker();long now=System.currentTimeMillis(),last=0;
        try{last=Long.parseLong(store.meta("blob_reconcile"));}catch(Exception ignored){}
        if(!force&&now-last<24*60*60*1000L)return;
        List<String> registered=new ArrayList<>();try(Cursor c=store.db.rawQuery("SELECT hash FROM blob",null)){while(c.moveToNext())registered.add(c.getString(0));}
        org.json.JSONArray missing=new org.json.JSONArray();
        for(String hash:registered){File content=file(hash);boolean absent=!content.isFile()||content.length()!=store.number("SELECT size FROM blob WHERE hash=?",hash);
            store.db.execSQL("UPDATE blob SET missing=? WHERE hash=?",new Object[]{absent?1:0,hash});
            if(absent){org.json.JSONArray refs=new org.json.JSONArray();try(Cursor c=store.db.rawQuery("SELECT owner_type,owner_id,role FROM blob_ref WHERE hash=?",new String[]{hash})){while(c.moveToNext())refs.put(new org.json.JSONObject().put("type",c.getString(0)).put("owner",c.getString(1)).put("role",c.getString(2)));}missing.put(new org.json.JSONObject().put("hash",hash).put("references",refs));}
        }
        File[] shards=root.listFiles(File::isDirectory);int orphans=0;
        if(shards!=null)for(File shard:shards){if(Files.isSymbolicLink(shard.toPath()))continue;File[] files=shard.listFiles(File::isFile);if(files==null)continue;
            for(File content:files){String hash=content.getName();long age=now-content.lastModified();if(Files.isSymbolicLink(content.toPath()))continue;
                if(hash.matches("[0-9a-f]{64}")&&shard.getName().equals(hash.substring(0,2))){
                    if(store.number("SELECT count(*) FROM blob WHERE hash=?",hash)>0)continue;orphans++;
                    if(age>=GRACE_MS&&!store.leased(hash)&&store.number("SELECT count(*) FROM blob_pending WHERE hash=? AND created_at>=?",hash,Long.toString(now-PENDING_MS))==0&&store.number("SELECT count(*) FROM backup_ref WHERE hash=?",hash)==0&&store.backupProtectionReady)
                        store.db.execSQL("INSERT OR IGNORE INTO blob_gc(hash,queued_at) VALUES(?,?)",new Object[]{hash,now});
                }else if(content.getName().endsWith(".tmp")&&age>=PENDING_MS){Files.deleteIfExists(content.toPath());StorageFiles.syncDirectory(shard);}
            }
        }
        if(missing.length()>0)StorageDatabase.problem="发现 "+missing.length()+" 个共享文件缺失或大小异常；引用已保留，请运行存储自检";
        String report=new org.json.JSONObject().put("time",now).put("unregisteredFiles",orphans).put("missing",missing).toString();
        StorageFiles.write(new File(store.files,"storage-v2/reconcile-latest.json"),out->out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        store.meta("blob_reconcile",Long.toString(now));
    }
    static String mime(String name){return name.endsWith(".png")?"image/png":name.endsWith(".json")?"application/json":"application/octet-stream";}
}
