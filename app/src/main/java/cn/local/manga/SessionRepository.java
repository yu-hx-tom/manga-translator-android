package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Durable browser sessions. Project imports take independent copies, never cache references. */
final class SessionRepository {
    static volatile String currentUrl = "";
    private static File root(Context c){return new File(c.getFilesDir(),"browser-sessions");}
    private static String id(String text){return UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    private static ComicProject session(Context c,String url,String title)throws Exception{
        String key=url.split("#",2)[0];File dir=new File(root(c),id(key));if(new File(dir,"project.json").isFile())return ComicProject.load(dir);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存浏览器会话");ComicProject s=new ComicProject(dir);s.id=dir.getName();s.title=title;s.sourceKind="web";s.sourceKey=key;s.created=System.currentTimeMillis();return s;
    }
    private static ComicProject.Page page(ComicProject session,String url,int order){String key=id(url);for(ComicProject.Page p:session.pages)if(p.id.equals(key))return p;ComicProject.Page p=new ComicProject.Page();p.id=key;p.label=String.format(Locale.ROOT,"%05d",order+1);p.kind=ComicProject.KIND_ORIGINAL;session.pages.add(p);return p;}
    static synchronized void original(Context c,String url,String title,String imageUrl,int order,android.graphics.Bitmap source)throws Exception{
        ComicProject s=session(c,url,title);ComicProject.Page p=page(s,imageUrl,order);File dir=s.pageDir(p);dir.mkdirs();File file=new File(dir,"original.png"),temporary=new File(dir,"original.tmp");
        if(!file.isFile()){try(FileOutputStream out=new FileOutputStream(temporary)){if(!source.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))throw new IOException("原图保存失败");}RenderedPageCache.replace(temporary,file);}
        p.originalName="original.png";if(p.imageName==null)p.imageName=p.originalName;s.pages.sort((a,b)->NaturalOrder.INSTANCE.compare(a.label,b.label));s.save();
    }
    static synchronized void record(Context c,String url,String title,String imageUrl,int order,File rendered,String draftKey) throws Exception {
        String key=url.split("#",2)[0]; File dir=new File(root(c),id(key));
        ComicProject session;
        if(new File(dir,"project.json").isFile())session=ComicProject.load(dir);
        else {if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存浏览器会话");session=new ComicProject(dir);session.id=dir.getName();session.title=title;session.sourceKind="web";session.sourceKey=key;session.created=System.currentTimeMillis();}
        String pageId=id(imageUrl);ComicProject.Page page=null;
        for(ComicProject.Page p:session.pages)if(p.id.equals(pageId))page=p;
        if(page==null){page=new ComicProject.Page();page.id=pageId;session.pages.add(page);}
        page.label=String.format(Locale.ROOT,"%05d",order+1);page.imageName="rendered.png";page.kind=ComicProject.KIND_RENDERED;page.status="translated";
        File pageDir=session.pageDir(page);pageDir.mkdirs();
        Files.copy(rendered.toPath(),new File(pageDir,page.imageName).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File draft=PageDraftStore.find(c,draftKey);
        if(draft!=null){PageDraftStore.copyTree(draft,session.draftDir(page));page.kind=ComicProject.KIND_EDITABLE;}
        session.pages.sort((a,b)->NaturalOrder.INSTANCE.compare(a.label,b.label));session.save();trim(c,session.id);
    }
    static synchronized List<ComicProject> list(Context c){
        List<ComicProject> out=new ArrayList<>();File[] dirs=root(c).listFiles(File::isDirectory);
        if(dirs!=null)for(File d:dirs)try{out.add(ComicProject.load(d));}catch(Exception ignored){}
        String current=currentUrl==null?"":currentUrl.split("#",2)[0];
        out.sort(Comparator.comparing((ComicProject p)->!p.sourceKey.equals(current)).thenComparing(Comparator.comparingLong((ComicProject p)->p.updated).reversed()));return out;
    }
    private static void trim(Context c,String keep){
        List<ComicProject> all=list(c);all.sort(Comparator.comparingLong(p->p.updated));long bytes=PageDraftStore.size(root(c));
        long budget=Math.min(800L*1024*1024,root(c).getUsableSpace()/5);
        for(ComicProject p:all)if(bytes>budget&&!p.id.equals(keep)){bytes-=PageDraftStore.size(p.dir);PageDraftStore.deleteTree(p.dir);}
    }
    static synchronized void clear(Context c){PageDraftStore.deleteTree(root(c));}
    static void choose(Activity activity){
        new Thread(()->{
            List<ComicProject> sessions=list(activity);
            activity.runOnUiThread(()->{
                if(activity.isDestroyed())return;
                LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16));
                if(sessions.isEmpty())body.addView(Ui.text(activity,"暂无翻译会话。先在浏览器翻译漫画，再到这里导入。",15,Ui.MUTED));
                ScrollView scroll=new ScrollView(activity);scroll.addView(body);
                AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("导入浏览器已翻译的漫画").setView(scroll).setNegativeButton("关闭",null).create();
                for(ComicProject session:sessions){
                    LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
                    ImageView cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(cover,new LinearLayout.LayoutParams(Ui.dp(activity,52),Ui.dp(activity,72)));
                    String current=session.sourceKey.equals(currentUrl==null?"":currentUrl.split("#",2)[0])?"当前页面 · ":"";
                    long translated=session.pages.stream().filter(p->p.editable()||"translated".equals(p.status)).count();
                    TextView label=Ui.text(activity,current+session.title+"\n"+Uri.parse(session.sourceKey).getHost()+" · "+session.pages.size()+" 页 · 已翻 "+translated+"\n"+android.text.format.DateUtils.getRelativeTimeSpanString(session.updated),13,Ui.INK);
                    label.setPadding(Ui.dp(activity,12),Ui.dp(activity,8),0,Ui.dp(activity,8));row.addView(label,new LinearLayout.LayoutParams(0,-2,1));body.addView(row);
                    if(!session.pages.isEmpty())new Thread(()->{BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=8;File image=session.imageFile(session.pages.get(0));android.graphics.Bitmap b=image==null?null:BitmapFactory.decodeFile(image.getPath(),o);activity.runOnUiThread(()->cover.setImageBitmap(b));}).start();
                    row.setOnClickListener(v->{dialog.dismiss();importSession(activity,session);});
                }
                dialog.show();dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,Math.min(Ui.dp(activity,560),activity.getResources().getDisplayMetrics().heightPixels*4/5));
            });
        },"session-list").start();
    }
    private static void importSession(Activity a,ComicProject session){
        ProjectLauncher.launch(a,session.title,"web","web:"+session.sourceKey,(progress,cancelled)->snapshot(a,session,progress,cancelled),null);
    }
    private static synchronized ProjectLauncher.Gathered snapshot(Activity a,ComicProject session,ProjectStore.Progress progress,java.util.function.BooleanSupplier cancelled)throws Exception{
        File scratch=new File(a.getCacheDir(),"session-import-"+UUID.randomUUID());
        return snapshotFiles(ComicProject.load(session.dir),scratch,progress,cancelled);
    }
    static ProjectLauncher.Gathered snapshotFiles(ComicProject session,File scratch,ProjectStore.Progress progress,java.util.function.BooleanSupplier cancelled)throws Exception{
        try{
            if(!scratch.mkdirs())throw new IOException("无法创建导入副本");
            List<ProjectStore.PageSpec> specs=new ArrayList<>();
            int done=0;progress.update(0,session.pages.size());
            for(ComicProject.Page p:session.pages){
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                File target=new File(scratch,p.id),draft=session.draftDir(p),image=session.imageFile(p);
                ProjectStore.PageSpec spec;
                if(p.editable()&&new File(draft,PageDraft.JSON).isFile()){
                    // Copy layers once; don't also copy the duplicate page original/rendered images.
                    PageDraftStore.copyTree(draft,target,cancelled);
                    File rendered=new File(target,"rendered.png");
                    if(!rendered.isFile()&&image!=null&&image.isFile())PageDraftStore.copyTree(image,rendered,cancelled);
                    spec=new ProjectStore.PageSpec(p.label,null,null,"png",false,target);
                }else{
                    if(image==null||!image.isFile())throw new IOException("会话页面已失效，请回网页重新加载："+p.label);
                    target.mkdirs();File saved=new File(target,"image.png");PageDraftStore.copyTree(image,saved,cancelled);
                    spec=new ProjectStore.PageSpec(p.label,null,null,"png",ComicProject.KIND_ORIGINAL.equals(p.kind));spec.ownedImage=saved;
                }
                spec.disposableSnapshot=true;specs.add(spec);progress.update(++done,session.pages.size());
            }
            ProjectLauncher.Gathered result=new ProjectLauncher.Gathered(specs,"");result.cleanup=()->PageDraftStore.deleteTree(scratch);return result;
        }catch(Exception e){PageDraftStore.deleteTree(scratch);throw e;}
    }
}
