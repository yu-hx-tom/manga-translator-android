package cn.local.manga;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Project imports are the primary path; existing folder-mode records remain accessible. */
public final class LocalLibraryActivity extends ShellActivity {
    private LinearLayout recentList, runningCard;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean active, legacyExpanded;
    private int generation;
    private final java.util.Map<String, ComicProject> localProjects = new java.util.HashMap<>();
    private final Runnable tick =
            new Runnable() {
                public void run() {
                    if (!active) return;
                    refreshRunning();
                    handler.postDelayed(this, 800);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.addView(
                Ui.appBar(
                        this,
                        null,
                        "本地翻译",
                        null,
                        Icons.iconButton(
                                this, R.drawable.ic_more_vert, "设置与翻译日志", this::settings)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout imports = new LinearLayout(this);
        imports.setBackground(Ui.card(this, 18));
        int[] icons = {
            R.drawable.ic_folder_open, R.drawable.ic_photo_library, R.drawable.ic_archive
        };
        String[] names = {"文件夹", "图片", "压缩包"};
        for (int i = 0; i < 3; i++) {
            final int choice = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(4), dp(14), dp(4), dp(14));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(Icons.icon(this, icons[i], Ui.ACCENT));
            card.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView text = Ui.text(this, "导入\n" + names[i], 14, Ui.INK);
            text.setGravity(Gravity.CENTER);
            card.addView(text, Ui.margins(this, 0, 8, 0, 0));
            card.setContentDescription("导入" + names[i]);
            card.setBackground(Ui.ripple(null, Ui.round(this, Ui.SURFACE, 18), Ui.RIPPLE));
            card.setOnClickListener(v -> LocalImport.pick(this, choice));
            Ui.pressable(card);
            imports.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        }
        page.addView(imports);
        runningCard = new LinearLayout(this);
        runningCard.setOrientation(LinearLayout.VERTICAL);
        page.addView(runningCard, Ui.margins(this, 0, 16, 0, 0));
        page.addView(Ui.heading(this, "我的本地漫画", 16), Ui.margins(this, 4, 18, 0, 8));
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        page.addView(recentList);
        setContentView(root);
    }

    @Override
    void settings(View anchor) {
        Ui.Sheet sheet = Ui.sheet(this, "本地翻译");
        sheet.item(
                R.drawable.ic_settings,
                "设置",
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        sheet.item(
                R.drawable.ic_receipt_long,
                "翻译日志",
                () -> startActivity(new Intent(this, LogsActivity.class)));
        sheet.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        handler.removeCallbacks(tick);
        tick.run();
        showRecents();
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        generation++;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        LocalImport.result(this, request, result, data);
    }

    private void refreshRunning() {
        runningCard.removeAllViews();
        String owner = TranslationTaskManager.owner;
        if (!TranslationTaskManager.running()
                || !(owner.startsWith("project:") || LocalBatch.activeFolder() != null)) {
            runningCard.setVisibility(View.GONE);
            return;
        }
        runningCard.setVisibility(View.VISIBLE);
        runningCard.setBackground(Ui.card(this, 16));
        runningCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        runningCard.addView(Ui.chip(this, "正在翻译", R.drawable.ic_translate, Ui.INFO));
        ComicProject running =
                owner.startsWith("project:") ? localProjects.get(owner.substring(8)) : null;
        if (running != null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ProjectCover.bind(cover, ProjectStore.cover(running));
            row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(64)));
            TextView name = Ui.heading(this, running.title, 14);
            name.setMaxLines(2);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
            np.setMarginStart(dp(12));
            row.addView(name, np);
            runningCard.addView(row, Ui.margins(this, 0, 6, 0, 6));
        }
        TextView status = Ui.text(this, TranslationTaskManager.detail, 13, Ui.INK);
        status.setMaxLines(2);
        runningCard.addView(status);
        ProgressBar bar = Ui.progressLine(this);
        int[] counts = TranslationTaskManager.counts;
        bar.setIndeterminate(counts[3] <= 0);
        if (counts[3] > 0) bar.setProgress((counts[0] + counts[1]) * 1000 / Math.max(1, counts[3]));
        runningCard.addView(bar);
        if (owner.startsWith("project:")) {
            String id = owner.substring(8);
            runningCard.setOnClickListener(
                    v -> startActivity(ProjectReaderActivity.intent(this, id, 1)));
        } else
            runningCard.setOnClickListener(
                    v -> {
                        if (LocalBatch.activeFolder() != null) open(LocalBatch.activeFolder());
                    });
    }

    private void showRecents() {
        int token = ++generation;
        io.execute(
                () -> {
                    List<ComicProject> projects = ProjectStore.list(this);
                    List<LocalComics.Recent> old = LocalComics.recents(this);
                    runOnUiThread(
                            () -> {
                                if (isDestroyed() || token != generation) return;
                                recentList.removeAllViews();
                                localProjects.clear();
                                int count = 0;
                                for (ComicProject p : projects)
                                    if ("local".equals(p.sourceKind)) {
                                        localProjects.put(p.id, p);
                                        count++;
                                        recentList.addView(
                                                projectRow(p), Ui.margins(this, 0, 0, 0, 10));
                                    }
                                if (count == 0)
                                    recentList.addView(
                                            Ui.text(
                                                    this,
                                                    "导入文件夹、图片或压缩包，开始翻译并保留编辑记录。",
                                                    14,
                                                    Ui.MUTED));
                                if (!old.isEmpty()) {
                                    Button fold =
                                            Icons.iconTextButton(
                                                    this,
                                                    legacyExpanded
                                                            ? R.drawable.ic_expand_less
                                                            : R.drawable.ic_expand_more,
                                                    "旧版文件夹（" + old.size() + "）",
                                                    Ui.TEXT,
                                                    null);
                                    recentList.addView(fold);
                                    LinearLayout legacy = new LinearLayout(this);
                                    legacy.setOrientation(LinearLayout.VERTICAL);
                                    legacy.setVisibility(legacyExpanded ? View.VISIBLE : View.GONE);
                                    recentList.addView(legacy);
                                    fold.setOnClickListener(
                                            v -> {
                                                legacyExpanded = !legacyExpanded;
                                                legacy.setVisibility(
                                                        legacyExpanded ? View.VISIBLE : View.GONE);
                                                Icons.setIcon(
                                                        fold,
                                                        legacyExpanded
                                                                ? R.drawable.ic_expand_less
                                                                : R.drawable.ic_expand_more,
                                                        Ui.ACCENT,
                                                        18);
                                            });
                                    for (LocalComics.Recent r : old) {
                                        View row =
                                                Ui.menuRow(
                                                        this,
                                                        R.drawable.ic_folder,
                                                        r.folder.name,
                                                        "旧版");
                                        row.setOnClickListener(v -> open(r.folder));
                                        row.setOnLongClickListener(
                                                v -> {
                                                    Ui.Sheet s = Ui.sheet(this, r.folder.name);
                                                    s.item(
                                                            R.drawable.ic_delete,
                                                            "从列表移除",
                                                            () -> {
                                                                LocalComics.forget(this, r.folder);
                                                                showRecents();
                                                            });
                                                    s.show();
                                                    return true;
                                                });
                                        legacy.addView(row);
                                    }
                                }
                            });
                });
    }

    private View projectRow(ComicProject p) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), 0, dp(10));
        row.setBackground(Ui.card(this, 16));
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.roundClip(cover, 8);
        ProjectCover.bind(cover, ProjectStore.cover(p));
        row.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(88)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.heading(this, p.title, 15);
        title.setMaxLines(2);
        words.addView(title);
        int translated = 0;
        for (ComicProject.Page page : p.pages)
            if (page.editable() || "translated".equals(page.status)) translated++;
        words.addView(Ui.text(this, p.pages.size() + " 页 · 已翻 " + translated, 12, Ui.MUTED));
        words.addView(
                Ui.text(
                        this,
                        android.text.format.DateUtils.getRelativeTimeSpanString(p.updated),
                        12,
                        Ui.MUTED));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
        wp.setMarginStart(dp(10));
        row.addView(words, wp);
        row.addView(
                Icons.iconButton(
                        this,
                        R.drawable.ic_more_vert,
                        "管理 " + p.title,
                        v -> {
                            Ui.Sheet s = Ui.sheet(this, p.title);
                            s.item(
                                    R.drawable.ic_book,
                                    "继续阅读",
                                    () ->
                                            startActivity(
                                                    ProjectReaderActivity.intent(this, p.id, 1)));
                            s.item(
                                    R.drawable.ic_edit_note,
                                    "在汉化工具中打开",
                                    () ->
                                            startActivity(
                                                    ProjectReaderActivity.intent(this, p.id, 2)));
                            s.show();
                        }),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.setOnClickListener(v -> startActivity(ProjectReaderActivity.intent(this, p.id, 1)));
        return row;
    }

    private void open(LocalComics.Folder folder) {
        LocalComics.remember(this, folder);
        startActivity(LocalFolderActivity.intent(this, folder));
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
