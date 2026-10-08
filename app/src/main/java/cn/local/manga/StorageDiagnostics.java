package cn.local.manga;

import android.content.Context;
import android.database.Cursor;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

final class StorageDiagnostics {
    private static final Object LOG_LOCK=new Object();
    static String run(Context context,boolean full,BooleanSupplier cancelled)throws Exception {
        long start=System.currentTimeMillis();List<JSONObject> events=new ArrayList<>();
        JSONObject database=StorageDatabase.call(context,s->{
            s.checkDatabase();s.refreshBackupReferences();StorageQuota.reconcile(s,true);JSONArray migration=new JSONArray(),missing=new JSONArray(),issues=new JSONArray();
            try(Cursor c=s.db.rawQuery("SELECT key,value FROM meta WHERE key LIKE 'migration_error/%'",null)){while(c.moveToNext())issues.put(new JSONObject().put("id",c.getString(0)).put("error",c.getString(1)));}
            try(Cursor c=s.db.rawQuery("SELECT id,step,state,source_count,target_count,checksum,error FROM migration ORDER BY id",null)){while(c.moveToNext())migration.put(new JSONObject().put("id",c.getString(0)).put("step",c.getInt(1)).put("state",c.getString(2)).put("sourceCount",c.getLong(3)).put("targetCount",c.getLong(4)).put("checksum",c.getString(5)).put("error",c.getString(6)));}
            int checked=0,hashed=0;List<String> all=new ArrayList<>();try(Cursor c=s.db.rawQuery("SELECT hash FROM blob ORDER BY hash",null)){while(c.moveToNext())all.add(c.getString(0));}Collections.shuffle(all,new Random(start));BlobStore blobs=new BlobStore(s);
            for(String hash:all){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();File file=blobs.file(hash);long size=s.number("SELECT size FROM blob WHERE hash=?",hash);boolean flagged=s.number("SELECT missing FROM blob WHERE hash=?",hash)!=0;checked++;String error="";
                if(!file.isFile())error="missing";else if(file.length()!=size)error="size";else if(full||flagged||hashed<50){hashed++;if(!hash.equals(StorageFiles.hash(file)))error="hash";}
                s.db.execSQL("UPDATE blob SET missing=? WHERE hash=?",new Object[]{error.isEmpty()?0:1,hash});
                if(!error.isEmpty()){JSONArray refs=new JSONArray();try(Cursor c=s.db.rawQuery("SELECT owner_type,owner_id,role FROM blob_ref WHERE hash=?",new String[]{hash})){while(c.moveToNext())refs.put(new JSONObject().put("type",c.getString(0)).put("owner",c.getString(1)).put("role",c.getString(2)));}missing.put(new JSONObject().put("hash",hash).put("problem",error).put("affectedReferences",refs));}
            }
            long[] sizes=StorageQuota.sizes(s);JSONArray categories=new JSONArray();for(int i=0;i<sizes.length;i++)categories.put(new JSONObject().put("name",StorageQuota.LABELS[i]).put("bytes",sizes[i]));
            JSONArray backups=new JSONArray();File[] saved=new File(s.files,"db-backup").listFiles(f->f.getName().endsWith(".db"));if(saved!=null)for(File file:saved)backups.put(new JSONObject().put("file",file.getName()).put("bytes",file.length()).put("modifiedAt",file.lastModified()).put("hasVerification",new File(file.getPath()+".verified").isFile()));
            return new JSONObject().put("kind","storage_check").put("time",start).put("sqliteVersion",s.scalar("SELECT sqlite_version()"))
                .put("journalMode",s.scalar("PRAGMA journal_mode")).put("foreignKeys",s.number("PRAGMA foreign_keys")).put("userVersion",s.db.getVersion()).put("quickCheck","ok").put("migrations",migration)
                .put("blobsChecked",checked).put("blobsHashed",hashed).put("fullHash",full).put("missingOrDamaged",missing).put("unreferencedBlobs",s.number("SELECT count(*) FROM blob b WHERE NOT EXISTS(SELECT 1 FROM blob_ref r WHERE r.hash=b.hash)"))
                .put("backupProtectedBlobs",s.number("SELECT count(DISTINCT hash) FROM backup_ref")).put("backups",backups).put("backupDay",s.meta("backup_day")).put("backupProtectionReady",s.backupProtectionReady).put("migrationIssues",issues).put("categories",categories).put("availableBytes",StorageQuota.availableBytes)
                .put("passed",missing.length()==0&&issues.length()==0&&s.number("SELECT count(*) FROM migration WHERE state!='done'")==0&&s.backupProtectionReady);
        });events.add(database);
        if(full){Map<String,File> roots=new LinkedHashMap<>();roots.put("files",context.getFilesDir());roots.put("cache",context.getCacheDir());roots.put("databases",context.getDatabasePath("manga.db").getParentFile());events.add(StorageCensus.scan(roots,cancelled));}
        JSONObject external=external(context,cancelled);events.add(external);write(context,events);
        return "存储自检："+(database.getBoolean("passed")?"通过":"存在未完成项目，请导出日志")+"\n数据库 quick_check：通过\n共享文件：检查 "+database.getInt("blobsChecked")+" 个，校验哈希 "+database.getInt("blobsHashed")+" 个\n缺失/损坏："+database.getJSONArray("missingOrDamaged").length()+" 个\n迁移问题："+database.getJSONArray("migrationIssues").length()+" 项\n"+(full?"全量普查已完成，":"")+"耗时 "+((System.currentTimeMillis()-start)/1000)+" 秒\n结果已写入日志，可用「查看／导出翻译日志」导出。"+(StorageDatabase.problem.isEmpty()?"":"\n注意："+StorageDatabase.problem);
    }
    private static JSONObject external(Context c,BooleanSupplier cancelled)throws Exception {
        long count=0,bytes=0;int unavailable=0;Set<String> seen=new HashSet<>();
        for(LocalComics.Recent recent:LocalComics.recents(c)){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();try{LocalComics.Listing listing=LocalComics.list(c,recent.folder);for(LocalComics.Page page:listing.pages)if(page.output!=null&&seen.add(recent.folder.tree+"/"+page.output.documentId)){count++;bytes+=Math.max(0,page.output.size);}}catch(Exception e){unavailable++;}}
        return new JSONObject().put("kind","storage_external_metadata").put("scope","remembered folders only").put("outputFiles",count).put("bytes",bytes).put("unavailableFolders",unavailable).put("imageBytesRead",false);
    }
    static void write(Context context,List<JSONObject> events)throws Exception {
        File directory=new File(context.getFilesDir(),"diagnostics");StringBuilder lines=new StringBuilder();for(JSONObject event:events)lines.append(event).append('\n');byte[] data=lines.toString().getBytes(StandardCharsets.UTF_8);
        synchronized(LOG_LOCK){
            StorageFiles.write(new File(directory,"storage-current.jsonl"),out->out.write(data));
            StorageFiles.write(new File(directory,"storage-history-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".jsonl"),out->out.write(data));
            File baseline=new File(directory,"storage-first-census.jsonl");if(!baseline.exists())for(JSONObject event:events)if("storage_census".equals(event.optString("kind"))&&event.optBoolean("complete")){StorageFiles.write(baseline,out->out.write(data));break;}
            File[] reports=directory.listFiles(f->f.getName().startsWith("storage-history-")&&f.getName().endsWith(".jsonl"));if(reports!=null){Arrays.sort(reports,Comparator.comparing(File::getName).reversed());for(int i=6;i<reports.length;i++)java.nio.file.Files.deleteIfExists(reports[i].toPath());}
        }
    }
    static void failure(Context context,Exception error){try{write(context,Collections.singletonList(new JSONObject().put("kind","storage_check_error").put("time",System.currentTimeMillis()).put("passed",false).put("reason",StorageDatabase.message(error))));}catch(Exception ignored){} }
    static void export(File directory,OutputStream out)throws IOException {synchronized(LOG_LOCK){File[] reports=directory.listFiles(f->f.getName().equals("storage-first-census.jsonl")||f.getName().startsWith("storage-history-")&&f.getName().endsWith(".jsonl"));if(reports==null)return;Arrays.sort(reports,Comparator.comparing(File::getName));for(File file:reports)try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}}}
    private StorageDiagnostics(){}
}
