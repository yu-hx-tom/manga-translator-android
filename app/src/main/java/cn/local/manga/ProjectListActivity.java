package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 我的汉化工程: open, export, rename or delete saved projects. */
public final class ProjectListActivity extends ShellActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private android.widget.ListView list;
    private java.util.List<ComicProject> projects=new java.util.ArrayList<>();
    private android.widget.BaseAdapter rows;
    private TextView currentSession;
    private TextView summary;
    private boolean destroyed;
    private final ExportJob.Listener exportChanged = this::reload;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.addView(Ui.appBar(this,null,"汉化工具",null,Icons.iconButton(this,R.drawable.ic_more_vert,"页面选项",this::settings)));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(8),dp(16),dp(16));
        LinearLayout web=importCard(R.drawable.ic_public,"导入浏览器译本","从浏览器已翻译的章节继续精修",()->SessionRepository.choose(this));
        currentSession=Ui.chip(this,"当前页面",R.drawable.ic_public,Ui.INFO);currentSession.setVisibility(View.GONE);web.addView(currentSession);page.addView(web,Ui.margins(this,0,0,0,10));
        page.addView(importCard(R.drawable.ic_upload_file,"本地文件","文件夹 / 图片 / ZIP / CBZ",()->LocalImport.choose(this)));
        summary=Ui.text(this,"读取中…",13,Ui.MUTED);page.addView(summary,Ui.margins(this,4,16,4,8));
        list=new android.widget.ListView(this);list.setDivider(null);list.addHeaderView(page,null,false);list.setClipToPadding(false);list.setPadding(0,0,0,dp(16));
        rows=new android.widget.BaseAdapter(){public int getCount(){return projects.size();}public Object getItem(int i){return projects.get(i);}public long getItemId(int i){return i;}public View getView(int i,View old,android.view.ViewGroup parent){LinearLayout box=new LinearLayout(ProjectListActivity.this);box.setPadding(dp(16),0,dp(16),dp(10));box.addView(row(projects.get(i),0,null),new LinearLayout.LayoutParams(-1,-2));return box;}};
        list.setAdapter(rows);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); ExportJob.listen(exportChanged); reload(); }
    @Override protected void onPause() { ExportJob.unlisten(exportChanged); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if(LocalImport.result(this,request,result,data))return;
        ExportFlow.onActivityResult(this, request, result, data);
    }

    private LinearLayout importCard(int icon,String title,String caption,Runnable action){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(Ui.card(this,18));TextView heading=Ui.heading(this,title,16);Icons.setIcon(heading,icon,Ui.ACCENT,24);card.addView(heading);card.addView(Ui.text(this,caption,13,Ui.MUTED),Ui.margins(this,0,8,0,0));card.setContentDescription(title+"，"+caption);card.setOnClickListener(v->action.run());Ui.pressable(card);return card;
    }
    @Override void settings(View anchor){Ui.Sheet sheet=Ui.sheet(this,"汉化工具");sheet.item(R.drawable.ic_settings,"设置",()->startActivity(new Intent(this,SettingsActivity.class)));sheet.item(R.drawable.ic_delete,"缓存管理",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("openCache",true)));sheet.show();}
    private int loadRevision;
    private void reload(){int token=++loadRevision;io.execute(()->{List<ComicProject> loaded=ProjectStore.list(this);boolean hasCurrent=false;String url=SessionRepository.currentUrl==null?"":SessionRepository.currentUrl.split("#",2)[0];for(ComicProject session:SessionRepository.list(this))if(session.sourceKey.equals(url)){hasCurrent=true;break;}boolean current=hasCurrent;runOnUiThread(()->{if(destroyed||token!=loadRevision)return;projects=loaded;summary.setText(loaded.isEmpty()?"还没有工程，使用上方入口导入漫画开始编辑。":loaded.size()+" 个工程");Icons.setIcon(summary,loaded.isEmpty()?R.drawable.ic_book:R.drawable.ic_folder,Ui.MUTED,loaded.isEmpty()?48:18);currentSession.setVisibility(current?View.VISIBLE:View.GONE);rows.notifyDataSetChanged();});});}

    private View row(ComicProject project, long size, Bitmap cover) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setBackground(Ui.ripple(Ui.card(this, 18), Ui.round(this, Ui.SURFACE, 18), Ui.RIPPLE));
        FrameLayout thumbBox = new FrameLayout(this); thumbBox.setBackground(Ui.round(this, Ui.SURFACE_SOFT, 12)); Ui.roundClip(thumbBox, 12);
        ImageView thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ProjectCover.bind(thumb,ProjectStore.cover(project));
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        row.addView(thumbBox, new LinearLayout.LayoutParams(dp(64), dp(88)));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.heading(this, project.title, 15); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        int edited = 0; for (ComicProject.Page p : project.pages) edited += p.editedCount();
        String detail = project.pages.size() + " 页 · 已校对 " + project.reviewedCount() + (edited > 0 ? " · 修改 " + edited + " 段" : "")
                + "\n" + (project.updated > 0 ? DateUtils.getRelativeTimeSpanString(project.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + " 编辑 · " : "")
                + "";
        TextView info = Ui.text(this, detail, 12, Ui.MUTED);
        words.addView(name); words.addView(info, Ui.margins(this, 0, 4, 0, 0));
        String export = ExportJob.status(project);
        if (!export.isEmpty()) { TextView status = Ui.text(this, export, 12, Ui.ACCENT_DEEP); status.setMaxLines(2); words.addView(status, Ui.margins(this, 0, 4, 0, 0)); }
        boolean failed=false;for(ComicProject.Page page:project.pages)if("failed".equals(page.status)){failed=true;break;}
        boolean translating=TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id);
        if(translating||failed||!project.pages.isEmpty()&&project.reviewedCount()==project.pages.size())words.addView(Ui.chip(this,translating?"翻译中":failed?"有失败页":"已全部校对",translating?R.drawable.ic_translate:failed?R.drawable.ic_error:R.drawable.ic_check_circle,translating?Ui.INFO:failed?Ui.NEGATIVE:Ui.POSITIVE),Ui.margins(this,0,6,0,0));
        // Reviewed progress as a slim bar.
        View track = new View(this); track.setBackground(Ui.round(this, Ui.INFO_SOFT, 2));
        FrameLayout bar = new FrameLayout(this); bar.addView(track, new FrameLayout.LayoutParams(-1, dp(4)));
        View fill = new View(this); fill.setBackground(Ui.round(this, Ui.SUCCESS, 2));
        float ratio = project.pages.isEmpty() ? 0 : project.reviewedCount() / (float) project.pages.size();
        bar.addView(fill, new FrameLayout.LayoutParams(0, dp(4)));
        bar.post(() -> { fill.getLayoutParams().width = Math.round(bar.getWidth() * ratio); fill.requestLayout(); });
        words.addView(bar, Ui.margins(this, 0, 8, 0, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = dp(12);
        row.addView(words, wordsParams);
        row.addView(Icons.iconButton(this,R.drawable.ic_more_vert,"管理 "+project.title,v->manage(project)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        Ui.pressable(row);
        row.setOnClickListener(v -> startActivity(ProjectReaderActivity.intent(this, project.id, 2)));
        row.setOnLongClickListener(v -> { manage(project); return true; });
        return row;
    }

    private void manage(ComicProject project) {
        if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)){android.widget.Toast.makeText(this,"请先停止该工程的翻译",0).show();return;}
        Ui.Sheet menu=Ui.sheet(this,project.title);String[] labels={"打开编辑","导出","重命名","删除工程"};int[] icons={R.drawable.ic_edit_note,R.drawable.ic_ios_share,R.drawable.ic_edit,R.drawable.ic_delete};for(int n=0;n<labels.length;n++){final int which=n;menu.item(icons[n],labels[n],()->{
            if (which == 0) startActivity(ProjectReaderActivity.intent(this, project.id, 2));
            else if (which == 1) ExportFlow.show(this, project, () -> {});
            else if (which == 2) {
                EditText name = new EditText(this); name.setSingleLine(true); name.setText(project.title); Ui.field(name);
                FrameLayout box = new FrameLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(name);
                new AlertDialog.Builder(this).setTitle("重命名工程").setView(box).setNegativeButton("取消", null)
                        .setPositiveButton("保存", (x, y) -> {
                            String value = name.getText().toString().trim(); if (value.isEmpty()) return;
                            project.title = value;
                            io.execute(() -> { try { project.save(); } catch (Exception error) { runOnUiThread(()->android.widget.Toast.makeText(this,"保存失败："+error.getMessage(),1).show()); } runOnUiThread(this::reload); });
                        }).show();
            } else new AlertDialog.Builder(this).setTitle("删除工程？").setMessage("删除「" + project.title + "」的所有修改和页面副本。已导出的文件不受影响。此操作不能撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> {
                        if (ExportJob.running(project)||TranslationTaskManager.owner.equals("project:"+project.id)&&TranslationTaskManager.running()) { summary.setText("该工程正在导出，完成后再删除。"); return; }
                        io.execute(() -> { ProjectStore.delete(project); runOnUiThread(this::reload); });
                    }).show();
        });}menu.show();
    }

    private int dp(float value) { return Ui.dp(this, value); }
}
