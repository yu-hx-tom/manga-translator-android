package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate settings screen; returning never recreates the browser page. */
public final class SettingsActivity extends ShellActivity {
    @Override protected boolean showsNavigation(){return false;}
    private Runnable afterSave;
    private EditText baseUrl,apiKey,textModel,imageModel,textPrompt,imagePrompt,concurrency,requestTimeout,retryInterval,rateLimitWait;
    private Spinner mode,reasoning,tier,retries,detector,textChoices,imageChoices,presetChoices;
    private LinearLayout textModelSection,imageModelSection;
    private final List<ApiPresets.Preset> presets=new ArrayList<>();
    private boolean applyingPreset;
    private final List<String> availableModels=new ArrayList<>();
    private boolean loadingModels;
    private TextView status;
    private Button cancel,saveButton;private ImageButton back;
    private TextView modeNote,storageInfo,advancedSummary;
    private LinearLayout reasoningSection,advancedBody;
    /** Widget values when last saved/loaded; differing values light up 保存并使用. */
    private String baseline="";
    private boolean dirty,finishAfterSave;
    private ProgressBar busyBar;
    private final List<View> inputs=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AtomicBoolean stopped=new AtomicBoolean();
    private Future<?> work;
    private int generation;
    private boolean busy;
    private boolean saving;
    private android.window.OnBackInvokedCallback systemBack;
    private final String[] efforts={"low","medium","high","xhigh","minimal","none","omit"};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);AppSettings settings=AppSettings.load(this);
        if(Build.VERSION.SDK_INT>=33){systemBack=this::onBackPressed;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(Ui.BG);
        Ui.insets(shell,0,0,0,0,true);
        back=Icons.iconButton(this,R.drawable.ic_arrow_back,"返回",v->onBackPressed());shell.addView(Ui.appBar(this,back,"设置",null));
        ScrollView scroll=new ScrollView(this);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(2),dp(16),dp(24));scroll.addView(page);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // Sticky footer: status + the single 保存并使用 (highlighted while there are unsaved changes).
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(16),dp(10),dp(16),dp(10));
        android.graphics.drawable.GradientDrawable footerBg=new android.graphics.drawable.GradientDrawable();footerBg.setColor(Ui.SURFACE);footer.setBackground(footerBg);footer.setElevation(dp(6));Ui.smoothLayout(footer);
        status=new Ui.StatusText(this);status.setTextSize(13);status.setTextColor(Ui.ICON);status.setLineSpacing(dp(2),1f);
        status.setText(settings.keyUnavailable?"原有Key无法读取，请重新填写。":"未改配置可直接返回；修改后点「保存并使用」。");status.setTextIsSelectable(true);footer.addView(status);
        busyBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);busyBar.setIndeterminate(true);busyBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));busyBar.setVisibility(View.GONE);footer.addView(busyBar,new LinearLayout.LayoutParams(-1,dp(4)));
        LinearLayout footerActions=new LinearLayout(this);Ui.smoothLayout(footerActions);
        saveButton=Ui.button(this,"保存并使用",Ui.TONAL,v->runAction(0));footerActions.addView(saveButton,new LinearLayout.LayoutParams(0,-2,1));
        cancel=new Button(this);cancel.setText("取消请求");Ui.style(cancel,Ui.DANGER_TONAL);cancel.setVisibility(View.GONE);cancel.setOnClickListener(v->{stopped.set(true);generation++;loadingModels=false;if(work!=null)work.cancel(true);setBusy(false);status.setText("已取消；已发送的测试请求可能仍会计费。");});
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(0,-2,1);cancelParams.leftMargin=dp(8);footerActions.addView(cancel,cancelParams);
        footer.addView(footerActions,Ui.margins(this,0,8,0,0));
        shell.addView(footer,new LinearLayout.LayoutParams(-1,-2));setContentView(shell);
        if(getIntent().getBooleanExtra("openCache",false))getWindow().getDecorView().post(this::clearDrafts);
        label(page,"常用设置在前，高级参数默认收起。修改后点底部「保存并使用」；返回时保留正在阅读的章节。",13);

        LinearLayout connection=section(page,R.drawable.ic_link,"连接接口",8);
        baseUrl=field(connection,"API 地址",settings.baseUrl,false,false);baseUrl.setHint("https://example.com/v1");
        apiKey=field(connection,"API Key",settings.apiKey,true,false);label(connection,"Key 只保存在本机，并用系统密钥库加密。",12);
        LinearLayout connectionActions=new LinearLayout(this);
        Button testButton=Ui.button(this,"测试连接",Ui.TONAL,x->runAction(1));Button listButton=Ui.button(this,"读取模型列表",Ui.TONAL,x->loadModels());
        connectionActions.addView(testButton,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(0,-2,1);listParams.leftMargin=dp(8);connectionActions.addView(listButton,listParams);
        inputs.add(testButton);inputs.add(listButton);connection.addView(connectionActions,Ui.margins(this,0,10,0,0));

        LinearLayout translation=section(page,R.drawable.ic_translate,"翻译方式与模型",14);
        mode=spinner(translation,"翻译模式",new String[]{"文字翻译＋本地嵌字（推荐）","图像编辑翻译"},"image".equals(settings.mode)?1:0);
        modeNote=label(translation,"",12);
        textModelSection=new LinearLayout(this);textModelSection.setOrientation(LinearLayout.VERTICAL);translation.addView(textModelSection);
        textChoices=spinner(textModelSection,"文字翻译模型",new String[]{settings.textModel},0);
        textModel=field(textModelSection,"模型名称（可手填，与下拉同步）",settings.textModel,false,false);
        imageModelSection=new LinearLayout(this);imageModelSection.setOrientation(LinearLayout.VERTICAL);translation.addView(imageModelSection);
        imageChoices=spinner(imageModelSection,"图像翻译模型",new String[]{settings.imageModel},0);
        imageModel=field(imageModelSection,"模型名称（可手填，与下拉同步）",settings.imageModel,false,false);
        reasoningSection=new LinearLayout(this);reasoningSection.setOrientation(LinearLayout.VERTICAL);translation.addView(reasoningSection);
        int effort=0;for(int i=0;i<efforts.length;i++)if(efforts[i].equals(settings.reasoningEffort))effort=i;
        reasoning=spinner(reasoningSection,"推理强度",new String[]{"低（默认）","中","高","更高","最少","不推理","不发送此参数"},effort);
        tier=spinner(translation,"服务级别",new String[]{"默认（自动）","标准","Fast（Priority）"},"priority".equals(settings.serviceTier)?2:"default".equals(settings.serviceTier)?1:0);
        label(translation,"服务级别用于文字翻译和图像编辑请求。标准或 Fast 需要接口和模型支持；不支持时会报错，不会自动改为其他级别。Fast 可能增加额度消耗。",12);

        LinearLayout detection=section(page,R.drawable.ic_document_scanner,"本地文字检测",14);
        String[] detectorLabels=new String[DetectorModels.IDS.length];int detectorIndex=0;
        for(int i=0;i<DetectorModels.IDS.length;i++){detectorLabels[i]=DetectorModels.label(DetectorModels.IDS[i]);if(DetectorModels.IDS[i].equals(settings.detectorModel))detectorIndex=i;}
        detector=spinner(detection,"本地检测模型",detectorLabels,detectorIndex);
        label(detection,"仅内置 PP-OCR 分块补检＋紧贴裁剪，离线运行；保留 2 像素留白，可能误检纹理。旧版本模型配置会自动迁移。",12);

        LinearLayout workbench=section(page,R.drawable.ic_edit_note,"汉化工作台",14);
        Switch drafts=new Switch(this);drafts.setText("翻译时保存可编辑草稿");drafts.setTextSize(14);drafts.setTextColor(Ui.ICON);drafts.setChecked(PageDraftStore.enabled(this));
        drafts.setOnCheckedChangeListener((b,on)->{PageDraftStore.setEnabled(this,on);status.setText(on?"已开启：之后翻译的页面可在汉化工作台逐段修改。":"已关闭：之后翻译的页面在工作台中只能浏览和导出。");});
        workbench.addView(drafts,Ui.margins(this,0,8,0,0));
        label(workbench,"开启后保存原图、去字底图和文字数据，便于逐段修改与导出，会增加存储占用。此开关立即生效。",12);
        storageInfo=label(workbench,"占用统计中…",12);
        workbench.addView(Icons.iconTextButton(this,R.drawable.ic_delete,"缓存管理",Ui.OUTLINED,x->clearDrafts()),Ui.margins(this,0,8,0,0));refreshStorage();

        LinearLayout presetSection=section(page,R.drawable.ic_bookmark,"整套配置预设",14);
        presetChoices=spinner(presetSection,"已保存的预设",new String[]{"尚无预设"},0);
        button(presetSection,"切换并使用所选预设",this::loadPreset);
        button(presetSection,"保存为预设并使用",this::savePreset);
        button(presetSection,"重命名／删除所选预设",this::managePreset,Ui.OUTLINED);
        label(presetSection,"预设包含地址、Key、两种模式的模型、推理强度、服务级别、提示词、检测与等待参数。切换会载入整套配置并保存，已发出的任务保持原参数。",12);

        // Advanced: collapsed by default; the header says whether anything differs from the defaults.
        LinearLayout advanced=Ui.section(this,page,null,null,14);
        LinearLayout advancedHead=new LinearLayout(this);advancedHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout advancedWords=new LinearLayout(this);advancedWords.setOrientation(LinearLayout.VERTICAL);
        TextView advancedTitle=Ui.heading(this,"高级参数",17);Icons.setIcon(advancedTitle,R.drawable.ic_tune,Ui.ACCENT,24);advancedWords.addView(advancedTitle);advancedSummary=Ui.text(this,"",12,Ui.MUTED);advancedWords.addView(advancedSummary,Ui.margins(this,0,3,0,0));
        advancedHead.addView(advancedWords,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(Icons.icon(this,R.drawable.ic_expand_more,Ui.MUTED));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);advancedHead.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));advancedHead.setMinimumHeight(dp(52));advancedHead.setContentDescription("展开或收起高级参数");
        advanced.addView(advancedHead);
        advancedBody=new LinearLayout(this);advancedBody.setOrientation(LinearLayout.VERTICAL);advancedBody.setVisibility(View.GONE);advanced.addView(advancedBody);
        advancedHead.setOnClickListener(v->{boolean open=advancedBody.getVisibility()!=View.VISIBLE;advancedBody.setVisibility(open?View.VISIBLE:View.GONE);
            if(Ui.motion())arrow.animate().rotation(open?180:0).setDuration(220).setInterpolator(Ui.EASE).start();else arrow.setRotation(open?180:0);});
        label(advancedBody,"并发、等待与重试",15);
        concurrency=field(advancedBody,"文字网络并发（1–32，默认10）",String.valueOf(settings.textConcurrency),false,false);concurrency.setInputType(InputType.TYPE_CLASS_NUMBER);
        requestTimeout=field(advancedBody,"单次请求超时（秒，10–600，默认180）",String.valueOf(settings.requestTimeoutSeconds),false,false);requestTimeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        retries=spinner(advancedBody,"失败后最多额外重试次数（默认1次）",new String[]{"0次（不重试）","1次","2次","3次","4次","5次"},Math.max(0,Math.min(5,settings.maxRetries)));
        retryInterval=field(advancedBody,"重试间隔（秒，1–120，默认5）",String.valueOf(settings.retryIntervalSeconds),false,false);retryInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateLimitWait=field(advancedBody,"限流等待（秒，1–300，默认30）",String.valueOf(settings.rateLimitWaitSeconds),false,false);rateLimitWait.setInputType(InputType.TYPE_CLASS_NUMBER);
        label(advancedBody,"超时按每次请求计算。429 至少等待限流间隔；Retry-After 更长时按服务器要求，超过300秒则停止本次重试。额外次数大于0时，漏回或无效译文补发一次，仅发送未完成段。保存后用于新任务，打开设置不会自动重新付费。",12);
        label(advancedBody,"提示词",15);
        textPrompt=field(advancedBody,"文字翻译提示词",settings.textPrompt,false,true);imagePrompt=field(advancedBody,"图像翻译提示词",settings.imagePrompt,false,true);
        button(advancedBody,"恢复默认提示词",()->{textPrompt.setText(AppSettings.DEFAULT_TEXT_PROMPT);imagePrompt.setText(AppSettings.DEFAULT_IMAGE_PROMPT);},Ui.OUTLINED);

        LinearLayout checks=section(page,R.drawable.ic_pulse,"诊断",14);
        button(checks,"验证文字模型服务级别（会调用）",()->new AlertDialog.Builder(this).setTitle("发送小型测试请求")
            .setMessage("将使用当前文字模型发送一句测试文字，失败时按上方设置重试，可能产生费用；只核对代理返回的服务级别，不能独立证明上游实际加速或计费。")
            .setNegativeButton("取消",null).setPositiveButton("发送测试",(d,w)->runAction(2)).show(),Ui.OUTLINED);
        button(checks,"查看最近请求诊断",()->new AlertDialog.Builder(this).setTitle("本机请求诊断").setMessage(ApiClient.lastDiagnostic()+"\n诊断仅保留耗时、服务级别与用量，不包含Key、漫画文本或完整响应。").setPositiveButton("关闭",null).show(),Ui.OUTLINED);
        button(checks,"查看／导出翻译日志",()->startActivity(new android.content.Intent(this,LogsActivity.class)),Ui.OUTLINED);
        Ui.enter(page,40);
        watch(textModel,this::refreshModelChoices);watch(imageModel,this::refreshModelChoices);
        watch(baseUrl,this::invalidateModelList);watch(apiKey,this::invalidateModelList);refreshModelChoices();
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){showModeModels();checkDirty();}
        });showModeModels();refreshPresets(null);
        trackChanges();
        shell.post(()->{baseline=signature();paintSave();refreshAdvancedSummary();});
    }
    private void showModeModels(){
        boolean image=mode.getSelectedItemPosition()==1;
        textModelSection.setVisibility(image?View.GONE:View.VISIBLE);imageModelSection.setVisibility(image?View.VISIBLE:View.GONE);
        reasoningSection.setVisibility(image?View.GONE:View.VISIBLE);
        modeNote.setText(image?"图像翻译：每个文字区块单独交给图像模型直接生成译图，不返回文字；页面可以导出，但不能在汉化工作台里改字。"
            :"文字翻译：模型识读并翻译每段文字，本地清除对话框里的原文后嵌入中文；背景上的文字用原位红字覆盖。之后可在汉化工作台逐段修改。");
    }
    /** All editable values in one string, compared with {@link #baseline} to know if anything changed. */
    private String signature(){
        StringBuilder s=new StringBuilder();
        for(View v:inputs){if(v instanceof EditText)s.append(((EditText)v).getText()).append('\u0001');else if(v instanceof Spinner&&v!=presetChoices&&v!=textChoices&&v!=imageChoices)s.append(((Spinner)v).getSelectedItemPosition()).append('\u0001');}
        return s.toString();
    }
    private void checkDirty(){boolean now=!baseline.isEmpty()&&!signature().equals(baseline);if(now!=dirty){dirty=now;paintSave();}refreshAdvancedSummary();}
    private void paintSave(){
        if(saveButton==null)return;
        Ui.style(saveButton,dirty?Ui.PRIMARY:Ui.TONAL);saveButton.setText(dirty?"保存并使用 · 有修改":"保存并使用");
        if(dirty&&Ui.motion()){saveButton.setScaleX(.96f);saveButton.setScaleY(.96f);saveButton.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();}
    }
    private void markSaved(){baseline=signature();dirty=false;paintSave();if(afterSave!=null){Runnable next=afterSave;afterSave=null;next.run();}if(finishAfterSave){finishAfterSave=false;finish();}}
    private void trackChanges(){
        for(View v:inputs){
            if(v instanceof EditText)((EditText)v).addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int b,int c){}
                public void onTextChanged(CharSequence s,int a,int b,int c){}
                public void afterTextChanged(Editable value){checkDirty();}});
            else if(v instanceof Spinner&&v!=mode&&v!=presetChoices&&v!=textChoices&&v!=imageChoices)((Spinner)v).setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(AdapterView<?> parent){}
                public void onItemSelected(AdapterView<?> parent,View view,int position,long id){checkDirty();}});
        }
    }
    private void refreshAdvancedSummary(){
        if(advancedSummary==null)return;
        boolean custom=!"10".equals(concurrency.getText().toString().trim())||!"180".equals(requestTimeout.getText().toString().trim())||retries.getSelectedItemPosition()!=1
            ||!"5".equals(retryInterval.getText().toString().trim())||!"30".equals(rateLimitWait.getText().toString().trim())
            ||!AppSettings.DEFAULT_TEXT_PROMPT.equals(textPrompt.getText().toString())||!AppSettings.DEFAULT_IMAGE_PROMPT.equals(imagePrompt.getText().toString());
        advancedSummary.setText((custom?"已自定义 · ":"默认值 · ")+"并发 "+concurrency.getText()+" · 超时 "+requestTimeout.getText()+" 秒 · 重试 "+retries.getSelectedItemPosition()+" 次 · 提示词");
    }
    private void refreshStorage(){
        worker.submit(()->{
            try{
                long[] groups=CacheStorage.sizes(getCacheDir(),getFilesDir());long total=0;for(long size:groups)total+=size;
                final long cache=total,projects=CacheStorage.size(ProjectStore.root(this));
                runOnUiThread(()->{if(!isDestroyed())storageInfo.setText("可管理缓存 "+fileSize(cache)+" · 汉化工程 "+fileSize(projects)+"（单独保留，不随缓存删除；网页资源缓存由浏览器管理）");});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())storageInfo.setText("暂时无法统计占用，可稍后打开缓存管理重试。");});}
        });
    }
    private String fileSize(long bytes){return android.text.format.Formatter.formatShortFileSize(this,bytes);}
    private void clearDrafts(){
        status.setText("正在统计缓存…");
        worker.submit(()->{try{
            long[] sizes=CacheStorage.sizes(getCacheDir(),getFilesDir());
            runOnUiThread(()->{if(isDestroyed()||isFinishing())return;
                LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(8));
                body.addView(Ui.text(this,"保留：浏览记录、收藏夹、接口配置、预设、日志、已保存汉化工程和恢复草稿，以及导出的文件。",14,Ui.INK));
                CheckBox[] boxes=new CheckBox[sizes.length];
                for(int i=0;i<boxes.length;i++){CheckBox box=new CheckBox(this);box.setText(CacheStorage.LABELS[i]+" · "+fileSize(sizes[i]));box.setTextSize(14);box.setChecked(i!=1);body.addView(box);boxes[i]=box;}
                CheckBox web=new CheckBox(this);web.setText("网页资源缓存（保留登录信息）");web.setTextSize(14);web.setChecked(true);body.addView(web);
                body.addView(Ui.text(this,"网页会话与草稿默认保留：清除后，未导入工程的页面需重新加载/翻译才能继续导入。清除译文缓存后再次翻译可能重新调用接口。已显示的页面可能暂时仍保留在内存中。",12,Ui.MUTED));
                ScrollView scroll=new ScrollView(this);scroll.addView(body);
                new AlertDialog.Builder(this).setTitle("缓存管理").setView(scroll).setNegativeButton("取消",null).setPositiveButton("清理所选",(d,w)->{
                    boolean[] selected=new boolean[boxes.length];boolean any=web.isChecked();for(int i=0;i<boxes.length;i++){selected[i]=boxes[i].isChecked();any|=selected[i];}
                    if(!any){status.setText("未选择清理项目。");return;}
                    cleanSelectedCaches(selected,web.isChecked());
                }).show();
            });
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("缓存统计失败："+e.getMessage());});}});
    }
    private void cleanSelectedCaches(boolean[] selected,boolean webCache){
        if(!CacheStorage.beginClear()){new AlertDialog.Builder(this).setMessage("正在翻译、读取页面、导入或导出，请等待完成，或先停止翻译后再清理。").setPositiveButton("知道了",null).show();return;}
        android.app.ProgressDialog progress=new android.app.ProgressDialog(this);progress.setMessage("正在清理所选缓存…");progress.setCancelable(false);progress.show();
        boolean webOk=true;
        if(webCache){android.webkit.WebView view=null;try{view=new android.webkit.WebView(this);view.clearCache(true);}catch(RuntimeException e){webOk=false;}finally{if(view!=null)try{view.destroy();}catch(RuntimeException e){webOk=false;}}}
        final boolean webCleared=webOk;
        // Dedicated worker: leaving Settings must not cancel the cleanup before releasing admission.
        new Thread(()->{
            CacheStorage.Result outcome;
            try{outcome=CacheStorage.clear(getCacheDir(),getFilesDir(),selected);}catch(RuntimeException e){outcome=new CacheStorage.Result();outcome.failures=1;}finally{CacheStorage.endClear();}
            final CacheStorage.Result result=outcome;
            runOnUiThread(()->{if(isDestroyed()||isFinishing())return;progress.dismiss();
                status.setText("已清理 "+fileSize(result.bytes)+"。"+(webCache?(webCleared?"已请求清理网页资源缓存（不计入上述容量）。":"网页资源缓存未能清理，可稍后重试。") : "")+(result.failures>0?"有 "+result.failures+" 项未能删除，可稍后重试。":"")+" 浏览记录、收藏和工程已保留。");refreshStorage();
            });
        },"cache-cleanup").start();
    }
    private boolean refreshPresets(String selectedId){
        presets.clear();boolean read=true;
        try{presets.addAll(new ApiPresets(this).list());}catch(Exception e){read=false;status.setText(e.getMessage());}
        List<String> names=new ArrayList<>();names.add(presets.isEmpty()?"尚无预设":"请选择预设");int selected=0;
        for(int i=0;i<presets.size();i++){ApiPresets.Preset preset=presets.get(i);names.add(preset.name);if(preset.id.equals(selectedId))selected=i+1;}
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);presetChoices.setAdapter(adapter);presetChoices.setSelection(selected);presetChoices.setEnabled(!busy&&!presets.isEmpty());return read;
    }
    private ApiPresets.Preset selectedPreset(){
        int index=presetChoices.getSelectedItemPosition()-1;
        if(index<0||index>=presets.size()){status.setText("请先选择一个已保存的预设");return null;}return presets.get(index);
    }
    private void savePreset(){
        if(busy)return;final AppSettings settings;try{settings=read();settings.validate();}catch(Exception e){status.setText(e.getMessage());return;}
        int index=presetChoices.getSelectedItemPosition()-1;EditText name=new EditText(this);name.setSingleLine(true);name.setHint("例如：OpenCode 低推理");
        if(index>=0&&index<presets.size())name.setText(presets.get(index).name);
        new AlertDialog.Builder(this).setTitle("保存整套配置预设").setMessage("填写名称；保存后同时使用当前整套设置。").setView(name).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w)->{
                String value=name.getText().toString().trim();ApiPresets.Preset existing=null;
                for(ApiPresets.Preset preset:presets)if(preset.name.equals(value)){existing=preset;break;}
                if(existing==null){runPreset(0,null,value,settings);return;}
                final String id=existing.id;
                new AlertDialog.Builder(this).setTitle("覆盖同名预设").setMessage("将用当前整套设置覆盖“"+value+"”。其他预设保留。")
                    .setNegativeButton("取消",null).setPositiveButton("覆盖保存",(dialog,which)->runPreset(0,id,value,settings)).show();
            }).show();
    }
    private void loadPreset(){if(busy)return;ApiPresets.Preset preset=selectedPreset();if(preset!=null)runPreset(1,preset.id,preset.name,null);}
    private void managePreset(){
        if(busy)return;ApiPresets.Preset preset=selectedPreset();if(preset==null)return;
        new AlertDialog.Builder(this).setTitle(preset.name).setItems(new String[]{"重命名","删除预设"},(d,which)->{
            if(which==0){EditText name=new EditText(this);name.setSingleLine(true);name.setText(preset.name);
                new AlertDialog.Builder(this).setTitle("重命名预设").setView(name).setNegativeButton("取消",null)
                    .setPositiveButton("保存名称",(dialog,button)->runPreset(2,preset.id,name.getText().toString(),null)).show();
            }else new AlertDialog.Builder(this).setTitle("删除预设").setMessage("删除“"+preset.name+"”？当前使用的设置仍保留。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,button)->runPreset(3,preset.id,preset.name,null)).show();
        }).show();
    }
    private void runPreset(int action,String id,String name,AppSettings prepared){
        if(busy)return;stopped=new AtomicBoolean();final AtomicBoolean stop=stopped;final int token=++generation;saving=true;setBusy(true);status.setText("处理预设中…");
        work=worker.submit(()->{
            String message,selectedId=id;AppSettings applied=null;boolean presetStored=false;
            try{ApiPresets store=new ApiPresets(this);
                if(action==0){selectedId=store.save(id,name,prepared);presetStored=true;prepared.save(this);message="预设“"+name.trim()+"”已保存并使用。";}
                else if(action==1){AppSettings loaded=store.load(id);loaded.save(this);applied=loaded;message="已切换并使用“"+name+"”，新任务使用这套配置。";}
                else if(action==2){store.rename(id,name);message="预设已重命名。";}
                else{store.delete(id);selectedId=null;message="预设已删除，当前使用的设置仍保留。";}
            }catch(Exception e){message=(presetStored?"预设已保存，但当前设置未能应用：":"预设操作未完成：")+(e.getMessage()==null?"请重试":e.getMessage());if(prepared!=null&&!prepared.apiKey.isEmpty())message=message.replace(prepared.apiKey,"[密钥已隐藏]");}
            final String result=message,selection=selectedId;final AppSettings loaded=applied;
            runOnUiThread(()->{if(isDestroyed()||token!=generation||stop.get())return;
                if(loaded!=null)applySettings(loaded);saving=false;setBusy(false);if(refreshPresets(selection))status.setText(result);
                if(action==0||action==1)markSaved();
            });
        });
    }
    private void applySettings(AppSettings s){
        applyingPreset=true;
        try{
            baseUrl.setText(s.baseUrl);apiKey.setText(s.apiKey);mode.setSelection("image".equals(s.mode)?1:0);textModel.setText(s.textModel);imageModel.setText(s.imageModel);
            int effort=0;for(int i=0;i<efforts.length;i++)if(efforts[i].equals(s.reasoningEffort))effort=i;reasoning.setSelection(effort);
            tier.setSelection("priority".equals(s.serviceTier)?2:"default".equals(s.serviceTier)?1:0);
            int detection=0;for(int i=0;i<DetectorModels.IDS.length;i++)if(DetectorModels.IDS[i].equals(s.detectorModel))detection=i;detector.setSelection(detection);
            textPrompt.setText(s.textPrompt);imagePrompt.setText(s.imagePrompt);concurrency.setText(String.valueOf(s.textConcurrency));requestTimeout.setText(String.valueOf(s.requestTimeoutSeconds));
            retries.setSelection(s.maxRetries);retryInterval.setText(String.valueOf(s.retryIntervalSeconds));rateLimitWait.setText(String.valueOf(s.rateLimitWaitSeconds));
        }finally{applyingPreset=false;}
        availableModels.clear();refreshModelChoices();showModeModels();
    }
    private void watch(EditText field,Runnable changed){field.addTextChangedListener(new TextWatcher(){
        public void beforeTextChanged(CharSequence s,int start,int count,int after){}
        public void onTextChanged(CharSequence s,int start,int before,int count){if(!applyingPreset)changed.run();}
        public void afterTextChanged(Editable value){}
    });}
    private void invalidateModelList(){
        availableModels.clear();
        if(loadingModels){stopped.set(true);generation++;loadingModels=false;if(work!=null)work.cancel(true);setBusy(false);}
        refreshModelChoices();status.setText("连接已修改，模型列表已失效；当前两项模型保留，请重新读取列表或手填后保存。");
    }
    private void refreshModelChoices(){bindModelChoice(textChoices,textModel);bindModelChoice(imageChoices,imageModel);}
    private void bindModelChoice(Spinner choice,EditText field){
        String current=field.getText().toString();List<String> values=new ArrayList<>();values.add(current);
        for(String id:availableModels)if(!id.equals(current))values.add(id);
        List<String> labels=new ArrayList<>(values);labels.set(0,current.isEmpty()?"未填写（可在下方手填）":current+"（当前）");
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choice.setOnItemSelectedListener(null);choice.setAdapter(adapter);choice.setSelection(0);
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(choice.getAdapter()!=adapter||position<0||position>=values.size())return;
                String selected=values.get(position);if(!selected.equals(field.getText().toString()))field.setText(selected);
            }
        });
    }
    private AppSettings read()throws Exception{
        AppSettings s=new AppSettings();s.baseUrl=baseUrl.getText().toString();s.apiKey=apiKey.getText().toString();s.textModel=textModel.getText().toString();s.imageModel=imageModel.getText().toString();s.mode=mode.getSelectedItemPosition()==1?"image":"text";
        s.textPrompt=textPrompt.getText().toString();s.imagePrompt=imagePrompt.getText().toString();
        s.detectorModel=DetectorModels.IDS[detector.getSelectedItemPosition()];
        s.textConcurrency=AppSettings.parseInteger(concurrency.getText().toString(),"文字网络并发",1,32);
        s.requestTimeoutSeconds=AppSettings.parseInteger(requestTimeout.getText().toString(),"单次请求超时（秒）",10,600);
        s.maxRetries=retries.getSelectedItemPosition();
        s.retryIntervalSeconds=AppSettings.parseInteger(retryInterval.getText().toString(),"重试间隔（秒）",1,120);
        s.rateLimitWaitSeconds=AppSettings.parseInteger(rateLimitWait.getText().toString(),"限流等待（秒）",1,300);
        s.reasoningEffort=efforts[reasoning.getSelectedItemPosition()];s.serviceTier=tier.getSelectedItemPosition()==2?"priority":tier.getSelectedItemPosition()==1?"default":"auto";return s;
    }
    private void runAction(int action){
        if(busy)return;final AppSettings settings;try{settings=read();if(action!=0)settings.validate();}catch(Exception e){finishAfterSave=false;status.setText(e.getMessage());Ui.shake(status);return;}
        stopped=new AtomicBoolean();final AtomicBoolean stop=stopped;final int token=++generation;saving=true;setBusy(true);status.setText("保存中…");
        work=worker.submit(()->{String result;try{settings.save(this);
            runOnUiThread(()->{if(!isDestroyed()&&token==generation&&!stop.get()){saving=false;setBusy(true);if(action!=0)status.setText(action==1?"检查模型列表…":"发送服务级别测试…");markSaved();}});
            result=action==0?"设置已保存。等待参数用于新任务；翻译内容配置改变后需确认开始。":action==1?ApiClient.testConnection(settings,stop::get)+"\n模型列表不能确认Fast支持。":ApiClient.probeServiceTier(settings,stop::get);
        }catch(Exception e){result=e.getMessage()==null?"操作未完成，请重试":e.getMessage();if(!settings.apiKey.isEmpty())result=result.replace(settings.apiKey,"[密钥已隐藏]");}final String message=result;runOnUiThread(()->{if(!isDestroyed()&&token==generation&&!stop.get()){saving=false;setBusy(false);status.setText(message);}});});
    }
    private void loadModels(){
        if(busy)return;final AppSettings settings;
        try{settings=read();settings.validateConnection();}catch(Exception e){status.setText(e.getMessage());return;}
        stopped=new AtomicBoolean();final AtomicBoolean stop=stopped;final int token=++generation;loadingModels=true;setBusy(true);status.setText("正在读取接口模型列表…");
        work=worker.submit(()->{
            try{List<String> models=ApiClient.listModels(settings,stop::get);
                runOnUiThread(()->{if(isFinishing()||isDestroyed()||token!=generation||stop.get())return;
                    if(!settings.baseUrl.equals(baseUrl.getText().toString().trim().replaceAll("/+$",""))||!settings.apiKey.equals(apiKey.getText().toString().trim())){loadingModels=false;setBusy(false);invalidateModelList();return;}
                    loadingModels=false;availableModels.clear();availableModels.addAll(models);refreshModelChoices();setBusy(false);
                    status.setText("已读取 "+models.size()+" 个可访问模型。请分别选择文字、图像模型，再保存；当前选择未自动更换。");});
            }catch(Exception error){String message=error.getMessage()==null?"无法读取模型列表，可继续手动填写模型名":error.getMessage();if(!settings.apiKey.isEmpty())message=message.replace(settings.apiKey,"[密钥已隐藏]");final String safe=message;
                runOnUiThread(()->{if(!isDestroyed()&&token==generation&&!stop.get()){loadingModels=false;setBusy(false);status.setText(safe+"\n原模型设置未改变。");}});}
        });
    }
    private void setBusy(boolean value){busy=value;for(View v:inputs)v.setEnabled(!value);presetChoices.setEnabled(!value&&!presets.isEmpty());back.setEnabled(!saving);cancel.setEnabled(!saving);cancel.setVisibility(value?View.VISIBLE:View.GONE);busyBar.setVisibility(value?View.VISIBLE:View.GONE);if(saveButton!=null)saveButton.setVisibility(value?View.GONE:View.VISIBLE);}
    private LinearLayout section(LinearLayout parent,int icon,String title,int margin){LinearLayout section=Ui.section(this,parent,title,null,margin);Icons.setIcon((TextView)section.getChildAt(0),icon,Ui.ACCENT,24);return section;}
    private TextView label(LinearLayout parent,String text,int size){TextView v=Ui.text(this,text,size,size<=12?Ui.MUTED:size<=14?Ui.ICON:Ui.INK);if(size==14)v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));v.setPadding(0,dp(size<=12?6:12),0,dp(6));parent.addView(v);return v;}
    private EditText field(LinearLayout parent,String name,String value,boolean secret,boolean multi){label(parent,name,14);EditText v=new EditText(this);v.setTextSize(14);v.setText(value);v.setSingleLine(!multi);v.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:multi?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);if(multi){v.setMinLines(3);v.setMaxLines(7);v.setGravity(android.view.Gravity.TOP|android.view.Gravity.START);}Ui.field(v);parent.addView(v,new LinearLayout.LayoutParams(-1,-2));inputs.add(v);return v;}
    private Spinner spinner(LinearLayout parent,String name,String[] entries,int selected){label(parent,name,14);Spinner v=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,entries);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);v.setAdapter(adapter);v.setSelection(selected);parent.addView(Ui.framed(v),new LinearLayout.LayoutParams(-1,dp(48)));inputs.add(v);return v;}
    private void button(LinearLayout parent,String text,Runnable action){button(parent,text,action,Ui.TONAL);}
    private void button(LinearLayout parent,String text,Runnable action,int kind){Button v=Ui.button(this,text,kind,x->action.run());parent.addView(v,Ui.margins(this,0,8,0,0));inputs.add(v);}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    @Override public void onBackPressed(){
        if(saving){status.setText("正在保存，请稍候再返回。");return;}
        if(dirty&&!busy){new AlertDialog.Builder(this).setTitle("有未保存的修改").setMessage("返回前要保存并使用这些修改吗？")
            .setPositiveButton("保存并返回",(d,w)->{finishAfterSave=true;runAction(0);}).setNegativeButton("放弃修改",(d,w)->{dirty=false;finish();}).setNeutralButton("继续编辑",null).show();return;}
        super.onBackPressed();
    }
    @Override protected void leaveEditor(Runnable next){if(saving){Toast.makeText(this,"正在保存，请稍候",0).show();return;}if(!dirty){next.run();return;}
        new AlertDialog.Builder(this).setTitle("有未保存的设置").setPositiveButton("保存",(d,w)->{afterSave=next;runAction(0);}).setNegativeButton("不保存",(d,w)->{applySettings(AppSettings.load(this));baseline=signature();dirty=false;paintSave();next.run();}).setNeutralButton("取消",null).show();}
    @Override protected void onDestroy(){stopped.set(true);generation++;if(work!=null)work.cancel(true);worker.shutdownNow();if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);super.onDestroy();}
}
