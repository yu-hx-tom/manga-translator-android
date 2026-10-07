package cn.local.manga;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.io.*;

/** Android document picker provides an explicit destination; no broad storage permission. */
public final class LogsActivity extends ShellActivity {
    @Override protected boolean showsNavigation(){return false;}
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private TranslationLog log;private TextView text;private Button export;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);log=new TranslationLog(new File(getFilesDir(),"diagnostics"));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);
        Ui.insets(root,0,0,0,0,false);
        root.addView(Ui.appBar(this,Icons.iconButton(this,R.drawable.ic_arrow_back,"返回",v->finish()),"翻译日志",null));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),Ui.dp(this,16));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout info=Ui.section(this,body,"翻译诊断日志 · 最近约 1 MB","记录任务、段落、失败阶段和原因。不会导出 Key、漫画图片或完整网址。重传无法修复漏检或不安全排版，具体请看失败阶段。",4);
        export=Icons.iconTextButton(this,R.drawable.ic_ios_share,"导出日志（JSONL）",Ui.PRIMARY,v->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"漫画翻译日志_"+System.currentTimeMillis()+".jsonl");startActivityForResult(intent,60);});info.addView(export,Ui.margins(this,0,10,0,0));
        ScrollView scroll=new ScrollView(this);scroll.setBackground(Ui.card(this,20));Ui.roundClip(scroll,20);
        text=Ui.text(this,"读取中…",12,Ui.ICON);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));text.setAlpha(.5f);
        scroll.addView(text);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1){{topMargin=Ui.dp(LogsActivity.this,12);}});setContentView(root);Ui.enter(body,40);
        worker.submit(()->{try{String all=log.read();String preview=all.length()>48000?"（仅预览末尾，导出包含保留的全部日志）\n"+all.substring(all.length()-48000):all;runOnUiThread(()->{if(!isDestroyed()){text.setText(preview.isEmpty()?"暂无日志。":preview);text.animate().alpha(1f).setDuration(240).start();}});}catch(Exception e){runOnUiThread(()->{text.setText("无法读取日志");text.setAlpha(1f);});}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=60||result!=RESULT_OK||data==null||data.getData()==null)return;
        export.setEnabled(false);android.net.Uri uri=data.getData();
        worker.submit(()->{String message;try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();log.export(out);message="日志已导出";}catch(Exception e){message="日志导出失败，请更换保存位置";}String done=message;runOnUiThread(()->{if(!isDestroyed()){export.setEnabled(true);Toast.makeText(this,done,Toast.LENGTH_LONG).show();}});});
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
