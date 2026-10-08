package cn.local.manga;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable draft snapshots. Existing render/edit code receives standard independent-layout directories. */
final class StoredDrafts {
    static final String ROOT="page-drafts-v2";
    static final class Prepared implements AutoCloseable {
        final JSONObject manifest;final Map<String,BlobStore.Prepared> files;
        Prepared(JSONObject manifest,Map<String,BlobStore.Prepared> files){this.manifest=manifest;this.files=files;}
        void reference(String type,String owner)throws Exception{for(Map.Entry<String,BlobStore.Prepared> file:files.entrySet())file.getValue().reference(type,owner,"draft/"+file.getKey());}
        public void close()throws Exception{Exception first=null;for(BlobStore.Prepared file:files.values())try{file.close();}catch(Exception e){first=e;}if(first!=null)throw first;}
    }
    static Prepared prepare(StorageDatabase store,File directory)throws Exception {
        PageDraft.read(directory); // Validate the actual legacy format, including its source image.
        Map<String,BlobStore.Prepared> content=new LinkedHashMap<>();JSONObject files=new JSONObject();BlobStore blobs=new BlobStore(store);
        try{
            List<Path> paths=new ArrayList<>();try(java.util.stream.Stream<Path> walk=Files.walk(directory.toPath())){walk.filter(Files::isRegularFile).sorted().forEach(paths::add);}
            for(Path path:paths){boolean view=StorageFiles.blobLink(path,blobs.root);if(Files.isSymbolicLink(path)&&!view)throw new IOException("草稿含重定向文件，未迁移");
                String name=directory.toPath().relativize(path).toString().replace(File.separatorChar,'/');
                // A 1.1.5 view links into the blob store; canonicalizing it leaves the directory, so resolve it explicitly.
                File source=view?path.toRealPath().toFile():StorageFiles.child(directory,name);String kind=name.equals(PageDraft.SOURCE)?"lossless-original":name.endsWith(".bin")?"mask":name.equals("rendered.png")?"lossless-rendered":name.equals("clean.png")?"lossless-layer":"metadata";
                BlobStore.Prepared file=blobs.put(source,kind);content.put(name,file);files.put(name,new JSONObject().put("hash",file.hash).put("size",file.bytes).put("kind",kind));
            }
            return new Prepared(new JSONObject().put("version",1).put("files",files),content);
        }catch(Exception e){for(BlobStore.Prepared file:content.values())file.close();throw e;}
    }
    /** Immutable aliases reduce disk duplication: hard link, else symbolic link, else verified copy (StorageFiles.linkOrCopy). */
    static File materialize(StorageDatabase store,JSONObject manifest,File root)throws Exception {
        File target=new File(root,generation(manifest));JSONObject entries=manifest.getJSONObject("files");
        StorageFiles.mkdir(target);BlobStore blobs=new BlobStore(store);
        for(Iterator<String> names=entries.keys();names.hasNext();){String name=names.next();JSONObject item=entries.getJSONObject(name);File from=blobs.file(item.getString("hash"));
            // Validate the name lexically: canonicalizing an existing symbolic-link view would resolve into the blob store.
            if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains("..")||Arrays.asList(name.split("/")).contains("."))throw new IOException("草稿文件名无效");
            File to=new File(target,name);
            if(!from.isFile()||from.length()!=item.getLong("size"))throw new IOException("草稿共享文件缺失，已保留引用");
            if(to.isFile()){if(to.length()!=from.length()||!item.getString("hash").equals(StorageFiles.hash(to)))throw new IOException("已有草稿快照损坏");StorageQuota.track(store,to);continue;}
            if(Files.isSymbolicLink(to.toPath()))Files.delete(to.toPath()); // Dangling link to a collected object: rebuild it.
            StorageFiles.linkOrCopy(from,to);
            StorageQuota.track(store,to);
        }
        PageDraft.read(target);return target;
    }
    static boolean commit(Context context,File staging,String key)throws Exception {
        return commit(context,staging,key,true);
    }
    private static boolean commit(Context context,File staging,String key,boolean activate)throws Exception {
        return StorageDatabase.call(context,store->{
            try(Prepared prepared=prepare(store,staging)){
                File directory=materialize(store,prepared.manifest,new File(new File(store.files,ROOT),key));
                store.db.beginTransaction();try{
                    new BlobStore(store).releaseOwner("draft",key);prepared.reference("draft",key);
                    store.db.execSQL("INSERT OR REPLACE INTO draft(id,json,updated_at) VALUES(?,?,?)",new Object[]{key,prepared.manifest.toString(),System.currentTimeMillis()});if(activate){store.meta("draft_authority/"+key,"db");StorageQuota.activateDraft(store,key);}store.db.setTransactionSuccessful();
                }finally{store.db.endTransaction();}
                return directory.isDirectory();
            }
        });
    }
    static File find(Context context,String key)throws Exception {
        return StorageDatabase.call(context,store->{if(!"db".equals(store.meta("draft_authority/"+key)))return null;String manifest=store.scalar("SELECT json FROM draft WHERE id=?",key);if(manifest==null)return null;
            store.db.execSQL("UPDATE draft SET updated_at=? WHERE id=?",new Object[]{System.currentTimeMillis(),key});
            return materialize(store,new JSONObject(manifest),new File(new File(store.files,ROOT),key));});
    }
    static void migrateLegacy(Context context,StorageDatabase store)throws Exception {
        File root=new File(store.files,"page-drafts");File[] directories=root.listFiles(File::isDirectory);if(directories==null)return;
        boolean changed=false;int scanned=0;for(File directory:directories){StorageDatabase.progress="核对旧草稿 "+(++scanned)+" / "+directories.length;String key=directory.getName();if(!PageDraftStore.validKey(key)||store.meta("draft_authority/"+key)!=null||store.number("SELECT count(*) FROM legacy_verified WHERE path=?","page-drafts/"+key)>0)continue;
            try{
                File json=new File(directory,PageDraft.JSON);if(!json.isFile())continue;PageDraft.read(directory);
                String checksum=treeHash(directory);StorageMigration migration=new StorageMigration(store,"draft-v1-"+key,checksum,1);
                StorageQuota.requireMigrationSpace(store,PageDraftStore.size(directory));
                MigrationRunner.run(migration,step->{
                    if(step==MigrationRunner.BACKUP){File backup=new File(store.files,"migration-backup/draft-v1/"+key+"/draft.json");if(!backup.isFile())StorageFiles.copy(json,backup);if(!StorageFiles.hash(backup).equals(StorageFiles.hash(json)))throw new IOException("草稿元数据备份不一致");}
                    else if(step==MigrationRunner.IMPORT){if(store.scalar("SELECT json FROM draft WHERE id=?",key)==null)commit(context,directory,key,false);}
                    else if(step==MigrationRunner.VERIFY){String manifest=store.scalar("SELECT json FROM draft WHERE id=?",key);File target=manifest==null?null:materialize(store,new JSONObject(manifest),new File(new File(store.files,ROOT),key));if(target==null||!checksum.equals(treeHash(target))||!checksum.equals(treeHash(directory)))throw new IOException("旧草稿逐文件核对失败");migration.verified(1);}
                    else if(step==MigrationRunner.CUTOVER){store.db.beginTransaction();try{store.db.execSQL("INSERT OR REPLACE INTO legacy_verified(path,migration_id,checksum,bytes) VALUES(?,?,?,?)",new Object[]{"page-drafts/"+key,migration.id,checksum,PageDraftStore.size(directory)});store.meta("draft_authority/"+key,"db");StorageQuota.activateDraft(store,key);migration.completed(MigrationRunner.DONE);store.db.setTransactionSuccessful();}finally{store.db.endTransaction();}}
                });
                changed=true;
                StorageMigration.resolved(store,"draft-v1-"+key);
            }catch(Exception e){StorageMigration.issue(store,"draft-v1-"+key,e);StorageDatabase.problem="部分旧草稿未迁移："+StorageDatabase.message(e);}
        }
        if(changed)store.backup(true);
    }
    static String generation(JSONObject manifest)throws Exception{return StorageFiles.hex(StorageFiles.digest().digest(StorageJson.canonical(manifest).getBytes(StandardCharsets.UTF_8)));}
    static String treeHash(File root)throws Exception {
        JSONObject files=new JSONObject();try(java.util.stream.Stream<Path> walk=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)walk.filter(Files::isRegularFile)::iterator){if(Files.isSymbolicLink(path)&&!StorageFiles.blobLink(path,null))throw new IOException("迁移源含符号链接");String name=root.toPath().relativize(path).toString().replace(File.separatorChar,'/');files.put(name,new JSONObject().put("bytes",Files.size(path)).put("sha256",StorageFiles.hash(path.toFile())));}}
        return StorageFiles.hex(StorageFiles.digest().digest(StorageJson.canonical(files).getBytes(StandardCharsets.UTF_8)));
    }
}
