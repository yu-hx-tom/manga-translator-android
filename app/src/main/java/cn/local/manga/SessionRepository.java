package cn.local.manga;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.widget.*;

import java.io.*;
import java.util.*;

/** Durable browser sessions. Project imports take independent copies, never cache references. */
final class SessionRepository {
    static volatile String currentUrl = "";

    static void original(
            Context c,
            String url,
            String title,
            String imageUrl,
            int order,
            android.graphics.Bitmap source)
            throws Exception {
        StoredSessions.original(c, url, title, imageUrl, order, source);
    }

    static void record(
            Context c,
            String url,
            String title,
            String imageUrl,
            int order,
            File rendered,
            String draftKey)
            throws Exception {
        StoredSessions.record(c, url, title, imageUrl, order, rendered, draftKey);
    }

    static List<ComicProject> list(Context c) {
        try {
            return StoredSessions.list(c);
        } catch (Exception e) {
            StorageDatabase.problem = "无法读取会话：" + StorageDatabase.message(e);
            return new ArrayList<>();
        }
    }

    static void choose(Activity activity) {
        if (!CacheStorage.beginUse()) {
            Toast.makeText(activity, "正在清理存储，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(
                        () -> {
                            List<ComicProject> sessions = list(activity);
                            activity.runOnUiThread(
                                    () -> {
                                        if (activity.isDestroyed()) {
                                            CacheStorage.endUse();
                                            return;
                                        }
                                        Ui.Sheet dialog = Ui.sheet(activity, "导入浏览器译本");
                                        LinearLayout body = dialog.body;
                                        if (sessions.isEmpty())
                                            body.addView(
                                                    Ui.text(
                                                            activity,
                                                            "暂无翻译会话。先在浏览器翻译漫画，再到这里导入。",
                                                            15,
                                                            Ui.MUTED));
                                        for (ComicProject session : sessions) {
                                            LinearLayout row = new LinearLayout(activity);
                                            row.setGravity(Gravity.CENTER_VERTICAL);
                                            ImageView cover = new ImageView(activity);
                                            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                            row.addView(
                                                    cover,
                                                    new LinearLayout.LayoutParams(
                                                            Ui.dp(activity, 52),
                                                            Ui.dp(activity, 72)));
                                            String current =
                                                    session.sourceKey.equals(
                                                                    currentUrl == null
                                                                            ? ""
                                                                            : currentUrl
                                                                                    .split("#", 2)[
                                                                                    0])
                                                            ? "当前页面 · "
                                                            : "";
                                            long translated =
                                                    session.pages.stream()
                                                            .filter(
                                                                    p ->
                                                                            p.editable()
                                                                                    || "translated"
                                                                                            .equals(
                                                                                                    p.status))
                                                            .count();
                                            TextView label =
                                                    Ui.text(
                                                            activity,
                                                            current
                                                                    + (session.legacySession
                                                                            ? "旧格式 · "
                                                                            : "")
                                                                    + (session.storageIssue
                                                                                    .isEmpty()
                                                                            ? ""
                                                                            : "文件待恢复 · ")
                                                                    + session.title
                                                                    + "\n"
                                                                    + Uri.parse(session.sourceKey)
                                                                            .getHost()
                                                                    + " · "
                                                                    + session.pages.size()
                                                                    + " 页 · 已翻 "
                                                                    + translated
                                                                    + "\n"
                                                                    + android.text.format.DateUtils
                                                                            .getRelativeTimeSpanString(
                                                                                    session.updated),
                                                            13,
                                                            Ui.INK);
                                            label.setPadding(
                                                    Ui.dp(activity, 12),
                                                    Ui.dp(activity, 8),
                                                    0,
                                                    Ui.dp(activity, 8));
                                            row.addView(
                                                    label, new LinearLayout.LayoutParams(0, -2, 1));
                                            body.addView(row);
                                            if (!session.pages.isEmpty())
                                                ProjectCover.bind(
                                                        cover,
                                                        session.imageFile(session.pages.get(0)));
                                            row.setOnClickListener(
                                                    v -> {
                                                        if (!session.storageIssue.isEmpty()) {
                                                            Toast.makeText(
                                                                            activity,
                                                                            session.storageIssue
                                                                                    + "；请运行存储自检",
                                                                            Toast.LENGTH_LONG)
                                                                    .show();
                                                            return;
                                                        }
                                                        importSession(activity, session);
                                                        dialog.dismiss();
                                                    });
                                        }
                                        dialog.setOnDismissListener(d -> CacheStorage.endUse());
                                        dialog.show();
                                    });
                        },
                        "session-list")
                .start();
    }

    private static void importSession(Activity a, ComicProject session) {
        ProjectLauncher.launch(
                a,
                session.title,
                "web",
                "web:" + session.sourceKey,
                (progress, cancelled) -> snapshot(a, session, progress, cancelled),
                null);
    }

    private static synchronized ProjectLauncher.Gathered snapshot(
            Activity a,
            ComicProject session,
            ProjectStore.Progress progress,
            java.util.function.BooleanSupplier cancelled)
            throws Exception {
        File scratch = new File(a.getCacheDir(), "session-import-" + UUID.randomUUID());
        ComicProject current =
                session.databaseSession
                        ? StorageDatabase.call(a, s -> StoredSessions.read(s, session.id))
                        : ComicProject.load(session.dir);
        if (session.databaseSession)
            StorageDatabase.call(
                    a,
                    s -> {
                        s.db.execSQL(
                                "UPDATE session SET last_access_at=? WHERE id=?",
                                new Object[] {System.currentTimeMillis(), session.id});
                        return null;
                    });
        return snapshotFiles(current, scratch, progress, cancelled);
    }

    static ProjectLauncher.Gathered snapshotFiles(
            ComicProject session,
            File scratch,
            ProjectStore.Progress progress,
            java.util.function.BooleanSupplier cancelled)
            throws Exception {
        try {
            if (!session.storageIssue.isEmpty()) throw new IOException(session.storageIssue);
            if (!scratch.mkdirs()) throw new IOException("无法创建导入副本");
            List<ProjectStore.PageSpec> specs = new ArrayList<>();
            int done = 0;
            progress.update(0, session.pages.size());
            for (ComicProject.Page p : session.pages) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                File target = new File(scratch, p.id),
                        draft = session.draftDir(p),
                        image = session.imageFile(p);
                ProjectStore.PageSpec spec;
                if (p.editable() && new File(draft, PageDraft.JSON).isFile()) {
                    // Copy layers once; don't also copy the duplicate page original/rendered
                    // images.
                    copyVerified(draft, target, cancelled);
                    File rendered = new File(target, "rendered.png");
                    if (!rendered.isFile() && image != null && image.isFile())
                        copyVerified(image, rendered, cancelled);
                    spec = new ProjectStore.PageSpec(p.label, null, null, "png", false, target);
                } else {
                    if (image == null || !image.isFile())
                        throw new IOException("会话页面已失效，请回网页重新加载：" + p.label);
                    target.mkdirs();
                    File saved = new File(target, "image.png");
                    copyVerified(image, saved, cancelled);
                    spec =
                            new ProjectStore.PageSpec(
                                    p.label,
                                    null,
                                    null,
                                    "png",
                                    ComicProject.KIND_ORIGINAL.equals(p.kind));
                    spec.ownedImage = saved;
                }
                spec.initialStatus = p.status;
                spec.initialReviewed = p.reviewed;
                if (p.editable())
                    spec.initialEdits = session.effectiveEdits(p, PageDraft.read(draft));
                spec.disposableSnapshot = true;
                specs.add(spec);
                progress.update(++done, session.pages.size());
            }
            ProjectLauncher.Gathered result = new ProjectLauncher.Gathered(specs, "");
            result.cleanup = () -> PageDraftStore.deleteTree(scratch);
            return result;
        } catch (Exception e) {
            PageDraftStore.deleteTree(scratch);
            throw e;
        }
    }

    private static void copyVerified(
            File from, File to, java.util.function.BooleanSupplier cancelled) throws Exception {
        PageDraftStore.copyTree(from, to, cancelled);
        String
                expected =
                        from.isDirectory() ? StoredDrafts.treeHash(from) : StorageFiles.hash(from),
                actual = to.isDirectory() ? StoredDrafts.treeHash(to) : StorageFiles.hash(to);
        if (!expected.equals(actual)) throw new IOException("工程导入副本校验失败，未提交工程");
    }
}
