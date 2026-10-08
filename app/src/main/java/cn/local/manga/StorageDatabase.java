package cn.local.manga;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** All database access uses one worker, including backup/restore and GC. Never wait on the UI thread. */
final class StorageDatabase {
    interface Work<T>{T run(StorageDatabase store)throws Exception;}
    private static volatile Thread writer;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"manga-storage");writer=t;return t;});
    private static StorageDatabase instance;
    static volatile String problem="";
    static volatile String progress="";
    static volatile long generation;
    static volatile boolean ready;
    final File files,cache,databaseFile;
    SQLiteDatabase db;
    private boolean healthy;
    boolean backupProtectionReady;
    private final Map<String,Integer> leases=new HashMap<>();

    private StorageDatabase(Context context)throws Exception {
        files=context.getFilesDir();cache=context.getCacheDir();databaseFile=context.getDatabasePath("manga.db");
        if(new File(files,"storage-v2/restore-pending").isFile())throw new IOException("上次数据库恢复被中断，请在存储自检中重新选择备份恢复");
        File authority=new File(files,"storage-v2/database-required");
        if(authority.isFile()&&!databaseFile.isFile())throw new IOException("存储数据库缺失，已停止写入；请从已验证备份恢复");
        StorageFiles.mkdir(databaseFile.getParentFile());
        try{open();checkDatabase();
            int version=db.getVersion();
            if(version>StorageSchema.VERSION)throw new IOException("数据库版本较新，拒绝写入");
            if(version==0){db.beginTransaction();try{for(String sql:StorageSchema.CREATE)db.execSQL(sql);db.setVersion(StorageSchema.VERSION);db.setTransactionSuccessful();}finally{db.endTransaction();}}
            healthy=true;problem="";refreshBackupReferences();StorageQuota.space(files);ready=true;
            StorageFiles.write(authority,out->out.write("manga.db required; never fall back after cutover\n".getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){ready=false;if(db!=null)try{db.close();}catch(Exception ignored){}throw e;}
    }
    private void open(){
        // Android's default corruption handler may delete databases. Preserve all files for recovery.
        db=SQLiteDatabase.openDatabase(databaseFile.getPath(),null,SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                damaged->{healthy=false;ready=false;problem="数据库损坏，原文件已保留，请从备份恢复";});
        db.setForeignKeyConstraintsEnabled(true);db.execSQL("PRAGMA synchronous=FULL");
    }
    static <T>T call(Context context,Work<T> work)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("存储操作禁止阻塞主线程");
        if(Thread.currentThread()==writer)return work.run(get(context));
        Future<T> result=IO.submit(()->work.run(get(context.getApplicationContext())));
        try{return result.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new RuntimeException(cause);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
    }
    static void start(Context context,Runnable finished){Context app=context.getApplicationContext();IO.execute(()->{try{StorageDatabase s=get(app);s.backup(false);new BlobStore(s).reconcile(false);upgrade(app,s);progress="正在整理存储…";StorageQuota.relinkViewsOnce(s);StorageQuota.reconcile(s,false);StorageQuota.maintain(s,true);}catch(Exception e){problem=message(e);}finally{progress="";finished.run();}});}
    static void upgrade(Context app,StorageDatabase s)throws Exception {
        try{progress="正在核对浏览记录、收藏和常用网站…";
            try{new DatabaseLibrary(app).load();}catch(Exception e){problem="浏览记录迁移未完成："+message(e);}
            StoredSessions.migrateAll(s);StoredDrafts.migrateLegacy(app,s);
        }finally{progress="";}
    }
    static void dispatch(Work<?> work){if(!ready)return;IO.execute(()->{if(instance!=null&&instance.healthy)try{work.run(instance);}catch(Exception e){problem=message(e);}});}
    static void async(Context context,Work<?> work){Context app=context.getApplicationContext();IO.execute(()->{try{work.run(get(app));}catch(Exception e){problem=message(e);}});}
    private static StorageDatabase get(Context context)throws Exception {
        try{if(instance==null)instance=new StorageDatabase(context);if(!instance.healthy)throw new IOException(problem);return instance;}
        catch(Exception e){problem=message(e);throw e;}
    }
    static String message(Throwable e){return e.getMessage()==null?"存储操作未完成":e.getMessage();}
    static void requireWorker(){if(Thread.currentThread()!=writer)throw new IllegalStateException("数据库只能在存储线程访问");}
    String scalar(String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    long number(String sql,String...args){String value=scalar(sql,args);return value==null?0:Long.parseLong(value);}
    String meta(String key){return scalar("SELECT value FROM meta WHERE key=?",key);}
    void meta(String key,String value){db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",new Object[]{key,value});}
    void checkDatabase()throws IOException {
        try(Cursor c=db.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0))||c.moveToNext())throw new IOException("数据库自检失败，已停止写入");}
        try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)){if(c.moveToFirst())throw new IOException("数据库引用不完整，已停止写入");}
    }
    void backup(boolean force)throws Exception {
        requireWorker();String day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
        if(!force&&day.equals(meta("backup_day")))return;
        File root=new File(files,"db-backup");StorageFiles.mkdir(root);
        String name="manga-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".db";File target=new File(root,name);
        // The copy includes its own protection set; restoring it preserves the referenced objects.
        db.execSQL("INSERT OR IGNORE INTO backup_ref(backup,hash) SELECT ?,hash FROM blob_ref",new Object[]{name});
        try {
            try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(!c.moveToFirst()||c.getInt(0)!=0)throw new IOException("数据库正忙，稍后再备份");}
            db.close();db=null;
            StorageFiles.copy(databaseFile,target);
            verifyBackup(target);
            String checksum=StorageFiles.hash(target);StorageFiles.write(new File(root,name+".verified"),out->out.write(checksum.getBytes(StandardCharsets.US_ASCII)));
        }finally{if(db==null)open();}
        File[] candidates=root.listFiles(f->f.getName().endsWith(".db")&&new File(root,f.getName()+".verified").isFile());
        if(candidates!=null){Arrays.sort(candidates,Comparator.comparing(File::getName).reversed());for(int i=3;i<candidates.length;i++)if(candidates[i].delete()){new File(root,candidates[i].getName()+".verified").delete();db.execSQL("DELETE FROM backup_ref WHERE backup=?",new Object[]{candidates[i].getName()});}}
        meta("backup_day",day);
        StorageFiles.syncDirectory(root);refreshBackupReferences();
    }
    /** Rebuild from all retained snapshots, including snapshots newer than a restored database. */
    void refreshBackupReferences()throws Exception {
        requireWorker();backupProtectionReady=false;File root=new File(files,"db-backup");
        File[] backups=root.listFiles(f->f.getName().endsWith(".db")&&new File(f.getPath()+".verified").isFile());
        File[] preserved=databaseFile.getParentFile().listFiles(f->f.getName().startsWith("manga.preserved-")&&f.getName().endsWith(".db"));
        List<File> all=new ArrayList<>();if(backups!=null)Collections.addAll(all,backups);if(preserved!=null)Collections.addAll(all,preserved);
        Map<String,Set<String>> references=new LinkedHashMap<>();
        try{
            for(File backup:all){
                boolean retained=backup.getName().startsWith("manga.preserved-");
                if(!retained){String expected=new String(java.nio.file.Files.readAllBytes(new File(backup.getPath()+".verified").toPath()),StandardCharsets.US_ASCII);
                    if(!expected.equals(StorageFiles.hash(backup)))throw new IOException("备份校验失败，已暂停文件回收："+backup.getName());}
                verifyBackup(backup);Set<String> hashes=new HashSet<>();
                try(SQLiteDatabase copy=SQLiteDatabase.openDatabase(backup.getPath(),null,SQLiteDatabase.OPEN_READONLY,damaged->{});Cursor c=copy.rawQuery("SELECT DISTINCT hash FROM blob_ref",null)){
                    while(c.moveToNext()){String hash=c.getString(0);if(!hash.matches("[0-9a-f]{64}"))throw new IOException("备份对象标识无效，已暂停文件回收");hashes.add(hash);}
                }references.put(backup.getName(),hashes);
            }
        }catch(Exception e){problem="备份或恢复前保留库无法核对，已暂停文件回收："+message(e);return;}
        db.beginTransaction();try{db.execSQL("DELETE FROM backup_ref");for(Map.Entry<String,Set<String>> entry:references.entrySet())for(String hash:entry.getValue())db.execSQL("INSERT INTO backup_ref(backup,hash) VALUES(?,?)",new Object[]{entry.getKey(),hash});db.setTransactionSuccessful();}finally{db.endTransaction();}
        backupProtectionReady=true;
    }
    static void verifyBackup(File file)throws IOException {
        try(SQLiteDatabase copy=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY,damaged->{})){
            try(Cursor c=copy.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0))||c.moveToNext())throw new IOException("数据库备份自检失败");}
            try(Cursor c=copy.rawQuery("PRAGMA foreign_key_check",null)){if(c.moveToFirst())throw new IOException("数据库备份引用校验失败");}
            if(copy.getVersion()!=StorageSchema.VERSION)throw new IOException("备份格式不兼容");
        }
    }
    /** Explicit user-confirmed restore. Available even when opening the live database fails. */
    static void restore(Context context,String backupName)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("恢复不能阻塞主线程");
        Future<?> restored=IO.submit(()->{
            if(!CacheStorage.beginClear())throw new IOException("正在使用数据，请先停止翻译或导出再恢复");
            try{
                File root=new File(context.getFilesDir(),"db-backup"),backup=StorageFiles.child(root,backupName),verified=new File(backup.getPath()+".verified");
                if(!backup.isFile()||!verified.isFile()||!new String(java.nio.file.Files.readAllBytes(verified.toPath()),StandardCharsets.US_ASCII).equals(StorageFiles.hash(backup)))throw new IOException("备份文件校验失败，未修改当前数据库");
                verifyBackup(backup);
                try(SQLiteDatabase check=SQLiteDatabase.openDatabase(backup.getPath(),null,SQLiteDatabase.OPEN_READONLY,damaged->{});Cursor c=check.rawQuery("SELECT DISTINCT b.hash,b.size FROM blob b JOIN blob_ref r ON r.hash=b.hash",null)){
                    while(c.moveToNext()){String hash=c.getString(0);if(!hash.matches("[0-9a-f]{64}"))throw new IOException("备份对象标识无效");File file=new File(new File(new File(context.getFilesDir(),"blobs"),hash.substring(0,2)),hash);
                        if(!file.isFile()||file.length()!=c.getLong(1)||!hash.equals(StorageFiles.hash(file)))throw new IOException("备份引用的图片缺失或损坏，未恢复");}
                }
                File database=context.getDatabasePath("manga.db"),pending=new File(context.getFilesDir(),"storage-v2/restore-pending");String token=UUID.randomUUID().toString();
                File preparedRestore=new File(database.getParentFile(),"manga.restore-"+token+".db");StorageFiles.copy(backup,preparedRestore);
                StorageFiles.write(pending,out->out.write(backupName.getBytes(StandardCharsets.UTF_8)));
                ready=false;if(instance!=null){instance.healthy=false;if(instance.db!=null)instance.db.close();instance=null;}generation++;
                for(String suffix:new String[]{"-wal","-shm",""}){File old=new File(database.getPath()+suffix);if(old.exists()){File preserved=new File(database.getParentFile(),"manga.preserved-"+token+".db"+suffix);java.nio.file.Files.move(old.toPath(),preserved.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);}}
                StorageFiles.syncDirectory(database.getParentFile());StorageFiles.publish(preparedRestore,database);
                java.nio.file.Files.delete(pending.toPath());StorageFiles.syncDirectory(pending.getParentFile());
                get(context);
            }finally{CacheStorage.endClear();}
            return null;
        });
        try{restored.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new RuntimeException(cause);}
    }
    synchronized AutoCloseable lease(String hash){leases.put(hash,leases.getOrDefault(hash,0)+1);return new AutoCloseable(){boolean closed;public void close(){synchronized(StorageDatabase.this){if(closed)return;closed=true;int n=leases.getOrDefault(hash,0)-1;if(n<=0)leases.remove(hash);else leases.put(hash,n);}}};}
    synchronized boolean leased(String hash){return leases.containsKey(hash);}
}
