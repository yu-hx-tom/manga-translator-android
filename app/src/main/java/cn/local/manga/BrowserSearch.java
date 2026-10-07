package cn.local.manga;

import android.app.Activity;
import android.content.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.*;
import androidx.webkit.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Search UI and a message-only, origin-scoped website term bridge. No Java object is exposed. */
final class BrowserSearch {
    private final Activity activity;
    private final EditText address;
    private final SharedPreferences prefs;
    private final LinearLayout rows;
    private final ScrollView panel;
    private final Consumer<String> navigate;
    private final HorizontalScrollView accessory;
    private final LinearLayout chips;
    private boolean imeVisible,siteFocused;
    private String candidateOrigin="",candidateTerm="",candidateField="";
    private long candidateAt;
    private int revision;
    BrowserSearch(Activity activity,EditText address,FrameLayout body,Consumer<String> navigate){
        this.activity=activity;this.address=address;this.navigate=navigate;
        prefs=activity.getSharedPreferences("browser-search-history",Context.MODE_PRIVATE);
        accessory=new HorizontalScrollView(activity);accessory.setHorizontalScrollBarEnabled(false);accessory.setBackgroundColor(0xffF2F5FA);
        accessory.setFocusable(false);accessory.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);accessory.setVisibility(View.GONE);
        accessory.setContentDescription("网站历史搜索词，可左右滑动");
        chips=new LinearLayout(activity);chips.setOrientation(LinearLayout.HORIZONTAL);chips.setGravity(Gravity.CENTER_VERTICAL);chips.setPadding(dp(8),dp(2),dp(8),dp(2));accessory.addView(chips);
        panel=new ScrollView(activity);panel.setFillViewport(true);panel.setBackgroundColor(Ui.BG);panel.setVisibility(View.GONE);
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(12),dp(8),dp(12),dp(16));panel.addView(rows);
        body.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        address.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){if(address.hasFocus())refresh();}public void afterTextChanged(Editable e){}});
    }
    View accessory(){return accessory;}
    void imeVisibility(boolean visible){imeVisible=visible;updateAccessory();}
    void pageStarted(){siteFocused=false;chips.removeAllViews();updateAccessory();}
    void navigation(String url){
        if(android.os.SystemClock.elapsedRealtime()-candidateAt<120000&&SearchTerms.matchesNavigation(url,candidateOrigin,candidateField,candidateTerm)){
            saveSite("site:"+candidateOrigin,candidateTerm);candidateTerm="";
        }
    }
    private void saveSite(String key,String term){
        List<String> sites=SearchTerms.read(prefs.getString("sites","[]"),128);sites.remove(key);sites.add(0,key);
        SharedPreferences.Editor edit=prefs.edit();if(sites.size()>128)edit.remove(sites.remove(sites.size()-1));
        edit.putString("sites",new JSONArray(sites).toString()).putString(key,SearchTerms.add(prefs.getString(key,"[]"),term,8)).apply();
    }
    private void updateAccessory(){accessory.setVisibility(imeVisible&&siteFocused&&!address.hasFocus()&&chips.getChildCount()>0?View.VISIBLE:View.GONE);}
    private void showEmptyTerms(){if(chips.getChildCount()==0){TextView empty=label("暂无历史词，提交搜索后会保留",13,Ui.MUTED);empty.setGravity(Gravity.CENTER_VERTICAL);empty.setPadding(dp(8),0,dp(8),0);chips.addView(empty,new LinearLayout.LayoutParams(-2,dp(48)));}}
    void focus(boolean focused){panel.setVisibility(focused?View.VISIBLE:View.GONE);if(focused)refresh();else revision++;updateAccessory();}
    void open(){address.requestFocus();address.setText("");address.post(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(address,InputMethodManager.SHOW_IMPLICIT));}
    boolean close(){if(!address.hasFocus())return false;address.clearFocus();((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);return true;}
    void record(String raw,String resolved){
        String value=raw==null?"":raw.trim();
        if(!value.isEmpty()&&resolved.startsWith("https://www.google.com/search?q=")&&!value.matches("(?i)^https?://.*"))
            prefs.edit().putString("omnibox",SearchTerms.add(prefs.getString("omnibox","[]"),value,100)).apply();
    }
    void install(WebView web,String script){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))return;
        WebViewCompat.addWebMessageListener(web,"__mangaSearchHistory",Collections.singleton("*"),(view,message,origin,mainFrame,reply)->{
            if(!mainFrame||activity.isDestroyed()||!("https".equals(origin.getScheme())||"http".equals(origin.getScheme()))||"manga-home.invalid".equals(origin.getHost()))return;
            try{
                String data=message.getData();if(data==null||data.length()>1024)return;JSONObject request=new JSONObject(data);
                String key="site:"+origin.toString(), op=request.optString("op");
                if("candidate".equals(op)){candidateOrigin=origin.toString();candidateTerm=request.optString("term");candidateField=request.optString("field");candidateAt=android.os.SystemClock.elapsedRealtime();return;}
                if("hide".equals(op)){siteFocused=false;updateAccessory();return;}
                if("save".equals(op)){
                    saveSite(key,request.optString("term"));
                }else if(!"load".equals(op))return;
                siteFocused=request.optBoolean("focused");chips.removeAllViews();
                String target=request.optString("target");
                for(String term:SearchTerms.read(prefs.getString(key,"[]"),8)){
                    TextView chip=label(term,14,Ui.INK);chip.setSingleLine();chip.setEllipsize(TextUtils.TruncateAt.END);chip.setMaxWidth(dp(240));chip.setGravity(Gravity.CENTER);chip.setPadding(dp(14),0,dp(14),0);
                    android.graphics.drawable.GradientDrawable outline=Ui.round(activity,Ui.SURFACE,8);outline.setStroke(dp(1),Ui.OUTLINE);chip.setBackground(outline);chip.setFocusable(false);
                    chip.setContentDescription("填入搜索框："+term+"，长按删除");chip.setOnClickListener(v->{try{
                        reply.postMessage(new JSONObject().put("op","fill").put("target",target).put("term",term).toString());
                    }catch(Exception ignored){pageStarted();}});
                    chip.setOnLongClickListener(v->{
                        if(candidateOrigin.equals(origin.toString())&&candidateTerm.equals(term))candidateTerm="";
                        prefs.edit().putString(key,SearchTerms.remove(prefs.getString(key,"[]"),term,8)).apply();
                        chips.removeView(chip);showEmptyTerms();updateAccessory();Toast.makeText(activity,"已删除词条："+term,Toast.LENGTH_SHORT).show();return true;
                    });
                    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(48));params.setMargins(0,0,dp(8),0);chips.addView(chip,params);
                }
                showEmptyTerms();accessory.scrollTo(0,0);updateAccessory();
            }catch(Exception ignored){/* Untrusted web messages cannot reach any other app data. */}
        });
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))WebViewCompat.addDocumentStartJavaScript(web,script,Collections.singleton("*"));
    }
    private void refresh(){
        int token=++revision;String query=address.getText().toString().trim(),needle=query.toLowerCase(Locale.ROOT);
        rows.removeAllViews();TextView caption=label("搜索记录和浏览历史",12,Ui.MUTED);caption.setPadding(dp(8),dp(8),0,dp(12));rows.addView(caption);
        int count=0;
        for(String term:SearchTerms.read(prefs.getString("omnibox","[]"),100))if(term.toLowerCase(Locale.ROOT).contains(needle)){addRow(R.drawable.ic_history,term,"历史搜索",term);if(++count==20)break;}
        final int termCount=count;
        BrowserLibrary.list(activity,false,query,0,(entries,error)->{
            if(token!=revision||!address.hasFocus()||activity.isDestroyed())return;
            int urls=0;if(entries!=null)for(LibraryStore.Entry entry:entries){if(BrowserAddress.HOME.equals(entry.url))continue;addRow(R.drawable.ic_public,entry.title,entry.url,entry.url);if(++urls==30)break;}
            if(termCount+urls==0){TextView empty=label(error!=null?error:query.isEmpty()?"搜索或访问网站后，记录会显示在这里":"没有匹配记录，可直接搜索或打开网址",14,Ui.MUTED);empty.setPadding(dp(8),dp(24),dp(8),0);rows.addView(empty);}
        });
    }
    private void addRow(int icon,String title,String subtitle,String value){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(68));
        ImageView symbol=new ImageView(activity);symbol.setImageDrawable(Icons.icon(activity,icon,Ui.MUTED));symbol.setScaleType(ImageView.ScaleType.CENTER_INSIDE);symbol.setPadding(dp(6),dp(6),dp(6),dp(6));row.addView(symbol,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout text=new LinearLayout(activity);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(8),dp(10),dp(8),dp(10));
        TextView first=label(title,15,Ui.INK),second=label(subtitle,12,Ui.MUTED);first.setSingleLine();second.setSingleLine();first.setEllipsize(TextUtils.TruncateAt.END);second.setEllipsize(TextUtils.TruncateAt.END);text.addView(first);text.addView(second);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.setOnClickListener(v->navigate.accept(value));row.setContentDescription(title+"，打开");row.setBackground(Ui.round(activity,Ui.BG,12));
        ImageButton fill=Icons.iconButton(activity,R.drawable.ic_north_west,"填入搜索框："+title,v->{address.setText(value);address.setSelection(address.length());});row.addView(fill,new LinearLayout.LayoutParams(dp(48),dp(48)));rows.addView(row);
    }
    private TextView label(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
