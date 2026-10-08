package cn.local.manga;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;

/** Disposable cache keys reference byte-identical immutable objects; key semantics are unchanged. */
final class StoredCache {
    static final class Entry {final byte[] bytes;final JSONObject metadata;Entry(byte[] bytes,JSONObject metadata){this.bytes=bytes;this.metadata=metadata;}}
    static String id(String type,String key){return type+"/"+key;}
    static Entry read(Context context,String type,String key,int maximum)throws Exception {
        return StorageDatabase.call(context,s->{String owner=id(type,key),json=s.scalar("SELECT metadata FROM cache_entry WHERE key=? AND kind=?",owner,type);if(json==null)return null;
            String kind=type.equals("source")?"encoded-original":"lossless-rendered";File file=new BlobStore(s).resolve("cache",owner,"content",kind);if(file==null||file.length()>maximum)return null;
            byte[] bytes=Files.readAllBytes(file.toPath());String expected=s.scalar("SELECT hash FROM blob_ref WHERE owner_type='cache' AND owner_id=? AND role='content'",owner);
            if(!StorageFiles.hex(StorageFiles.digest().digest(bytes)).equals(expected))throw new IOException("共享缓存校验失败");
            JSONObject metadata=new JSONObject(json);ensureAlias(s,file,expected,metadata);
            s.db.execSQL("UPDATE cache_entry SET last_access_at=? WHERE key=?",new Object[]{System.currentTimeMillis(),owner});return new Entry(bytes,metadata);});
    }
    static JSONObject metadata(Context context,String type,String key)throws Exception {
        return StorageDatabase.call(context,s->{String json=s.scalar("SELECT metadata FROM cache_entry WHERE key=? AND kind=?",id(type,key),type);return json==null?null:new JSONObject(json);});
    }
    static void write(Context context,String type,String key,byte[] bytes,JSONObject metadata,File alias)throws Exception {
        StorageDatabase.call(context,s->{String owner=id(type,key),kind=type.equals("source")?"encoded-original":"lossless-rendered";
            try(BlobStore.Prepared prepared=new BlobStore(s).put(bytes,kind,type.equals("source")?"application/octet-stream":"image/png")){
                if(alias!=null){String relative=StorageFiles.relativeView(s.cache,alias);if(!relative.startsWith("browser-pages-v2/"))throw new IOException("缓存视图路径无效");metadata.put("alias",relative);}
                s.db.beginTransaction();try{prepared.reference("cache",owner,"content");long now=System.currentTimeMillis();s.db.execSQL("INSERT OR REPLACE INTO cache_entry(key,kind,metadata,updated_at,last_access_at) VALUES(?,?,?,?,?)",new Object[]{owner,type,metadata.toString(),now,now});s.db.setTransactionSuccessful();}finally{s.db.endTransaction();}
                ensureAlias(s,new BlobStore(s).file(prepared.hash),prepared.hash,metadata);
            }StorageQuota.maintain(s,false);return null;});
    }
    /** Aliases are disposable views: the committed blob is the authority after any interrupted write. */
    private static File ensureAlias(StorageDatabase s,File source,String hash,JSONObject metadata)throws Exception {
        String relative=metadata.optString("alias",null);if(relative==null)return source;
        if(!relative.startsWith("browser-pages-v2/"))throw new IOException("缓存视图路径无效");File target=StorageFiles.viewChild(s.cache,relative);
        if(Files.isSymbolicLink(target.toPath())&&target.exists()&&!StorageFiles.blobLink(target.toPath(),new BlobStore(s).root))throw new IOException("缓存视图指向文件库之外");
        if(!target.isFile()||target.length()!=source.length()||!hash.equals(StorageFiles.hash(target)))linkCopy(source,target);
        StorageQuota.track(s,target);return target;
    }
    static File committedFile(Context context,String type,String key)throws Exception {
        return StorageDatabase.call(context,s->{String owner=id(type,key),json=s.scalar("SELECT metadata FROM cache_entry WHERE key=? AND kind=?",owner,type);if(json==null)return null;
            File source=new BlobStore(s).resolve("cache",owner,"content",type.equals("source")?"encoded-original":"lossless-rendered");if(source==null)return null;
            String hash=s.scalar("SELECT hash FROM blob_ref WHERE owner_type='cache' AND owner_id=? AND role='content'",owner);if(!hash.equals(StorageFiles.hash(source)))throw new IOException("共享缓存校验失败");
            ensureAlias(s,source,hash,new JSONObject(json));return source;});
    }
    static void patch(Context context,String type,String key,String field,Object value)throws Exception {
        StorageDatabase.call(context,s->{String owner=id(type,key),json=s.scalar("SELECT metadata FROM cache_entry WHERE key=?",owner);if(json!=null){JSONObject data=new JSONObject(json);if(value==null)data.remove(field);else data.put(field,value);s.db.execSQL("UPDATE cache_entry SET metadata=? WHERE key=?",new Object[]{data.toString(),owner});}return null;});
    }
    static void remove(Context context,String type,String key)throws Exception {
        StorageDatabase.call(context,s->{StorageQuota.removeCache(s,id(type,key));return null;});
    }
    static void linkCopy(File source,File target)throws Exception {StorageFiles.linkOrCopy(source,target);}
}
