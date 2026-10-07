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
    static void show(Activity activity, ComicProject project, Runnable save) {
        if (ExportJob.running()) { Toast.makeText(activity, "已有导出任务在进行，请等它完成", Toast.LENGTH_SHORT).show(); return; }
        ExportJob.Options options = ExportJob.Options.from(project.exportOptions);
        int target = project.exportOptions.optInt("target", TARGET_NEW_FOLDER);
        int editable = 0, rendered = 0, original = 0;
        for (ComicProject.Page page : project.pages) {
            if (page.editable()) editable++; else if (ComicProject.KIND_ORIGINAL.equals(page.kind)) original++; else rendered++;
        }
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 20); body.setPadding(pad, Ui.dp(activity, 4), pad, 0);
        body.addView(Ui.text(activity, "共 " + project.pages.size() + " 页 · 可编辑 " + editable + (rendered > 0 ? " · 成品图 " + rendered : "")
                + (original > 0 ? " · 未翻译 " + original : "") + " · 已校对 " + project.reviewedCount(), 13, Ui.MUTED));
        RadioGroup targets = group(activity, body, "保存到", target, "新建文件夹（在所选位置下创建「" + ExportWriters.safe(project.title) + "」）",
                "已有文件夹", "ZIP 压缩包", "CBZ 漫画包（漫画阅读器可直接打开）", "相册（Pictures/漫画翻译助手）");
        RadioGroup format = group(activity, body, "图片格式", options.webp?2:options.jpeg ? 1 : 0, "PNG（无损，推荐）", "JPEG（体积更小）","WebP");
        TextView qualityLabel=Ui.text(activity,"JPG / WebP 质量："+options.quality,13,Ui.MUTED);body.addView(qualityLabel);android.widget.SeekBar quality=new android.widget.SeekBar(activity);quality.setMax(99);quality.setProgress(options.quality-1);body.addView(quality);
        quality.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar s){}public void onStopTrackingTouch(android.widget.SeekBar s){}public void onProgressChanged(android.widget.SeekBar s,int p,boolean user){qualityLabel.setText("JPG / WebP 质量："+(p+1));}});
        android.widget.Button range=Ui.button(activity,options.selected.isEmpty()?"范围：全部页":"范围：已选择 "+options.selected.size()+" 页",Ui.TONAL,v->{
            String[] labels=new String[project.pages.size()];boolean[] chosen=new boolean[labels.length];for(int i=0;i<labels.length;i++){labels[i]=(i+1)+" · "+project.pages.get(i).label;chosen[i]=options.selected.isEmpty()||options.selected.contains(project.pages.get(i).id);}
            new AlertDialog.Builder(activity).setTitle("选择导出页").setMultiChoiceItems(labels,chosen,(d,n,b)->chosen[n]=b).setNegativeButton("取消",null).setNeutralButton("全部页",(d,w)->{options.selected.clear();((android.widget.Button)v).setText("范围：全部页");}).setPositiveButton("应用",(d,w)->{int count=0;for(boolean b:chosen)if(b)count++;if(count==0){Toast.makeText(activity,"至少选择一页",0).show();return;}options.selected.clear();for(int i=0;i<chosen.length;i++)if(chosen[i])options.selected.add(project.pages.get(i).id);((android.widget.Button)v).setText("范围：已选择 "+count+" 页");}).show();
        });body.addView(range);
        RadioGroup naming = group(activity, body, "文件命名", options.keepNames ? 1 : 0, "按顺序编号 001、002…", "保留原文件名");
        CheckBox reviewed = check(activity, body, "只导出已标记“已校对”的页", options.reviewedOnly);
        CheckBox untranslated = check(activity, body, "包含未翻译的原图", options.includeUntranslated);
        CheckBox script = check(activity, body, "附带翻译稿（翻译稿.txt，含每段原文与译文）", options.script);
        untranslated.setVisibility(original > 0 ? View.VISIBLE : View.GONE);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        new AlertDialog.Builder(activity).setTitle("导出「" + project.title + "」").setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导出", (d, w) -> {
                    options.jpeg = format.getCheckedRadioButtonId() == 1;
                    options.webp=format.getCheckedRadioButtonId()==2;options.quality=quality.getProgress()+1;
                    options.keepNames = naming.getCheckedRadioButtonId() == 1;
                    options.reviewedOnly = reviewed.isChecked();
                    options.includeUntranslated = untranslated.isChecked();
                    options.script = script.isChecked();
                    int chosen = Math.max(0, targets.getCheckedRadioButtonId());
                    try { project.exportOptions = options.json().put("target", chosen); } catch (Exception ignored) {}
                    try{save.run();project.save();begin(activity, project, options, chosen);}
                    catch(Exception error){Toast.makeText(activity,"保存失败，未开始导出："+error.getMessage(),Toast.LENGTH_LONG).show();}
                }).show();
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

    private static RadioGroup group(Context context, LinearLayout parent, String title, int checked, String... labels) {
        TextView heading = Ui.text(context, title, 14, 0xff3C4657);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        parent.addView(heading, Ui.margins(context, 0, 14, 0, 2));
        RadioGroup group = new RadioGroup(context);
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(context); button.setId(i); button.setText(labels[i]); button.setTextSize(14);
            group.addView(button);
        }
        group.check(Math.min(Math.max(0, checked), labels.length - 1));
        parent.addView(group);
        return group;
    }
    private static CheckBox check(Context context, LinearLayout parent, String label, boolean value) {
        CheckBox box = new CheckBox(context); box.setText(label); box.setTextSize(14); box.setChecked(value);
        parent.addView(box, Ui.margins(context, 0, 4, 0, 0));
        return box;
    }
}
