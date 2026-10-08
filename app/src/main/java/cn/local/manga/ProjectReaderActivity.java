package cn.local.manga;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.concurrent.*;

/**
 * Continuous reader, using ListView recycling and bounded decoding rather than fifty full bitmaps.
 */
public class ProjectReaderActivity extends ShellActivity {
    static Intent intent(Context c, String id, int tab) {
        return new Intent(
                        c,
                        tab == 1 ? LocalProjectReaderActivity.class : ProjectReaderActivity.class)
                .putExtra("project", id)
                .putExtra("shellTab", tab);
    }

    private ComicProject project;
    private ListView list;
    private TextView title, pageNumber, subtitle;
    private QuickPageRail rail;
    private boolean original;
    private volatile int revision;
    private final android.util.LruCache<String, Bitmap> thumbnails =
            new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Pages adapter = new Pages();
    private final Runnable taskUi =
            () -> {
                if (!isDestroyed()) adapter.notifyDataSetChanged();
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar =
                Ui.appBar(
                        this,
                        Icons.iconButton(this, R.drawable.ic_arrow_back, "返回", v -> finish()),
                        "漫画阅读",
                        "读取中…",
                        Icons.iconButton(
                                this,
                                R.drawable.ic_ios_share,
                                "导出工程",
                                v -> {
                                    if (project != null) ExportFlow.show(this, project, () -> {});
                                }),
                        Icons.iconButton(this, R.drawable.ic_more_vert, "阅读选项", this::menu));
        title = bar.findViewWithTag("app-bar-title");
        subtitle = bar.findViewWithTag("app-bar-subtitle");
        title.setOnClickListener(v -> rename());
        root.addView(bar);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        pageNumber = Ui.text(this, "0 / 0", 13, Ui.MUTED);
        pageNumber.setGravity(Gravity.CENTER);
        pageNumber.setMinHeight(Ui.dp(this, 48));
        pageNumber.setContentDescription("页码，点击跳转");
        pageNumber.setOnClickListener(v -> jump());
        toolbar.addView(pageNumber, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        toolbar.addView(
                Ui.segmented(
                        this,
                        new String[] {"译图", "原图"},
                        0,
                        selected -> {
                            original = selected == 1;
                            revision++;
                            adapter.notifyDataSetChanged();
                        }),
                new LinearLayout.LayoutParams(0, -2, 2));
        root.addView(toolbar);
        list = new ListView(this);
        list.setDividerHeight(Ui.dp(this, 6));
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (p, v, pos, id) -> {
                    if (project == null) return;
                    if (TranslationTaskManager.running()
                            && TranslationTaskManager.owner.equals("project:" + project.id)) {
                        Toast.makeText(
                                        this,
                                        "请先停止工程翻译再编辑，已完成页会保留",
                                        android.widget.Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    startActivity(
                            WorkbenchActivity.intent(this, project.id, pos)
                                    .putExtra("shellTab", tab));
                });
        list.setOnItemLongClickListener(
                (p, v, pos, id) -> {
                    pageMenu(pos);
                    return true;
                });
        list.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    public void onScrollStateChanged(AbsListView v, int s) {}

                    public void onScroll(AbsListView v, int first, int count, int total) {
                        pageNumber.setText(
                                pageNumber
                                        .getContext()
                                        .getString(
                                                R.string.project_reader_activity_message_15,
                                                (total == 0 ? 0 : first + 1),
                                                total));
                        if (rail != null) rail.update(first, total);
                    }
                });
        LinearLayout reader = new LinearLayout(this);
        reader.addView(list, new LinearLayout.LayoutParams(0, -1, 1));
        rail = new QuickPageRail(this, index -> list.setSelection(index));
        reader.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 48), -1));
        root.addView(reader, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TranslationTaskManager.listen(taskUi);
        reload();
    }

    @Override
    protected void onPause() {
        TranslationTaskManager.unlisten(taskUi);
        super.onPause();
    }

    void styleChanged() {
        revision++;
        adapter.notifyDataSetChanged();
    }

    private void reload() {
        images.execute(
                () -> {
                    try {
                        ComicProject loaded =
                                ProjectStore.open(this, getIntent().getStringExtra("project"));
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed()) return;
                                    project = loaded;
                                    title.setText(project.title);
                                    subtitle.setText(
                                            subtitle.getContext()
                                                    .getString(
                                                            R.string
                                                                    .project_reader_activity_message_16,
                                                            project.pages.size(),
                                                            project.pages.stream()
                                                                    .filter(
                                                                            p ->
                                                                                    p.editable()
                                                                                            || "translated"
                                                                                                    .equals(
                                                                                                            p.status))
                                                                    .count()));
                                    revision++;
                                    adapter.notifyDataSetChanged();
                                    refreshTask();
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        this,
                                                        "工程读取失败：" + e.getMessage(),
                                                        android.widget.Toast.LENGTH_LONG)
                                                .show());
                    }
                });
    }

    protected String translationDisabled() {
        return project == null || project.pages.isEmpty() ? "请先导入漫画" : null;
    }

    protected void startTranslation() {
        ProjectTranslation.start(this, project, -1, this::reload);
    }

    private void menu(View anchor) {
        Ui.Sheet sheet = Ui.sheet(this, "阅读选项");
        sheet.item(
                R.drawable.ic_settings,
                "设置",
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        sheet.item(R.drawable.ic_error, "查看失败页", this::showFailedPages);
        if (tab == 1)
            sheet.item(
                    R.drawable.ic_edit_note,
                    "在汉化工具中打开",
                    null,
                    project != null,
                    () -> startActivity(intent(this, project.id, 2)));
        sheet.item(
                R.drawable.ic_palette,
                "工程默认样式",
                null,
                project != null,
                () -> StylePanel.projectDefaults(this, project));
        sheet.show();
    }

    @Override
    protected void showFailedPages() {
        if (project == null) return;
        Ui.Sheet sheet = Ui.sheet(this, "失败页");
        int count = 0;
        for (int i = 0; i < project.pages.size(); i++)
            if ("failed".equals(project.pages.get(i).status)) {
                final int index = i;
                count++;
                sheet.item(
                        R.drawable.ic_error,
                        "第 " + (i + 1) + " 页",
                        () -> {
                            list.setSelection(index);
                            retry(index);
                        });
            }
        if (count == 0) sheet.body.addView(Ui.text(this, "没有失败页", 14, Ui.MUTED));
        sheet.show();
    }

    private void retry(int index) {
        if (project == null || TranslationTaskManager.running()) return;
        new AlertDialog.Builder(this)
                .setTitle("重试这一页")
                .setMessage("重新翻译会覆盖本页文字修改，继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "重试",
                        (d, w) -> ProjectTranslation.start(this, project, index, this::reload))
                .show();
    }

    private void rename() {
        if (project == null) return;
        EditText text = new EditText(this);
        text.setSingleLine(true);
        text.setText(project.title);
        new AlertDialog.Builder(this)
                .setTitle("重命名工程")
                .setView(text)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            String value = text.getText().toString().trim();
                            if (!value.isEmpty()) {
                                project.title = value;
                                title.setText(value);
                                save();
                            }
                        })
                .show();
    }

    private void jump() {
        if (project == null) return;
        EditText input = new EditText(this);
        input.setInputType(2);
        input.setHint("页码 1–" + project.pages.size());
        new AlertDialog.Builder(this)
                .setTitle("跳转到页")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "前往",
                        (d, w) -> {
                            try {
                                list.setSelection(
                                        Math.max(
                                                0,
                                                Math.min(
                                                        project.pages.size() - 1,
                                                        Integer.parseInt(input.getText().toString())
                                                                - 1)));
                            } catch (Exception e) {
                                Toast.makeText(this, "请输入页码", android.widget.Toast.LENGTH_SHORT)
                                        .show();
                            }
                        })
                .show();
    }

    private void pageMenu(int index) {
        if (project == null) return;
        ComicProject.Page page = project.pages.get(index);
        Ui.Sheet sheet = Ui.sheet(this, "第 " + (index + 1) + " 页");
        boolean enabled = !TranslationTaskManager.running();
        String reason = enabled ? null : "请先停止翻译";
        sheet.item(R.drawable.ic_restart, "重新翻译该页", reason, enabled, () -> retry(index));
        sheet.item(
                R.drawable.ic_delete,
                "删除该页",
                reason,
                enabled,
                () ->
                        new AlertDialog.Builder(this)
                                .setMessage("删除本页及编辑？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton(
                                        "删除",
                                        (d, w) -> {
                                            project.pages.remove(page);
                                            save();
                                            adapter.notifyDataSetChanged();
                                        })
                                .show());
        sheet.item(
                R.drawable.ic_image,
                "设为封面",
                reason,
                enabled,
                () -> {
                    project.coverPageId = page.id;
                    save();
                    images.execute(() -> ProjectStore.writeCover(project));
                });
        sheet.show();
    }

    private void save() {
        ComicProject target = project;
        images.execute(
                () -> {
                    try {
                        target.save();
                    } catch (Exception e) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        this,
                                                        "保存失败：" + e.getMessage(),
                                                        android.widget.Toast.LENGTH_LONG)
                                                .show());
                    }
                });
    }

    @Override
    protected void onActivityResult(int r, int s, Intent d) {
        super.onActivityResult(r, s, d);
        ExportFlow.onActivityResult(this, r, s, d);
    }

    @Override
    protected void onDestroy() {
        revision++;
        thumbnails.evictAll();
        images.shutdownNow();
        super.onDestroy();
    }

    private final class Pages extends BaseAdapter {
        public int getCount() {
            return project == null ? 0 : project.pages.size();
        }

        public Object getItem(int p) {
            return project.pages.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int position, View recycled, ViewGroup parent) {
            FrameLayout row =
                    recycled instanceof FrameLayout
                            ? (FrameLayout) recycled
                            : new FrameLayout(ProjectReaderActivity.this);
            if (row.getChildCount() == 0) {
                ImageView image = new ImageView(ProjectReaderActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setAdjustViewBounds(true);
                row.addView(image, new FrameLayout.LayoutParams(-1, -2));
            }
            if (row.getChildCount() > 1) {
                TextView old = (TextView) row.getChildAt(1);
                for (android.graphics.drawable.Drawable d : old.getCompoundDrawablesRelative())
                    if (d instanceof ProgressRingDrawable) ((ProgressRingDrawable) d).stop();
                row.removeViewAt(1);
            }
            ComicProject.Page p = project.pages.get(position);
            ImageView view = (ImageView) row.getChildAt(0);
            boolean working =
                    TranslationTaskManager.running()
                            && TranslationTaskManager.owner.equals("project:" + project.id)
                            && p.id.equals(ProjectTranslation.activePageId);
            boolean failed = "failed".equals(p.status);
            boolean edited = p.editedCount() > 0;
            boolean translated = p.editable() || "translated".equals(p.status);
            String state =
                    working ? "翻译中" : failed ? "失败" : edited ? "已编辑" : translated ? "已翻译" : "未翻译";
            int tone =
                    working || edited
                            ? Ui.INFO
                            : failed ? Ui.NEGATIVE : translated ? Ui.POSITIVE : Ui.NEUTRAL;
            TextView badge =
                    Ui.chip(
                            ProjectReaderActivity.this,
                            state,
                            working
                                    ? R.drawable.ic_translate
                                    : failed
                                            ? R.drawable.ic_error
                                            : edited
                                                    ? R.drawable.ic_edit
                                                    : translated
                                                            ? R.drawable.ic_check_circle
                                                            : R.drawable.ic_circle,
                            tone);
            if (working) {
                ProgressRingDrawable ring = new ProgressRingDrawable();
                ring.setTint(Ui.ACCENT);
                ring.update(0, true, true);
                ring.setBounds(
                        0,
                        0,
                        Ui.dp(ProjectReaderActivity.this, 16),
                        Ui.dp(ProjectReaderActivity.this, 16));
                badge.setCompoundDrawablesRelative(ring, null, null, null);
                ring.start();
                badge.addOnAttachStateChangeListener(
                        new View.OnAttachStateChangeListener() {
                            public void onViewAttachedToWindow(View v) {
                                ring.start();
                            }

                            public void onViewDetachedFromWindow(View v) {
                                ring.stop();
                            }
                        });
            }
            if (failed) {
                badge.setMinHeight(Ui.dp(ProjectReaderActivity.this, 48));
                badge.setContentDescription("第 " + (position + 1) + " 页翻译失败，点击重试");
                badge.setOnClickListener(v -> retry(position));
            }
            FrameLayout.LayoutParams badgePos =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            badgePos.setMargins(
                    0,
                    Ui.dp(ProjectReaderActivity.this, 8),
                    Ui.dp(ProjectReaderActivity.this, 8),
                    0);
            row.addView(badge, badgePos);
            view.setImageDrawable(null);
            view.setMinimumHeight(Ui.dp(ProjectReaderActivity.this, 180));
            Object token = new Object();
            row.setTag(token);
            final int rev = revision;
            final boolean showOriginal = original;
            final ComicProject target = project;
            String cacheKey = rev + ":" + p.id + ":" + showOriginal;
            Bitmap cached = thumbnails.get(cacheKey);
            if (cached != null) {
                view.setMinimumHeight(0);
                view.setImageBitmap(cached);
                return row;
            }
            images.execute(
                    () -> {
                        if (isDestroyed() || rev != revision || row.getTag() != token) return;
                        Bitmap bitmap = null;
                        try {
                            File file =
                                    p.editable()
                                            ? new File(
                                                    target.draftDir(p),
                                                    showOriginal
                                                            ? PageDraft.SOURCE
                                                            : "rendered.png")
                                            : target.imageFile(p);
                            if (p.editable()
                                    && !showOriginal
                                    && (p.editedCount() > 0
                                            || !target.defaultStyle.isDefault()
                                            || !file.isFile())) {
                                try (PageComposer composer =
                                        new PageComposer(
                                                PageDraft.read(target.draftDir(p))
                                                        .withEdits(p.edits),
                                                1400)) {
                                    composer.layoutAll(
                                            target.effectiveEdits(p, composer.draft),
                                            () -> isDestroyed() || rev != revision);
                                    bitmap = composer.compose(null);
                                    int width = Math.min(900, bitmap.getWidth());
                                    Bitmap small =
                                            Bitmap.createScaledBitmap(
                                                    bitmap,
                                                    width,
                                                    Math.max(
                                                            1,
                                                            bitmap.getHeight()
                                                                    * width
                                                                    / bitmap.getWidth()),
                                                    true);
                                    if (small != bitmap) bitmap.recycle();
                                    bitmap = small;
                                }
                            } else if (file != null && file.isFile()) {
                                BitmapFactory.Options o = new BitmapFactory.Options();
                                o.inJustDecodeBounds = true;
                                BitmapFactory.decodeFile(file.getPath(), o);
                                o.inSampleSize = 1;
                                while (o.outWidth / o.inSampleSize > 1000
                                        || o.outHeight / o.inSampleSize > 2400) o.inSampleSize *= 2;
                                o.inJustDecodeBounds = false;
                                bitmap = BitmapFactory.decodeFile(file.getPath(), o);
                            }
                        } catch (Exception | OutOfMemoryError ignored) {
                        }
                        Bitmap ready = bitmap;
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed() || rev != revision) {
                                        if (ready != null) ready.recycle();
                                        return;
                                    }
                                    if (ready != null) thumbnails.put(cacheKey, ready);
                                    if (row.getTag() != token) return;
                                    if (ready != null) {
                                        view.setMinimumHeight(0);
                                        view.setImageBitmap(ready);
                                    } else
                                        badge.setText(
                                                badge.getContext()
                                                        .getString(
                                                                R.string
                                                                        .project_reader_activity_message_17,
                                                                badge.getText()));
                                });
                    });
            return row;
        }
    }
}
