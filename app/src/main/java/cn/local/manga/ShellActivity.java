package cn.local.manga;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Shared shell; existing native activities remain alive and each tab retains its own stack. */
public class ShellActivity extends Activity {
    private static final ArrayList<WeakReference<ShellActivity>> screens = new ArrayList<>();
    protected int tab;
    private Button action;
    private TextView taskStatus;
    private TextView exportStatus;
    private final ExportJob.Listener exportChanged=this::refreshExport;
    private WeakReference<ShellActivity> parentScreen;
    private final Runnable taskChanged = this::refreshTask;
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        int defaultTab=this instanceof BrowserActivity?0:this instanceof LocalLibraryActivity||this instanceof LocalFolderActivity||this instanceof LocalReaderActivity||this instanceof MainActivity?1:2;
        if(auxiliary())for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&!a.isDestroyed()&&!a.isFinishing())defaultTab=a.tab;}
        tab = getIntent().getIntExtra("shellTab",defaultTab);
        for(WeakReference<ShellActivity> ref:screens){ShellActivity previous=ref.get();if(previous!=null&&previous.tab==tab&&!previous.isFinishing()&&!previous.isDestroyed())parentScreen=ref;}
        screens.add(new WeakReference<>(this));
    }
    @Override public void setContentView(View content) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            View navigation=root.findViewWithTag("shell-navigation");
            if(this instanceof BrowserActivity&&navigation!=null){boolean keyboard=android.os.Build.VERSION.SDK_INT>=30?insets.isVisible(WindowInsets.Type.ime()):insets.getSystemWindowInsetBottom()>Ui.dp(this,120);navigation.setVisibility(keyboard?View.GONE:View.VISIBLE);onKeyboardVisibility(keyboard);}
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(0,0,0,Math.max(bars.bottom,ime.bottom));
                return new WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(),android.graphics.Insets.of(bars.left,bars.top,bars.right,0)).setInsets(WindowInsets.Type.ime(),android.graphics.Insets.NONE).build();}
            v.setPadding(0,0,0,insets.getSystemWindowInsetBottom());return new WindowInsets.Builder(insets).setSystemWindowInsets(android.graphics.Insets.of(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),0)).build();
        });
        // Each legacy content view handles top/IME insets; reserve the navigation inset only once here.
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        taskStatus = Ui.text(this, "", 12, Ui.MUTED); taskStatus.setSingleLine(true); taskStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        taskStatus.setPadding(Ui.dp(this,16), Ui.dp(this,4), Ui.dp(this,16), Ui.dp(this,4));
        taskStatus.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("翻译任务").setMessage(TranslationTaskManager.detail).setPositiveButton("关闭", null).show());
        root.addView(taskStatus);
        exportStatus=Ui.text(this,"",12,Ui.ACCENT_DEEP);exportStatus.setSingleLine(true);exportStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);exportStatus.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),Ui.dp(this,6));
        exportStatus.setOnClickListener(v->{if(ExportJob.running())new AlertDialog.Builder(this).setMessage("停止导出？").setNegativeButton("继续",null).setPositiveButton("停止",(d,w)->ExportJob.cancel()).show();else ExportFlow.openResult(this);});root.addView(exportStatus);
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(Ui.dp(this,4), 0, Ui.dp(this,4), 0);
        nav.setTag("shell-navigation");
        String[] labels = {"浏览器", "本地翻译", "汉化工具"}, icons = {"◎", "▣", "✎"};
        for (int i=0; i<3; i++) {
            final int target=i; LinearLayout cell=new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            TextView icon=Ui.text(this,icons[i],22,i==tab?Ui.ACCENT:Ui.MUTED), label=Ui.text(this,labels[i],12,i==tab?Ui.ACCENT:Ui.MUTED);
            label.setSingleLine(true); icon.setGravity(Gravity.CENTER); label.setGravity(Gravity.CENTER); cell.addView(icon); cell.addView(label);
            cell.setContentDescription(labels[i]); cell.setOnClickListener(v->leaveEditor(()->switchTab(target))); cell.setBackground(Ui.round(this,i==tab?0xffE8F0FE:Ui.BG,16));
            nav.addView(cell,new LinearLayout.LayoutParams(0,Ui.dp(this,56),1));
        }
        action=Ui.button(this,"开始翻译",Ui.TONAL,v->{
            if(TranslationTaskManager.running()){TranslationTaskManager.stop();return;}
            String reason=translationDisabled(); if(reason!=null) Toast.makeText(this,reason,Toast.LENGTH_SHORT).show(); else startTranslation();
        }); action.setSingleLine(true); action.setTextSize(12); action.setPadding(Ui.dp(this,6),0,Ui.dp(this,6),0);
        nav.addView(action,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1.35f)); root.addView(nav);
        // Last child sits directly above the root's IME inset, below task/export status.
        View accessory=keyboardAccessory();if(accessory!=null)root.addView(accessory,new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));
        super.setContentView(root); refreshTask();
        refreshExport();
    }
    protected View keyboardAccessory(){return null;}
    protected void onKeyboardVisibility(boolean visible){}
    private boolean auxiliary(){return this instanceof SettingsActivity||this instanceof LibraryActivity||this instanceof LogsActivity;}
    private ShellActivity targetScreen(){ShellActivity target=null;for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&a!=this&&a.tab==tab&&!a.auxiliary()&&!a.isDestroyed()&&!a.isFinishing())target=a;}return target;}
    protected String translationDisabled(){ShellActivity target=auxiliary()?targetScreen():null;return target==null?"请先打开可翻译的漫画或工程":target.translationDisabled();}
    protected void startTranslation(){ShellActivity target=auxiliary()?targetScreen():null;if(target!=null)target.startTranslation();}
    protected void leaveEditor(Runnable next){next.run();}
    protected final void refreshTask(){
        if(action==null)return;
        boolean active=TranslationTaskManager.running();
        action.setText(TranslationTaskManager.state==TranslationTaskManager.State.STOPPING?"正在停止…":active?"停止翻译":"开始翻译");
        action.setEnabled(TranslationTaskManager.state!=TranslationTaskManager.State.STOPPING);
        action.setAlpha(!active&&translationDisabled()!=null?.4f:1f);
        taskStatus.setText(TranslationTaskManager.detail); taskStatus.setVisibility(TranslationTaskManager.state==TranslationTaskManager.State.IDLE?View.GONE:View.VISIBLE);
    }
    protected void switchTab(int target){
        if(target==tab)return;
        ShellActivity latest=null;
        for(WeakReference<ShellActivity> ref:screens){ShellActivity a=ref.get();if(a!=null&&!a.isFinishing()&&!a.isDestroyed()&&!a.auxiliary()&&a.tab==target)latest=a;}
        Class<?> type=latest!=null?latest.getClass():target==0?BrowserActivity.class:target==1?LocalLibraryActivity.class:ProjectListActivity.class;
        Intent intent=latest!=null?new Intent(latest.getIntent()):new Intent(this,type);
        if(type==BrowserActivity.class)intent.removeExtra("url");
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); overridePendingTransition(R.anim.screen_open_enter,R.anim.screen_open_exit);
    }
    private void refreshExport(){if(exportStatus!=null){String text=ExportJob.globalStatus();exportStatus.setText(text+(ExportJob.running()?" · 点此取消":text.startsWith("已导出")?" · 打开 / 分享":""));exportStatus.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}}
    @Override protected void onResume(){super.onResume(); screens.removeIf(r->r.get()==null||r.get()==this);screens.add(new WeakReference<>(this));TranslationTaskManager.listen(taskChanged);ExportJob.listen(exportChanged);refreshTask();refreshExport();}
    @Override protected void onPause(){TranslationTaskManager.unlisten(taskChanged);ExportJob.unlisten(exportChanged);super.onPause();}
    @Override public void finish(){
        ShellActivity parent=parentScreen==null?null:parentScreen.get();
        if(parent!=null&&!parent.isFinishing()&&!parent.isDestroyed())startActivity(new Intent(parent.getIntent()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        super.finish();
    }
    @Override public void onBackPressed(){
        if(this instanceof LocalLibraryActivity||this instanceof ProjectListActivity){switchTab(0);return;}
        super.onBackPressed();
    }
    void settings(View anchor){PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add("设置").setOnMenuItemClickListener(i->{startActivity(new Intent(this,SettingsActivity.class).putExtra("shellTab",tab));return true;});menu.show();}
}
