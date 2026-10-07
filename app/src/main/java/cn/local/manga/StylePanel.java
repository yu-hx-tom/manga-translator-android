package cn.local.manga;

import android.app.*;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;

/** Native controls sharing the existing UI theme; updates preview while the controls move. */
final class StylePanel {
    private static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
    static void show(Activity a,PageComposer.Edit original,Rect bounds,Consumer<PageComposer.Edit> change){
        PageComposer.Edit edit=original.copy();edit.format.custom=true;
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(Ui.dp(a,16),0,Ui.dp(a,16),Ui.dp(a,12));
        Consumer<Float> size=v->{edit.format.fontSize=v;change.accept(edit.copy());};
        number(a,body,"字号 8–96",edit.format.fontSize,8,96,size);
        CheckBox fit=new CheckBox(a);fit.setText("自动适配文本框");fit.setChecked(edit.format.autoFit);fit.setOnCheckedChangeListener((b,v)->{edit.format.autoFit=v;change.accept(edit.copy());});body.addView(fit);
        Button color=Ui.button(a,"文字颜色 / HEX",Ui.TONAL,v->color(a,edit.color==0?Color.BLACK:edit.color,c->{edit.color=c;change.accept(edit.copy());}));body.addView(color);
        body.addView(Ui.button(a,"描边颜色 / HEX",Ui.TONAL,v->color(a,edit.format.strokeColor,c->{edit.format.strokeColor=c;change.accept(edit.copy());})));
        number(a,body,"描边宽度 0–10",edit.format.strokeWidth,0,10,v->{edit.format.strokeWidth=v;change.accept(edit.copy());});
        choices(a,body,"字体",new String[]{"sans-serif","serif","monospace","sans-serif-condensed","sans-serif-medium","cursive"},edit.format.fontFamily,v->{edit.format.fontFamily=v;change.accept(edit.copy());});
        choices(a,body,"方向",new String[]{"横排","竖排"},Boolean.TRUE.equals(edit.vertical)?"竖排":"横排",v->{edit.vertical="竖排".equals(v);change.accept(edit.copy());});
        choices(a,body,"对齐",new String[]{"左","中","右"},new String[]{"左","中","右"}[edit.format.align],v->{edit.format.align="左".equals(v)?0:"右".equals(v)?2:1;change.accept(edit.copy());});
        decimal(a,body,"行间距倍数（0.5–3）",edit.format.lineSpacing,.5f,3,v->{edit.format.lineSpacing=v;change.accept(edit.copy());});
        decimal(a,body,"字间距像素（-4–30）",edit.format.letterSpacing,-4,30,v->{edit.format.letterSpacing=v;change.accept(edit.copy());});
        Rect box=edit.format.box==null?new Rect(bounds):new Rect(edit.format.box);
        decimal(a,body,"X",box.left,0,50000,v->{box.offsetTo(Math.round(v),box.top);edit.format.box=new Rect(box);change.accept(edit.copy());});
        decimal(a,body,"Y",box.top,0,50000,v->{box.offsetTo(box.left,Math.round(v));edit.format.box=new Rect(box);change.accept(edit.copy());});
        decimal(a,body,"宽度",box.width(),8,50000,v->{box.right=box.left+Math.round(v);edit.format.box=new Rect(box);change.accept(edit.copy());});
        decimal(a,body,"高度",box.height(),8,50000,v->{box.bottom=box.top+Math.round(v);edit.format.box=new Rect(box);change.accept(edit.copy());});
        decimal(a,body,"旋转角度",edit.format.rotation,-180,180,v->{edit.format.rotation=v;change.accept(edit.copy());});
        ScrollView scroll=new ScrollView(a);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(a).setTitle("文本样式 · 实时预览").setView(scroll).setPositiveButton("完成",(d,w)->change.accept(edit.copy())).setNegativeButton("取消",(d,w)->change.accept(original.copy())).setOnCancelListener(d->change.accept(original.copy())).create();dialog.show();
        dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,a.getResources().getDisplayMetrics().heightPixels/2);dialog.getWindow().setDimAmount(.06f);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    static void projectDefaults(Activity a,ComicProject p){if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+p.id)){Toast.makeText(a,"请先停止工程翻译",0).show();return;}show(a,p.defaultStyle,new Rect(0,0,200,100),edit->{p.defaultStyle=edit.copy();p.defaultStyle.format.box=null;p.defaultStyle.format.rotation=0;if(a instanceof ProjectReaderActivity)((ProjectReaderActivity)a).styleChanged();IO.execute(()->{try{p.save();}catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"保存失败："+e.getMessage(),1).show());}});});}
    private static void number(Activity a,LinearLayout body,String label,float value,int min,int max,Consumer<Float> change){
        body.addView(Ui.text(a,label,13,Ui.MUTED));LinearLayout row=new LinearLayout(a);SeekBar slider=new SeekBar(a);slider.setMax(max-min);slider.setProgress(Math.round(value)-min);EditText input=new EditText(a);input.setInputType(2);input.setSingleLine();input.setText(String.valueOf(Math.round(value)));row.addView(slider,new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));row.addView(input,new LinearLayout.LayoutParams(Ui.dp(a,64),Ui.dp(a,48)));body.addView(row);
        boolean[] binding={false};slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){if(user){binding[0]=true;input.setText(String.valueOf(p+min));binding[0]=false;change.accept((float)(p+min));}}});
        watch(input,()->{if(binding[0])return;try{int v=Integer.parseInt(input.getText().toString());if(v<min||v>max){input.setError(min+"–"+max);return;}slider.setProgress(v-min);change.accept((float)v);}catch(Exception ignored){}});
    }
    private static void decimal(Activity a,LinearLayout body,String label,float value,float min,float max,Consumer<Float> change){EditText field=new EditText(a);field.setHint(label);field.setSingleLine();field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(String.valueOf(value));body.addView(Ui.text(a,label,12,Ui.MUTED));body.addView(field);watch(field,()->{try{float v=Float.parseFloat(field.getText().toString());if(!Float.isFinite(v)||v<min||v>max){field.setError(min+"–"+max);return;}change.accept(v);}catch(Exception ignored){}});}
    private static void choices(Activity a,LinearLayout body,String label,String[] values,String selected,Consumer<String> change){body.addView(Ui.text(a,label,12,Ui.MUTED));Spinner spinner=new Spinner(a);spinner.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,values));for(int i=0;i<values.length;i++)if(values[i].equals(selected))spinner.setSelection(i);body.addView(spinner);final String[] last={selected};spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(!values[position].equals(last[0])){last[0]=values[position];change.accept(last[0]);}}});}
    private static void watch(EditText field,Runnable change){field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){change.run();}});}
    private static void color(Activity a,int initial,Consumer<Integer> change){
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(Ui.dp(a,16),0,Ui.dp(a,16),0);EditText hex=new EditText(a);hex.setSingleLine();hex.setText(String.format("#%08X",initial));body.addView(hex);int[] rgb={Color.red(initial),Color.green(initial),Color.blue(initial)};
        LinearLayout presets=new LinearLayout(a);for(int c:new int[]{Color.BLACK,Color.WHITE,Color.RED,Color.BLUE,0xff008040}){Button b=Ui.button(a,"●",Ui.TEXT,v->hex.setText(String.format("#%08X",c)));b.setTextColor(c);presets.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));}body.addView(presets);
        for(int i=0;i<3;i++){final int channel=i;body.addView(Ui.text(a,new String[]{"红","绿","蓝"}[i],12,Ui.MUTED));SeekBar s=new SeekBar(a);s.setMax(255);s.setProgress(rgb[i]);body.addView(s);s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){if(user){rgb[channel]=p;hex.setText(String.format("#%08X",Color.rgb(rgb[0],rgb[1],rgb[2])));}}});}
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("颜色").setView(body).setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{change.accept(Color.parseColor(hex.getText().toString().trim()));dialog.dismiss();}catch(Exception e){hex.setError("请输入 #RRGGBB 或 #AARRGGBB");}}));dialog.show();
    }
}
