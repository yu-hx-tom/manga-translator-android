package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One diagnostics entry plus the existing cache-management entry; all I/O is background work. */
final class StorageTools {
    static void show(Activity a){
        String message=StorageDatabase.problem.isEmpty()?"检查数据库、迁移和文件引用。全量普查会读取内部文件计算哈希，可能需要数分钟，可随时取消。":"存储提示："+StorageDatabase.problem;
        new AlertDialog.Builder(a).setTitle("存储自检").setMessage(message).setPositiveButton("选择检查",(d,w)->new AlertDialog.Builder(a).setTitle("存储操作").setItems(new String[]{"快速自检（抽样 50 个哈希）","全量自检与存储普查","重试未完成迁移","从数据库备份恢复"},(dialog,index)->{
            if(index<2)runCheck(a,index==1);else if(index==2)retry(a);else backups(a);
        }).show()).setNegativeButton("关闭",null).show();
    }
    private interface Action {String run(AtomicBoolean cancelled)throws Exception;}
    private static void background(Activity a,String title,boolean canCancel,Action action){
        AtomicBoolean cancelled=new AtomicBoolean();ProgressDialog progress=new ProgressDialog(a);progress.setTitle(title);progress.setMessage("正在后台处理…");progress.setCancelable(canCancel);progress.setOnCancelListener(d->cancelled.set(true));if(canCancel)progress.setButton(ProgressDialog.BUTTON_NEGATIVE,"取消",(d,w)->cancelled.set(true));progress.show();
        new Thread(()->{String message;try{message=action.run(cancelled);}catch(Exception e){StorageDiagnostics.failure(a,e);message=StorageDatabase.message(e);}String result=message;
            a.runOnUiThread(()->{if(a.isDestroyed()||a.isFinishing())return;progress.dismiss();new AlertDialog.Builder(a).setTitle(title).setMessage(result).setPositiveButton("知道了",null).show();});
        },"storage-check").start();
    }
    private static void runCheck(Activity a,boolean full){background(a,full?"全量存储自检":"存储自检",true,cancelled->StorageDiagnostics.run(a,full,cancelled::get));}
    private static void retry(Activity a){background(a,"重试迁移",false,cancelled->StorageDatabase.call(a,s->{StorageDatabase.problem="";StorageDatabase.upgrade(a.getApplicationContext(),s);StorageQuota.reconcile(s,true);s.backup(true);BrowserLibrary.reload();return StorageDatabase.problem.isEmpty()?"迁移核对完成，旧数据仍保留。":StorageDatabase.problem;}));}
    private static void backups(Activity a){new Thread(()->{
        File root=new File(a.getFilesDir(),"db-backup");File[] files=root.listFiles(f->f.getName().endsWith(".db")&&new File(root,f.getName()+".verified").isFile());if(files==null)files=new File[0];Arrays.sort(files,Comparator.comparing(File::getName).reversed());File[] available=files;
        a.runOnUiThread(()->{if(a.isDestroyed()||a.isFinishing())return;if(available.length==0){new AlertDialog.Builder(a).setMessage("暂无已验证的数据库备份；当前数据库和旧文件会继续保留。").setPositiveButton("知道了",null).show();return;}
            String[] names=new String[available.length];for(int i=0;i<names.length;i++)names[i]=available[i].getName();new AlertDialog.Builder(a).setTitle("选择数据库备份").setItems(names,(d,index)->new AlertDialog.Builder(a).setTitle("确认恢复这个备份？").setMessage("备份之后新增的记录不会自动合并。恢复前会保留当前数据库及其 WAL 文件；工程、图片和旧数据目录不删除。\n\n"+names[index]).setNegativeButton("取消",null).setPositiveButton("恢复",(q,w)->background(a,"数据库恢复",false,c->{StorageDatabase.restore(a.getApplicationContext(),names[index]);return "数据库已恢复。请重新打开相关页面；原数据库已保留。";})).show()).setNegativeButton("取消",null).show();
        });
    },"storage-backups").start();}
    static void cache(Activity a){new Thread(()->{try{
        long[] sizes=StorageDatabase.call(a,s->{StorageQuota.reconcile(s,false);return StorageQuota.sizes(s);});
        a.runOnUiThread(()->{if(a.isDestroyed()||a.isFinishing())return;LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(a,20),Ui.dp(a,8),Ui.dp(a,20),Ui.dp(a,8));CheckBox[] options=new CheckBox[4];
            for(int i=0;i<sizes.length;i++){if(i==3&&sizes[i]==0)continue;String label=StorageQuota.LABELS[i]+" · "+android.text.format.Formatter.formatShortFileSize(a,sizes[i]);if(i==0||i==1||i==3){CheckBox box=new CheckBox(a);box.setText(label);box.setChecked(i==0);box.setEnabled(i!=3||sizes[i]>0);body.addView(box);options[i]=box;}else body.addView(Ui.text(a,label,14,Ui.MUTED));}
            body.addView(Ui.text(a,"可再生缓存清理包含旧版的图片、译文和检测缓存。工程、收藏历史、授权和本地输出不在此清理。旧格式且迁移失败的会话继续保留。\n共享图片仍被会话、草稿或数据库备份引用时会保留；无引用对象有 10 分钟保护期。",12,Ui.MUTED));
            ScrollView scroll=new ScrollView(a);scroll.addView(body);new AlertDialog.Builder(a).setTitle("缓存管理").setView(scroll).setNegativeButton("取消",null).setPositiveButton("清理所选",(d,w)->{
                boolean cache=options[0].isChecked(),sessions=options[1].isChecked(),legacy=options[3]!=null&&options[3].isChecked();if(!cache&&!sessions&&!legacy)return;
                Runnable clean=()->clear(a,cache,sessions,legacy);
                if(sessions||legacy)new AlertDialog.Builder(a).setTitle("确认清理？").setMessage((sessions?"清理会话后，未导入工程的会话将无法再导入。\n":"")+(legacy?"只删除已逐项核对通过、之后没有变化的旧数据。\n":"")+"已保存工程保留。").setNegativeButton("取消",null).setPositiveButton("确认清理",(q,v)->clean.run()).show();else clean.run();
            }).show();
        });
    }catch(Exception e){a.runOnUiThread(()->{if(!a.isDestroyed())new AlertDialog.Builder(a).setMessage("无法读取存储统计："+StorageDatabase.message(e)).setPositiveButton("知道了",null).show();});}},"storage-sizes").start();}
    private static void clear(Activity a,boolean caches,boolean sessions,boolean legacy){
        if(!CacheStorage.beginClear()){Toast.makeText(a,"正在翻译、导入或导出，请等待完成再清理",Toast.LENGTH_LONG).show();return;}
        background(a,"清理存储",false,c->{try{StorageDatabase.call(a,s->{StorageQuota.clearSelected(s,caches,sessions,legacy);return null;});return "清理完成。仍被其他内容或备份引用的图片会保留。";}finally{CacheStorage.endClear();}});
    }
    private StorageTools(){}
}
