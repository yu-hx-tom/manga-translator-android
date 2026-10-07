package cn.local.manga;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** SAF imports. Archive extraction uses generated file names, with bounded count and expanded bytes. */
final class LocalImport {
    static final int FOLDER=9101,IMAGES=9102,ARCHIVE=9103;
    static void choose(Activity a){new AlertDialog.Builder(a).setTitle("选择本地文件").setItems(new String[]{"选择文件夹","选择图片（可多选）","选择压缩包（zip/cbz）"},(d,i)->pick(a,i)).show();}
    static void pick(Activity a,int choice){
        Intent intent=choice==0?new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE):new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(choice==1?"image/*":"*/*");
        if(choice==1)intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{a.startActivityForResult(intent,FOLDER+choice);}catch(Exception e){Toast.makeText(a,"没有可用的文件选择器",0).show();}
    }
    static boolean result(Activity a,int request,int result,Intent data){
        if(request<FOLDER||request>ARCHIVE)return false;
        if(result!=Activity.RESULT_OK||data==null)return true;
        if(!CacheStorage.beginUse()){Toast.makeText(a,"缓存清理中，请稍后导入",0).show();return true;}
        AtomicBoolean stop=new AtomicBoolean();ProgressDialog dialog=new ProgressDialog(a);dialog.setTitle("导入本地漫画");dialog.setMessage("正在读取文件…");dialog.setCancelable(false);dialog.setButton(DialogInterface.BUTTON_NEGATIVE,"取消",(d,w)->stop.set(true));dialog.show();
        new Thread(()->{
            File staging=new File(a.getCacheDir(),"import-"+UUID.randomUUID());staging.mkdirs();
            try{
                List<ProjectStore.PageSpec> specs=new ArrayList<>();String title="本地漫画";
                if(request==FOLDER){Uri tree=data.getData();if(tree==null)throw new IOException("未选择文件夹");String id=DocumentsContract.getTreeDocumentId(tree);title=id.substring(id.lastIndexOf('/')+1);gatherFolder(a,new LocalComics.Folder(tree,id,title),specs,stop,0);}
                else if(request==IMAGES){List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());for(Uri uri:uris){String name=name(a,uri);specs.add(new ProjectStore.PageSpec(name,null,()->a.getContentResolver().openInputStream(uri),LocalComics.extensionOf(name),true));}}
                else{
                    Uri uri=data.getData();if(uri==null)throw new IOException("未选择压缩包");title=name(a,uri);long total=0;int count=0;
                    try(ZipInputStream zip=new ZipInputStream(a.getContentResolver().openInputStream(uri))){ZipEntry entry;byte[] buffer=new byte[65536];int entries=0;
                        while((entry=zip.getNextEntry())!=null){if(stop.get())throw new java.util.concurrent.CancellationException();if(++entries>10000)throw new IOException("压缩包条目过多");if(entry.isDirectory())continue;
                            if(!image(entry.getName())){int n;while((n=zip.read(buffer))!=-1){if(stop.get())throw new java.util.concurrent.CancellationException();total+=n;if(total>2L*1024*1024*1024)throw new IOException("解压内容过大");}continue;}
                            if(++count>2000)throw new IOException("压缩包超过 2000 页，请按章节导入");
                            String label=entry.getName();File file=new File(staging,"page-"+count);try(FileOutputStream out=new FileOutputStream(file)){int n;long page=0;while((n=zip.read(buffer))!=-1){if(stop.get())throw new java.util.concurrent.CancellationException();total+=n;page+=n;if(total>2L*1024*1024*1024||page>128L*1024*1024)throw new IOException("解压内容过大，请按章节导入");out.write(buffer,0,n);}}
                            specs.add(new ProjectStore.PageSpec(label,null,()->new FileInputStream(file),LocalComics.extensionOf(label),true));
                        }
                    }
                }
                specs.sort((x,y)->NaturalOrder.INSTANCE.compare(x.label,y.label));
                ComicProject project=ProjectStore.create(a,title,"local","",specs,(n,t)->a.runOnUiThread(()->dialog.setMessage("正在导入 "+n+" / "+t)),stop::get);
                a.runOnUiThread(()->{dialog.dismiss();if(!a.isDestroyed())a.startActivity(ProjectReaderActivity.intent(a,project.id,a instanceof LocalLibraryActivity?1:2));});
            }catch(java.util.concurrent.CancellationException ignored){a.runOnUiThread(dialog::dismiss);}
            catch(Exception|OutOfMemoryError e){a.runOnUiThread(()->{dialog.dismiss();if(!a.isDestroyed())new AlertDialog.Builder(a).setTitle("导入失败").setMessage(e instanceof OutOfMemoryError?"内存不足，请减少图片数量":e.getMessage()).setPositiveButton("重新选择",(d,w)->choose(a)).setNegativeButton("关闭",null).show();});}
            finally{try{PageDraftStore.deleteTree(staging);}finally{CacheStorage.endUse();}}
        },"local-import").start();return true;
    }
    private static void gatherFolder(Activity a,LocalComics.Folder folder,List<ProjectStore.PageSpec> out,AtomicBoolean stop,int depth)throws Exception{
        if(depth>12)throw new IOException("目录层级过深");if(stop.get())throw new java.util.concurrent.CancellationException();
        LocalComics.Listing listing=LocalComics.list(a,folder);
        for(LocalComics.Page p:listing.pages){if(out.size()>=2000)throw new IOException("文件夹超过 2000 页");Uri uri=DocumentsContract.buildDocumentUriUsingTree(folder.tree,p.source.documentId);out.add(new ProjectStore.PageSpec(folder.name+"/"+p.source.name,null,()->a.getContentResolver().openInputStream(uri),LocalComics.extensionOf(p.source.name),true));}
        for(LocalComics.Entry child:listing.folders)gatherFolder(a,new LocalComics.Folder(folder.tree,child.documentId,folder.name+"/"+child.name),out,stop,depth+1);
    }
    private static boolean image(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|bmp|gif)$");}
    private static String name(Activity a,Uri uri){try(android.database.Cursor c=a.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "图片.png";}
}
