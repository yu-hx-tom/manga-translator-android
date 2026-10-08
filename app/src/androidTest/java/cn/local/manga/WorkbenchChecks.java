package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/**
 * 1.0.0 workbench checks (no network): a page rendered by the real TranslationEngine.renderTextPage must be
 * reproduced pixel-for-pixel by PageComposer from its draft; edits change only what they should; projects and
 * ZIP exports round-trip. Run: adb shell am instrument -w -e suite workbench cn.local.manga.test/cn.local.manga.ChecksInstrumentation
 */
public final class WorkbenchChecks {
    private WorkbenchChecks() {}

    public static String run(Context context) throws Exception {
        int checks = 0;
        PageDraftStore.setEnabled(context, true);
        Bitmap page = syntheticPage();
        List<Region> regions = Arrays.asList(
                region("block_01", new Rect(80, 90, 230, 330), true, new Rect(150, 110, 172, 300), new Rect(110, 110, 132, 260)),
                region("block_02", new Rect(330, 520, 540, 600), false, new Rect(345, 540, 520, 570)));
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:9/v1"; settings.apiKey = "test-only"; settings.mode = "text";
        TranslationEngine engine = new TranslationEngine(context);
        TranslationEngine.Result result;
        try (TranslationEngine.PreparedText job = engine.prepareTextPage(page, regions, settings, () -> false)) {
            job.values.put("block_01", new JSONObject().put("zh", "早上好呀").put("skip", false).put("originalText", "おはよう"));
            job.values.put("block_02", new JSONObject().put("zh", "轰隆隆").put("skip", false).put("originalText", "ゴゴゴ"));
            result = engine.renderTextPage(job, () -> false);
        } finally { engine.close(); }
        File staged = result.draft;
        try {
            require(staged != null && staged.isDirectory(), "renderTextPage stages a draft"); checks++;
            require(result.succeeded > 0, "synthetic page draws at least one paragraph"); checks++;
            require(!samePixels(result.image, page), "rendered page differs from the source"); checks++;

            PageDraft draft = PageDraft.read(staged);
            require(new File(staged,"clean.png").isFile()&&new File(staged,"rendered.png").isFile(),"clean and rendered layers persist separately");checks++;
            require(draft.items.size() == 2 && draft.width == page.getWidth() && draft.height == page.getHeight(), "draft keeps regions and size"); checks++;
            require("早上好呀".equals(draft.item("block_01").machine) && "おはよう".equals(draft.item("block_01").original), "draft keeps machine and original text"); checks++;

            // Golden check: no edits == the translation output, pixel for pixel.
            try (PageComposer composer = new PageComposer(draft)) {
                composer.layoutAll(Collections.emptyMap(), () -> false);
                Bitmap composed = composer.compose(null);
                require(samePixels(composed, result.image), "PageComposer reproduces renderTextPage exactly"); checks++;

                // A text edit changes the page; restoring the machine text restores the exact output.
                PageComposer.Edit edit = new PageComposer.Edit(); edit.text = "晚上好";
                composer.layout("block_01", edit, () -> false);
                Bitmap edited = composer.compose(null);
                require(!samePixels(edited, result.image), "editing text changes the page"); checks++;
                composer.layout("block_01", new PageComposer.Edit(), () -> false);
                require(samePixels(composer.compose(edited), result.image), "reverting the edit restores the original output"); checks++;

                // Hiding affects only the text layer; verified cleanup stays in the saved clean layer.
                PageComposer.Edit hide = new PageComposer.Edit(); hide.hidden = true;
                composer.layout("block_01", hide, () -> false);
                composer.layout("block_02", hide, () -> false);
                Bitmap clean=android.graphics.BitmapFactory.decodeFile(new File(draft.dir,"clean.png").getPath());
                require(clean!=null&&samePixels(composer.compose(null),clean), "hidden paragraphs show the clean layer"); checks++;if(clean!=null)clean.recycle();

                // Font scale: larger never exceeds the safe area and is not smaller than the default.
                PageComposer.Placement normal = composer.layout("block_01", new PageComposer.Edit(), () -> false);
                PageComposer.Edit bigger = new PageComposer.Edit(); bigger.scale = 1.6f;
                PageComposer.Placement large = composer.layout("block_01", bigger, () -> false);
                require(normal.mode == large.mode && large.font >= normal.font, "font scale enlarges (or keeps) the lettering"); checks++;
                PageComposer.Edit smaller = new PageComposer.Edit(); smaller.scale = .6f;
                PageComposer.Placement small = composer.layout("block_01", smaller, () -> false);
                require(small.mode == PageComposer.Mode.ORIGINAL || small.font <= normal.font, "font scale shrinks the lettering"); checks++;
                PageComposer.Edit inPlace = new PageComposer.Edit(); inPlace.inPlace = true;
                require(composer.layout("block_01", inPlace, () -> false).mode != PageComposer.Mode.BUBBLE, "forced in-place text skips bubble cleanup"); checks++;
            }
            PageComposer.Edit styled=new PageComposer.Edit();styled.text="可编辑的中文";styled.format.custom=true;styled.format.fontSize=30;styled.format.autoFit=false;styled.format.strokeWidth=2;styled.format.strokeColor=Color.WHITE;styled.color=Color.BLUE;styled.format.box=new Rect(80,90,230,330);
            Map<String,PageComposer.Edit> styles=new HashMap<>();styles.put("block_01",styled);
            try(PageComposer full=new PageComposer(draft);PageComposer small=new PageComposer(draft,250)){
                full.layoutAll(styles,()->false);small.layoutAll(styles,()->false);Bitmap exported=full.compose(null),preview=small.compose(null);
                require(exported.getWidth()==600&&exported.getHeight()==800,"export retains original resolution");checks++;
                require(Math.max(preview.getWidth(),preview.getHeight())<=250,"preview bitmap is bounded");checks++;
                require(!samePixels(exported,result.image),"font color stroke and text alter the text layer");checks++;
                exported.recycle();preview.recycle();
            }
            PageComposer.Edit added=new PageComposer.Edit();added.text="补漏文本";added.format.added=true;added.format.custom=true;added.format.box=new Rect(20,650,240,740);styles.put("manual-check",added);
            PageDraft expanded=draft.withEdits(styles);require(expanded.items.size()==draft.items.size()+1,"manual boxes survive draft expansion");checks++;
            try(PageComposer composer=new PageComposer(expanded)){composer.layoutAll(styles,()->false);Bitmap extra=composer.compose(null);require(extra!=null,"manual box renders through shared composer");checks++;extra.recycle();}

            // Draft store: commit under a content key and find it again.
            String key = LocalComics.sha("workbench-check\n" + System.nanoTime());
            require(PageDraftStore.commit(context, staged, key), "draft commits under its key"); checks++;
            staged = null;
            File committed = PageDraftStore.find(context, key);
            require(committed != null && PageDraft.read(committed).items.size() == 2, "committed draft is found and readable"); checks++;

            // Project round trip through ProjectStore (copies the draft) and edits persisted as JSON.
            ComicProject project = ProjectStore.create(context, "检查工程", "check", "check:" + key,
                    Collections.singletonList(new ProjectStore.PageSpec("001.png", key, null, null, false)), (d, t) -> {}, () -> false);
            try {
                require(project.pages.size() == 1 && project.pages.get(0).editable(), "project adopts the draft as an editable page"); checks++;
                // Importing a legacy page must reuse its finished image without preparing layers.
                File legacy=new File(context.getCacheDir(),"legacy-import-check");PageDraftStore.copyTree(project.draftDir(project.pages.get(0)),legacy);
                new File(legacy,"clean.png").delete();
                ProjectStore.PageSpec legacySpec=new ProjectStore.PageSpec("legacy",null,null,"png",false,legacy);legacySpec.disposableSnapshot=true;
                ComicProject legacyProject=ProjectStore.create(context,"legacy","check","legacy",Collections.singletonList(legacySpec),(d,t)->{},()->false);
                try{
                    File imported=legacyProject.draftDir(legacyProject.pages.get(0));
                    require(!legacy.exists()&&!new File(imported,"clean.png").exists(),"legacy import moves snapshot without eager cleanup");checks++;
                    require(ProjectStore.cover(legacyProject).isFile(),"legacy import cover uses saved rendered image");checks++;
                    ProjectStore.ensureLayers(imported);require(new File(imported,"clean.png").isFile(),"opening legacy editor prepares layers on demand");checks++;
                }finally{ProjectStore.delete(legacyProject);PageDraftStore.deleteTree(legacy);}
                PageComposer.Edit saved = new PageComposer.Edit(); saved.text = "改过的译文"; saved.scale = 1.2f; saved.vertical = Boolean.FALSE; saved.color = Color.RED;
                project.pages.get(0).edits.put("block_01", saved); project.pages.get(0).reviewed = true;
                project.save();
                ComicProject loaded = ComicProject.load(project.dir);
                PageComposer.Edit back = loaded.pages.get(0).edits.get("block_01");
                require(back != null && back.equals(saved) && loaded.pages.get(0).reviewed, "project edits survive save/load"); checks++;
                require(ProjectStore.findBySource(context, "check:" + key) != null, "project is found by its source"); checks++;
                PageDraftStore.deleteTree(committed);
                require(PageDraft.read(project.draftDir(project.pages.get(0))).items.size()==2,"project copies survive cache removal");checks++;
            } finally { ProjectStore.delete(project); }
            PageDraftStore.deleteTree(committed);

            // ZIP writer: STORED images and deflated text read back intact.
            File zip = new File(context.getCacheDir(), "workbench-check.zip");
            zip.delete();
            byte[] image = {(byte) 137, 'P', 'N', 'G', 1, 2, 3, 4, 5}, text = "翻译稿".getBytes("UTF-8");
            try (ExportWriters.ZipWriter writer = new ExportWriters.ZipWriter(context, Uri.fromFile(zip), "check.zip", true)) {
                writer.write("001.png", "image/png", image);
                writer.write("翻译稿.txt", "text/plain", text);
            }
            Map<String, byte[]> entries = new HashMap<>();
            try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry entry; byte[] buffer = new byte[4096];
                while ((entry = in.getNextEntry()) != null) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    entries.put(entry.getName(), out.toByteArray());
                }
            }
            require(Arrays.equals(entries.get("001.png"), image) && Arrays.equals(entries.get("翻译稿.txt"), text), "zip entries read back intact"); checks++;
            zip.delete();

            // Export naming: zero padded, unique, original names sanitised.
            List<String> used = new ArrayList<>();
            ComicProject.Page named = new ComicProject.Page(); named.label = "p:01?.jpg";
            require("007.png".equals(ExportJob.name(named, 7, 3, "png", false, used)), "numbered names are zero padded"); checks++;
            require("p_01_.jpg".equals(ExportJob.name(named, 8, 3, "jpg", true, used)) && "p_01__2.jpg".equals(ExportJob.name(named, 9, 3, "jpg", true, used)), "kept names are sanitised and unique"); checks++;
        } finally {
            PageDraftStore.discard(staged);
            if (result.image != null && !result.image.isRecycled()) result.image.recycle();
            page.recycle();
        }
        return checks + " workbench checks";
    }

    /** White page: a closed white balloon with dark vertical "glyph" strokes, and lettering on a grey screen tone. */
    private static Bitmap syntheticPage() {
        Bitmap page = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(page);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(3);
        canvas.drawOval(60, 70, 250, 350, ink);
        ink.setStyle(Paint.Style.FILL);
        for (int y = 115; y < 295; y += 26) { canvas.drawRect(154, y, 168, y + 16, ink); if (y < 255) canvas.drawRect(114, y, 128, y + 16, ink); }
        Paint tone = new Paint(); tone.setColor(0xff8C8C8C);
        for (int x = 300; x < 580; x += 6) for (int y = 500; y < 620; y += 6) canvas.drawRect(x, y, x + 3, y + 3, tone);
        for (int x = 350; x < 515; x += 24) canvas.drawRect(x, 543, x + 16, 567, ink);
        return page;
    }
    private static Region region(String id, Rect box, boolean vertical, Rect... lines) { return new Region(id, box, Arrays.asList(lines), vertical); }
    private static boolean samePixels(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int w = a.getWidth(), h = a.getHeight(); int[] x = new int[w * h], y = new int[w * h];
        a.getPixels(x, 0, w, 0, 0, w, h); b.getPixels(y, 0, w, 0, 0, w, h);
        return Arrays.equals(x, y);
    }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError("Workbench: " + message); }
}
