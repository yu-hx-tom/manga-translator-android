package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Creates, lists and deletes 汉化工程 under filesDir/projects. Projects are user data and never
 * auto-evicted.
 */
final class ProjectStore {
    private ProjectStore() {}

    /**
     * One page offered to a new project: an editable draft key, and/or a finished/original image to
     * fall back on.
     */
    static final class PageSpec {
        final String label, draftKey, imageExtension;
        final Callable<InputStream> image;
        final boolean original;
        File ownedDraft;
        File ownedImage;
        boolean disposableSnapshot;
        String initialStatus;
        boolean initialReviewed;
        java.util.Map<String, PageComposer.Edit> initialEdits;

        PageSpec(
                String label,
                String key,
                Callable<InputStream> image,
                String extension,
                boolean original,
                File ownedDraft) {
            this(label, key, image, extension, original);
            this.ownedDraft = ownedDraft;
        }

        PageSpec(
                String label,
                String draftKey,
                Callable<InputStream> image,
                String imageExtension,
                boolean original) {
            this.label = label;
            this.draftKey = draftKey;
            this.image = image;
            this.imageExtension = imageExtension;
            this.original = original;
        }
    }

    interface Progress {
        void update(int done, int total);
    }

    static File root(Context context) {
        return new File(context.getFilesDir(), "projects");
    }

    static List<ComicProject> list(Context context) {
        List<ComicProject> result = new ArrayList<>();
        File[] dirs = root(context).listFiles(File::isDirectory);
        if (dirs != null)
            for (File dir : dirs) {
                try {
                    result.add(ComicProject.load(dir));
                } catch (Exception damaged) {
                    /* skipped; still deletable from disk */
                }
            }
        result.sort(Comparator.comparingLong((ComicProject p) -> p.updated).reversed());
        return result;
    }

    static ComicProject open(Context context, String id) throws Exception {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new java.io.IOException("工程不存在");
        return ComicProject.load(new File(root(context), id));
    }

    static ComicProject findBySource(Context context, String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) return null;
        for (ComicProject project : list(context))
            if (sourceKey.equals(project.sourceKey)) return project;
        return null;
    }

    static void delete(ComicProject project) {
        PageDraftStore.deleteTree(project.dir);
    }

    static long size(ComicProject project) {
        return PageDraftStore.size(project.dir);
    }

    static File cover(ComicProject project) {
        return new File(project.dir, "cover.jpg");
    }

    /**
     * Copies every page's draft (or image) into a new project folder. Cancels cleanly (no partial
     * project left).
     */
    static ComicProject create(
            Context context,
            String title,
            String sourceKind,
            String sourceKey,
            List<PageSpec> specs,
            Progress progress,
            BooleanSupplier cancelled)
            throws Exception {
        String id = UUID.randomUUID().toString();
        File dir = new File(root(context), id);
        if (!dir.mkdirs()) throw new java.io.IOException("无法创建工程文件夹，请检查存储空间");
        ComicProject project = new ComicProject(dir);
        project.id = id;
        project.title = title == null || title.trim().isEmpty() ? "未命名工程" : title.trim();
        project.sourceKind = sourceKind;
        project.sourceKey = sourceKey;
        project.created = System.currentTimeMillis();
        try {
            int index = 0;
            for (PageSpec spec : specs) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                ComicProject.Page page = new ComicProject.Page();
                page.id = String.format(java.util.Locale.ROOT, "p%04d", ++index);
                page.label = spec.label;
                if (spec.ownedDraft != null && new File(spec.ownedDraft, PageDraft.JSON).isFile()) {
                    if (spec.disposableSnapshot)
                        PageDraftStore.takeSnapshot(
                                spec.ownedDraft, project.draftDir(page), cancelled);
                    else
                        PageDraftStore.copyTree(spec.ownedDraft, project.draftDir(page), cancelled);
                    PageDraft.read(project.draftDir(page));
                    page.kind = ComicProject.KIND_EDITABLE;
                } else if (adoptDraft(context, project, page, spec.draftKey, cancelled))
                    page.kind = ComicProject.KIND_EDITABLE;
                else if (spec.ownedImage != null && spec.disposableSnapshot) {
                    page.imageName = "image.png";
                    if (spec.original) page.originalName = page.imageName;
                    PageDraftStore.takeSnapshot(
                            spec.ownedImage, project.imageFile(page), cancelled);
                    page.kind =
                            spec.original ? ComicProject.KIND_ORIGINAL : ComicProject.KIND_RENDERED;
                } else if (spec.image != null && copyImage(project, page, spec, cancelled))
                    page.kind =
                            spec.original ? ComicProject.KIND_ORIGINAL : ComicProject.KIND_RENDERED;
                else throw new java.io.IOException("无法读取页面：" + spec.label + "，请重新选择文件");
                // Older drafts still have a usable translated image. Keep it for reading/cover;
                // prepare their missing clean layer only when the editor actually opens that page.
                if (page.editable()
                        && spec.image != null
                        && !new File(project.draftDir(page), "rendered.png").isFile()) {
                    try (InputStream in = spec.image.call()) {
                        if (in != null)
                            copyStream(
                                    in,
                                    new File(project.draftDir(page), "rendered.png"),
                                    cancelled);
                    }
                }
                if (!ComicProject.KIND_ORIGINAL.equals(page.kind)) page.status = "translated";
                if (spec.initialStatus != null) {
                    page.status = spec.initialStatus;
                    page.reviewed = spec.initialReviewed;
                    if (spec.initialEdits != null)
                        for (java.util.Map.Entry<String, PageComposer.Edit> edit :
                                spec.initialEdits.entrySet())
                            page.edits.put(edit.getKey(), edit.getValue().copy());
                }
                project.pages.add(page);
                progress.update(index, specs.size());
            }
            if (project.pages.isEmpty()) throw new java.io.IOException("没有可加入工程的页面：请先翻译至少一页");
            if (cancelled.getAsBoolean()) throw new CancellationException();
            project.save();
            writeCover(project);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            return project;
        } catch (Exception | Error failure) {
            PageDraftStore.deleteTree(dir);
            throw failure;
        }
    }

    private static boolean adoptDraft(
            Context context,
            ComicProject project,
            ComicProject.Page page,
            String key,
            BooleanSupplier cancelled) {
        File draft = PageDraftStore.find(context, key);
        if (draft == null) return false;
        File target = project.draftDir(page);
        try {
            PageDraftStore.copyTree(draft, target, cancelled);
            PageDraft.read(target);
            return true;
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (Exception damaged) {
            PageDraftStore.deleteTree(target);
            return false;
        }
    }

    static void ensureLayers(File dir) throws Exception {
        if (!new File(dir, "clean.png").isFile())
            try (PageComposer composer = new PageComposer(PageDraft.read(dir))) {
                composer.persistLayers(null);
            }
    }

    private static void copyStream(InputStream in, File target, BooleanSupplier cancelled)
            throws Exception {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                out.write(buffer, 0, n);
            }
        }
    }

    private static boolean copyImage(
            ComicProject project,
            ComicProject.Page page,
            PageSpec spec,
            BooleanSupplier cancelled) {
        File dir = project.pageDir(page);
        String extension =
                spec.imageExtension == null
                        ? "png"
                        : spec.imageExtension.toLowerCase(java.util.Locale.ROOT);
        page.imageName = "image." + extension;
        if (spec.original) page.originalName = page.imageName;
        File target = new File(dir, page.imageName);
        try (InputStream in = spec.image.call()) {
            if (in == null) return false;
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            copyStream(in, target, cancelled);
            return target.length() > 0;
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (Exception unreadable) {
            target.delete();
            page.imageName = null;
            return false;
        }
    }

    /** Small JPEG of the first page for the project list. Best effort. */
    static void writeCover(ComicProject project) {
        if (project.pages.isEmpty()) return;
        ComicProject.Page first = project.pages.get(0);
        for (ComicProject.Page p : project.pages) if (p.id.equals(project.coverPageId)) first = p;
        Bitmap full = null, small = null;
        try {
            File ready =
                    first.editable()
                            ? new File(project.draftDir(first), "rendered.png")
                            : project.imageFile(first);
            if (first.editable()
                    && (first.editedCount() > 0
                            || !project.defaultStyle.isDefault()
                            || !ready.isFile())) {
                try (PageComposer composer =
                        new PageComposer(
                                PageDraft.read(project.draftDir(first)).withEdits(first.edits),
                                1440)) {
                    composer.layoutAll(project.effectiveEdits(first, composer.draft), () -> false);
                    full = composer.compose(null);
                }
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                if (ready == null) return;
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(ready.getPath(), options);
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 720
                        || options.outHeight / options.inSampleSize > 2880)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                full = BitmapFactory.decodeFile(ready.getPath(), options);
            }
            if (full == null) return;
            // Keep the aspect ratio; very tall strips show their top part only.
            int width = 360,
                    height =
                            Math.max(
                                    1,
                                    Math.round(
                                            full.getHeight() * (width / (float) full.getWidth())));
            int sourceHeight =
                    Math.min(
                            full.getHeight(),
                            Math.round(Math.min(height, 1440) * full.getWidth() / (float) width));
            Bitmap top =
                    sourceHeight < full.getHeight()
                            ? Bitmap.createBitmap(full, 0, 0, full.getWidth(), sourceHeight)
                            : full;
            small = Bitmap.createScaledBitmap(top, width, Math.min(height, 1440), true);
            if (top != full && top != small) top.recycle();
            try (OutputStream out = new FileOutputStream(cover(project))) {
                PerformanceDiagnostics.compress(
                        "cover", small, Bitmap.CompressFormat.JPEG, 85, out);
            }
        } catch (Exception | OutOfMemoryError ignored) {
        } finally {
            if (small != null && small != full) small.recycle();
            if (full != null) full.recycle();
        }
    }
}
