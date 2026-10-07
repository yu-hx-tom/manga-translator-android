package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Entry for the local-folder mode: pick an extracted comic folder, or reopen a recent one. */
public final class LocalLibraryActivity extends ShellActivity {
    private static final int PICK_FOLDER = 71;
    private LinearLayout recentList, runningCard;
    private TextView runningText;
    private final LocalBatch.Listener batchChanged = this::refreshRunning;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.addView(Ui.appBar(this,null,"本地翻译",null,Icons.iconButton(this,R.drawable.ic_more_vert,"页面选项",this::settings)));
        ScrollView scroll = new ScrollView(this); scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 24));
        Ui.smoothLayout(page);
        scroll.addView(page); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        Button more=Ui.button(this,"⋮ 设置",Ui.TEXT,v->startActivity(new Intent(this,SettingsActivity.class))); page.addView(more);
        LinearLayout hero = Ui.section(this, page, "选择漫画文件夹",
                "适合下载并解压好的漫画：选中装有图片的文件夹即可逐页翻译；含多个章节子文件夹时可逐个进入。\n"
                        + "译图保存到该文件夹下的「" + LocalComics.OUTPUT_DIR + "」子文件夹，原图不会被修改。", 8);
        TextView icon = Ui.text(this, "📂", 34, Ui.INK); icon.setGravity(Gravity.CENTER);
        hero.addView(icon, 0, Ui.margins(this, 0, 0, 0, 6));
        hero.addView(Ui.button(this, "＋  选择文件夹", Ui.PRIMARY, v -> LocalImport.pick(this,0)), Ui.margins(this, 0, 12, 0, 0));
        hero.addView(Ui.button(this, "✍️  我的汉化工程（编辑与导出）", Ui.TONAL, v -> startActivity(new android.content.Intent(this, ProjectListActivity.class))), Ui.margins(this, 0, 8, 0, 0));

        runningCard = Ui.section(this, page, "正在翻译", null, 14);
        runningText = new Ui.StatusText(this); runningText.setTextSize(13); runningText.setTextColor(0xff3C4657);
        runningCard.addView(runningText, Ui.margins(this, 0, 4, 0, 0));
        runningCard.addView(Ui.button(this, "查看进度", Ui.TONAL, v -> {
            LocalComics.Folder folder = LocalBatch.activeFolder();
            if (folder != null) open(folder);
        }), Ui.margins(this, 0, 8, 0, 0));
        runningCard.setVisibility(View.GONE);

        hero.addView(Ui.button(this,"选择图片（可多选）",Ui.TONAL,v->LocalImport.pick(this,1)));hero.addView(Ui.button(this,"选择压缩包（zip/cbz）",Ui.TONAL,v->LocalImport.pick(this,2)));
        TextView recentTitle = Ui.heading(this, "最近打开", 15);
        page.addView(recentTitle, Ui.margins(this, 4, 18, 0, 6));
        recentList = new LinearLayout(this); recentList.setOrientation(LinearLayout.VERTICAL); Ui.smoothLayout(recentList);
        page.addView(recentList);
        Ui.enter(page, 40);
    }

    @Override protected void onResume() {
        super.onResume();
        LocalBatch.listen(batchChanged);
        refreshRunning();
        showRecents();
    }
    @Override protected void onPause() { LocalBatch.unlisten(batchChanged); super.onPause(); }

    private void refreshRunning() {
        LocalComics.Folder folder = LocalBatch.activeFolder();
        runningCard.setVisibility(folder == null ? View.GONE : View.VISIBLE);
        if (folder != null) runningText.setText("「" + folder.name + "」\n" + LocalBatch.progressLine(folder));
    }

    private void showRecents() {
        recentList.removeAllViews();
        for(ComicProject p:ProjectStore.list(this))if("local".equals(p.sourceKind)){Button entry=Ui.button(this,p.title+" · "+p.pages.size()+" 页",Ui.TONAL,v->startActivity(ProjectReaderActivity.intent(this,p.id,1)));recentList.addView(entry);}
        List<LocalComics.Recent> recents = LocalComics.recents(this);
        if (recents.isEmpty()) {
            TextView empty = Ui.text(this, "还没有打开过本地文件夹。选好文件夹后会出现在这里，下次点一下即可继续。", 13, Ui.MUTED);
            empty.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
            recentList.addView(empty);
            return;
        }
        int index = 0;
        for (LocalComics.Recent recent : recents) {
            View row = recentRow(recent);
            recentList.addView(row, Ui.margins(this, 0, 0, 0, 8));
            if (Ui.motion()) {
                row.setAlpha(0f); row.setTranslationY(Ui.dp(this, 10));
                row.animate().alpha(1f).translationY(0f).setStartDelay(60L + Math.min(index++, 8) * 40L).setDuration(300).setInterpolator(Ui.EASE).start();
            }
        }
    }

    private View recentRow(LocalComics.Recent recent) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        row.setBackground(Ui.ripple(Ui.card(this, 16), Ui.round(this, 0xffFFFFFF, 16), 0x241A73E8));
        TextView badge = Ui.text(this, "📁", 18, Ui.INK); badge.setGravity(Gravity.CENTER); badge.setBackground(Ui.round(this, 0xffEDF2FA, 12));
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, recent.folder.name, 15, Ui.INK); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        String when = recent.opened > 0 ? DateUtils.getRelativeTimeSpanString(recent.opened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "";
        TextView detail = Ui.text(this, (LocalBatch.isActive(recent.folder) ? "正在翻译 · " : "") + "上次打开 " + when, 12, 0xff8790A0);
        words.addView(name); words.addView(detail, Ui.margins(this, 0, 3, 0, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = Ui.dp(this, 12);
        row.addView(words, wordsParams);
        row.addView(Ui.text(this, "›", 20, 0xffA8B2C2));
        Ui.pressable(row);
        row.setOnClickListener(v -> open(recent.folder));
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle(recent.folder.name).setItems(new String[]{"从列表移除"}, (d, which) -> {
                LocalComics.forget(this, recent.folder); showRecents();
            }).show();
            return true;
        });
        return row;
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_FOLDER); }
        catch (Exception missing) { Toast.makeText(this, "本机没有可用的文件夹选择器", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if(LocalImport.result(this,request,result,data))return;
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(tree, flags); } catch (Exception ignored) { /* still usable this session */ }
        String id = DocumentsContract.getTreeDocumentId(tree);
        String name = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = "所选文件夹";
        LocalComics.Folder folder = new LocalComics.Folder(tree, id, name);
        open(folder);
    }

    private void open(LocalComics.Folder folder) {
        if (!LocalComics.granted(this, folder.tree) && !LocalBatch.isActive(folder)) {
            // Session-only grants (when persisting failed) still work until the app restarts.
            try { LocalComics.list(this, folder); }
            catch (Exception lost) {
                LocalComics.forget(this, folder); showRecents();
                Toast.makeText(this, "该文件夹的访问权限已失效，请重新选择", Toast.LENGTH_LONG).show();
                return;
            }
        }
        LocalComics.remember(this, folder);
        startActivity(LocalFolderActivity.intent(this, folder));
    }
}
