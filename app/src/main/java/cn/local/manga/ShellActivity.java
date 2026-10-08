package cn.local.manga;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Shared native shell. Tab navigation retains each activity's existing editor state. */
public class ShellActivity extends Activity {
    private static final ArrayList<WeakReference<ShellActivity>> screens=new ArrayList<>();
    protected int tab;
    private Button action;
    private TextView taskStatus,exportStatus;
    private LinearLayout taskPanel,taskChips,exportPanel;
    private ProgressBar taskProgress,exportProgress;
    private ProgressRingDrawable actionRing;
    private boolean keyboardVisible;
    private View navigation;
    private WeakReference<ShellActivity> parentScreen;
    private final Runnable taskChanged=this::refreshTask;
    private final ExportJob.Listener exportChanged=this::refreshExport;

    @Override protected void onCreate(android.os.Bundle state){
        super.onCreate(state);
        PerformanceDiagnostics.initialize(this);
        int defaultTab=this instanceof BrowserActivity?0:this instanceof LocalLibraryActivity||this instanceof LocalFolderActivity||this instanceof LocalReaderActivity||this instanceof MainActivity?1:2;
        if(auxiliary())for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&!a.isDestroyed()&&!a.isFinishing())defaultTab=a.tab;}
        tab=getIntent().getIntExtra("shellTab",defaultTab);
        for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&a.tab==tab&&!a.isFinishing()&&!a.isDestroyed())parentScreen=ref;}
        screens.add(new WeakReference<>(this));
    }
    protected boolean showsNavigation(){return true;}
    @Override public void setContentView(View content){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            boolean keyboard=android.os.Build.VERSION.SDK_INT>=30?insets.isVisible(WindowInsets.Type.ime()):insets.getSystemWindowInsetBottom()>Ui.dp(this,120);
            keyboardChanged(keyboard);
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(0,0,0,Math.max(bars.bottom,ime.bottom));
                return new WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(),android.graphics.Insets.of(bars.left,bars.top,bars.right,0)).setInsets(WindowInsets.Type.ime(),android.graphics.Insets.NONE).build();}
            v.setPadding(0,0,0,insets.getSystemWindowInsetBottom());return new WindowInsets.Builder(insets).setSystemWindowInsets(android.graphics.Insets.of(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),0)).build();
        });
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        taskPanel=new LinearLayout(this);taskPanel.setOrientation(LinearLayout.VERTICAL);taskPanel.setTag("shell-task-status");taskProgress=Ui.progressLine(this);taskPanel.addView(taskProgress);
        LinearLayout taskRow=new LinearLayout(this);taskRow.setGravity(Gravity.CENTER_VERTICAL);taskRow.setPadding(Ui.dp(this,8),Ui.dp(this,3),Ui.dp(this,8),Ui.dp(this,3));taskChips=new LinearLayout(this);taskRow.addView(taskChips);
        taskStatus=Ui.text(this,"",12,Ui.MUTED);taskStatus.setSingleLine(true);taskStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);taskRow.addView(taskStatus,new LinearLayout.LayoutParams(0,-2,1));taskPanel.addView(taskRow);
        taskPanel.setContentDescription("查看翻译任务详情");taskPanel.setOnClickListener(v->showTask());root.addView(taskPanel);
        exportPanel=new LinearLayout(this);exportPanel.setOrientation(LinearLayout.VERTICAL);exportProgress=Ui.progressLine(this);exportPanel.addView(exportProgress);
        exportStatus=Ui.text(this,"",12,Ui.ACCENT_DEEP);exportStatus.setSingleLine(true);exportStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);exportStatus.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),Ui.dp(this,6));exportPanel.addView(exportStatus);
        exportPanel.setOnClickListener(v->{if(ExportJob.running()){Ui.Sheet sheet=Ui.sheet(this,"导出进行中");sheet.item(R.drawable.ic_stop,"停止导出",ExportJob::cancel);sheet.show();}else ExportFlow.openResult(this);});root.addView(exportPanel);
        if(showsNavigation()){navigation=makeNavigation();root.addView(navigation,new LinearLayout.LayoutParams(-1,Ui.dp(this,64)));}
        View accessory=keyboardAccessory();if(accessory!=null)root.addView(accessory,new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));
        super.setContentView(root);refreshTask();refreshExport();
        if(android.os.Build.VERSION.SDK_INT<30)root.getViewTreeObserver().addOnGlobalLayoutListener(()->{android.graphics.Rect visible=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(visible);keyboardChanged(root.getRootView().getHeight()-visible.bottom>Ui.dp(this,120));});
    }
    private View makeNavigation(){
        LinearLayout nav=new LinearLayout(this);nav.setTag("shell-navigation");nav.setTransitionName("main-navigation");nav.setBackgroundColor(Ui.BG);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(Ui.dp(this,4),0,Ui.dp(this,4),0);
        String[] labels={"浏览器","本地翻译","汉化工具"};int[] normal={R.drawable.ic_tab_browser,R.drawable.ic_tab_local,R.drawable.ic_tab_workshop},active={R.drawable.ic_tab_browser_active,R.drawable.ic_tab_local_active,R.drawable.ic_tab_workshop_active};
        for(int i=0;i<3;i++){
            final int target=i;boolean selected=i==tab;LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);cell.setMinimumHeight(Ui.dp(this,64));
            FrameLayout iconBox=new FrameLayout(this);View pill=new View(this);pill.setBackground(Ui.round(this,Ui.ACCENT_SOFT,16));pill.setVisibility(selected?View.VISIBLE:View.INVISIBLE);iconBox.addView(pill,new FrameLayout.LayoutParams(-1,-1));
            ImageView plain=new ImageView(this),filled=new ImageView(this);plain.setImageDrawable(Icons.icon(this,normal[i],Ui.MUTED));filled.setImageDrawable(Icons.icon(this,active[i],Ui.ACCENT_DEEP));
            // Float alpha is View opacity (0..1); ImageView's int overload is drawable alpha (0..255).
            iconBox.addView(plain,new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.CENTER));iconBox.addView(filled,new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.CENTER));plain.setAlpha(selected?0f:1f);filled.setAlpha(selected?1f:0f);cell.addView(iconBox,new LinearLayout.LayoutParams(Ui.dp(this,56),Ui.dp(this,32)));
            if(selected&&Ui.motion()){pill.setScaleX(.2f);pill.animate().scaleX(1).setDuration(200).setInterpolator(Ui.EASE).start();filled.setAlpha(0f);filled.animate().alpha(1f).setDuration(200).start();}
            TextView label=Ui.text(this,labels[i],12,selected?Ui.ACCENT_DEEP:Ui.MUTED);label.setSingleLine(true);label.setGravity(Gravity.CENTER);if(selected)label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);cell.addView(label);
            cell.setContentDescription(labels[i]);cell.setSelected(selected);cell.setOnClickListener(v->leaveEditor(()->switchTab(target)));Ui.pressable(cell);nav.addView(cell,new LinearLayout.LayoutParams(0,Ui.dp(this,64),1));
        }
        action=Icons.iconTextButton(this,R.drawable.ic_translate,"开始翻译",Ui.PRIMARY,v->{if(TranslationTaskManager.running()){TranslationTaskManager.stop();return;}String reason=translationDisabled();if(reason!=null)Toast.makeText(this,reason,Toast.LENGTH_SHORT).show();else startTranslation();});
        action.setSingleLine(true);action.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);action.setPadding(Ui.dp(this,4),0,Ui.dp(this,4),0);action.setCompoundDrawablePadding(Ui.dp(this,4));action.setMinWidth(0);action.setMinimumWidth(0);
        nav.addView(action,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1.35f));actionRing=new ProgressRingDrawable();actionRing.setBounds(0,0,Ui.dp(this,20),Ui.dp(this,20));return nav;
    }
    private void keyboardChanged(boolean visible){if(navigation!=null)navigation.setVisibility(visible?View.GONE:View.VISIBLE);if(keyboardVisible==visible)return;keyboardVisible=visible;refreshTask();refreshExport();onKeyboardVisibility(visible);}
    protected View keyboardAccessory(){return null;}
    protected void onKeyboardVisibility(boolean visible){}
    private boolean auxiliary(){return this instanceof SettingsActivity||this instanceof LibraryActivity||this instanceof LogsActivity;}
    private ShellActivity targetScreen(){ShellActivity target=null;for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&a!=this&&a.tab==tab&&!a.auxiliary()&&!a.isDestroyed()&&!a.isFinishing())target=a;}return target;}
    protected String translationDisabled(){ShellActivity target=auxiliary()?targetScreen():null;return target==null?"请先打开可翻译的漫画或工程":target.translationDisabled();}
    protected void startTranslation(){ShellActivity target=auxiliary()?targetScreen():null;if(target!=null)target.startTranslation();}
    protected void leaveEditor(Runnable next){next.run();}
    protected void showFailedPages(){startActivity(new Intent(this,LogsActivity.class).putExtra("shellTab",tab));}
    private void showTask(){
        Ui.Sheet sheet=Ui.sheet(this,"翻译任务");TextView detail=Ui.text(this,TranslationTaskManager.detail,14,Ui.INK);detail.setTextIsSelectable(true);sheet.body.addView(detail,Ui.margins(this,8,8,8,16));
        sheet.item(R.drawable.ic_stop,"停止翻译",null,TranslationTaskManager.running(),TranslationTaskManager::stop);
        sheet.item(R.drawable.ic_error,"查看失败页",null,TranslationTaskManager.counts[1]>0,()->{
            ShellActivity owner=null;
            for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a==null||a.isDestroyed()||a.isFinishing())continue;
                if(TranslationTaskManager.owner.equals("browser")&&a instanceof BrowserActivity||TranslationTaskManager.owner.equals("project:"+a.getIntent().getStringExtra("project"))&&a instanceof ProjectReaderActivity||TranslationTaskManager.owner.equals("local")&&a instanceof LocalFolderActivity)owner=a;
            }
            if(owner!=null){startActivity(new Intent(owner.getIntent()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));owner.showFailedPages();}else showFailedPages();
        });sheet.show();
    }
    protected final void refreshTask(){
        boolean active=TranslationTaskManager.running(),stopping=TranslationTaskManager.state==TranslationTaskManager.State.STOPPING;int[] counts=TranslationTaskManager.counts;
        if(action!=null){String label=stopping?"正在停止…":active?"停止翻译":"开始翻译";action.setText(label);action.setContentDescription(label);action.setEnabled(!stopping);
            action.setAlpha(1f);
            Ui.style(action,active?Ui.DANGER_TONAL:Ui.PRIMARY);action.setTextSize(12);action.setPadding(Ui.dp(this,4),0,Ui.dp(this,4),0);action.setCompoundDrawablePadding(Ui.dp(this,4));
            if(active){action.setCompoundDrawablesRelative(actionRing,null,null,null);action.setCompoundDrawableTintList(null);actionRing.setTint(stopping?Ui.DISABLED:Ui.DANGER);actionRing.update(counts[3]>0?(counts[0]+counts[1])/(float)counts[3]:0,stopping||counts[3]<=0,stopping);}else{actionRing.stop();Icons.setIcon(action,R.drawable.ic_translate,Ui.SURFACE,18);}}
        if(taskPanel!=null){taskPanel.setVisibility(!showsNavigation()||keyboardVisible||TranslationTaskManager.state==TranslationTaskManager.State.IDLE?View.GONE:View.VISIBLE);taskStatus.setText(TranslationTaskManager.detail);taskChips.removeAllViews();
            if(counts[1]>0)addTaskChip("失败 "+counts[1],R.drawable.ic_error,Ui.NEGATIVE);addTaskChip("处理中 "+counts[2],R.drawable.ic_translate,Ui.INFO);
            taskProgress.setIndeterminate(active&&counts[3]<=0);taskProgress.setProgress(counts[3]>0?Math.min(1000,(counts[0]+counts[1])*1000/counts[3]):0,true);}
    }
    private void addTaskChip(String label,int icon,int tone){TextView chip=Ui.chip(this,label,icon,tone);chip.setTextSize(10);chip.setPadding(Ui.dp(this,5),Ui.dp(this,2),Ui.dp(this,5),Ui.dp(this,2));taskChips.addView(chip);}
    protected void switchTab(int target){
        if(target==tab)return;ShellActivity latest=null;for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&!a.isFinishing()&&!a.isDestroyed()&&!a.auxiliary()&&a.tab==target)latest=a;}
        Class<?> type=latest!=null?latest.getClass():target==0?BrowserActivity.class:target==1?LocalLibraryActivity.class:ProjectListActivity.class;Intent intent=latest!=null?new Intent(latest.getIntent()):new Intent(this,type);if(type==BrowserActivity.class)intent.removeExtra("url");
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));overridePendingTransition(R.anim.tab_fade_in,R.anim.tab_fade_out);
    }
    private void refreshExport(){if(exportPanel==null)return;String text=ExportJob.globalStatus();exportStatus.setText(exportStatus.getContext().getString(R.string.shell_activity_message_25, text, (ExportJob.running()?" · 点此停止":text.startsWith("已导出")?" · 打开 / 分享":"")));exportPanel.setVisibility(!showsNavigation()||keyboardVisible||text.isEmpty()?View.GONE:View.VISIBLE);exportProgress.setIndeterminate(ExportJob.running());}
    @Override protected void onResume(){super.onResume();screens.removeIf(r->r.get()==null||r.get()==this);screens.add(new WeakReference<>(this));TranslationTaskManager.listen(taskChanged);ExportJob.listen(exportChanged);refreshTask();refreshExport();}
    @Override protected void onPause(){TranslationTaskManager.unlisten(taskChanged);ExportJob.unlisten(exportChanged);if(actionRing!=null)actionRing.dispose();super.onPause();}
    @Override public void finish(){ShellActivity parent=parentScreen==null?null:parentScreen.get();if(parent!=null&&!parent.isFinishing()&&!parent.isDestroyed())startActivity(new Intent(parent.getIntent()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));super.finish();}
    @Override public void onBackPressed(){if(this instanceof LocalLibraryActivity||this instanceof ProjectListActivity){switchTab(0);return;}super.onBackPressed();}
    void settings(View anchor){Ui.Sheet menu=Ui.sheet(this,"页面选项");menu.item(R.drawable.ic_settings,"设置",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("shellTab",tab)));menu.show();}
}
