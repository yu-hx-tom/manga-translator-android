package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Export options dialog + system pickers. The export itself runs in {@link ExportJob} on a snapshot of the
 * project re-read from disk, so editing can continue while it runs.
 */
final class ExportFlow {
    static final int PICK_FOLDER = 8101, CREATE_ARCHIVE = 8102;
    static final int TARGET_NEW_FOLDER = 0, TARGET_FOLDER = 1, TARGET_ZIP = 2, TARGET_CBZ = 3, TARGET_GALLERY = 4;
    private static final String PREFS = "workbench", LAST_TREE = "export_tree";

    private static ComicProject pendingProject;
    private static ExportJob.Options pendingOptions;
    private static int pendingTarget;
    private static Uri lastDestination;
    private static boolean lastArchive;

    private ExportFlow() {}

    /** Shows the options; {@code save} flushes the editor's unsaved changes before the snapshot is taken. */
    static void show(Activity activity,ComicProject project,Runnable save){
        if(ExportJob.running()){Toast.makeText(activity,"已有导出任务在进行，请等它完成",Toast.LENGTH_SHORT).show();return;}
        ExportJob.Options options=ExportJob.Options.from(project.exportOptions);int remembered=project.exportOptions.optInt("target",TARGET_NEW_FOLDER);int[] destination={remembered==TARGET_GALLERY?2:remembered==TARGET_ZIP||remembered==TARGET_CBZ?1:0};int[] folder={remembered==TARGET_FOLDER?1:0},archive={remembered==TARGET_CBZ?1:0};
        Ui.Sheet sheet=Ui.sheet(activity,"导出「"+project.title+"」");LinearLayout body=sheet.body;body.addView(Ui.text(activity,project.pages.size()+" 页 · 已校对 "+project.reviewedCount(),13,Ui.MUTED));sheet.group("保存到");
        LinearLayout targets=new LinearLayout(activity);body.addView(targets);String[] labels={"文件夹","压缩包","相册"};int[] icons={R.drawable.ic_folder_open,R.drawable.ic_archive,R.drawable.ic_photo_library};LinearLayout[] cards=new LinearLayout[3];
        Ui.Segmented folders=Ui.segmented(activity,new String[]{"新建文件夹","已有文件夹"},folder[0],value->folder[0]=value);Ui.Segmented archives=Ui.segmented(activity,new String[]{"ZIP","CBZ"},archive[0],value->archive[0]=value);
        TextView location=Ui.text(activity,"",12,Ui.MUTED);Runnable paintTargets=()->{for(int i=0;i<cards.length;i++)if(cards[i]!=null){cards[i].setSelected(destination[0]==i);cards[i].setBackground(Ui.round(activity,destination[0]==i?Ui.ACCENT_SOFT:Ui.SURFACE_SOFT,14));}folders.setVisibility(destination[0]==0?View.VISIBLE:View.GONE);archives.setVisibility(destination[0]==1?View.VISIBLE:View.GONE);location.setText(destination[0]==0?"选择保存位置后，可新建「"+ExportWriters.safe(project.title)+"」文件夹":destination[0]==1?"CBZ 可在漫画阅读器中直接打开":"保存到 Pictures/漫画翻译助手");};
        for(int i=0;i<3;i++){final int index=i;LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(android.view.Gravity.CENTER);card.setPadding(Ui.dp(activity,4),Ui.dp(activity,14),Ui.dp(activity,4),Ui.dp(activity,14));android.widget.ImageView icon=new android.widget.ImageView(activity);icon.setImageDrawable(Icons.icon(activity,icons[i],Ui.ACCENT));card.addView(icon,new LinearLayout.LayoutParams(Ui.dp(activity,24),Ui.dp(activity,24)));card.addView(Ui.text(activity,labels[i],14,Ui.INK));card.setContentDescription("保存到"+labels[i]);card.setOnClickListener(v->{destination[0]=index;paintTargets.run();});Ui.pressable(card);cards[i]=card;LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)cp.setMarginStart(Ui.dp(activity,8));targets.addView(card,cp);}
        body.addView(folders,Ui.margins(activity,0,8,0,0));body.addView(archives,Ui.margins(activity,0,8,0,0));body.addView(location,Ui.margins(activity,4,6,4,0));paintTargets.run();
        double[] averagePixels={Double.NaN};TextView estimate=Ui.text(activity,"估算体积中…",13,Ui.MUTED);sheet.footer.addView(estimate,Ui.margins(activity,0,0,0,8));Runnable refresh=()->{int count=selectedCount(project,options);double bytes=averagePixels[0]*count*(options.jpeg?.08+.12*options.quality/100d:options.webp?.06+.10*options.quality/100d:.65);estimate.setText(count+" 页 · "+(Double.isNaN(bytes)?"估算体积中…":"约 "+android.text.format.Formatter.formatShortFileSize(activity,Math.max(0,(long)bytes)))+"（粗略估计）");};
        sheet.group("格式");LinearLayout qualityBox=new LinearLayout(activity);qualityBox.setOrientation(LinearLayout.VERTICAL);TextView qualityLabel=Ui.text(activity,"质量："+options.quality,13,Ui.MUTED);qualityBox.addView(qualityLabel);android.widget.SeekBar quality=new android.widget.SeekBar(activity);quality.setContentDescription("JPG 或 WebP 图片质量");quality.setMax(99);quality.setProgress(options.quality-1);qualityBox.addView(quality,new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));quality.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar v){}public void onStopTrackingTouch(android.widget.SeekBar v){}public void onProgressChanged(android.widget.SeekBar v,int progress,boolean user){if(user){options.quality=progress+1;qualityLabel.setText("质量："+options.quality);refresh.run();}}});
        body.addView(Ui.segmented(activity,new String[]{"PNG","JPG","WebP"},options.webp?2:options.jpeg?1:0,value->{options.jpeg=value==1;options.webp=value==2;qualityBox.setVisibility(value==0?View.GONE:View.VISIBLE);refresh.run();}));qualityBox.setVisibility(!options.jpeg&&!options.webp?View.GONE:View.VISIBLE);body.addView(qualityBox);
        sheet.group("范围");int[] scope={options.selected.isEmpty()?(options.reviewedOnly?1:0):2};Ui.Segmented[] range={null};range[0]=Ui.segmented(activity,new String[]{"全部","已校对","自选"},scope[0],value->{if(value==2){choosePages(activity,project,options,()->{scope[0]=2;range[0].select(2);refresh.run();},()->range[0].select(scope[0]));}else{scope[0]=value;options.selected.clear();options.reviewedOnly=value==1;refresh.run();}});body.addView(range[0]);
        int original=0;for(ComicProject.Page page:project.pages)if(ComicProject.KIND_ORIGINAL.equals(page.kind))original++;
        CheckBox untranslated=check(activity,body,"包含未翻译的原图",options.includeUntranslated);untranslated.setVisibility(original>0?View.VISIBLE:View.GONE);untranslated.setOnCheckedChangeListener((v,value)->{options.includeUntranslated=value;refresh.run();});
        sheet.group("文件选项");body.addView(Ui.segmented(activity,new String[]{"顺序编号","原文件名"},options.keepNames?1:0,value->options.keepNames=value==1));CheckBox script=check(activity,body,"附带翻译稿（原文和译文）",options.script);script.setOnCheckedChangeListener((v,value)->options.script=value);
        sheet.footer.addView(Icons.iconTextButton(activity,R.drawable.ic_ios_share,"开始导出",Ui.PRIMARY,v->{if(selectedCount(project,options)==0){Toast.makeText(activity,"没有符合条件的页面，请调整范围",Toast.LENGTH_SHORT).show();return;}int target=destination[0]==2?TARGET_GALLERY:destination[0]==1?(archive[0]==1?TARGET_CBZ:TARGET_ZIP):(folder[0]==1?TARGET_FOLDER:TARGET_NEW_FOLDER);try{project.exportOptions=options.json().put("target",target);save.run();project.save();sheet.dismiss();begin(activity,project,options,target);}catch(Exception error){Toast.makeText(activity,"保存失败，未开始导出："+error.getMessage(),Toast.LENGTH_LONG).show();}}));
        refresh.run();sheet.show();new Thread(()->{double pixels=0;int samples=0;for(ComicProject.Page page:project.pages){java.io.File file=page.editable()?new java.io.File(project.draftDir(page),PageDraft.SOURCE):project.imageFile(page);if(file!=null&&file.isFile()){android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(file.getPath(),bounds);if(bounds.outWidth>0&&bounds.outHeight>0){pixels+=(double)bounds.outWidth*bounds.outHeight;samples++;}}if(samples>=8)break;}double average=samples==0?1200d*1800:pixels/samples;activity.runOnUiThread(()->{averagePixels[0]=average;if(sheet.isShowing()&&!activity.isDestroyed())refresh.run();});},"export-estimate").start();
    }
    private static int selectedCount(ComicProject project,ExportJob.Options options){int count=0;for(ComicProject.Page page:project.pages)if((options.selected.isEmpty()||options.selected.contains(page.id))&&(!options.reviewedOnly||page.reviewed)&&(options.includeUntranslated||!ComicProject.KIND_ORIGINAL.equals(page.kind)))count++;return count;}
    private static void choosePages(Activity activity,ComicProject project,ExportJob.Options options,Runnable applied,Runnable cancelled){
        boolean[] selected=new boolean[project.pages.size()];for(int i=0;i<selected.length;i++)selected[i]=options.selected.isEmpty()||options.selected.contains(project.pages.get(i).id);boolean[] committed={false};Ui.Sheet sheet=Ui.sheet(activity,"选择导出页");android.widget.GridView grid=PageGrid.create(activity,project,selected,index->selected[index]=!selected[index]);sheet.body.addView(grid,new LinearLayout.LayoutParams(-1,Ui.dp(activity,400)));sheet.footer.addView(Ui.button(activity,"应用选择",Ui.PRIMARY,v->{int count=0;for(boolean checked:selected)if(checked)count++;if(count==0){Toast.makeText(activity,"至少选择一页",Toast.LENGTH_SHORT).show();return;}options.selected.clear();for(int i=0;i<selected.length;i++)if(selected[i])options.selected.add(project.pages.get(i).id);options.reviewedOnly=false;committed[0]=true;sheet.dismiss();applied.run();}));sheet.setOnDismissListener(d->{if(!committed[0])cancelled.run();});sheet.show();
    }

    private static void begin(Activity activity, ComicProject project, ExportJob.Options options, int target) {
        lastDestination=null;lastArchive=target==TARGET_ZIP||target==TARGET_CBZ;
        pendingProject = project; pendingOptions = options; pendingTarget = target;
        if (target == TARGET_GALLERY) { start(activity, w -> new ExportWriters.GalleryWriter(w, project.title)); return; }
        if (target == TARGET_ZIP || target == TARGET_CBZ) {
            boolean cbz = target == TARGET_CBZ;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(cbz ? "application/octet-stream" : "application/zip")
                    .putExtra(Intent.EXTRA_TITLE, ExportWriters.safe(project.title) + (cbz ? ".cbz" : ".zip"));
            launch(activity, intent, CREATE_ARCHIVE);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        String last = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_TREE, null);
        if (last != null) try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(last)); } catch (Exception ignored) {}
        launch(activity, intent, PICK_FOLDER);
    }

    private static void launch(Activity activity, Intent intent, int request) {
        try { activity.startActivityForResult(intent, request); }
        catch (Exception missing) { Toast.makeText(activity, "本机没有可用的文件选择器，可改为导出到相册", Toast.LENGTH_LONG).show(); }
    }

    /** Call from the host activity's onActivityResult; returns true when the result belonged to the export. */
    static boolean onActivityResult(Activity activity, int request, int result, Intent data) {
        if (request != PICK_FOLDER && request != CREATE_ARCHIVE) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || pendingProject == null) return true;
        Uri uri = data.getData();
        lastDestination=uri;
        ComicProject project = pendingProject;
        if (request == PICK_FOLDER) {
            try { activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_TREE, uri.toString()).apply();
            boolean create = pendingTarget == TARGET_NEW_FOLDER;
            start(activity, w -> new ExportWriters.FolderWriter(w, uri, create ? project.title : null, false));
        } else {
            boolean cbz = pendingTarget == TARGET_CBZ;
            String name = ExportWriters.safe(project.title) + (cbz ? ".cbz" : ".zip");
            start(activity, w -> new ExportWriters.ZipWriter(w, uri, (cbz ? "漫画包「" : "压缩包「") + name + "」", cbz));
        }
        return true;
    }

    private interface WriterFactory { ExportWriters.Writer create(Context context) throws Exception; }
    static void openResult(Activity activity){
        if(lastDestination==null){Toast.makeText(activity,"可在相册或所选文件夹查看导出结果",0).show();return;}
        new AlertDialog.Builder(activity).setTitle("导出结果").setItems(lastArchive?new String[]{"打开文件","分享"}:new String[]{"打开所在文件夹"},(d,w)->{
            try{Intent intent;if(w==1){intent=new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,lastDestination);intent.setClipData(android.content.ClipData.newRawUri("漫画",lastDestination));}
                else intent=new Intent(Intent.ACTION_VIEW).setDataAndType(lastDestination,lastArchive?"application/zip":DocumentsContract.Document.MIME_TYPE_DIR);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(Intent.createChooser(intent,w==1?"分享漫画":"打开导出结果"));
            }catch(Exception e){Toast.makeText(activity,"没有可打开此位置的应用，请使用文件管理器",1).show();}
        }).show();
    }

    /** Opens the destination and the project snapshot off the main thread, then hands both to ExportJob. */
    private static void start(Activity activity, WriterFactory factory) {
        ComicProject project = pendingProject; ExportJob.Options options = pendingOptions;
        pendingProject = null; pendingOptions = null;
        Context app = activity.getApplicationContext();
        Toast.makeText(activity, "开始导出，可继续编辑", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error;
            try {
                ComicProject snapshot = ComicProject.load(project.dir);
                ExportWriters.Writer writer = factory.create(app);
                error = ExportJob.start(app, snapshot, options, writer);
            } catch (Exception failure) { error = failure.getMessage() == null ? "无法开始导出" : failure.getMessage(); }
            if (error != null) { String message = error; activity.runOnUiThread(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show()); }
        }, "comic-export-open").start();
    }

    private static CheckBox check(Context context, LinearLayout parent, String label, boolean value) {
        CheckBox box = new CheckBox(context); box.setText(label); box.setTextSize(14); box.setChecked(value);
        parent.addView(box, Ui.margins(context, 0, 4, 0, 0));
        return box;
    }
}
