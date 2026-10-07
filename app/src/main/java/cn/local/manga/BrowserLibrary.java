package cn.local.manga;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only library; disk access never runs on the browser UI thread. */
public final class BrowserLibrary {
    public interface Callback{void done(String error);}
    interface ListCallback{void loaded(List<LibraryStore.Entry> entries,String error);}
    private interface Mutation{void apply(LibraryStore store)throws Exception;}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"browser-library"));
    private static LibraryStore store;
    private BrowserLibrary(){}
    public static boolean isWebUrl(String url){return LibraryStore.isWebUrl(url);}
    private static LibraryStore get(Context context)throws Exception{
        if(store==null)store=new LibraryStore(new File(context.getFilesDir(),"browsing-library.json"));return store;
    }
    private static void deliver(Runnable action){new Handler(Looper.getMainLooper()).post(action);}
    private static void change(Context context,Mutation mutation,Callback callback){
        Context app=context.getApplicationContext();
        IO.execute(()->{String error=null;try{mutation.apply(get(app));}catch(Exception failed){store=null;error="无法保存浏览记录，请检查存储空间；原有记录已保留。";}
            if(callback!=null){String result=error;deliver(()->callback.done(result));}});
    }
    public static void recordVisit(Context context,String title,String url){if(isWebUrl(url))change(context,s->s.visit(title,url,System.currentTimeMillis()),null);}
    public static void addBookmark(Context context,String title,String url,Callback callback){
        if(!isWebUrl(url)){if(callback!=null)deliver(()->callback.done("当前页面无法收藏，请先打开正常网页"));return;}
        change(context,s->s.bookmark(title,url,System.currentTimeMillis()),callback);
    }
    static void rename(Context context,String url,String title,Callback callback){change(context,s->s.rename(url,title),callback);}
    static void remove(Context context,String url,boolean bookmark,Callback callback){change(context,s->s.remove(url,bookmark),callback);}
    static void clearHistory(Context context,Callback callback){change(context,LibraryStore::clearHistory,callback);}
    interface SitesCallback{void loaded(org.json.JSONArray sites,String error);}
    static void sites(Context context,SitesCallback callback){Context app=context.getApplicationContext();IO.execute(()->{try{org.json.JSONArray rows=get(app).frequentSites();deliver(()->callback.loaded(rows,null));}catch(Exception failed){store=null;deliver(()->callback.loaded(null,"无法读取常用网站"));}});}
    static void editSite(Context context,String previous,String name,String url,boolean pinned,boolean hidden,Callback callback){change(context,s->s.editShortcut(previous,name,url,pinned,hidden),callback);}
    static void list(Context context,boolean bookmarks,String query,int offset,ListCallback callback){
        Context app=context.getApplicationContext();
        IO.execute(()->{try{List<LibraryStore.Entry> values=get(app).list(bookmarks,query,offset,101);deliver(()->callback.loaded(values,null));}
            catch(Exception failed){store=null;deliver(()->callback.loaded(null,"无法读取浏览记录，原文件已保留。"));}});
    }
}
