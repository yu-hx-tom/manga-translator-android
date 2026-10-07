package cn.local.manga;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Continuous reader, using ListView recycling and bounded decoding rather than fifty full bitmaps. */
public class ProjectReaderActivity extends ShellActivity {
    static Intent intent(Context c,String id,int tab){return new Intent(c,tab==1?LocalProjectReaderActivity.class:ProjectReaderActivity.class).putExtra("project",id).putExtra("shellTab",tab);}
    private ComicProject project; private ListView list;private TextView title,pageNumber;private boolean original;private volatile int revision;
    private final android.util.LruCache<String,Bitmap> thumbnails=new android.util.LruCache<String,Bitmap>(24*1024*1024){protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
    private final ExecutorService images=Executors.newSingleThreadExecutor();private final Pages adapter=new Pages();
    private final Runnable taskUi=()->{if(!isDestroyed())adapter.notifyDataSetChanged();};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);Ui.insets(root,0,0,0,0,false);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.button(this,"‹",Ui.TEXT,v->finish()),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        title=Ui.heading(this,"漫画阅读",16);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setOnClickListener(v->rename());bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(Ui.button(this,"导出",Ui.TEXT,v->{if(project!=null)ExportFlow.show(this,project,()->{});}));
        Button more=Ui.button(this,"⋮",Ui.TEXT,null);more.setOnClickListener(v->menu(more));bar.addView(more,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));root.addView(bar);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        pageNumber=Ui.text(this,"0 / 0",13,Ui.MUTED);pageNumber.setPadding(Ui.dp(this,16),0,0,0);pageNumber.setOnClickListener(v->jump());toolbar.addView(pageNumber,new LinearLayout.LayoutParams(0,-2,1));
        Button toggle=Ui.button(this,"查看原图",Ui.TEXT,null);toggle.setOnClickListener(v->{original=!original;toggle.setText(original?"查看译图":"查看原图");revision++;adapter.notifyDataSetChanged();});toolbar.addView(toggle);root.addView(toolbar);
        if(tab==1){Button workshop=Ui.button(this,"在汉化工具中打开",Ui.TEXT,v->{if(project!=null)startActivity(intent(this,project.id,2));});workshop.setTextSize(12);workshop.setSingleLine(true);toolbar.addView(workshop);}
        list=new ListView(this);list.setDividerHeight(Ui.dp(this,6));list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{if(project==null)return;if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)){Toast.makeText(this,"请先停止工程翻译再编辑，已完成页会保留",0).show();return;}startActivity(WorkbenchActivity.intent(this,project.id,pos).putExtra("shellTab",tab));});
        list.setOnItemLongClickListener((p,v,pos,id)->{pageMenu(pos);return true;});list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int s){}public void onScroll(AbsListView v,int first,int count,int total){pageNumber.setText((total==0?0:first+1)+" / "+total);}});
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    @Override protected void onResume(){super.onResume();TranslationTaskManager.listen(taskUi);reload();}
    @Override protected void onPause(){TranslationTaskManager.unlisten(taskUi);super.onPause();}
    void styleChanged(){revision++;adapter.notifyDataSetChanged();}
    private void reload(){images.execute(()->{try{ComicProject loaded=ProjectStore.open(this,getIntent().getStringExtra("project"));runOnUiThread(()->{if(isDestroyed())return;project=loaded;title.setText(project.title);revision++;adapter.notifyDataSetChanged();refreshTask();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"工程读取失败："+e.getMessage(),1).show());}});}
    protected String translationDisabled(){return project==null||project.pages.isEmpty()?"请先导入漫画":null;}
    protected void startTranslation(){ProjectTranslation.start(this,project,-1,this::reload);}
    private void menu(View anchor){PopupMenu m=new PopupMenu(this,anchor);m.getMenu().add("设置").setOnMenuItemClickListener(i->{startActivity(new Intent(this,SettingsActivity.class));return true;});
        if(tab==1)m.getMenu().add("在汉化工具中打开").setOnMenuItemClickListener(i->{startActivity(intent(this,project.id,2));return true;});
        m.getMenu().add("工程默认样式").setOnMenuItemClickListener(i->{if(project!=null)StylePanel.projectDefaults(this,project);return true;});m.show();}
    private void rename(){if(project==null)return;EditText text=new EditText(this);text.setSingleLine(true);text.setText(project.title);new AlertDialog.Builder(this).setTitle("重命名工程").setView(text).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String value=text.getText().toString().trim();if(!value.isEmpty()){project.title=value;title.setText(value);save();}}).show();}
    private void jump(){if(project==null)return;EditText input=new EditText(this);input.setInputType(2);input.setHint("页码 1–"+project.pages.size());new AlertDialog.Builder(this).setTitle("跳转到页").setView(input).setNegativeButton("取消",null).setPositiveButton("前往",(d,w)->{try{list.setSelection(Math.max(0,Math.min(project.pages.size()-1,Integer.parseInt(input.getText().toString())-1)));}catch(Exception e){Toast.makeText(this,"请输入页码",0).show();}}).show();}
    private void pageMenu(int index){ComicProject.Page page=project.pages.get(index);new AlertDialog.Builder(this).setTitle("第 "+(index+1)+" 页").setItems(new String[]{"重新翻译该页","删除该页","设为封面"},(d,w)->{
        if(TranslationTaskManager.running()){Toast.makeText(this,"请先停止翻译再修改工程",0).show();return;}
        if(w==0)new AlertDialog.Builder(this).setMessage("重新翻译会覆盖本页文字修改，继续？").setNegativeButton("取消",null).setPositiveButton("重新翻译",(x,y)->ProjectTranslation.start(this,project,index,this::reload)).show();
        if(w==1)new AlertDialog.Builder(this).setMessage("删除本页及编辑？").setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->{project.pages.remove(page);save();adapter.notifyDataSetChanged();}).show();
        if(w==2){project.coverPageId=page.id;save();images.execute(()->ProjectStore.writeCover(project));}
    }).show();}
    private void save(){ComicProject target=project;images.execute(()->{try{target.save();}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"保存失败："+e.getMessage(),1).show());}});}
    @Override protected void onActivityResult(int r,int s,Intent d){super.onActivityResult(r,s,d);ExportFlow.onActivityResult(this,r,s,d);}
    @Override protected void onDestroy(){revision++;thumbnails.evictAll();images.shutdownNow();super.onDestroy();}
    private final class Pages extends BaseAdapter {
        public int getCount(){return project==null?0:project.pages.size();}public Object getItem(int p){return project.pages.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(ProjectReaderActivity.this);
            if(row.getChildCount()==0){row.setOrientation(LinearLayout.VERTICAL);row.addView(Ui.text(ProjectReaderActivity.this,"",12,Ui.MUTED));ImageView image=new ImageView(ProjectReaderActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setAdjustViewBounds(true);row.addView(image,new LinearLayout.LayoutParams(-1,-2));}
            ComicProject.Page p=project.pages.get(position);TextView badge=(TextView)row.getChildAt(0);ImageView view=(ImageView)row.getChildAt(1);
            String state=TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)&&p.id.equals(ProjectTranslation.activePageId)?"翻译中":p.editedCount()>0?"已编辑":"failed".equals(p.status)?"失败 · 点此编辑或长按重试":p.editable()||"translated".equals(p.status)?"已翻译":"未翻译";
            badge.setText((position+1)+" / "+getCount()+" · "+state);badge.setGravity(Gravity.END);view.setImageDrawable(null);view.setMinimumHeight(Ui.dp(ProjectReaderActivity.this,180));
            Object token=new Object();row.setTag(token);final int rev=revision;final boolean showOriginal=original;final ComicProject target=project;
            String cacheKey=rev+":"+p.id+":"+showOriginal;Bitmap cached=thumbnails.get(cacheKey);if(cached!=null){view.setMinimumHeight(0);view.setImageBitmap(cached);return row;}
            images.execute(()->{if(isDestroyed()||rev!=revision||row.getTag()!=token)return;Bitmap bitmap=null;try{
                File file=p.editable()?new File(target.draftDir(p),showOriginal?PageDraft.SOURCE:"rendered.png"):target.imageFile(p);
                if(p.editable()&&!showOriginal&&(p.editedCount()>0||!target.defaultStyle.isDefault()||!file.isFile())){try(PageComposer composer=new PageComposer(PageDraft.read(target.draftDir(p)).withEdits(p.edits),1400)){composer.layoutAll(target.effectiveEdits(p,composer.draft),()->isDestroyed()||rev!=revision);bitmap=composer.compose(null);int width=Math.min(900,bitmap.getWidth());Bitmap small=Bitmap.createScaledBitmap(bitmap,width,Math.max(1,bitmap.getHeight()*width/bitmap.getWidth()),true);if(small!=bitmap)bitmap.recycle();bitmap=small;}}
                else if(file!=null&&file.isFile()){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>1000||o.outHeight/o.inSampleSize>2400)o.inSampleSize*=2;o.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeFile(file.getPath(),o);}
            }catch(Exception|OutOfMemoryError ignored){}
                Bitmap ready=bitmap;runOnUiThread(()->{if(isDestroyed()||rev!=revision){if(ready!=null)ready.recycle();return;}if(ready!=null)thumbnails.put(cacheKey,ready);if(row.getTag()!=token)return;if(ready!=null){view.setMinimumHeight(0);view.setImageBitmap(ready);}else badge.setText(badge.getText()+" · 图片读取失败");});
            });return row;
        }
    }
}
