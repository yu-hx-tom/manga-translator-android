package cn.local.manga;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Device-only synthetic UI regression. No translation endpoint is called and no user project is
 * edited.
 */
final class Ui110Checks {
    private static int checks;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    private static void main(Instrumentation test, Action action) throws Exception {
        Throwable[] failed = {null};
        test.runOnMainSync(
                () -> {
                    try {
                        action.run();
                    } catch (Throwable e) {
                        failed[0] = e;
                    }
                });
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = WorkbenchActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = WorkbenchActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static List<View> views(View root) {
        List<View> all = new ArrayList<>();
        all.add(root);
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                all.addAll(views(((ViewGroup) root).getChildAt(i)));
        return all;
    }

    static String run(Instrumentation test) throws Exception {
        checks = 0;
        Context context = test.getTargetContext();
        boolean drafts = PageDraftStore.enabled(context);
        String key = "ui110-" + UUID.randomUUID();
        ComicProject project = null;
        File staged = null;
        WorkbenchActivity activity = null;
        Bitmap source = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(source);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        canvas.drawOval(60, 60, 360, 380, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int y = 100; y < 300; y += 28) canvas.drawRect(180, y, 196, y + 18, paint);
        try {
            PageDraftStore.setEnabled(context, true);
            List<Region> regions =
                    Collections.singletonList(
                            new Region(
                                    "fixture",
                                    new Rect(150, 90, 240, 320),
                                    Collections.singletonList(new Rect(180, 100, 196, 300)),
                                    true));
            AppSettings settings = new AppSettings();
            settings.mode = "text";
            settings.baseUrl = "http://127.0.0.1:9/v1";
            settings.apiKey = "unused-offline-test";
            TranslationEngine engine = new TranslationEngine(context);
            TranslationEngine.Result translated;
            try (TranslationEngine.PreparedText job =
                    engine.prepareTextPage(source, regions, settings, () -> false)) {
                job.values.put(
                        "fixture",
                        new JSONObject()
                                .put("zh", "这是测试文字")
                                .put("originalText", "合成样例")
                                .put("skip", false));
                translated = engine.renderTextPage(job, () -> false);
            } finally {
                engine.close();
            }
            staged = translated.draft;
            check(staged != null, "synthetic draft exists");
            check(PageDraftStore.commit(context, staged, key), "synthetic draft committed");
            staged = null;
            if (translated.image != source) translated.image.recycle();
            project =
                    ProjectStore.create(
                            context,
                            "1.1.0 合成界面检查",
                            "check",
                            "ui110:" + key,
                            Collections.singletonList(
                                    new ProjectStore.PageSpec("001.png", key, null, null, false)),
                            (d, t) -> {},
                            () -> false);
            activity =
                    (WorkbenchActivity)
                            test.startActivitySync(
                                    WorkbenchActivity.intent(context, project.id, 0)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final WorkbenchActivity screen = activity;
            boolean[] loaded = {false};
            for (int i = 0; i < 120 && !loaded[0]; i++) {
                test.waitForIdleSync();
                main(
                        test,
                        () ->
                                loaded[0] =
                                        field(screen, "composer") != null
                                                && !((LinearLayout) field(screen, "cards"))
                                                        .isLayoutRequested());
                if (!loaded[0]) Thread.sleep(50);
            }
            check(loaded[0], "editor loaded");
            test.waitForIdleSync();
            main(
                    test,
                    () -> {
                        View root = (View) field(screen, "editorRoot"),
                                preview = (View) field(screen, "preview");
                        check(
                                preview.getHeight() >= root.getHeight() * .45f,
                                "half drawer retains at least 45 percent preview height");
                        for (View view : views(screen.getWindow().getDecorView()))
                            if (view instanceof ImageButton
                                    && view.getVisibility() == View.VISIBLE) {
                                check(
                                        view.getContentDescription() != null
                                                && view.getContentDescription().length() > 0,
                                        "icon has description");
                                check(
                                        view.getWidth() >= Ui.dp(screen, 48)
                                                && view.getHeight() >= Ui.dp(screen, 48),
                                        "measured icon target at least 48dp");
                            }
                        PageDraft draft = (PageDraft) field(screen, "draft");
                        ComicProject current = (ComicProject) field(screen, "project");
                        PageDraft.Item item = draft.items.get(0);
                        int before = ((Deque<?>) field(screen, "undo")).size();
                        Map<String, PageComposer.Edit> original =
                                new LinkedHashMap<>(current.pages.get(0).edits);
                        call(screen, "openStyle", new Class[] {PageDraft.Item.class}, item);
                        StylePanel.Editor editor = (StylePanel.Editor) field(screen, "styleEditor");
                        EditText size = null;
                        for (View view : views(editor))
                            if (view instanceof EditText) {
                                size = (EditText) view;
                                break;
                            }
                        check(size != null, "embedded style size control exists");
                        size.setText("38");
                        check(
                                current.pages.get(0).edits.equals(original),
                                "live style preview does not persist changes");
                        editor.cancel();
                        check(
                                current.pages.get(0).edits.equals(original)
                                        && ((Deque<?>) field(screen, "undo")).size() == before,
                                "cancel preserves exact model and undo stack");
                        call(screen, "openStyle", new Class[] {PageDraft.Item.class}, item);
                        editor = (StylePanel.Editor) field(screen, "styleEditor");
                        for (View view : views(editor))
                            if (view instanceof EditText) {
                                ((EditText) view).setText("38");
                                break;
                            }
                        editor.commit();
                        check(
                                ((Deque<?>) field(screen, "undo")).size() == before + 1,
                                "done creates one undo step");
                        PageComposer.Edit saved = current.pages.get(0).edits.get(item.region.id);
                        check(
                                saved != null && saved.format.fontSize == 38 && saved.scale == 1,
                                "absolute font size committed");
                        call(screen, "undo", new Class[] {});
                        check(
                                current.pages.get(0).edits.equals(original),
                                "single undo restores original edit");
                        Method keyboard =
                                ShellActivity.class.getDeclaredMethod(
                                        "keyboardChanged", boolean.class);
                        keyboard.setAccessible(true);
                        keyboard.invoke(screen, true);
                        check(
                                screen.getWindow()
                                                .getDecorView()
                                                .findViewWithTag("shell-navigation")
                                                .getVisibility()
                                        == View.GONE,
                                "keyboard controller hides navigation");
                        check(
                                screen.getWindow()
                                                .getDecorView()
                                                .findViewWithTag("shell-task-status")
                                                .getVisibility()
                                        == View.GONE,
                                "keyboard controller hides task status");
                        keyboard.invoke(screen, false);
                    });
            Bitmap plain = fontSample(false), bold = fontSample(true);
            check(!plain.sameAs(bold), "coarse black changes Chinese glyph pixels");
            plain.recycle();
            bold.recycle();
            File output = new File(context.getExternalFilesDir(null), "ui110-checks");
            output.mkdirs();
            Bitmap screenshot = test.getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (FileOutputStream out =
                        new FileOutputStream(
                                new File(
                                        output,
                                        "编辑页-"
                                                + context.getResources()
                                                        .getConfiguration()
                                                        .screenWidthDp
                                                + "dp.png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                screenshot.recycle();
            }
            return checks
                    + " synthetic UI checks passed at "
                    + context.getResources().getConfiguration().screenWidthDp
                    + "dp; keyboard visibility controller exercised, actual"
                    + " IME/launcher/notifications/50-project timing need manual checks";
        } finally {
            if (activity != null) {
                WorkbenchActivity screen = activity;
                main(test, screen::finish);
                test.waitForIdleSync();
            }
            if (project != null) ProjectStore.delete(project);
            PageDraftStore.discard(staged);
            File committed = PageDraftStore.find(context, key);
            if (committed != null) PageDraftStore.deleteTree(committed);
            PageDraftStore.setEnabled(context, drafts);
            source.recycle();
        }
    }

    private static Bitmap fontSample(boolean bold) {
        Bitmap image = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(40);
        paint.setTypeface(FontChoices.typeface(bold ? FontChoices.BOLD : FontChoices.NORMAL));
        paint.setColor(Color.BLACK);
        new Canvas(image).drawText("汉字永国翻译", 4, 50, paint);
        return image;
    }
}
