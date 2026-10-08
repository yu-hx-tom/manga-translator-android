package cn.local.manga;

import android.graphics.Rect;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Disk/model regression checks. No Android UI, bitmap rasterization or network is emulated. */
public final class UiRefactorChecks {
    private static int checks;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        check(
                Arrays.equals(DetectorModels.IDS, new String[] {DetectorModels.PP_ID}),
                "only PP-OCR selectable");
        check(DetectorModels.PP_ID.equals(new AppSettings().detectorModel), "PP-OCR default");
        for (String old : new String[] {"rtdetr_v4_s_int8", "rtdetr_r50_fp32", "rtdetr_r50_int8"})
            check(!DetectorModels.isValid(old), "removed model invalid " + old);
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:9/v1";
        settings.apiKey = "unused-local-check";
        JSONObject legacy = settings.snapshot();
        legacy.put("detectorModel", "rtdetr_r50_int8");
        check(
                DetectorModels.PP_ID.equals(AppSettings.fromSnapshot(legacy).detectorModel),
                "saved preset migration");
        List<String> names = new ArrayList<>(Arrays.asList("10.png", "2.png", "1.png"));
        names.sort(NaturalOrder.INSTANCE);
        check(names.equals(Arrays.asList("1.png", "2.png", "10.png")), "natural sorting");
        PageComposer.Edit edit = new PageComposer.Edit();
        edit.text = "测试译文";
        edit.vertical = true;
        edit.color = 0xff325ABC;
        edit.format.custom = true;
        edit.format.fontSize = 37;
        edit.format.strokeWidth = 3;
        edit.format.strokeColor = 0xffEEEEEE;
        edit.format.fontFamily = "serif";
        edit.format.lineSpacing = 1.3f;
        edit.format.letterSpacing = 2;
        edit.format.rotation = 15;
        edit.format.align = 2;
        edit.format.box = new Rect(4, 8, 180, 240);
        PageComposer.Edit restored = ComicProject.edit(ComicProject.json(edit));
        check(restored.equals(edit), "all text and style values round trip");
        PageComposer.Edit copied = edit.copy();
        copied.format.box.left = 77;
        check(edit.format.box.left == 4, "deep copy isolates box edits");
        check(!edit.isDefault(), "custom style retained");
        check(new PageComposer.Edit().isDefault(), "legacy default retained");
        for (String family :
                new String[] {
                    "sans-serif",
                    "serif",
                    "monospace",
                    "sans-serif-condensed",
                    "sans-serif-medium",
                    "cursive",
                    "sans-serif-black"
                }) {
            PageComposer.Edit legacyFont = new PageComposer.Edit();
            legacyFont.format.fontFamily = family;
            legacyFont.format.custom = true;
            legacyFont.format.fontSize = 32;
            legacyFont.scale = 1.2f;
            check(
                    ComicProject.edit(ComicProject.json(legacyFont)).equals(legacyFont),
                    "old font and scale retained without eager migration: " + family);
        }
        TextStyle clamped =
                TextStyle.from(
                        new JSONObject()
                                .put("fontSize", 200)
                                .put("strokeWidth", 99)
                                .put("lineSpacing", 0)
                                .put("align", 9));
        check(
                clamped.fontSize == 96
                        && clamped.strokeWidth == 10
                        && clamped.lineSpacing == .5f
                        && clamped.align == 2,
                "style bounds");
        File dir = new File(args[0], "project-" + UUID.randomUUID());
        dir.mkdirs();
        ComicProject project = new ComicProject(dir);
        project.id = UUID.randomUUID().toString();
        project.title = "测试工程";
        project.sourceKind = "local";
        project.sourceKey = "fixture";
        project.created = 1;
        ComicProject.Page page = new ComicProject.Page();
        page.id = "p0001";
        page.kind = ComicProject.KIND_ORIGINAL;
        page.label = "1.png";
        page.imageName = "image.png";
        page.originalName = "image.png";
        page.edits.put("block", edit);
        project.pages.add(page);
        project.defaultStyle = edit.copy();
        project.coverPageId = page.id;
        try {
            project.save();
            check(new File(dir, "project.json").isFile(), "formal save exists");
            check(!new File(dir, "project.json.tmp").exists(), "atomic rename consumed temp");
            ComicProject saved = ComicProject.load(dir);
            check(saved.pages.get(0).edits.get("block").equals(edit), "formal save style restored");
            check(
                    saved.defaultStyle.equals(edit) && saved.coverPageId.equals(page.id),
                    "project defaults and cover");
            page.edits.get("block").text = "未保存草稿";
            project.write("draft.json", project.snapshot());
            check(
                    "测试译文".equals(ComicProject.load(dir).pages.get(0).edits.get("block").text),
                    "draft never overwrites formal edits");
            check(
                    "未保存草稿"
                            .equals(
                                    ComicProject.load(dir, "draft.json")
                                            .pages
                                            .get(0)
                                            .edits
                                            .get("block")
                                            .text),
                    "draft recovery survives reload");
            page.status = "translating";
            project.save();
            check(
                    "failed".equals(ComicProject.load(dir).pages.get(0).status),
                    "interrupted page becomes retryable");
            check(
                    "image.png".equals(ComicProject.load(dir).pages.get(0).originalName),
                    "original remains available");
            String original = Files.readString(new File(dir, "project.json").toPath());
            Files.writeString(new File(dir, "project.json.tmp").toPath(), "partial");
            check(
                    Files.readString(new File(dir, "project.json").toPath()).equals(original),
                    "partial write leaves saved data intact");
            ExportJob.Options options = new ExportJob.Options();
            options.webp = true;
            options.quality = 79;
            options.selected.add("p0001");
            ExportJob.Options back = ExportJob.Options.from(options.json());
            check(
                    back.webp && back.quality == 79 && back.selected.equals(options.selected),
                    "WebP quality and selected range survive save");
            check("p_1_.png".equals(ExportWriters.safe("p:1?.png")), "export file names sanitized");
            List<String> assets = new ArrayList<>();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(args[1])) {
                zip.stream()
                        .filter(e -> e.getName().endsWith(".onnx"))
                        .forEach(e -> assets.add(e.getName()));
            }
            check(
                    assets.equals(Collections.singletonList("assets/detector.onnx")),
                    "APK contains exactly the PP-OCR model: " + assets);
        } finally {
            for (File f : Objects.requireNonNull(dir.listFiles())) f.delete();
            dir.delete();
        }
        System.out.println(
                "UiRefactorChecks: "
                        + checks
                        + " checks passed (disk/model/APK only; Android UI and rendering not"
                        + " executed)");
    }
}
