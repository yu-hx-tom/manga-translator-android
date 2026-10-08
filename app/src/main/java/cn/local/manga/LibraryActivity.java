package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;

/** Searchable bookmarks/history, returning a selected URL to the existing WebView. */
public final class LibraryActivity extends ShellActivity {
    @Override protected boolean showsNavigation(){return false;}
    private boolean bookmarks,destroyed,loading;
    private int generation;
    private EditText search;
    private TextView status;
    private Button more;
    private ListView list;
    private ArrayAdapter<LibraryStore.Entry> adapter;
    private final ArrayList<LibraryStore.Entry> entries=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable searchChanged=()->reload(false);
    @Override public void onCreate(Bundle state){
        super.onCreate(state);bookmarks=getIntent().getBooleanExtra("bookmarks",true);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(Ui.BG);
        Ui.insets(page,0,0,0,0,true);
        page.addView(Ui.appBar(this,Icons.iconButton(this,R.drawable.ic_arrow_back,"返回阅读",v->finish()),bookmarks?"收藏夹":"历史记录",null));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),0,dp(16),0);page.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        search=new EditText(this);search.setSingleLine(true);search.setHint("搜索标题或网址");Icons.setIcon(search,R.drawable.ic_search,Ui.MUTED,20);search.setTextSize(14);Ui.field(search);body.addView(search,Ui.margins(this,0,4,0,0));
        status=new Ui.StatusText(this);status.setText("读取中…");status.setTextSize(12);status.setTextColor(Ui.MUTED);status.setPadding(dp(4),dp(10),dp(4),dp(6));body.addView(status);
        list=new ListView(this);list.setDivider(null);list.setDividerHeight(0);list.setSelector(new android.graphics.drawable.ColorDrawable(0));list.setClipToPadding(false);list.setPadding(0,0,0,dp(16));list.setVerticalScrollBarEnabled(false);
        // Rows slide up one after another whenever a fresh result set is shown.
        android.view.animation.AnimationSet rowIn=new android.view.animation.AnimationSet(true);
        rowIn.addAnimation(new android.view.animation.AlphaAnimation(0f,1f));
        rowIn.addAnimation(new android.view.animation.TranslateAnimation(android.view.animation.Animation.RELATIVE_TO_SELF,0,android.view.animation.Animation.RELATIVE_TO_SELF,0,android.view.animation.Animation.RELATIVE_TO_SELF,.25f,android.view.animation.Animation.RELATIVE_TO_SELF,0));
        rowIn.setDuration(300);rowIn.setInterpolator(Ui.EASE);
        android.view.animation.LayoutAnimationController rows=new android.view.animation.LayoutAnimationController(rowIn,.12f);list.setLayoutAnimation(rows);
        body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        more=Ui.button(this,"加载更多",Ui.TONAL,v->reload(true));FrameLayout moreFrame=new FrameLayout(this);moreFrame.setPadding(0,dp(6),0,dp(6));moreFrame.addView(more,new FrameLayout.LayoutParams(-1,-2));list.addFooterView(moreFrame,null,false);
        adapter=new ArrayAdapter<LibraryStore.Entry>(this,android.R.layout.simple_list_item_2,android.R.id.text1,entries){
            @Override public View getView(int position,View convertView,ViewGroup parent){
                View row=convertView!=null?convertView:makeRow();LibraryStore.Entry entry=getItem(position);
                TextView title=row.findViewById(android.R.id.text1),detail=row.findViewById(android.R.id.text2);
                title.setText(entry.displayTitle(bookmarks));title.setMaxLines(2);
                String time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(bookmarks?entry.bookmarkedAt:entry.visitedAt));
                detail.setText(detail.getContext().getString(R.string.library_activity_message_8, time, entry.url));detail.setMaxLines(3);return row;
            }
        };list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->{if(position>=entries.size())return;String url=entries.get(position).url;
            if(BrowserLibrary.isWebUrl(url)){setResult(RESULT_OK,new Intent().putExtra("url",url));finish();}});
        list.setOnItemLongClickListener((parent,view,position,id)->{if(position>=entries.size())return false;manage(entries.get(position));return true;});
        if(!bookmarks){Button clear=Ui.button(this,"清空历史记录",Ui.DANGER_TONAL,v->new AlertDialog.Builder(this).setTitle("清空历史记录？")
            .setMessage("收藏夹会保留。").setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which)->BrowserLibrary.clearHistory(this,this::changed)).show());body.addView(clear,Ui.margins(this,0,4,0,10));}
        setContentView(page);Ui.enter(body,60);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){handler.removeCallbacks(searchChanged);handler.postDelayed(searchChanged,250);}public void afterTextChanged(Editable e){}});reload(false);
    }
    private void reload(boolean append){
        if(destroyed||(append&&loading))return;int token=++generation;loading=true;more.setEnabled(false);
        if(!append){entries.clear();adapter.notifyDataSetChanged();status.setText("读取中…");}
        int offset=entries.size();String term=search.getText().toString();
        BrowserLibrary.list(this,bookmarks,term,offset,(values,error)->{
            if(destroyed||isFinishing()||token!=generation)return;loading=false;more.setEnabled(true);
            if(error!=null){status.setText(error);more.setVisibility(View.GONE);return;}
            boolean hasMore=values.size()>100;boolean fresh=entries.isEmpty();entries.addAll(values.subList(0,Math.min(100,values.size())));adapter.notifyDataSetChanged();more.setVisibility(hasMore?View.VISIBLE:View.GONE);
            if(fresh&&!entries.isEmpty()&&Ui.motion())list.scheduleLayoutAnimation();
            status.setText(entries.isEmpty()?(term.isEmpty()?(bookmarks?"还没有收藏。在浏览器菜单中收藏当前页。":"还没有浏览记录。"):"没有匹配的记录。"):("已显示 "+entries.size()+" 条 · 点击打开，长按管理"));
        });
    }
    private void manage(LibraryStore.Entry entry){
        new AlertDialog.Builder(this).setTitle(entry.displayTitle(bookmarks)).setItems(bookmarks?new String[]{"修改收藏名称","取消收藏"}:new String[]{"收藏此页","删除这条历史"},(d,index)->{
            if(bookmarks&&index==0){EditText name=new EditText(this);name.setSingleLine(true);name.setText(entry.displayTitle(true));new AlertDialog.Builder(this).setTitle("修改收藏名称").setView(name)
                .setNegativeButton("取消",null).setPositiveButton("保存",(dialog,which)->BrowserLibrary.rename(this,entry.url,name.getText().toString(),this::changed)).show();}
            else if(!bookmarks&&index==0)BrowserLibrary.addBookmark(this,entry.title,entry.url,error->{if(!destroyed&&!isFinishing())Toast.makeText(this,error==null?"已收藏":error,Toast.LENGTH_SHORT).show();});
            else new AlertDialog.Builder(this).setMessage(bookmarks?"取消这条收藏？":"删除这条历史记录？收藏会保留。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->BrowserLibrary.remove(this,entry.url,bookmarks,this::changed)).show();
        }).show();
    }
    /** Card row reusing the stock text1/text2 ids so getView stays a plain bind. */
    private View makeRow(){
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(12),dp(12),dp(12));
        row.setBackground(Ui.ripple(Ui.card(this,16),Ui.round(this,Ui.SURFACE,16),Ui.RIPPLE));
        ImageView badge=new ImageView(this);badge.setImageDrawable(Icons.icon(this,bookmarks?R.drawable.ic_star:R.drawable.ic_history,Ui.ACCENT));badge.setPadding(dp(8),dp(8),dp(8),dp(8));badge.setBackground(Ui.round(this,Ui.ACCENT_SOFT,12));
        row.addView(badge,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);
        TextView title=Ui.text(this,"",15,Ui.INK);title.setId(android.R.id.text1);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail=Ui.text(this,"",12,Ui.MUTED);detail.setId(android.R.id.text2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(title);words.addView(detail,Ui.margins(this,0,3,0,0));
        LinearLayout.LayoutParams wordsParams=new LinearLayout.LayoutParams(0,-2,1);wordsParams.leftMargin=dp(12);row.addView(words,wordsParams);
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(Icons.icon(this,R.drawable.ic_chevron_right,Ui.MUTED));row.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        // Cards sit in a FrameLayout so the gap between rows is real spacing, not part of the ripple.
        FrameLayout holder=new FrameLayout(this);holder.setPadding(0,dp(4),0,dp(4));
        holder.addView(row,new FrameLayout.LayoutParams(-1,-2));
        row.setDuplicateParentStateEnabled(true);Ui.pressable(row);
        return holder;
    }
    private void changed(String error){if(destroyed||isFinishing())return;if(error!=null)Toast.makeText(this,error,Toast.LENGTH_LONG).show();reload(false);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){destroyed=true;generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
