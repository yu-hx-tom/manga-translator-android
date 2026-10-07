package cn.local.manga;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** A reusable four-tab style editor, embedded in the workbench's existing drawer. */
final class StylePanel {
    private static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
    static Editor create(Activity a,PageComposer.Edit initial,Rect bounds,Consumer<PageComposer.Edit> preview,Consumer<PageComposer.Edit> done,Runnable cancel){return new Editor(a,initial,bounds,preview,done,cancel);}
    static void projectDefaults(Activity a,ComicProject project){
        if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)){Toast.makeText(a,"请先停止工程翻译",Toast.LENGTH_SHORT).show();return;}
        PageComposer.Edit original=project.defaultStyle.copy();Ui.Sheet sheet=Ui.sheet(a,"工程默认样式");boolean[] committed={false};
        Runnable restore=()->{project.defaultStyle=original.copy();if(a instanceof ProjectReaderActivity)((ProjectReaderActivity)a).styleChanged();};
        Editor editor=create(a,original,new Rect(0,0,200,100),edit->{project.defaultStyle=edit.copy();project.defaultStyle.format.box=null;project.defaultStyle.format.rotation=0;if(a instanceof ProjectReaderActivity)((ProjectReaderActivity)a).styleChanged();},edit->{committed[0]=true;project.defaultStyle=edit.copy();project.defaultStyle.format.box=null;project.defaultStyle.format.rotation=0;if(a instanceof ProjectReaderActivity)((ProjectReaderActivity)a).styleChanged();IO.execute(()->{try{project.save();}catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"保存失败："+e.getMessage(),Toast.LENGTH_LONG).show());}});sheet.dismiss();},sheet::dismiss);
        sheet.body.addView(editor,new LinearLayout.LayoutParams(-1,Ui.dp(a,420)));sheet.setOnDismissListener(d->{if(!committed[0])restore.run();});sheet.show();
    }
    static final class Editor extends LinearLayout {
        private final Activity activity;private final PageComposer.Edit edit;private final Rect initialBox;private final Consumer<PageComposer.Edit> preview,done;private final Runnable cancel;
        private final LinearLayout content;private int colorTarget;private boolean coarse,closed;private String normalFamily;
        Editor(Activity a,PageComposer.Edit initial,Rect bounds,Consumer<PageComposer.Edit> preview,Consumer<PageComposer.Edit> done,Runnable cancel){
            super(a);activity=a;edit=initial.copy();edit.format.custom=true;edit.format.fontSize=Math.max(8,Math.min(96,edit.format.fontSize*edit.scale));edit.scale=1;initialBox=new Rect(bounds);this.preview=preview;this.done=done;this.cancel=cancel;normalFamily=FontChoices.SERIF.equals(edit.format.fontFamily)?FontChoices.SERIF:FontChoices.NORMAL;
            setOrientation(VERTICAL);setPadding(Ui.dp(a,12),0,Ui.dp(a,12),0);
            Ui.Segmented tabs=Ui.segmented(a,new String[]{"文字","颜色","排版","位置"},0,this::tab);addView(tabs);
            ScrollView scroll=new ScrollView(a);content=new LinearLayout(a);content.setOrientation(VERTICAL);content.setPadding(0,Ui.dp(a,8),0,Ui.dp(a,12));scroll.addView(content);addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout footer=new LinearLayout(a);footer.addView(Ui.button(a,"取消",Ui.TEXT,v->cancel()),new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));footer.addView(Ui.button(a,"完成",Ui.PRIMARY,v->commit()),new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));addView(footer);tab(0);
        }
        void commit(){if(closed)return;closed=true;done.accept(edit.copy());}
        void cancel(){if(closed)return;closed=true;cancel.run();}
        private void changed(){if(!closed)preview.accept(edit.copy());}
        private void tab(int value){content.removeAllViews();switch(value){case 0:text();break;case 1:colors();break;case 2:layout();break;default:position();}}
        private void text(){
            slider(activity,content,"字号",edit.format.fontSize,8,96,1,v->{edit.format.fontSize=v;changed();});
            CheckBox fit=new CheckBox(activity);fit.setText("自动适配文本框");fit.setChecked(edit.format.autoFit);fit.setOnCheckedChangeListener((button,checked)->{edit.format.autoFit=checked;changed();});content.addView(fit);
            content.addView(Ui.text(activity,"字体",13,Ui.MUTED));HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);LinearLayout fonts=new LinearLayout(activity);scroll.addView(fonts);content.addView(scroll);
            ArrayList<String> families=new ArrayList<>(Arrays.asList(FontChoices.NORMAL,FontChoices.BOLD));if(FontChoices.hasCjkSerif())families.add(FontChoices.SERIF);
            for(String family:families){Button card=Ui.button(activity,FontChoices.label(family)+"\n样例文字 Aa",FontChoices.label(edit.format.fontFamily).equals(FontChoices.label(family))?Ui.TONAL:Ui.OUTLINED,v->{edit.format.fontFamily=family;if(!FontChoices.BOLD.equals(family))normalFamily=family;changed();tab(0);});card.setTypeface(FontChoices.typeface(family));card.setContentDescription(FontChoices.label(family)+"，样例文字 Aa");LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(activity,140),Ui.dp(activity,84));cp.setMarginEnd(Ui.dp(activity,8));fonts.addView(card,cp);}
            CheckBox bold=new CheckBox(activity);bold.setText("加粗（粗黑）");bold.setChecked(FontChoices.BOLD.equals(edit.format.fontFamily));bold.setOnCheckedChangeListener((button,checked)->{edit.format.fontFamily=checked?FontChoices.BOLD:normalFamily;changed();tab(0);});content.addView(bold);
        }
        private int selectedColor(){return colorTarget==0?(edit.color==0?Ui.BLACK:edit.color):edit.format.strokeColor;}
        private void applyColor(int color){if(colorTarget==0)edit.color=color;else edit.format.strokeColor=color;changed();}
        private void colors(){
            content.addView(Ui.segmented(activity,new String[]{"文字色","描边色"},colorTarget,value->{colorTarget=value;tab(1);}));
            int current=selectedColor();LinearLayout currentRow=new LinearLayout(activity);currentRow.setGravity(Gravity.CENTER_VERTICAL);View sample=new View(activity);sample.setBackground(Ui.round(activity,current,12));currentRow.addView(sample,new LinearLayout.LayoutParams(Ui.dp(activity,56),Ui.dp(activity,56)));EditText hex=new EditText(activity);hex.setSingleLine(true);hex.setHint("#RRGGBB / #AARRGGBB");hex.setText(String.format(Locale.ROOT,"#%08X",current));Ui.field(hex);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,Ui.dp(activity,56),1);hp.setMarginStart(Ui.dp(activity,12));currentRow.addView(hex,hp);content.addView(currentRow,Ui.margins(activity,0,8,0,8));
            int[] presets={Ui.BLACK,Ui.SURFACE,Ui.ACCENT,Ui.SUCCESS,Ui.DANGER,Ui.WARNING,Ui.PURPLE,Ui.PINK,Ui.CYAN,Ui.ORANGE,Ui.BROWN,Ui.GRAY};
            swatches(presets,current,hex);int[] recent=recentColors(activity);if(recent.length>0){content.addView(Ui.text(activity,"最近使用",12,Ui.MUTED));swatches(recent,current,hex);}
            LinearLayout custom=new LinearLayout(activity);custom.setOrientation(VERTICAL);custom.setVisibility(GONE);SeekBar[] channels=new SeekBar[3];String[] labels={"红","绿","蓝"};int[] rgb={Color.red(current),Color.green(current),Color.blue(current)};
            for(int i=0;i<3;i++){final int channel=i;custom.addView(Ui.text(activity,labels[i],12,Ui.MUTED));SeekBar bar=new SeekBar(activity);bar.setMax(255);bar.setProgress(rgb[i]);bar.setContentDescription(labels[i]+"色通道");channels[i]=bar;custom.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){rememberColor(activity,selectedColor());}public void onProgressChanged(SeekBar b,int value,boolean user){if(user){rgb[channel]=value;hex.setText(String.format(Locale.ROOT,"#%08X",Color.rgb(rgb[0],rgb[1],rgb[2])));}}});}
            Button expand=Icons.iconTextButton(activity,R.drawable.ic_expand_more,"自定义 RGB",Ui.TEXT,v->custom.setVisibility(custom.getVisibility()==VISIBLE?GONE:VISIBLE));content.addView(expand);content.addView(custom);
            watch(hex,()->{try{int value=Color.parseColor(hex.getText().toString().trim());applyColor(value);sample.setBackground(Ui.round(activity,value,12));rgb[0]=Color.red(value);rgb[1]=Color.green(value);rgb[2]=Color.blue(value);for(int i=0;i<3;i++)channels[i].setProgress(rgb[i]);hex.setError(null);}catch(IllegalArgumentException e){hex.setError("请输入完整的 HEX 颜色");}});
            hex.setOnFocusChangeListener((v,focused)->{if(!focused)rememberColor(activity,selectedColor());});
            slider(activity,content,"描边宽度",edit.format.strokeWidth,0,10,.5f,v->{edit.format.strokeWidth=v;changed();});
        }
        private void swatches(int[] colors,int current,EditText hex){int columns=activity.getResources().getConfiguration().screenWidthDp<360?5:6;LinearLayout row=null;for(int i=0;i<colors.length;i++){if(i%columns==0){row=new LinearLayout(activity);content.addView(row);}final int color=colors[i];View swatch=Ui.swatch(activity,color,color==current);swatch.setOnClickListener(v->{hex.setText(String.format(Locale.ROOT,"#%08X",color));rememberColor(activity,color);tab(1);});row.addView(swatch,new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));}}
        private void layout(){
            content.addView(Ui.text(activity,"文字方向",13,Ui.MUTED));content.addView(Ui.segmented(activity,new String[]{"横排","竖排"},Boolean.TRUE.equals(edit.vertical)?1:0,value->{edit.vertical=value==1;changed();}));
            content.addView(Ui.text(activity,"对齐",13,Ui.MUTED));Ui.Segmented align=Ui.segmented(activity,new String[]{"左","中","右"},edit.format.align,value->{edit.format.align=value;changed();});align.icons(R.drawable.ic_align_left,R.drawable.ic_align_center,R.drawable.ic_align_right);content.addView(align);
            slider(activity,content,"行间距",edit.format.lineSpacing,.5f,3,.1f,value->{edit.format.lineSpacing=value;changed();});slider(activity,content,"字间距",edit.format.letterSpacing,-4,30,.5f,value->{edit.format.letterSpacing=value;changed();});
        }
        private Rect box(){return edit.format.box==null?new Rect(initialBox):new Rect(edit.format.box);}
        private void move(int dx,int dy){Rect box=box();box.offset(dx*(coarse?10:1),dy*(coarse?10:1));box.offsetTo(Math.max(0,box.left),Math.max(0,box.top));edit.format.box=box;changed();}
        private void resize(int dw,int dh){Rect box=box();box.right=Math.max(box.left+8,box.right+dw*(coarse?10:1));box.bottom=Math.max(box.top+8,box.bottom+dh*(coarse?10:1));edit.format.box=box;changed();}
        private void position(){
            CheckBox step=new CheckBox(activity);step.setText("每次移动 10px");step.setChecked(coarse);step.setOnCheckedChangeListener((b,v)->coarse=v);content.addView(step);
            LinearLayout pad=new LinearLayout(activity);pad.setGravity(Gravity.CENTER);int[] icons={R.drawable.ic_arrow_back,R.drawable.ic_arrow_back,R.drawable.ic_arrow_forward,R.drawable.ic_arrow_forward};String[] labels={"左移","上移","下移","右移"};int[] dx={-1,0,0,1},dy={0,-1,1,0};
            for(int i=0;i<4;i++){final int index=i;View key=Icons.iconButton(activity,icons[i],labels[i],v->move(dx[index],dy[index]));if(i==1||i==2)key.setRotation(90);repeat(key,()->move(dx[index],dy[index]));pad.addView(key,new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));}content.addView(pad);
            LinearLayout size=new LinearLayout(activity);String[] names={"宽 −","宽 +","高 −","高 +"};for(int i=0;i<4;i++){final int index=i;Button button=Ui.button(activity,names[i],Ui.TONAL,v->resize(index==0?-1:index==1?1:0,index==2?-1:index==3?1:0));repeat(button,()->resize(index==0?-1:index==1?1:0,index==2?-1:index==3?1:0));size.addView(button,new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));}content.addView(size);
            slider(activity,content,"旋转（0° 吸附）",edit.format.rotation,-180,180,1,value->{edit.format.rotation=Math.abs(value)<=3?0:value;changed();});content.addView(Icons.iconTextButton(activity,R.drawable.ic_restart,"重置位置",Ui.TONAL,v->{edit.format.box=null;edit.format.rotation=0;changed();tab(3);}));
            LinearLayout precise=new LinearLayout(activity);precise.setOrientation(VERTICAL);precise.setVisibility(GONE);content.addView(Icons.iconTextButton(activity,R.drawable.ic_expand_more,"数值",Ui.TEXT,v->precise.setVisibility(precise.getVisibility()==VISIBLE?GONE:VISIBLE)));content.addView(precise);
            Rect box=box();numeric(activity,precise,"X",box.left,0,50000,v->{Rect b=box();b.offsetTo(Math.round(v),b.top);edit.format.box=b;changed();});numeric(activity,precise,"Y",box.top,0,50000,v->{Rect b=box();b.offsetTo(b.left,Math.round(v));edit.format.box=b;changed();});numeric(activity,precise,"宽",box.width(),8,50000,v->{Rect b=box();b.right=b.left+Math.round(v);edit.format.box=b;changed();});numeric(activity,precise,"高",box.height(),8,50000,v->{Rect b=box();b.bottom=b.top+Math.round(v);edit.format.box=b;changed();});
        }
    }
    private static void repeat(View button,Runnable action){final boolean[] held={false};Runnable repeat=new Runnable(){public void run(){if(!held[0]||!button.isAttachedToWindow())return;action.run();button.postDelayed(this,75);}};button.setOnLongClickListener(v->{held[0]=true;repeat.run();return true;});button.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)held[0]=false;return false;});button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){held[0]=false;button.removeCallbacks(repeat);}});}
    private static void slider(Activity a,LinearLayout parent,String label,float value,float min,float max,float step,Consumer<Float> change){
        parent.addView(Ui.text(a,label,13,Ui.MUTED),Ui.margins(a,0,8,0,0));LinearLayout row=new LinearLayout(a);SeekBar bar=new SeekBar(a);bar.setMax(Math.round((max-min)/step));bar.setProgress(Math.round((value-min)/step));bar.setContentDescription(label);EditText input=new EditText(a);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(format(value));input.setContentDescription(label+"数值");row.addView(bar,new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));row.addView(input,new LinearLayout.LayoutParams(Ui.dp(a,72),Ui.dp(a,48)));parent.addView(row);boolean[] binding={false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int progress,boolean user){if(user){float number=min+progress*step;if(label.startsWith("旋转")&&Math.abs(number)<=3)number=0;binding[0]=true;input.setText(format(number));binding[0]=false;change.accept(number);}}});watch(input,()->{if(binding[0])return;try{float number=Float.parseFloat(input.getText().toString());if(!Float.isFinite(number)||number<min||number>max){input.setError(format(min)+"–"+format(max));return;}bar.setProgress(Math.round((number-min)/step));change.accept(number);}catch(NumberFormatException ignored){}});
    }
    private static void numeric(Activity a,LinearLayout parent,String label,float value,float min,float max,Consumer<Float> change){parent.addView(Ui.text(a,label,12,Ui.MUTED));EditText field=new EditText(a);field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setSingleLine(true);field.setText(format(value));field.setContentDescription(label);Ui.field(field);parent.addView(field);watch(field,()->{try{float number=Float.parseFloat(field.getText().toString());if(Float.isFinite(number)&&number>=min&&number<=max)change.accept(number);else field.setError(format(min)+"–"+format(max));}catch(NumberFormatException ignored){}});}
    private static String format(float value){return value==Math.round(value)?String.valueOf(Math.round(value)):String.format(Locale.ROOT,"%.1f",value);}
    private static void watch(EditText field,Runnable action){field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable text){action.run();}});}
    private static int[] recentColors(Activity a){String raw=a.getSharedPreferences("ui_style",0).getString("recent_colors","");ArrayList<Integer> colors=new ArrayList<>();for(String value:raw.split(",")){try{colors.add((int)Long.parseLong(value,16));}catch(NumberFormatException ignored){}}int[] out=new int[colors.size()];for(int i=0;i<out.length;i++)out[i]=colors.get(i);return out;}
    private static void rememberColor(Activity a,int color){LinkedHashSet<Integer> colors=new LinkedHashSet<>();colors.add(color);for(int item:recentColors(a))if(colors.size()<6)colors.add(item);StringBuilder raw=new StringBuilder();for(int item:colors){if(raw.length()>0)raw.append(',');raw.append(Integer.toHexString(item));}a.getSharedPreferences("ui_style",0).edit().putString("recent_colors",raw.toString()).apply();}
}
