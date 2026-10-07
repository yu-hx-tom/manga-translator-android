package cn.local.manga;

import android.app.Activity;
import android.graphics.*;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial, page-at-a-time project translation. Every completed page commits before the next begins. */
final class ProjectTranslation {
    static volatile String activePageId="";
    static void start(Activity activity,ComicProject project,int only,Runnable refresh){
        AppSettings settings=AppSettings.load(activity);
        try{settings.validate();}catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();return;}
        AtomicBoolean cancelled=new AtomicBoolean();String owner="project:"+project.id;
        if(!TranslationTaskManager.begin(activity,owner,()->cancelled.set(true))){Toast.makeText(activity,"已有翻译任务，请先停止",0).show();return;}
        android.content.Context app=activity.getApplicationContext();
        new Thread(()->{
            int done=0,failed=0;String last="";
            try(TranslationEngine engine=new TranslationEngine(app)){
                for(int i=0;i<project.pages.size();i++){
                    if(cancelled.get())break;
                    ComicProject.Page page=project.pages.get(i);
                    if(only>=0?i!=only:(page.editable()||"translated".equals(page.status))&&!"failed".equals(page.status))continue;
                    String previousStatus=page.status;
                    page.status="translating";activePageId=page.id; final int number=i+1;
                    TranslationTaskManager.progress(owner,"正在翻译 "+number+" / "+project.pages.size());
                    Bitmap source=null;
                    try{
                        if(ComicProject.KIND_RENDERED.equals(page.kind)&&page.originalName==null)throw new IOException("旧成品图缺少原图，请回原网页重新翻译后导入");
                        File original=page.originalName!=null?new File(project.pageDir(page),page.originalName):page.editable()?new File(project.draftDir(page),PageDraft.SOURCE):project.imageFile(page);
                        if(original==null)throw new IOException("原图缺失");
                        source=ImageDecoder.decodeBitmap(ImageDecoder.createSource(original),(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
                        try(PagePipeline.Page result=PagePipeline.run(app,engine,source,settings,false,true,cancelled::get,(m,n,t)->TranslationTaskManager.progress(owner,"第 "+number+" 页 · "+m),"")){
                            if(!result.noText&&!result.rendered())throw new IOException(result.summary);
                            File dir=project.pageDir(page);dir.mkdirs();
                            if(result.draft!=null){
                                File target=project.draftDir(page), staged=new File(dir,"draft-new");PageDraftStore.copyTree(result.draft,staged);PageDraft.read(staged);
                                File old=new File(dir,"draft-old");PageDraftStore.deleteTree(old);if(target.exists()&&!target.renameTo(old))throw new IOException("无法更新草稿");
                                if(!staged.renameTo(target)){old.renameTo(target);throw new IOException("无法保存草稿");}PageDraftStore.deleteTree(old);page.kind=ComicProject.KIND_EDITABLE;page.edits.clear();
                                if(!project.defaultStyle.isDefault())for(PageDraft.Item item:PageDraft.read(target).items)page.edits.put(item.region.id,project.defaultStyle.copy());
                            }else if(result.rendered())page.kind=ComicProject.KIND_RENDERED;
                            Bitmap output=result.image==null?source:result.image;File image=new File(dir,"rendered.png");
                            try(FileOutputStream out=new FileOutputStream(image)){if(!output.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("无法保存译图");}
                            if(!page.editable()&&!result.noText)page.imageName="rendered.png";
                            page.status=result.outcome!=null&&result.outcome.incomplete()?"failed":"translated";
                            synchronized(project){project.save();}done++;
                        }
                    }catch(java.util.concurrent.CancellationException e){page.status=previousStatus;break;}
                    catch(Exception|OutOfMemoryError e){failed++;page.status="failed";last=e instanceof OutOfMemoryError?"图片过大，内存不足":e.getMessage();}
                    finally{if(source!=null&&!source.isRecycled())source.recycle();}
                    activity.runOnUiThread(()->{if(!activity.isDestroyed())refresh.run();});
                }
                synchronized(project){project.save();}
            }catch(Exception e){last=e.getMessage();}
            activePageId="";TranslationTaskManager.done(owner,(cancelled.get()?"已停止 · ":"")+"完成 "+done+" 页 · 失败 "+failed+(last.isEmpty()?"":" · "+last));
            activity.runOnUiThread(()->{if(!activity.isDestroyed())refresh.run();});
        },"project-translation").start();
    }
}
