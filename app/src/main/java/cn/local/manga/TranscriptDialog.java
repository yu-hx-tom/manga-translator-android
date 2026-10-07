package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Read-only local view: opening, paging and copying never start recognition or translation. */
final class TranscriptDialog {
    private static final int PAGE_SIZE=20;
    static void show(Activity activity,TranslationTranscript value){
        final TranslationTranscript transcript=value==null?TranslationTranscript.unavailable():value;
        if(transcript.rows.isEmpty()){
            new AlertDialog.Builder(activity).setTitle("识读原文 / 译文").setMessage(transcript.describe()).setPositiveButton("关闭",null).show();return;
        }
        float density=activity.getResources().getDisplayMetrics().density;int pad=(int)(16*density);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,0);
        TextView note=new TextView(activity);note.setText("原文是模型识读结果，可能有误。查看或复制不会请求翻译。\n段落位置对应处理时的原图。"+(transcript.truncated?"\n记录过长，仅保留部分；译图不受影响。":""));note.setTextSize(13);note.setTextColor(Ui.MUTED);root.addView(note);
        LinearLayout pager=new LinearLayout(activity);pager.setGravity(Gravity.CENTER_VERTICAL);pager.setPadding(0,pad/2,0,0);
        Button previous=Icons.iconTextButton(activity,R.drawable.ic_chevron_left,"上一组",Ui.TONAL,null),next=Icons.iconTextButton(activity,R.drawable.ic_chevron_right,"下一组",Ui.TONAL,null);
        TextView pageInfo=Ui.text(activity,"",13,Ui.MUTED);pageInfo.setGravity(Gravity.CENTER);
        pager.addView(previous,new LinearLayout.LayoutParams(-2,-2));pager.addView(pageInfo,new LinearLayout.LayoutParams(0,-2,1));pager.addView(next,new LinearLayout.LayoutParams(-2,-2));root.addView(pager);
        ScrollView scroll=new ScrollView(activity);TextView text=new TextView(activity);text.setTextSize(16);text.setTextColor(Ui.INK);text.setTextIsSelectable(true);text.setLineSpacing(4*density,1f);text.setPadding(0,pad/2,0,pad);scroll.addView(text);
        int height=(int)Math.max(180*density,Math.min(450*density,activity.getResources().getDisplayMetrics().heightPixels*.55));root.addView(scroll,new LinearLayout.LayoutParams(-1,height));
        int[] page={0};int pages=(transcript.rows.size()+PAGE_SIZE-1)/PAGE_SIZE;
        Runnable update=()->{
            int start=page[0]*PAGE_SIZE,end=Math.min(transcript.rows.size(),start+PAGE_SIZE);SpannableStringBuilder content=new SpannableStringBuilder();
            for(int i=start;i<end;i++){
                TranslationTranscript.Row row=transcript.rows.get(i);
                if(i>start)muted(content,"\n\n────────────\n\n");
                bold(content,"段落 "+(i+1)+" · "+row.id,Ui.ACCENT_DEEP);content.append("\n");
                muted(content,row.statusLabel()+"\n位置 "+row.left+","+row.top+" — "+row.right+","+row.bottom+(row.vertical?" · 竖排":" · 横排"));
                content.append("\n\n");bold(content,"原文",Ui.ICON);content.append("\n").append(row.originalLabel());
                content.append("\n\n");bold(content,"译文",Ui.ICON);content.append("\n").append(row.zh.isEmpty()?"（无文字译文）":row.zh);
                if(!row.error.isEmpty()){content.append("\n\n");int at=content.length();content.append("说明：").append(row.error);content.setSpan(new ForegroundColorSpan(Ui.DANGER),at,content.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            }
            text.setText(content);pageInfo.setText((page[0]+1)+" / "+pages+" · 共"+transcript.rows.size()+"段");previous.setEnabled(page[0]>0);next.setEnabled(page[0]+1<pages);scroll.post(()->scroll.scrollTo(0,0));
        };
        previous.setOnClickListener(v->{if(page[0]>0){page[0]--;update.run();slide(text,-1);}});next.setOnClickListener(v->{if(page[0]+1<pages){page[0]++;update.run();slide(text,1);}});update.run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("识读原文 / 译文").setView(root).setPositiveButton("关闭",null).setNeutralButton("复制当前组",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button->{
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("漫画原文与译文",text.getText().toString()));Toast.makeText(activity,"已复制当前组",Toast.LENGTH_SHORT).show();}
        }));dialog.show();
    }
    /** New page content slides in from the side the reader is moving toward. */
    private static void slide(View view,int direction){
        if(!Ui.motion())return;
        view.animate().cancel();view.setAlpha(0f);view.setTranslationX(direction*Ui.dp(view.getContext(),28));
        view.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Ui.EASE).start();
    }
    private static void bold(SpannableStringBuilder out,String value,int color){
        int at=out.length();out.append(value);
        out.setSpan(new StyleSpan(Typeface.BOLD),at,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(color),at,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private static void muted(SpannableStringBuilder out,String value){
        int at=out.length();out.append(value);
        out.setSpan(new ForegroundColorSpan(Ui.MUTED),at,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(.86f),at,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
