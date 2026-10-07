package cn.local.manga;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native browser. Web content has no Java interface, credentials or access to app files. */
public final class BrowserActivity extends ShellActivity {
    private static final int REQUEST_LIBRARY=2052;
    private boolean mainFrameFailed; private final java.util.concurrent.atomic.AtomicInteger activeWorkers=new java.util.concurrent.atomic.AtomicInteger(); private String settlingSummary;
    private WebView web;
    private EditText address;
    private BrowserSearch search;
    private String searchScript="";
    private String fullAddress="";
    private TextView status;
    private ProgressBar progress;
    private Button cancelButton,autoButton,backButton,forwardButton,go; private boolean loadingPage; private boolean bookmarked;
    private LinearLayout controls,report;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService translators=Executors.newCachedThreadPool();
    private final ExecutorService preparation=Executors.newSingleThreadExecutor();
    private final AutoTranslationQueue queue=new AutoTranslationQueue();
    private final ArrayList<Future<?>> imageWork=new ArrayList<>();
    private WebPageBridge pageBridge;
    private PageCacheStore pages;
    private boolean autoRunning, resumeAuto;
    private int quietScans;
    private String pausedPage, pausedConfig;
    private String selectedDetector;
    private String autoError="";
    private final Map<String,PageOutcome> pageOutcomes=new LinkedHashMap<>();
    private final Set<String> failedPages=new HashSet<>();
    private boolean retryScanRequested;
    private final Set<String> retryOnScan=new HashSet<>();
    private final LinkedHashMap<String,String> recentPageDetails=new LinkedHashMap<>();
    private final Runnable autoTick=this::scanAuto;
    private final Runnable recoveryTick=this::recoverVisibleCache;
    private boolean foreground;
    private final Map<String,Integer> recoveryAttempts=new ConcurrentHashMap<>();
    private AppSettings autoSettings;
    private String autoPage,autoAgent; private volatile String sessionTitle="网页漫画";
    private String documentId=UUID.randomUUID().toString();
    private AtomicBoolean cancelled=new AtomicBoolean();
    private volatile int generation;
    private boolean busy,showTranslated=true;
    private volatile boolean destroyed;
    private Future<?> work;
    private String bridge,captureScript;
    private TranslationEngine engine;
    private TranslationStages stages;
    private float touchX,touchY;
    private String touchDocument;
    private boolean selectingPage;
    private android.graphics.drawable.GradientDrawable autoBackground;
    private android.animation.ValueAnimator autoPulse;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try(InputStream in=getAssets().open("browser_bridge.js");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);bridge=bytes.toString("UTF-8");
        }catch(Exception e){Toast.makeText(this,"网页图片组件加载失败",Toast.LENGTH_LONG).show();finish();return;}
        try(InputStream in=getAssets().open("browser_capture.js");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);captureScript=bytes.toString("UTF-8");bridge=captureScript+";\n"+bridge;
        }catch(Exception e){Toast.makeText(this,"画布读取组件加载失败",Toast.LENGTH_LONG).show();finish();return;}
        try(InputStream in=getAssets().open("browser_search.js");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);searchScript=bytes.toString("UTF-8");
        }catch(IOException ignored){}
        selectedDetector=AppSettings.load(this).detectorModel;
        pages=new PageCacheStore(getCacheDir());
        pageBridge=new WebPageBridge(this,main,new WebPageBridge.Host(){
            @Override public WebView web(){return web;}
            @Override public boolean valid(int token){return BrowserActivity.this.valid(token);}
            @Override public boolean destroyed(){return destroyed;}
            @Override public boolean pageChanged(){return stopIfPageChanged();}
        });
        engine=new TranslationEngine(getApplicationContext());buildUi();
        String url=getIntent().getStringExtra("url");
        if(state==null||web.restoreState(state)==null)navigate(url==null||url.trim().isEmpty()?BrowserAddress.HOME:url);
    }
    private void buildUi(){
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(0xffF8FAFD);getWindow().setNavigationBarColor(0xffF8FAFD);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xffF8FAFD);
        root.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        LinearLayout location=new LinearLayout(this);location.setGravity(Gravity.CENTER_VERTICAL);location.setPadding(dp(4),dp(6),dp(4),dp(6));root.addView(location,new LinearLayout.LayoutParams(-1,dp(64)));
        Button home=button("⌂",location,()->navigate(BrowserAddress.HOME));home.setContentDescription("返回首页");home.setTextSize(25);
        address=new EditText(this);address.setSingleLine();address.setTextSize(14);address.setTextColor(0xff303B4D);address.setHintTextColor(0xff737F90);address.setHint("搜索或输入网址");address.setPadding(dp(16),0,dp(12),0);address.setBackground(addressBackground());address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);location.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        go=button("↻",location,()->{if(address.hasFocus())navigate(address.getText().toString());else if(loadingPage)web.stopLoading();else web.reload();});go.setContentDescription("搜索或打开网址");go.setTextSize(23);
        Button menu=button("⋮",location,()->{});menu.setContentDescription("浏览器菜单");menu.setTextSize(25);menu.setOnClickListener(v->showMenu(menu));
        address.setOnEditorActionListener((v,a,event)->{navigate(address.getText().toString());return true;});
        address.setOnFocusChangeListener((v,focused)->{go.setText(focused?"→":loadingPage?"✕":"↻");String url=web==null?null:web.getUrl();if(url!=null){if(focused){address.setText(isHome(url)?"":url);address.selectAll();}else showAddress(url);}if(search!=null)search.focus(focused);});
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff4285F4));progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff4285F4));progress.setVisibility(View.INVISIBLE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        FrameLayout body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);web.setBackgroundColor(0xffF8FAFD);body.addView(web,new FrameLayout.LayoutParams(-1,-1));
        search=new BrowserSearch(this,address,body,this::navigate);search.install(web,searchScript);
        report=new LinearLayout(this);report.setPadding(dp(16),0,dp(12),0);report.setVisibility(View.GONE);
        status=new Ui.StatusText(this);status.setGravity(Gravity.CENTER_VERTICAL);status.setTextSize(11);status.setTextColor(0xff637085);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setText("打开网页，自由浏览");status.setContentDescription("翻译状态，点击查看完整详情");status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("浏览与翻译状态").setMessage(status.getText()).setPositiveButton("继续浏览",null).show());report.addView(status,new LinearLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(8),dp(4),dp(8),dp(4));controls.setVisibility(View.GONE);
        backButton=button("‹",controls,()->{if(web.canGoBack())web.goBack();else navigate(BrowserAddress.HOME);});backButton.setContentDescription("后退");backButton.setTextSize(27);
        forwardButton=button("›",controls,()->{if(web.canGoForward())web.goForward();});forwardButton.setContentDescription("前进");forwardButton.setTextSize(27);
        Button refresh=button("↻",controls,()->web.reload());refresh.setContentDescription("刷新网页");refresh.setTextSize(23);
        autoButton=button("开始翻译",controls,()->{if(busy)cancelWork();else startAuto();});autoButton.setLayoutParams(new LinearLayout.LayoutParams(0,dp(44),1));autoButton.setTextColor(0xff185ABC);autoBackground=rounded(0xffD3E3FD,22);autoButton.setBackground(Ui.ripple(autoBackground,rounded(0xffFFFFFF,22),0x30185ABC));autoButton.setTag(R.id.ui_tint_color,0xffD3E3FD);autoButton.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        Button bookmarks=button("☆",controls,()->openLibrary(true));bookmarks.setContentDescription("收藏夹");bookmarks.setTextSize(24);
        cancelButton=new Button(this);cancelButton.setVisibility(View.GONE);setContentView(root);root.setFocusableInTouchMode(true);root.requestFocus();
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);
        s.setSafeBrowsingEnabled(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        // Record sources before site scripts draw. Old WebViews still support readable canvas snapshots.
        try{if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(web,captureScript,Collections.singleton("*"));
        }catch(RuntimeException unsupported){/* Readable canvas and ordinary/background image capture remain available. */}
        web.setWebChromeClient(new WebChromeClient(){@Override public void onProgressChanged(WebView view,int p){if(!busy){if(progress.getVisibility()!=View.VISIBLE){progress.setIndeterminate(false);progress.setProgress(0);}progress.setProgress(p,true);Ui.reveal(progress,p<100,View.INVISIBLE);}}});
        web.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=event.getX();touchY=event.getY();touchDocument=documentId;}
            return false; // WebView keeps normal scrolling, zooming and long-press recognition.
        });
        web.setOnLongClickListener(v->{
            if(isHome(web.getUrl())||!Objects.equals(touchDocument,documentId))return false;
            WebView.HitTestResult hit=web.getHitTestResult();
            if(hit!=null&&hit.getType()==WebView.HitTestResult.EDIT_TEXT_TYPE)return false;
            selectPressedPage();return true;
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                if("manga-home.invalid".equalsIgnoreCase(request.getUrl().getHost())){
                    if(request.isForMainFrame()){
                        String path=request.getUrl().getPath();
                        if("/".equals(path))return false;
                        if(isHome(view.getUrl())){switch(path){
                            case "/search":search.open();break;case "/bookmarks":openLibrary(true);break;case "/history":openLibrary(false);break;
                            case "/shortcut-add":editHomeSite(null);break;
                            case "/shortcut-edit":String key=request.getUrl().getQueryParameter("site");BrowserLibrary.sites(BrowserActivity.this,(sites,error)->{if(sites!=null)for(int i=0;i<sites.length();i++){JSONObject site=sites.optJSONObject(i);if(site!=null&&site.optString("key").equals(key)){homeSiteMenu(site);break;}}});break;
                            case "/translate":startActivity(new Intent(BrowserActivity.this,MainActivity.class));break;
                            case "/projects":startActivity(new Intent(BrowserActivity.this,ProjectListActivity.class));break;
                            case "/local":startActivity(new Intent(BrowserActivity.this,LocalLibraryActivity.class));break;
                            case "/settings":startActivity(new Intent(BrowserActivity.this,SettingsActivity.class));break;
                        }}
                    }return true;
                }
                String scheme=request.getUrl().getScheme();if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))return false;
                if(request.isForMainFrame())status.setText("此链接需要外部应用；请使用网站的网页入口");return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if("manga-home.invalid".equalsIgnoreCase(request.getUrl().getHost())){
                    try{return new WebResourceResponse("text/html","UTF-8",getAssets().open("browser_home.html"));}
                    catch(IOException error){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream("首页暂时无法载入".getBytes(StandardCharsets.UTF_8)));}
                }return null;
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){
                search.navigation(url);
                search.pageStarted();
                mainFrameFailed=false;
                cancelWork();queue.clear();pageOutcomes.clear();failedPages.clear();recentPageDetails.clear();documentId=UUID.randomUUID().toString();showTranslated=true;
                if(url.startsWith("http://")||url.startsWith("https://"))showAddress(url);
                boolean home=isHome(url);loadingPage=true;go.setText(address.hasFocus()?"→":"✕");refreshTask();
                status.setText(home?"首页":"正在加载网页…");
                if(!home)view.evaluateJavascript(captureScript,null);
            }
            @Override public void onPageFinished(WebView view,String url){
                search.navigation(url);
                if(destroyed)return;loadingPage=false;go.setText(address.hasFocus()?"→":"↻");refreshTask();
                if(!isHome(url)){view.evaluateJavascript(captureScript,null);view.evaluateJavascript(searchScript,null);}
                if(!isHome(url)&&!mainFrameFailed&&BrowserLibrary.isWebUrl(url)&&Objects.equals(url,view.getUrl()))BrowserLibrary.recordVisit(BrowserActivity.this,view.getTitle(),url);
                if(!isHome(url)&&(url.startsWith("http://")||url.startsWith("https://"))){getSharedPreferences("browser",MODE_PRIVATE).edit().putString("lastUrl",url).apply();showAddress(url);}
                backButton.setEnabled(view.canGoBack());forwardButton.setEnabled(view.canGoForward());
                if(isHome(url)){showAddress(url);refreshHome();}
                else if(!busy&&!mainFrameFailed)status.setText("网页已载入 · 需要翻译时，点「开始翻译」");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){mainFrameFailed=true;status.setText("网页加载失败，请检查网址和手机网络");}}
            @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){search.navigation(url);}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&response.getStatusCode()>=400)mainFrameFailed=true;}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
                cancelWork();destroyed=true;worker.shutdownNow();translators.shutdownNow();preparation.shutdownNow();((android.view.ViewGroup)view.getParent()).removeView(view);view.destroy();web=null;
                new AlertDialog.Builder(BrowserActivity.this).setMessage("网页进程已结束，请重新打开浏览器。").setPositiveButton("返回首页",(d,w)->finish()).setCancelable(false).show();return true;
            }
        });
    }
    private void showMenu(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);
        // Grouped by task (dividers between groups): this chapter → records → library → tools.
        boolean onPage=web!=null&&!isHome(web.getUrl());
        menu.getMenu().add(0,12,0,"首页");
        menu.getMenu().add(0,17,1,"前进").setEnabled(web!=null&&web.canGoForward()); menu.getMenu().add(0,18,2,"在外部浏览器打开").setEnabled(onPage);
        menu.getMenu().add(0,15,1,"汉化与导出本章…").setEnabled(onPage);
        menu.getMenu().add(1,3,2,showTranslated?"查看原图":"查看译图").setEnabled(!busy&&onPage);
        menu.getMenu().add(1,10,3,"重试未完成部分").setEnabled((!busy||autoRunning)&&onPage);
        menu.getMenu().add(1,5,4,"手选网页图片").setEnabled(!busy&&onPage);
        menu.getMenu().add(1,4,5,"翻译当前画面").setEnabled(!busy&&onPage);
        menu.getMenu().add(2,6,6,"处理详情").setEnabled(!recentPageDetails.isEmpty());
        menu.getMenu().add(2,13,7,"识读原文 / 译文").setEnabled(!recentPageDetails.isEmpty());
        menu.getMenu().add(2,11,8,"查看／导出翻译日志");
        menu.getMenu().add(3,7,9,bookmarked?"取消收藏":"收藏当前页面").setEnabled(onPage&&BrowserLibrary.isWebUrl(web.getUrl()));
        menu.getMenu().add(3,8,10,"收藏夹");menu.getMenu().add(3,9,11,"历史记录");
        menu.getMenu().add(4,14,12,"本地漫画文件夹");menu.getMenu().add(4,16,13,"我的汉化工程");menu.getMenu().add(4,2,14,"单页翻译");menu.getMenu().add(4,1,15,"设置");
        menu.getMenu().setGroupDividerEnabled(true);
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 17:if(web.canGoForward())web.goForward();break; case 18:try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(web.getUrl())));}catch(Exception e){Toast.makeText(this,"没有可用浏览器",0).show();}break;
            case 12:navigate(BrowserAddress.HOME);break;
            case 13:showTranscripts();break;
            case 14:startActivity(new Intent(this,LocalLibraryActivity.class));break;
            case 15:exportChapter();break;
            case 16:startActivity(new Intent(this,ProjectListActivity.class));break;
            case 1:startActivity(new Intent(this,SettingsActivity.class));break;
            case 2:startActivity(new Intent(this,MainActivity.class));break;
            case 3:toggleImages();break;case 4:snapshot();break;case 5:scanImages();break;case 6:showOutcomes();break;
            case 10:retryIncomplete();break;
            case 11:startActivity(new Intent(this,LogsActivity.class));break;
            case 7:if(web!=null){BrowserLibrary.Callback callback=error->{if(error==null)bookmarked=!bookmarked;Toast.makeText(this,error==null?(bookmarked?"已收藏":"已取消收藏"):error,Toast.LENGTH_SHORT).show();};if(bookmarked)BrowserLibrary.remove(this,web.getUrl(),true,callback);else BrowserLibrary.addBookmark(this,web.getTitle(),web.getUrl(),callback);}break;
            case 8:case 9:startActivityForResult(new Intent(this,LibraryActivity.class).putExtra("bookmarks",item.getItemId()==8),REQUEST_LIBRARY);break;
        }return true;});menu.show();
    }
    private static boolean isHome(String url){return BrowserAddress.HOME.equals(url);}
    private android.graphics.drawable.GradientDrawable rounded(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void openLibrary(boolean bookmarks){startActivityForResult(new Intent(this,LibraryActivity.class).putExtra("bookmarks",bookmarks),REQUEST_LIBRARY);}
    private void refreshHome(){
        BrowserLibrary.sites(this,(sites,error)->{if(!destroyed&&web!=null&&isHome(web.getUrl()))web.evaluateJavascript("window.updateSites&&window.updateSites("+(sites==null?"[]":sites.toString())+")",null);});
        for(boolean bookmarks:new boolean[]{true,false})BrowserLibrary.list(this,bookmarks,"",0,(entries,error)->{
            if(destroyed||web==null||!isHome(web.getUrl())||entries==null)return;
            JSONArray rows=new JSONArray();for(int i=0;i<Math.min(3,entries.size());i++)try{LibraryStore.Entry e=entries.get(i);if(!isHome(e.url))rows.put(new JSONObject().put("title",e.displayTitle(bookmarks)).put("url",e.url));}catch(JSONException ignored){}
            web.evaluateJavascript("window.updateHome&&window.updateHome("+JSONObject.quote(bookmarks?"bookmarks":"recent")+","+rows+")",null);
        });
    }
    private void homeSiteMenu(JSONObject site){
        if(destroyed||!isHome(web.getUrl()))return;
        boolean pinned=site.optBoolean("pinned");new AlertDialog.Builder(this).setTitle(site.optString("title")).setItems(new String[]{"编辑名称和网址",pinned?"取消固定":"固定到前面","移除此网站"},(d,which)->{
            if(which==0){editHomeSite(site);return;}
            BrowserLibrary.editSite(this,site.optString("key"),site.optString("title"),site.optString("url"),!pinned,which==2,error->{if(error!=null)Toast.makeText(this,error,Toast.LENGTH_LONG).show();refreshHome();});
        }).show();
    }
    private void editHomeSite(JSONObject site){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(8),dp(24),0);
        EditText name=new EditText(this);name.setSingleLine();name.setHint("网站名称");fields.addView(name);
        EditText url=new EditText(this);url.setSingleLine();url.setHint("网址，例如 https://example.com");url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);fields.addView(url);
        CheckBox fixed=new CheckBox(this);fixed.setText("固定到前面");fixed.setChecked(site==null||site.optBoolean("pinned"));fields.addView(fixed);
        if(site!=null){name.setText(site.optString("title"));url.setText(site.optString("url"));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(site==null?"新增网站":"编辑网站").setView(fields).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String address=url.getText().toString().trim();if(!address.contains("://"))address="https://"+address;
            if(!BrowserLibrary.isWebUrl(address)||isHome(address)){url.setError("请输入有效的网站地址");return;}
            BrowserLibrary.editSite(this,site==null?null:site.optString("key"),name.getText().toString().trim(),address,fixed.isChecked(),false,error->{if(error!=null){Toast.makeText(this,error,Toast.LENGTH_LONG).show();return;}dialog.dismiss();refreshHome();});
        }));dialog.show();
    }
    private void showAddress(String url){SessionRepository.currentUrl=url;BrowserLibrary.list(this,true,"",0,(entries,error)->{bookmarked=entries!=null&&entries.stream().anyMatch(e->url.equals(e.url));});fullAddress=url;if(address.hasFocus())return;if(isHome(url)){address.setText("");return;}try{address.setText(new URI(url).getHost());}catch(Exception e){address.setText(url);}}
    private void navigate(String value){
        try{
            String url=BrowserAddress.resolve(value);
            search.record(value,url);
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();web.loadUrl(url);
        }catch(IllegalArgumentException invalid){Toast.makeText(this,invalid.getMessage(),Toast.LENGTH_SHORT).show();}
    }
    @Override protected View keyboardAccessory(){return search==null?null:search.accessory();}
    @Override protected void onKeyboardVisibility(boolean visible){if(search!=null)search.imeVisibility(visible);}
    protected String translationDisabled(){return web==null||isHome(web.getUrl())?"请先打开漫画网页":null;}
    protected void startTranslation(){startAuto();}
    private void startAuto(){
        startAuto(false);
    }
    private boolean startAuto(boolean requestedOnly){
        if(busy||web==null||isHome(web.getUrl()))return false;
        AppSettings settings=AppSettings.load(this);try{settings.validate();}catch(Exception e){settingsNeeded(e.getMessage());return false;}
        String url=web.getUrl();if(url==null||!(url.startsWith("https://")||url.startsWith("http://"))){status.setText("请先打开漫画章节网页");return false;}
        if(!TranslationTaskManager.begin(this,"browser",this::cancelWork)){Toast.makeText(this,"已有翻译任务，请先停止",Toast.LENGTH_SHORT).show();return false;}
        quietScans=0;sessionTitle=web.getTitle()==null?"网页漫画":web.getTitle();autoSettings=settings;autoPage=url;autoAgent=web.getSettings().getUserAgentString();
        Runtime runtime=Runtime.getRuntime();stages=new TranslationStages(settings.textConcurrency,AutoTranslationQueue.availableMemory(runtime.maxMemory(),0),()->AutoTranslationQueue.availableMemory(runtime.maxMemory(),runtime.totalMemory()-runtime.freeMemory()));
        queue.configureTextTarget("text".equals(settings.mode)?settings.textConcurrency:0);
        queue.requestedOnly(requestedOnly);
        retryOnScan.clear();retryScanRequested=!requestedOnly;
        if(!requestedOnly){queue.restartUnfinished();retryOnScan.addAll(failedPages);for(Map.Entry<String,PageOutcome> entry:pageOutcomes.entrySet())if(entry.getValue().incomplete())retryOnScan.add(entry.getKey());}
        final int token=begin("正在寻找漫画图片…");autoRunning=true;queue.resume(SystemClock.elapsedRealtime());autoError="";autoButton.setEnabled(true);autoButton.setText("停止翻译");
        work=submitBrowser(worker,()->{try{pageBridge.js(bridge,token);pageBridge.js("window.__mangaBrowserV1.toggleTranslations(true)",token);
            main.post(()->{if(valid(token)&&autoRunning){showTranslated=true;main.post(autoTick);}});
        }catch(Exception|OutOfMemoryError e){main.post(()->{if(valid(token)){cancelWork();status.setText("启动失败："+message(e));}});}});
        return true;
    }
    private void scanAuto(){
        if(!autoRunning||destroyed)return;
        if(stopIfPageChanged())return;
        final int token=generation;
        final boolean inspectIncomplete=retryScanRequested;retryScanRequested=false;
        work=submitBrowser(worker,()->{try{
            pageBridge.js(bridge,token);
            JSONObject found=new JSONObject((String)pageBridge.js("JSON.stringify(window.__mangaBrowserV1.scanAuto())",token));
            JSONArray list=found.getJSONArray("images");ArrayList<AutoTranslationQueue.Image> images=new ArrayList<>();Set<String> savedIncomplete=new HashSet<>();
            for(int i=0;i<list.length();i++){
                JSONObject item=list.getJSONObject(i);String id=item.optString("id"),url=item.optString("url");
                if(id.length()>0&&id.length()<100&&url.length()<=BrowserImageLoader.MAX_ENCODED_BYTES*2)
                    images.add(new AutoTranslationQueue.Image(id,url,item.optBoolean("visible"),item.optBoolean("ahead"),item.optBoolean("translated")&&"applied".equals(item.optString("state")),"pending".equals(item.optString("state")),i,item.optInt("width"),item.optInt("height"),"image".equals(autoSettings.mode),item.optBoolean("nearViewport")));
                if(inspectIncomplete){File saved=pages.file(autoPage,documentId,url,item.optInt("width"),item.optInt("height"),autoSettings);if(saved.isFile()&&pages.readOutcome(saved).incomplete())savedIncomplete.add(id+"\n"+url);}
            }
            main.post(()->{if(!valid(token)||!autoRunning)return;
                queue.scan(images);retryOnScan.addAll(savedIncomplete);
                for(AutoTranslationQueue.Image image:images)if(retryOnScan.remove(image.key)){if(queue.retry(image,false))requestBadge(image.id,image.url,"请求中 · 补试未完成部分");}
                imageWork.removeIf(Future::isDone);
                Runtime runtime=Runtime.getRuntime();long available=AutoTranslationQueue.availableMemory(runtime.maxMemory(),runtime.totalMemory()-runtime.freeMemory());
                List<AutoTranslationQueue.Image> admitted=queue.take(SystemClock.elapsedRealtime(),available,runtime.maxMemory());
                for(int at=0;at<admitted.size();at++){
                    AutoTranslationQueue.Image image=admitted.get(at);
                    if(image.retryMode!=0)requestBadge(image.id,image.url,"请求中");
                    final int queueToken=queue.epoch();final AtomicBoolean stop=cancelled;
                    final AppSettings settings=autoSettings;final String page=autoPage,agent=autoAgent,document=documentId;
                    final TranslationStages pipeline=stages;
                    try{imageWork.add(submitBrowser(translators,()->translateAuto(image,token,queueToken,stop,settings,page,agent,document,pipeline)));}
                    catch(RuntimeException|OutOfMemoryError e){
                        for(int unsent=at;unsent<admitted.size();unsent++)queue.defer(admitted.get(unsent),queueToken);
                        queue.backoff(SystemClock.elapsedRealtime(),true);autoError="可用资源不足，稍后自动继续";break;
                    }
                }
                autoStatus();
                if(queue.running()==0&&queue.waiting()==0){if(++quietScans>=3){autoRunning=false;finishTask(status.getText()+"\n本轮结束；点开始翻译可继续未完成页面，下拉可加载新页面。");return;}}else quietScans=0;
                main.postDelayed(autoTick,1000);
            });
        }catch(Exception|OutOfMemoryError e){main.post(()->{if(valid(token)&&autoRunning){autoError=message(e);queue.backoff(SystemClock.elapsedRealtime(),e instanceof OutOfMemoryError);autoStatus();main.postDelayed(autoTick,2500);}});}});
    }
    /** Read-only disk recovery also runs after Stop: it never starts a translation request. */
    private void recoverVisibleCache(){
        if(!foreground||busy||web==null||destroyed||!showTranslated||autoSettings==null||!samePage(autoPage,web.getUrl()))return;
        final int token=generation;final AppSettings settings=autoSettings;final String page=autoPage,document=documentId;
        work=submitBrowser(worker,()->{byte[] cached=null;TranslationStages.Lease pixels=null;String recoveryKey=null;long recoveryAt=-1;
            try{
                pageBridge.js(bridge,token);JSONObject found=new JSONObject((String)pageBridge.js("JSON.stringify(window.__mangaBrowserV1.scanAuto())",token));JSONArray images=found.getJSONArray("images");
                for(int i=0;i<images.length();i++){
                    check(token);JSONObject item=images.getJSONObject(i);
                    if(!item.optBoolean("nearViewport")||item.optBoolean("translated")||"pending".equals(item.optString("state")))continue;
                    String id=item.getString("id"),url=item.getString("url");File file=pages.file(page,document,url,item.optInt("width"),item.optInt("height"),settings);
                    if(!file.isFile()||recoveryAttempts.getOrDefault(id+"\n"+url,0)>=2)continue;
                    recoveryAttempts.merge(id+"\n"+url,1,Integer::sum);
                    recoveryKey=id+"\n"+url;recoveryAt=SystemClock.elapsedRealtime();
                    pixels=stages.pixels(AutoTranslationQueue.estimateMemory(item.optInt("width"),item.optInt("height")),()->!valid(token)||!foreground);
                    cached=pages.read(file,false);
                    if(cached!=null&&pageBridge.replace(id,url,cached,token)){
                        PageOutcome.Timings timing=PageOutcome.Timings.cache(SystemClock.elapsedRealtime()-recoveryAt,true);
                        PageOutcome restored=pages.readOutcome(file);recoveryAttempts.remove(id+"\n"+url);
                        main.post(()->{if(valid(token)){rememberOutcome(id+"\n"+url,restored,timing,"","");if(restored.incomplete())status.setText("已恢复译图；仍有未完成段落，可在菜单查看处理详情");}});
                    }else throw new IOException("缓存译图回填未完成");
                    break;
                }
            }catch(Exception|OutOfMemoryError ignored){/* A missing/stale cached page must never cause a paid retry. */
                if(recoveryAt>=0&&valid(token)&&foreground){String key=recoveryKey;PageOutcome.Timings timing=PageOutcome.Timings.cache(SystemClock.elapsedRealtime()-recoveryAt,false);main.post(()->{if(valid(token))rememberOutcome(key,null,timing,"缓存恢复未完成，保留当前网页。","");});}
            }
            finally{if(pixels!=null)pixels.close();main.post(()->{if(valid(token)&&foreground&&!busy)main.postDelayed(recoveryTick,1500);});}
        });
    }
    private void translateAuto(AutoTranslationQueue.Image item,int token,int queueToken,AtomicBoolean stop,AppSettings settings,String page,String agent,String document,TranslationStages pipeline){
        Bitmap source=null;byte[] cached=null;TranslationEngine.Result output=null;boolean success=false,noText=false,slowDown=false,storageFull=false,modelRequestsStarted=false,pageCacheReady=false;String error="";File reservedCache=null,sessionCache=null;
        TranslationStages.Lease pixels=null;TranslationEngine.PreparedText prepared=null;PageOutcome outcome=null;
        Future<?> preparationTask=null;AtomicBoolean stopPreparation=new AtomicBoolean();String contentKey=null,cacheKind="未命中",precomputeDetail="";boolean originalCacheHit=false,detectionCacheHit=false;
        int httpStatus=0;long started=SystemClock.elapsedRealtime(),loadMs=-1,detectMs=-1,prepareMs=-1,requestMs=-1;
        long responseAt=-1,cacheRestoreAt=-1,timingEnd=-1,queueMs=-1,renderMs=-1,pngMs=-1,saveMs=-1,replaceMs=-1;boolean applied=false;
        java.util.function.BooleanSupplier stopped=()->stop.get()||!valid(token);
        java.util.function.IntSupplier priority=()->queue.priority(item);
        try{
            check(token);JSONObject originalInfo=pageBridge.requireOriginal(item.id,item.url,token,"图片已变化，等待新图片");
            File cacheFile=pages.file(page,document,item.url,originalInfo.optInt("width"),originalInfo.optInt("height"),settings);sessionCache=cacheFile;
            if(item.retryMode==0&&cacheFile.isFile()){
                cacheRestoreAt=SystemClock.elapsedRealtime();
                pixels=pipeline.pixels(item.restorationBytes,stopped,priority);
                cached=pages.read(cacheFile,true);
            }
            if(cached==null&&pixels!=null){pixels.close();pixels=null;}
            if(cached==null&&item.cacheOnly){main.post(()->{if(valid(token)&&autoRunning){queue.cacheMiss(item,queueToken);autoStatus();}});return;}
            if(cached!=null){cacheKind="本次阅读译图";outcome=pages.readOutcome(cacheFile);if(!pageBridge.replace(item.id,item.url,cached,token))throw new Exception("译图回填未完成");timingEnd=SystemClock.elapsedRealtime();applied=true;success=true;}
            else{
                cacheRestoreAt=-1;
                pages.reserve(cacheFile);reservedCache=cacheFile;
                if(pixels==null)pixels=pipeline.pixels(item.memoryBytes,stopped,priority);
                long loadAt=SystemClock.elapsedRealtime();
                source=pageBridge.loadImage(item.id,item.url,page,agent,token,stopped);
                SessionRepository.original(this,page,sessionTitle,item.url,item.order,source);
                loadMs=SystemClock.elapsedRealtime()-loadAt;originalCacheHit=!item.url.startsWith("manga-canvas:")&&BrowserImageLoader.lastCacheHit();
                check(token);String sourceHash=DetectionCache.contentHash(source,stopped);contentKey=PagePipeline.contentKey(sourceHash,settings);
                RenderedPageCache.Entry previous=item.retryMode==0?new RenderedPageCache(getCacheDir()).read(contentKey):null;
                if(previous!=null){
                    cacheKind="跨次阅读译图（原图内容已核对）";cacheRestoreAt=SystemClock.elapsedRealtime();outcome=previous.outcome;
                    source.recycle();source=null;pages.save(cacheFile,previous.png,outcome);pageCacheReady=true;
                    pages.saveDraftKey(cacheFile,PageDraftStore.find(this,contentKey)!=null?contentKey:null);
                    if(!pageBridge.replace(item.id,item.url,previous.png,token))throw new Exception("译图回填未完成");
                    timingEnd=SystemClock.elapsedRealtime();applied=true;success=true;
                }else{
                long detectAt=SystemClock.elapsedRealtime();final Bitmap detectionSource=source;
                List<Region> regions=engine.detect(detectionSource,settings,sourceHash,item.retryMode!=0,stopped,pipeline,priority);detectMs=SystemClock.elapsedRealtime()-detectAt;detectionCacheHit=engine.lastDetectionCacheHit();check(token);
                if(regions.isEmpty()){outcome=PagePipeline.noText(settings,"本次未检测到文字；如原图有对白，可停止后手选检查。");noText=true;success=true;}
                else{
                    if("text".equals(settings.mode)){
                        long prepareAt=SystemClock.elapsedRealtime();
                        prepared=engine.prepareTextPage(source,regions,regions,settings,stopped,item.retryMode==2);
                        prepareMs=SystemClock.elapsedRealtime()-prepareAt;
                        source.recycle();source=null;pixels.close();pixels=null;
                        final TranslationEngine.PreparedText job=prepared;
                        if(!job.batches.isEmpty())preparationTask=preparation.submit(()->{
                            java.util.function.BooleanSupplier preparationCancelled=()->stopPreparation.get()||stopped.getAsBoolean();
                            try{engine.precomputeTextPage(job,preparationCancelled,pipeline,()->Math.max(100,priority.getAsInt()+100));}
                            catch(Exception|OutOfMemoryError optional){/* Rendering safely computes any missing region mask. */}
                        });
                        modelRequestsStarted=true;
                        long requestAt=SystemClock.elapsedRealtime();
                        try{pipeline.withRequests(()->{engine.requestTextPage(job,settings,stopped);if(job.throttleFailure!=null)throw job.throttleFailure;return null;},stopped,priority);}
                        catch(CancellationException|InterruptedException e){throw e;}
                        catch(Exception e){if(job.throttleFailure==null||job.values.isEmpty())throw e;error=message(e);httpStatus=PagePipeline.throttleStatus(e);slowDown=httpStatus!=0;}
                        finally{requestMs=SystemClock.elapsedRealtime()-requestAt;stopPreparation.set(true);if(preparationTask!=null)preparationTask.cancel(true);}
                        responseAt=SystemClock.elapsedRealtime();long phase=responseAt;
                        try{pixels=pipeline.pixels(prepared.renderMemoryBytes(item.restorationBytes),stopped,priority);}finally{queueMs=SystemClock.elapsedRealtime()-phase;}
                        phase=SystemClock.elapsedRealtime();try{output=engine.renderTextPage(prepared,stopped);}finally{renderMs=SystemClock.elapsedRealtime()-phase;}
                        precomputeDetail="；提前准备 "+job.precomputedRegions()+" 区，回填复用 "+job.cleanupCacheHits()+" 区，现场计算 "+job.cleanupCacheMisses()+" 区";
                    }else{modelRequestsStarted=true;output=engine.translate(source,regions,regions,settings,(m,n,t)->{},stopped,item.retryMode==2);}
                     check(token);outcome=PagePipeline.outcomeOf(output);
                     if(output.throttleFailure!=null){httpStatus=PagePipeline.throttleStatus(output.throttleFailure);slowDown=httpStatus!=0;error=message(output.throttleFailure);}
                     if(output.succeeded==0&&output.failed>0)throw new Exception(output.summary);
                    if(output.succeeded==0){noText=true;success=true;timingEnd=SystemClock.elapsedRealtime();}
                    else{byte[] png;long phase=SystemClock.elapsedRealtime();try{png=WebPageBridge.encodePng(output.image);}finally{pngMs=SystemClock.elapsedRealtime()-phase;}
                        phase=SystemClock.elapsedRealtime();try{pages.save(cacheFile,png,outcome);new RenderedPageCache(getCacheDir()).write(contentKey,png,outcome);}finally{saveMs=SystemClock.elapsedRealtime()-phase;}pageCacheReady=true;
                        phase=SystemClock.elapsedRealtime();try{if(!pageBridge.replace(item.id,item.url,png,token))throw new Exception("译图回填未完成，稍后重试");applied=true;}finally{long ended=SystemClock.elapsedRealtime();replaceMs=ended-phase;if(applied)timingEnd=ended;}
                        success=true;
                        // After the page is already visible: file the editable draft (renames only).
                        if(output.draft!=null){boolean kept=PageDraftStore.commit(this,output.draft,contentKey);output.draft=null;pages.saveDraftKey(cacheFile,kept?contentKey:null);}
                    }
                }
                }
            }
        }catch(CancellationException|InterruptedException ignored){return;}
        catch(PageCacheStore.StorageFullException e){if(!valid(token)||stop.get())return;timingEnd=SystemClock.elapsedRealtime();storageFull=true;error=e.getMessage();}
        catch(Exception|OutOfMemoryError e){if(!valid(token)||stop.get())return;timingEnd=SystemClock.elapsedRealtime();error=message(e);int status=PagePipeline.throttleStatus(e);if(status!=0)httpStatus=status;slowDown=e instanceof OutOfMemoryError||httpStatus!=0;}
        finally{if(sessionCache!=null&&sessionCache.isFile())try{SessionRepository.record(this,page,sessionTitle,item.url,item.order,sessionCache,pages.readDraftKey(sessionCache));}catch(Exception e){main.post(()->Toast.makeText(this,"会话保存失败："+e.getMessage(),1).show());}stopPreparation.set(true);if(preparationTask!=null)preparationTask.cancel(true);if(reservedCache!=null)pages.release(reservedCache);if(output!=null&&output.image!=source&&!output.image.isRecycled())output.image.recycle();if(output!=null)PageDraftStore.discard(output.draft);if(source!=null&&!source.isRecycled())source.recycle();if(pixels!=null)pixels.close();if(prepared!=null)prepared.close();}
        final boolean done=success,empty=noText,throttled=slowDown,diskFull=storageFull,allowPageRetry=!modelRequestsStarted||(item.retryMode==0&&pageCacheReady);final String failure=error;final PageOutcome pageOutcome=outcome;
        final PageOutcome.Timings timing=responseAt>=0?PageOutcome.Timings.text(queueMs,renderMs,pngMs,saveMs,replaceMs,Math.max(0,timingEnd-responseAt),applied):cacheRestoreAt>=0?PageOutcome.Timings.cache(Math.max(0,timingEnd-cacheRestoreAt),applied):null;
        final String unmeasured="image".equals(settings.mode)?"图像翻译模式：未记录本地文字回填分段耗时。":noText?"本次无需回填译图；未记录完整回填耗时。":"模型译文尚未就绪；未记录本地回填耗时。";
        final long queueDelay=modelRequestsStarted?ApiClient.retryWaitMillis(settings.retryIntervalSeconds,settings.rateLimitWaitSeconds,httpStatus,0):-1;
        final String performance="缓存："+cacheKind+"；原图复用="+originalCacheHit+"，检测复用="+detectionCacheHit+"；读取原图 "+loadMs+" ms；检测（含排队） "+detectMs+" ms；请求准备 "+prepareMs+" ms；翻译等待（含排队/重试） "+requestMs+" ms；本页总耗时 "+(SystemClock.elapsedRealtime()-started)+" ms"+precomputeDetail+"（-1 表示未执行）";
        main.post(()->{if(valid(token)&&autoRunning){rememberOutcome(item.key,pageOutcome,timing,failure,unmeasured,performance);long now=SystemClock.elapsedRealtime();if(diskFull){queue.defer(item,queueToken);queue.blockNewTranslations();}else queue.finish(item,queueToken,done,empty,now,allowPageRetry);if(item.retryMode!=0)requestBadge(item.id,item.url,"");if(!failure.isEmpty()){autoError=failure;if(!diskFull){if(queueDelay>=0)queue.backoff(now,throttled,queueDelay);else queue.backoff(now,throttled);}}autoStatus();}});
    }
    private void autoStatus(){main.post(()->TranslationTaskManager.progress("browser",status.getText().toString()));
        int incomplete=0;for(PageOutcome outcome:pageOutcomes.values())if(outcome.incomplete())incomplete++;
        status.setText("已处理 "+queue.completed()+" 张 · 部分未完成 "+incomplete+" 张 · 请求中 "+(stages==null?0:stages.networkActive())+" 张 · 待翻译 "+queue.waiting()+" 张 · 处理中 "+queue.running()+" 张\n"
            +"前方就绪 "+queue.readyAhead()+" 张 · "+(queue.waitingForStorage()?"可用存储不足，暂缓新图":queue.waitingForMemory()?"手机可用内存不足，暂缓新图":queue.running()==0?(queue.waiting()>0?"稍候自动继续":"等待新图加载") : "继续向下阅读即可")
            +(incomplete>0?"\n仍有段落未回填，菜单「重试未完成部分」可补发，或查看处理详情和日志":"")
            +(queue.exhausted()>0?" · "+queue.exhausted()+" 张已停止自动重试":"")+(queue.oversized()>0?"\n"+queue.oversized()+" 张图片过大，请缩小图片或改用文字模式":autoError.isEmpty()?"":"\n"+autoError));
    }
    private void rememberOutcome(String key,PageOutcome outcome,PageOutcome.Timings timing,String failure,String unmeasured){
        rememberOutcome(key,outcome,timing,failure,unmeasured,"");
    }
    private void rememberOutcome(String key,PageOutcome outcome,PageOutcome.Timings timing,String failure,String unmeasured,String performance){
        if(outcome!=null)pageOutcomes.put(key,outcome);
        if(failure!=null&&!failure.isEmpty())failedPages.add(key);else if(outcome!=null)failedPages.remove(key);
        String detail=outcome==null?"本次处理未完成。":outcome.describe();
        if(failure!=null&&!failure.isEmpty())detail+="\n本次未完成："+failure;
        detail+="\n"+(timing==null?unmeasured:timing.describe());
        if(!performance.isEmpty())detail+="\n"+performance;
        PageOutcome.rememberRecent(recentPageDetails,key,detail);
        for(Map.Entry<String,PageOutcome> entry:pageOutcomes.entrySet()){
            PageOutcome old=entry.getValue();
            if(!recentPageDetails.containsKey(entry.getKey())&&!old.transcript.rows.isEmpty())entry.setValue(new PageOutcome(old.detected,old.succeeded,old.failed,old.skipped,old.preservedOriginal,old.detail,TranslationTranscript.unavailable(),old.needsCleanupRetry));
        }
        new TranslationLog(new File(getFilesDir(),"diagnostics")).record(Integer.toHexString(key.hashCode()),"","page_result",detail,AppSettings.load(this));
    }
    private void showOutcomes(){
        StringBuilder detail=new StringBuilder("最近处理记录（最新在前，最多20张，按完成顺序）\n编号不是漫画页码；计时仅保留在本次阅读内存中。\n\n");int count=0;
        ArrayList<String> records=new ArrayList<>(recentPageDetails.values());Collections.reverse(records);
        for(String record:records)detail.append("处理记录 ").append(++count).append("\n").append(record).append("\n\n");
        if(count==0)detail.append("暂无处理记录。\n");
        detail.append("耗时不表示所有文字都已识别或回填；请结合每条的段落统计。菜单可重试未完成部分，或导出日志。原笔画残留、未检出的文字不保证能靠重传修复。");
        TextView text=new TextView(this);text.setText(detail.toString());text.setTextIsSelectable(true);text.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(text);new AlertDialog.Builder(this).setTitle("处理详情").setView(scroll).setPositiveButton("关闭",null).setNeutralButton("原文 / 译文",(d,w)->showTranscripts()).show();
    }
    private void showTranscripts(){
        ArrayList<String> keys=new ArrayList<>(recentPageDetails.keySet());Collections.reverse(keys);
        if(keys.isEmpty()){TranscriptDialog.show(this,TranslationTranscript.unavailable());return;}
        String[] labels=new String[keys.size()];
        for(int i=0;i<keys.size();i++){PageOutcome row=pageOutcomes.get(keys.get(i));labels[i]="处理记录 "+(i+1)+(i==0?"（最新）":"")+(row==null?" · 未完成":" · "+row.detected+"段 / 回填"+row.succeeded+"段");}
        new AlertDialog.Builder(this).setTitle("选择图片 · 最近20张处理记录").setItems(labels,(d,index)->{
            PageOutcome row=pageOutcomes.get(keys.get(index));TranscriptDialog.show(this,row==null?TranslationTranscript.unavailable():row.transcript);
        }).setNegativeButton("关闭",null).show();
    }
    private void retryIncomplete(){
        if(busy&&!autoRunning)return;
        Set<String> keys=new HashSet<>(failedPages);
        for(Map.Entry<String,PageOutcome> entry:pageOutcomes.entrySet()){
            PageOutcome o=entry.getValue();if(o.incomplete())keys.add(entry.getKey());
        }
        if(keys.isEmpty()){status.setText("没有记录到可重传的失败/跳过段落；漏检或仅叠字请用手选图片检查");return;}
        if(!autoRunning&&!startAuto(true))return;
        retryOnScan.addAll(keys);quietScans=0;
        Toast.makeText(this,"未完成页面已等待重新入队，已有任务继续处理",Toast.LENGTH_SHORT).show();
    }
    private void selectPressedPage(){
        if(selectingPage||web==null)return;
        if(busy&&!autoRunning){Toast.makeText(this,"正在处理手选任务，请完成或停止后再选",Toast.LENGTH_SHORT).show();return;}
        selectingPage=true;
        final String document=documentId;
        final float x=touchX/Math.max(1,web.getWidth()),y=touchY/Math.max(1,web.getHeight());
        web.evaluateJavascript(bridge,ignored->{
            if(destroyed||web==null||!Objects.equals(document,documentId)){selectingPage=false;return;}
            web.evaluateJavascript("JSON.stringify(window.__mangaBrowserV1.selectAt("+x+","+y+"))",encoded->{
                selectingPage=false;if(destroyed||web==null||!Objects.equals(document,documentId))return;
                try{
                    Object value=new JSONTokener(encoded).nextValue();
                    if(!(value instanceof String))throw new Exception();
                    JSONObject item=new JSONObject((String)value);
                    if(item.optString("id").isEmpty()||item.optString("id").length()>100||item.optString("url").isEmpty()
                        ||item.optString("url").length()>BrowserImageLoader.MAX_ENCODED_BYTES*2)throw new Exception();
                    PageOutcome known=pageOutcomes.get(item.optString("id")+"\n"+item.optString("url"));
                    AlertDialog.Builder selectedDialog=new AlertDialog.Builder(this).setTitle("已选中这一页漫画")
                        .setMessage(item.optInt("width")+" × "+item.optInt("height")+" 像素\n重新翻译会将这一页加入当前请求队列，跳过旧译文缓存；其他页面继续处理。补试复用已有有效译文，只请求缺失部分。\n已在请求中的页面不会重复提交。")
                        .setPositiveButton("重新翻译这一页",(d,w)->retryPressedPage(item,document,true))
                        .setNeutralButton("仅补试缺失部分",(d,w)->retryPressedPage(item,document,false));
                    if(known!=null)selectedDialog.setNegativeButton("原文 / 译文",(d,w)->TranscriptDialog.show(this,known.transcript));
                    else selectedDialog.setNegativeButton("取消",null);
                    selectedDialog.show();
                }catch(Exception invalid){Toast.makeText(this,"此处未找到可处理的漫画图片；受限画布或跨域阅读器可用菜单中的翻译当前画面",Toast.LENGTH_LONG).show();}
            });
        });
    }
    private void retryPressedPage(JSONObject item,String document,boolean forceFresh){
        if(web==null||!Objects.equals(document,documentId)){status.setText("网页已变化，请重新长按选择");return;}
        if(busy&&!autoRunning){status.setText("请先完成或停止当前手选任务");return;}
        if(!autoRunning&&!startAuto(true))return;
        AutoTranslationQueue.Image selected=new AutoTranslationQueue.Image(item.optString("id"),item.optString("url"),true,false,false,false,0,item.optInt("width"),item.optInt("height"),"image".equals(autoSettings.mode),true);
        boolean added=queue.retry(selected,forceFresh);quietScans=0;
        if(added)requestBadge(selected.id,selected.url,"请求中 · 等待队列");
        Toast.makeText(this,added?"本页已重新进入请求队列，其他页面继续处理":"本页已在请求队列中",Toast.LENGTH_SHORT).show();
    }
    private void requestBadge(String id,String url,String label){if(web!=null)web.evaluateJavascript("window.__mangaBrowserV1&&window.__mangaBrowserV1.setRequestState&&window.__mangaBrowserV1.setRequestState("+JSONObject.quote(id)+","+JSONObject.quote(url)+","+JSONObject.quote(label)+")",null);}
    /**
     * 汉化与导出本章: lists this page's images in document order and collects the ones translated during this
     * reading (editable when a draft exists, otherwise as finished images). Runs without stopping translation.
     */
    private void exportChapter(){
        if(web==null||isHome(web.getUrl())){Toast.makeText(this,"请先打开并翻译漫画章节",Toast.LENGTH_SHORT).show();return;}
        final String page=web.getUrl(),document=documentId,title=web.getTitle()==null||web.getTitle().trim().isEmpty()?"网页漫画":web.getTitle().trim();
        final AppSettings settings=autoSettings!=null?autoSettings:AppSettings.load(this);
        final int token=generation;
        status.setText("正在收集本章已翻译的图片…");
        submitBrowser(worker,()->{try{
            pageBridge.js(bridge,token);JSONObject found=new JSONObject((String)pageBridge.js("JSON.stringify(window.__mangaBrowserV1.scan())",token));
            JSONArray images=found.getJSONArray("images");List<ProjectStore.PageSpec> specs=new ArrayList<>();int untranslated=0,number=0;
            for(int i=0;i<images.length();i++){
                JSONObject item=images.getJSONObject(i);String id=item.optString("id"),url=item.optString("url");
                if(id.isEmpty()||url.isEmpty())continue;
                File file=pages.file(page,document,url,item.optInt("width"),item.optInt("height"),settings);
                if(!file.isFile()){JSONObject original=pageBridge.original(id,token);if(original!=null)file=pages.file(page,document,url,original.optInt("width"),original.optInt("height"),settings);}
                if(!file.isFile()){if(item.optInt("width")>=180&&item.optInt("height")>=180)untranslated++;continue;}
                final File cached=file;number++;
                specs.add(new ProjectStore.PageSpec("第 "+number+" 张",pages.readDraftKey(cached),()->new FileInputStream(cached),"png",false));
            }
            String note=untranslated>0?untranslated+" 张图片本次阅读中尚未翻译，未加入工程":"";
            ProjectLauncher.Gathered gathered=new ProjectLauncher.Gathered(specs,note);
            main.post(()->{if(destroyed)return;
                if(specs.isEmpty()){status.setText("本章还没有已翻译的图片：先点「开始翻译」并向下阅读，再汉化与导出");return;}
                status.setText("找到 "+specs.size()+" 张已翻译图片"+(note.isEmpty()?"":"；"+note));
                ProjectLauncher.launch(this,title,"web","web:"+page.split("#",2)[0],()->gathered,null);});
        }catch(Exception|OutOfMemoryError e){main.post(()->{if(!destroyed)status.setText("无法收集本章图片："+message(e));});}});
    }
    private void scanImages(){
        if(busy)return;int token=begin("正在读取网页中已加载的图片…");
        work=submitBrowser(worker,()->{try{
            pageBridge.js(bridge,token);JSONObject found=new JSONObject((String)pageBridge.js("JSON.stringify(window.__mangaBrowserV1.scan())",token));
            JSONArray array=found.getJSONArray("images");ArrayList<JSONObject> items=new ArrayList<>();
            for(int i=0;i<Math.min(200,array.length());i++){JSONObject item=array.getJSONObject(i);if(item.optString("id").length()<100&&item.optString("url").length()<=BrowserImageLoader.MAX_ENCODED_BYTES*2)items.add(item);}
            main.post(()->{if(valid(token)){finishTask("找到 "+items.size()+" 张已加载图片");chooseImages(items,found,token);}});
        }catch(Exception|OutOfMemoryError error){fail(token,error);}});
    }
    private void chooseImages(List<JSONObject> items,JSONObject found,int scanToken){
        if(items.isEmpty()){new AlertDialog.Builder(this).setMessage("当前没有可提取的已加载图片。先稍微滚动页面，等待漫画图片出现后再试；画布或跨域嵌入阅读器可用「菜单 → 翻译当前画面」。").setPositiveButton("知道了",null).show();return;}
        String[] labels=new String[items.size()];boolean[] selected=new boolean[items.size()];
        for(int i=0;i<items.size();i++){JSONObject x=items.get(i);int w=x.optInt("width"),h=x.optInt("height");labels[i]=(i+1)+" · "+w+"×"+h+" · "+shortLabel(x);selected[i]=w>=180&&h>=180;}
        String title="已加载图片（"+items.size()+"）";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(labels,selected,(d,i,value)->selected[i]=value)
            .setPositiveButton("翻译选中",(d,w)->{
                if(!valid(scanToken)){status.setText("网页已变化，请重新扫描");return;}
                ArrayList<JSONObject> chosen=new ArrayList<>();for(int i=0;i<items.size();i++)if(selected[i])chosen.add(items.get(i));
                if(chosen.isEmpty()){status.setText("没有选择图片");return;}translateImages(chosen);
            }).setNeutralButton("全选",null).setNegativeButton("关闭",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{boolean all=true;for(boolean b:selected)all&=b;for(int i=0;i<selected.length;i++){selected[i]=!all;dialog.getListView().setItemChecked(i,!all);}}));dialog.show();
        status.setText("选择漫画图片后翻译；会调用当前配置的接口。"+(found.optBoolean("truncated")?"本次最多列出 200 张。":"")+(found.optInt("inaccessibleFrames")>0?"跨域嵌入内容请用当前画面。":""));
    }
    private String shortLabel(JSONObject item){String alt=item.optString("alt").trim();if(!alt.isEmpty())return alt.length()>24?alt.substring(0,24):alt;try{return new URI(item.getString("url")).getHost()==null?"页面图片":new URI(item.getString("url")).getHost();}catch(Exception ignored){return "页面图片";}}
    private void translateImages(List<JSONObject> items){
        translateImages(items,false);
    }
    private void translateImages(List<JSONObject> items,boolean forceFresh){
        if(!TranslationTaskManager.begin(this,"browser",this::cancelWork)){Toast.makeText(this,"请先停止当前任务",0).show();return;}
        final AppSettings settings=AppSettings.load(this);try{settings.validate();}catch(Exception e){settingsNeeded(e.getMessage());return;}
        final String pageUrl=web.getUrl(),agent=web.getSettings().getUserAgentString(),document=documentId;final int token=begin("准备翻译 "+items.size()+" 张已加载图片…");final AtomicBoolean stop=cancelled;
        work=submitBrowser(worker,()->{
            int done=0,failed=0,blocks=0,partial=0;String firstError="";
            for(JSONObject item:items){
                Bitmap source=null;PagePipeline.Page result=null;PageOutcome manualOutcome=null;String detailKey=null;
                java.util.function.BooleanSupplier stopped=()->stop.get()||!valid(token);
                try{
                    check(token);String id=item.getString("id"),url=item.getString("url");
                    detailKey=id+"\n"+url;
                    pageBridge.requireOriginal(id,url,token,"图片已随网页变化，跳过旧图片");
                    int current=done+failed+1;String prefix="图片 "+current+"/"+items.size();
                    report(token,prefix+"：读取原图…",done+failed,items.size());
                    source=pageBridge.loadImage(id,url,pageUrl,agent,token,stopped);
                    SessionRepository.original(this,pageUrl,sessionTitle,url,items.indexOf(item),source);
                    // Manual selection always re-runs detection (it is how users retry), so no rendered-page reuse here.
                    result=PagePipeline.run(this,engine,source,settings,false,forceFresh,stopped,(message,n,total)->report(token,prefix+" · "+message,n,total),"");
                    check(token);
                    if(result.noText)throw new Exception("本页未检测到文字；可更换检测模型后重试，当前图片保留");
                    manualOutcome=result.outcome;if(!result.rendered())throw new Exception(result.summary);
                    byte[] png=WebPageBridge.encodePng(result.image);
                    check(token);File cacheFile=pages.file(pageUrl,document,url,item.optInt("width"),item.optInt("height"),settings);
                    pages.reserve(cacheFile);try{pages.save(cacheFile,png,manualOutcome);}finally{pages.release(cacheFile);}
                    PagePipeline.remember(this,result,png);
                    pages.saveDraftKey(cacheFile,result.keepDraft(this)?result.key:null);SessionRepository.record(this,pageUrl,sessionTitle,url,items.indexOf(item),cacheFile,pages.readDraftKey(cacheFile));
                    if(!pageBridge.replace(id,url,png,token))throw new Exception("网页拒绝译图或图片已变化，已保留原图");
                    PageOutcome recorded=manualOutcome;main.post(()->{if(valid(token))rememberOutcome(id+"\n"+url,recorded,null,"","手选图片模式：未记录分段耗时。");});
                    done++;blocks+=result.succeeded;if(manualOutcome.incomplete())partial++;
                }catch(CancellationException|InterruptedException cancelledTask){return;}
                catch(Exception|OutOfMemoryError error){if(!valid(token)||stop.get())return;failed++;if(firstError.isEmpty())firstError=message(error);
                    if(detailKey!=null){String key=detailKey,reason=message(error);PageOutcome recorded=manualOutcome;main.post(()->{if(valid(token))rememberOutcome(key,recorded,null,reason,"手选图片模式：未记录分段耗时。");});}
                }
                finally{if(result!=null)result.close();if(source!=null&&!source.isRecycled())source.recycle();}
            }
            String summary="处理完成："+done+" 张，回填 "+blocks+" 个段落；失败 "+failed+" 张"+(partial>0?"，部分段落失败 "+partial+" 张":"")+(firstError.isEmpty()?"":"。"+firstError);
            main.post(()->{if(valid(token))finishTask(summary);});
        });
    }
    private void toggleImages(){
        if(busy)return;final int token=begin("正在切换原图/译图…");final boolean show=!showTranslated;
        work=submitBrowser(worker,()->{try{pageBridge.js(bridge,token);pageBridge.js("window.__mangaBrowserV1.toggleTranslations("+show+")",token);main.post(()->{if(valid(token)){showTranslated=show;finishTask(show?"已显示网页译图":"已显示原图，随时可切回译图");}});}catch(Exception|OutOfMemoryError e){fail(token,e);}});
    }
    private void snapshot(){
        if(TranslationTaskManager.running()){Toast.makeText(this,"请先停止当前任务",0).show();return;}
        if(busy)return;AppSettings settings=AppSettings.load(this);try{settings.validate();}catch(Exception e){settingsNeeded(e.getMessage());return;}
        if(web.getWidth()<1||web.getHeight()<1)return;
        final Bitmap screen;
        try{float scale=Math.min(1f,2400f/Math.max(web.getWidth(),web.getHeight()));screen=Bitmap.createBitmap(Math.max(1,(int)(web.getWidth()*scale)),Math.max(1,(int)(web.getHeight()*scale)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(screen);canvas.scale(scale,scale);web.draw(canvas);}
        catch(RuntimeException|OutOfMemoryError e){status.setText("当前网页画面过大或暂不能捕获，请稍后重试");return;}
        if(!TranslationTaskManager.begin(this,"browser",this::cancelWork)){screen.recycle();return;}int token=begin("正在翻译当前网页画面…");AtomicBoolean stop=cancelled;
        work=submitBrowser(worker,()->{try{
            List<Region> regions=engine.detect(screen,settings);TranslationEngine.Result out=engine.translate(screen,regions,settings,(m,n,total)->report(token,m,n,total),()->stop.get()||!valid(token));
            PageDraftStore.discard(out.draft);out.draft=null; // screen captures are not workbench pages
            main.post(()->{if(!valid(token)){out.image.recycle();screen.recycle();return;}finishTask(out.summary);ResultView preview=new ResultView(this);preview.setBitmap(out.image);preview.setShowBoxes(false);preview.setEditable(false);boolean[] original={false};
                LinearLayout resultPanel=new LinearLayout(this);resultPanel.setOrientation(LinearLayout.VERTICAL);resultPanel.addView(preview,new LinearLayout.LayoutParams(-1,dp(380)));
                Button transcriptButton=new Button(this);transcriptButton.setText("识读原文 / 译文");transcriptButton.setOnClickListener(v->TranscriptDialog.show(this,out.transcript));resultPanel.addView(transcriptButton);
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("当前画面翻译").setView(resultPanel).setPositiveButton("关闭",null).setNeutralButton("原图/译图",null).setNegativeButton("保存译图",null).create();
                dialog.setOnShowListener(d->{dialog.getButton(-3).setOnClickListener(v->{original[0]=!original[0];preview.setBitmap(original[0]?screen:out.image);});dialog.getButton(-2).setOnClickListener(v->submitBrowser(worker,()->{try{Storage.save(this,out.image,"漫画网页译图");main.post(()->Toast.makeText(this,"已保存到相册",Toast.LENGTH_SHORT).show());}catch(Exception e){main.post(()->Toast.makeText(this,"保存失败",Toast.LENGTH_SHORT).show());}}));});dialog.show();
            });
        }catch(Exception|OutOfMemoryError e){fail(token,e);}});
    }
    /** Main-thread check also catches SPA history changes without a page-load callback. */
    private boolean stopIfPageChanged(){
        if(!autoRunning)return false;
        String current=web==null?null:web.getUrl();
        if(current!=null&&current.split("#",2)[0].equals(autoPage.split("#",2)[0]))return false;
        cancelWork();queue.clear();pageOutcomes.clear();failedPages.clear();recentPageDetails.clear();documentId=UUID.randomUUID().toString();showTranslated=true;
        status.setText("网页地址已变化，请在新章节点击开始翻译");return true;
    }
    private Future<?> submitBrowser(ExecutorService executor,Runnable job){final int token=generation;return executor.submit(()->{
        if(!CacheStorage.beginUse()){main.post(()->{if(valid(token)&&busy)finishTask("缓存清理中，请稍后重试");});return;}
        activeWorkers.incrementAndGet();try{if(valid(token))job.run();}finally{activeWorkers.decrementAndGet();CacheStorage.endUse();main.post(this::settleTask);}
    });}
    private void settleTask(){if(settlingSummary!=null&&activeWorkers.get()==0){String summary=settlingSummary;settlingSummary=null;TranslationTaskManager.done("browser",summary);}}
    private int begin(String text){settlingSummary=null;main.removeCallbacks(recoveryTick);cancelled=new AtomicBoolean();busy=true;int token=++generation;status.setText(text);Ui.reveal(progress,true,View.INVISIBLE);progress.setIndeterminate(true);autoButton.setText("取消任务");autoButton.setEnabled(true);paintAuto(true);return token;}
    private void finishTask(String text){settlingSummary=text;settleTask();busy=false;status.setText(text);Ui.reveal(progress,false,View.INVISIBLE);cancelButton.setVisibility(View.GONE);autoButton.setEnabled(true);autoButton.setText("开始翻译");paintAuto(false);main.removeCallbacks(recoveryTick);if(foreground)main.postDelayed(recoveryTick,1500);}
    /** Start/stop pill: blue when idle, softly breathing red while a task can be stopped. */
    private void paintAuto(boolean running){
        if(autoButton==null||autoBackground==null)return;
        if(autoPulse!=null){autoPulse.cancel();autoPulse=null;}
        autoBackground.setAlpha(255);
        Ui.tint(autoButton,autoBackground,running?0xffFCE8E6:0xffD3E3FD);
        autoButton.setTextColor(running?0xffB3261E:0xff185ABC);
        if(!running||destroyed||!Ui.motion())return;
        autoPulse=android.animation.ValueAnimator.ofInt(255,175);autoPulse.setDuration(900);autoPulse.setStartDelay(300);
        autoPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);autoPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);autoPulse.setInterpolator(Ui.STANDARD);
        final android.graphics.drawable.GradientDrawable shape=autoBackground;autoPulse.addUpdateListener(a->shape.setAlpha((Integer)a.getAnimatedValue()));autoPulse.start();
    }
    private android.graphics.drawable.Drawable addressBackground(){
        android.graphics.drawable.GradientDrawable idle=rounded(0xffE8EEF8,28),focused=rounded(0xffFFFFFF,28);focused.setStroke(dp(2),0xff4285F4);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.setEnterFadeDuration(180);states.setExitFadeDuration(180);
        states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},idle);return states;
    }
    private void cancelWork(){TranslationTaskManager.stopping("browser");
        retryOnScan.clear();retryScanRequested=false;
        if(web!=null)web.evaluateJavascript("window.__mangaBrowserV1&&window.__mangaBrowserV1.clearRequestStates&&window.__mangaBrowserV1.clearRequestStates()",null);
        resumeAuto=false;
        cancelled.set(true);generation++;autoRunning=false;main.removeCallbacks(autoTick);main.removeCallbacks(recoveryTick);
        if(pageBridge!=null)pageBridge.cancelPendingReplacements();
        queue.stop();for(Future<?> task:imageWork)task.cancel(true);imageWork.clear();if(work!=null)work.cancel(true);
        if(status!=null)finishTask("已停止；译图保留。已发出的请求可能仍计费，点开始翻译可继续");
    }
    private boolean valid(int token){return !destroyed&&token==generation;}
    private void check(int token){if(!valid(token)||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private void report(int token,String text,int done,int total){main.post(()->{if(valid(token)){TranslationTaskManager.progress("browser",text);status.setText(text);progress.setIndeterminate(total<=0);if(total>0){progress.setMax(total);progress.setProgress(done,true);}}});}
    private void fail(int token,Throwable error){main.post(()->{if(valid(token))finishTask("操作未完成："+message(error));});}
    private String message(Throwable e){if(e instanceof OutOfMemoryError)return "图片过大，手机内存不足，请减少选图或使用当前画面";String m=e.getMessage();if(m==null)m="请重试";String key=AppSettings.load(this).apiKey;if(!key.isEmpty())m=m.replace(key,"[隐藏]");return m.length()>180?m.substring(0,180):m;}
    private void settingsNeeded(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("设置接口",(d,w)->startActivity(new Intent(this,SettingsActivity.class))).setNegativeButton("继续浏览",null).show();}
    private Button button(String title,LinearLayout row,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(14);b.setTextColor(0xff53647B);b.setAllCaps(false);android.util.TypedValue ripple=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,ripple,true);b.setBackgroundResource(ripple.resourceId);b.setBackgroundTintList(null);b.setPadding(dp(4),0,dp(4),0);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));Ui.pressable(b);row.addView(b,new LinearLayout.LayoutParams(dp(48),dp(48)));b.setOnClickListener(v->action.run());return b;}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private long lastBack;
    @Override public void onBackPressed(){if(search!=null&&search.close())return;if(web!=null&&web.canGoBack())web.goBack();else if(web!=null&&!isHome(web.getUrl()))navigate(BrowserAddress.HOME);else if(SystemClock.elapsedRealtime()-lastBack<2000)moveTaskToBack(true);else{lastBack=SystemClock.elapsedRealtime();Toast.makeText(this,"再按一次退出",0).show();}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.hasExtra("url")){String url=intent.getStringExtra("url");if(BrowserLibrary.isWebUrl(url))navigate(url);}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(web!=null)web.saveState(out);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQUEST_LIBRARY&&result==RESULT_OK&&data!=null){
            String url=data.getStringExtra("url");
            if(BrowserLibrary.isWebUrl(url)&&web!=null){resumeAuto=false;cancelWork();navigate(url);}
            else status.setText("该记录不是有效的网页地址");
        }
    }
    private String outputConfig(AppSettings s){return s.renderFingerprint();}
    private boolean samePage(String a,String b){return a!=null&&b!=null&&a.split("#",2)[0].equals(b.split("#",2)[0]);}
    @Override protected void onPause(){
        foreground=false;main.removeCallbacks(recoveryTick);
        super.onPause();CookieManager.getInstance().flush();
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;recoveryAttempts.clear();if(web!=null)web.onResume();
        AppSettings resumedSettings=AppSettings.load(this);
        if(selectedDetector!=null&&!selectedDetector.equals(resumedSettings.detectorModel)){
            cancelWork();resumeAuto=false;queue.clear();pageOutcomes.clear();failedPages.clear();recentPageDetails.clear();autoSettings=null;
            if(web!=null)web.evaluateJavascript("if(window.__mangaBrowserV1)window.__mangaBrowserV1.restoreAll()",null);
            showTranslated=true;status.setText("本地检测模型已改变，已恢复原图；点开始翻译使用新模型。");
        }
        selectedDetector=resumedSettings.detectorModel;
        if(web!=null&&isHome(web.getUrl()))refreshHome();
        if(resumeAuto){resumeAuto=false;
            if(web!=null&&samePage(pausedPage,web.getUrl())){
                AppSettings current=AppSettings.load(this);
                if(Objects.equals(pausedConfig,outputConfig(current)))startAuto();
                else status.setText("翻译设置已变化，原译图保留；确认后点开始翻译继续");
            }
        }
        if(!busy){main.removeCallbacks(recoveryTick);main.post(recoveryTick);}
    }
    @Override protected void onDestroy(){cancelWork();destroyed=true;worker.shutdownNow();translators.shutdownNow();preparation.shutdownNow();if(engine!=null)new Thread(engine::close,"manga-detector-close").start();if(web!=null){web.stopLoading();web.setWebChromeClient(null);web.setWebViewClient(new WebViewClient());((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();}
}

