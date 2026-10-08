package cn.local.manga;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Incremental durable session rows. Project imports always copy independent files. */
final class StoredSessions {
    static String id(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String key(String url) {
        return url.split("#", 2)[0];
    }

    private static String owner(String session, String page) {
        return session + "/" + page;
    }

    private static String activeKey(String id) {
        return "session_authority/" + id;
    }

    private static String sessionId(StorageDatabase s, String url) throws Exception {
        String id = id(key(url));
        if (s.number("SELECT count(*) FROM session WHERE id=?", id + "-v2") > 0) return id + "-v2";
        File old = new File(new File(s.files, "browser-sessions"), id);
        if (new File(old, "project.json").isFile() && s.meta(activeKey(id)) == null) {
            try {
                migrate(s, old);
            } catch (Exception e) {
                StorageDatabase.problem = "旧会话未迁移，继续只读保留：" + StorageDatabase.message(e);
                return id + "-v2";
            }
        }
        return id;
    }

    private static void ensure(StorageDatabase s, String id, String url, String title)
            throws Exception {
        long now = System.currentTimeMillis();
        JSONObject header =
                new JSONObject()
                        .put("schema", 1)
                        .put("schemaVersion", 2)
                        .put("id", id)
                        .put("title", title)
                        .put("sourceKind", "web")
                        .put("sourceKey", key(url))
                        .put("created", now)
                        .put("updated", now);
        s.db.execSQL(
                "INSERT OR IGNORE INTO"
                    + " session(id,source_key,title,created_at,updated_at,last_access_at,extra_json)"
                    + " VALUES(?,?,?,?,?,?,?)",
                new Object[] {id, key(url), title, now, now, now, header.toString()});
    }

    private static JSONObject page(StorageDatabase s, String session, String imageUrl, int order)
            throws Exception {
        String pageId = id(imageUrl),
                json =
                        s.scalar(
                                "SELECT json FROM session_page WHERE session_id=? AND page_id=?",
                                session,
                                pageId);
        return (json == null
                        ? new JSONObject()
                                .put("id", pageId)
                                .put("kind", ComicProject.KIND_ORIGINAL)
                                .put("status", "untranslated")
                                .put("reviewed", false)
                                .put("edits", new JSONObject())
                        : new JSONObject(json))
                .put("label", String.format(Locale.ROOT, "%05d", order + 1));
    }

    private static void upsertPage(
            StorageDatabase s, String session, JSONObject page, int order, long updated)
            throws Exception {
        s.db.execSQL(
                "INSERT OR REPLACE INTO"
                    + " session_page(session_id,page_id,label,page_order,kind,status,json,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                new Object[] {
                    session,
                    page.getString("id"),
                    page.optString("label"),
                    order,
                    page.optString("kind", "rendered"),
                    page.optString("status", "untranslated"),
                    page.toString(),
                    updated
                });
    }

    private static void touch(StorageDatabase s, String id) {
        long now = System.currentTimeMillis();
        s.db.execSQL(
                "UPDATE session SET updated_at=?,last_access_at=? WHERE id=?",
                new Object[] {now, now, id});
        s.meta(activeKey(id), "db");
    }

    static void original(
            Context context, String url, String title, String imageUrl, int order, Bitmap bitmap)
            throws Exception {
        StorageDatabase.call(
                context,
                s -> {
                    String session = sessionId(s, url);
                    JSONObject page = page(s, session, imageUrl, order);
                    BlobStore blobs = new BlobStore(s);
                    try (BlobStore.Prepared original =
                            blobs.put(
                                    out -> {
                                        if (!PerformanceDiagnostics.compress(
                                                "session_original",
                                                bitmap,
                                                Bitmap.CompressFormat.PNG,
                                                100,
                                                out)) throw new IOException("会话原图编码失败");
                                    },
                                    "lossless-original",
                                    "image/png")) {
                        page.put("original", "original.png");
                        if (!page.has("image")) page.put("image", "original.png");
                        s.db.beginTransaction();
                        try {
                            ensure(s, session, url, title);
                            original.reference(
                                    "session_page",
                                    owner(session, page.getString("id")),
                                    "original.png");
                            upsertPage(s, session, page, order, System.currentTimeMillis());
                            touch(s, session);
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                        PerformanceDiagnostics.file("session_original", blobs.file(original.hash));
                    }
                    return null;
                });
    }

    static void record(
            Context context,
            String url,
            String title,
            String imageUrl,
            int order,
            File rendered,
            String draftKey)
            throws Exception {
        // Resolve before entering the writer; nested calls on that worker are safe too.
        File draft = PageDraftStore.find(context, draftKey);
        StorageDatabase.call(
                context,
                s -> {
                    String session = sessionId(s, url);
                    JSONObject page = page(s, session, imageUrl, order);
                    BlobStore blobs = new BlobStore(s);
                    try (BlobStore.Prepared result = blobs.put(rendered, "lossless-rendered");
                            StoredDrafts.Prepared prepared =
                                    draft == null ? null : StoredDrafts.prepare(s, draft)) {
                        page.put("image", "rendered.png")
                                .put(
                                        "kind",
                                        prepared == null
                                                ? ComicProject.KIND_RENDERED
                                                : ComicProject.KIND_EDITABLE)
                                .put("status", "translated");
                        String owner = owner(session, page.getString("id"));
                        s.db.beginTransaction();
                        try {
                            ensure(s, session, url, title);
                            s.db.execSQL(
                                    "DELETE FROM blob_ref WHERE owner_type='session_page' AND"
                                            + " owner_id=? AND role LIKE 'draft/%'",
                                    new Object[] {owner});
                            result.reference("session_page", owner, "rendered.png");
                            if (prepared != null) prepared.reference("session_page", owner);
                            upsertPage(s, session, page, order, System.currentTimeMillis());
                            touch(s, session);
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                        PerformanceDiagnostics.file("session_rendered", blobs.file(result.hash));
                    }
                    return null;
                });
    }

    static List<ComicProject> list(Context context) throws Exception {
        return StorageDatabase.call(
                context,
                s -> {
                    migrateAll(s);
                    List<ComicProject> out = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    try (Cursor c =
                            s.db.rawQuery(
                                    "SELECT id FROM session ORDER BY updated_at DESC", null)) {
                        while (c.moveToNext())
                            if ("db".equals(s.meta(activeKey(c.getString(0)))))
                                ids.add(c.getString(0));
                    }
                    for (String id : ids) out.add(read(s, id));
                    File[] old = new File(s.files, "browser-sessions").listFiles(File::isDirectory);
                    if (old != null)
                        for (File dir : old)
                            if (s.meta(activeKey(dir.getName())) == null) {
                                try {
                                    ComicProject legacy = ComicProject.load(dir);
                                    legacy.legacySession = true;
                                    out.add(legacy);
                                } catch (Exception ignored) {
                                    StorageDatabase.problem = "存在无法读取的旧会话，旧目录已保留";
                                }
                            }
                    String current =
                            key(
                                    SessionRepository.currentUrl == null
                                            ? ""
                                            : SessionRepository.currentUrl);
                    out.sort(
                            Comparator.comparing((ComicProject p) -> !p.sourceKey.equals(current))
                                    .thenComparing(
                                            Comparator.comparingLong((ComicProject p) -> p.updated)
                                                    .reversed()));
                    return out;
                });
    }

    static ComicProject read(StorageDatabase s, String id) throws Exception {
        String json = s.scalar("SELECT extra_json FROM session WHERE id=?", id);
        if (json == null) throw new IOException("会话不存在");
        JSONObject header = new JSONObject(json);
        ComicProject out = new ComicProject(new File(new File(s.files, "session-views-v2"), id));
        out.id = id;
        out.title = header.optString("title");
        out.sourceKind = "web";
        out.sourceKey = header.optString("sourceKey");
        out.created = header.optLong("created");
        out.updated = s.number("SELECT updated_at FROM session WHERE id=?", id);
        out.databaseSession = true;
        out.coverPageId = header.optString("coverPageId");
        if (header.optJSONObject("defaultStyle") != null)
            out.defaultStyle = ComicProject.edit(header.getJSONObject("defaultStyle"));
        if (header.optJSONObject("export") != null)
            out.exportOptions = header.getJSONObject("export");
        BlobStore blobs = new BlobStore(s);
        try (Cursor c =
                s.db.rawQuery(
                        "SELECT json FROM session_page WHERE session_id=? ORDER BY"
                                + " page_order,page_id",
                        new String[] {id})) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject(c.getString(0));
                ComicProject.Page page = new ComicProject.Page();
                page.id = row.getString("id");
                page.label = row.optString("label");
                page.kind = row.optString("kind");
                page.status = row.optString("status");
                page.imageName = row.optString("image", null);
                page.originalName = row.optString("original", null);
                page.reviewed = row.optBoolean("reviewed");
                JSONObject edits = row.optJSONObject("edits");
                if (edits != null)
                    for (Iterator<String> keys = edits.keys(); keys.hasNext(); ) {
                        String name = keys.next();
                        page.edits.put(name, ComicProject.edit(edits.getJSONObject(name)));
                    }
                String owner = owner(id, page.id);
                try {
                    if (page.imageName != null)
                        out.sessionImages.put(
                                page.id,
                                blobs.resolve(
                                        "session_page",
                                        owner,
                                        page.imageName,
                                        "lossless-original",
                                        "lossless-rendered",
                                        "encoded-original"));
                    if (page.editable()) {
                        JSONObject files = new JSONObject();
                        try (Cursor f =
                                s.db.rawQuery(
                                        "SELECT r.role,r.hash,r.kind,b.size FROM blob_ref r JOIN"
                                            + " blob b ON r.hash=b.hash WHERE"
                                            + " r.owner_type='session_page' AND r.owner_id=? AND"
                                            + " r.role LIKE 'draft/%'",
                                        new String[] {owner})) {
                            while (f.moveToNext())
                                files.put(
                                        f.getString(0).substring(6),
                                        new JSONObject()
                                                .put("hash", f.getString(1))
                                                .put("kind", f.getString(2))
                                                .put("size", f.getLong(3)));
                        }
                        out.sessionDrafts.put(
                                page.id,
                                StoredDrafts.materialize(
                                        s,
                                        new JSONObject().put("version", 1).put("files", files),
                                        new File(new File(out.dir, "pages"), page.id)));
                    }
                } catch (Exception damaged) {
                    out.storageIssue = "第 " + page.label + " 页：" + StorageDatabase.message(damaged);
                    StorageDatabase.problem = out.storageIssue;
                }
                out.pages.add(page);
            }
        }
        return out;
    }

    static void migrateAll(StorageDatabase s) {
        File[] dirs = new File(s.files, "browser-sessions").listFiles(File::isDirectory);
        if (dirs == null) return;
        int scanned = 0;
        try {
            for (File dir : dirs)
                try {
                    StorageDatabase.progress = "核对旧会话 " + (++scanned) + " / " + dirs.length;
                    migrate(s, dir);
                } catch (Exception e) {
                    StorageMigration.issue(s, "session-v1-" + dir.getName(), e);
                    StorageDatabase.problem = "部分旧会话未迁移：" + StorageDatabase.message(e);
                }
        } finally {
            StorageDatabase.progress = "";
        }
    }

    private static void migrate(StorageDatabase s, File dir) throws Exception {
        String id = dir.getName();
        if (s.meta(activeKey(id)) != null) {
            StorageMigration.resolved(s, "session-v1-" + id);
            return;
        }
        File source = new File(dir, "project.json");
        if (!source.isFile() || source.length() > 16L * 1024 * 1024)
            throw new IOException("会话元数据缺失或过大");
        JSONObject json =
                new JSONObject(
                        new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        if (json.getInt("schema") != 1
                || json.optInt("schemaVersion", 1) > 2
                || !id.equals(json.getString("id"))) throw new IOException("旧会话版本或标识不支持，未迁移");
        JSONArray pages = json.getJSONArray("pages");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            String pageId = page.getString("id");
            if (!pageId.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(pageId))
                throw new IOException("旧会话页标识无效或重复");
            File pageDir = StorageFiles.child(dir, "pages/" + pageId);
            if (page.has("image") && !StorageFiles.child(pageDir, page.getString("image")).isFile())
                throw new IOException("旧会话图片缺失");
            if (ComicProject.KIND_EDITABLE.equals(page.optString("kind")))
                PageDraft.read(new File(pageDir, "draft"));
        }
        String checksum = StoredDrafts.treeHash(dir);
        StorageMigration migration =
                new StorageMigration(s, "session-v1-" + id, checksum, pages.length());
        StorageQuota.requireMigrationSpace(s, PageDraftStore.size(dir));
        MigrationRunner.run(
                migration,
                step -> {
                    if (step == MigrationRunner.BACKUP) {
                        File backup = new File(s.files, "migration-backup/session-v1/" + id);
                        StorageFiles.mkdir(backup);
                        try (java.util.stream.Stream<Path> walk = Files.walk(dir.toPath())) {
                            for (Path file :
                                    (Iterable<Path>)
                                            walk.filter(
                                                            p ->
                                                                    p.getFileName()
                                                                                    .toString()
                                                                                    .equals(
                                                                                            "project.json")
                                                                            || p.getFileName()
                                                                                    .toString()
                                                                                    .equals(
                                                                                            "draft.json"))
                                                    ::iterator) {
                                String relative = dir.toPath().relativize(file).toString();
                                File target = StorageFiles.child(backup, relative);
                                if (!target.isFile()) StorageFiles.copy(file.toFile(), target);
                                if (!StorageFiles.hash(file.toFile())
                                        .equals(StorageFiles.hash(target)))
                                    throw new IOException("会话元数据备份不一致");
                            }
                        }
                    } else if (step == MigrationRunner.IMPORT) {
                        JSONObject header = StorageJson.copy(json);
                        header.remove("pages");
                        s.db.execSQL(
                                "INSERT OR IGNORE INTO"
                                    + " session(id,source_key,title,created_at,updated_at,last_access_at,extra_json)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                new Object[] {
                                    id,
                                    json.optString("sourceKey"),
                                    json.optString("title"),
                                    json.optLong("created"),
                                    json.optLong("updated"),
                                    json.optLong("updated"),
                                    header.toString()
                                });
                        for (int i = 0; i < pages.length(); i++) {
                            JSONObject page = pages.getJSONObject(i);
                            String pageId = page.getString("id");
                            File pageDir = new File(new File(dir, "pages"), pageId);
                            Map<String, BlobStore.Prepared> files = new LinkedHashMap<>();
                            try {
                                try (java.util.stream.Stream<Path> walk =
                                        Files.walk(pageDir.toPath())) {
                                    for (Path file :
                                            (Iterable<Path>)
                                                    walk.filter(Files::isRegularFile)::iterator) {
                                        if (Files.isSymbolicLink(file))
                                            throw new IOException("旧会话文件含重定向");
                                        String role =
                                                pageDir.toPath()
                                                        .relativize(file)
                                                        .toString()
                                                        .replace(File.separatorChar, '/');
                                        String kind =
                                                role.endsWith(".bin")
                                                        ? "mask"
                                                        : role.endsWith(".json")
                                                                ? "metadata"
                                                                : role.endsWith("source.png")
                                                                                || role.equals(
                                                                                        page
                                                                                                .optString(
                                                                                                        "original"))
                                                                        ? "lossless-original"
                                                                        : role.endsWith("clean.png")
                                                                                ? "lossless-layer"
                                                                                : "lossless-rendered";
                                        files.put(role, new BlobStore(s).put(file.toFile(), kind));
                                    }
                                }
                                s.db.beginTransaction();
                                try {
                                    for (Map.Entry<String, BlobStore.Prepared> file :
                                            files.entrySet())
                                        file.getValue()
                                                .reference(
                                                        "session_page",
                                                        owner(id, pageId),
                                                        file.getKey());
                                    upsertPage(s, id, page, i, json.optLong("updated"));
                                    s.db.setTransactionSuccessful();
                                } finally {
                                    s.db.endTransaction();
                                }
                            } finally {
                                for (BlobStore.Prepared file : files.values()) file.close();
                            }
                        }
                    } else if (step == MigrationRunner.VERIFY) {
                        JSONObject actual =
                                new JSONObject(
                                        s.scalar("SELECT extra_json FROM session WHERE id=?", id));
                        JSONArray rows = new JSONArray();
                        try (Cursor c =
                                s.db.rawQuery(
                                        "SELECT json FROM session_page WHERE session_id=? ORDER BY"
                                                + " page_order",
                                        new String[] {id})) {
                            while (c.moveToNext()) rows.put(new JSONObject(c.getString(0)));
                        }
                        actual.put("pages", rows);
                        StorageJson.same(json, actual, "会话全部元数据及页序");
                        BlobStore blobs = new BlobStore(s);
                        for (int i = 0; i < pages.length(); i++) {
                            JSONObject page = pages.getJSONObject(i);
                            String pageId = page.getString("id");
                            File pageDir = new File(new File(dir, "pages"), pageId);
                            try (Cursor c =
                                    s.db.rawQuery(
                                            "SELECT role,hash FROM blob_ref WHERE"
                                                    + " owner_type='session_page' AND owner_id=?",
                                            new String[] {owner(id, pageId)})) {
                                while (c.moveToNext()) {
                                    File from = StorageFiles.child(pageDir, c.getString(0));
                                    String hash = c.getString(1);
                                    if (!hash.equals(StorageFiles.hash(from))
                                            || !hash.equals(StorageFiles.hash(blobs.file(hash))))
                                        throw new IOException("会话图片哈希不一致");
                                }
                            }
                        }
                        if (!checksum.equals(StoredDrafts.treeHash(dir)))
                            throw new IOException("迁移期间会话源已变化");
                        migration.verified(rows.length());
                    } else if (step == MigrationRunner.CUTOVER) {
                        s.db.beginTransaction();
                        try {
                            s.meta(activeKey(id), "db");
                            s.db.execSQL(
                                    "INSERT OR REPLACE INTO"
                                            + " legacy_verified(path,migration_id,checksum,bytes)"
                                            + " VALUES(?,?,?,?)",
                                    new Object[] {
                                        "browser-sessions/" + id,
                                        migration.id,
                                        checksum,
                                        PageDraftStore.size(dir)
                                    });
                            migration.completed(MigrationRunner.DONE);
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                    }
                });
        StorageMigration.resolved(s, "session-v1-" + id);
        s.backup(true);
    }

    private StoredSessions() {}
}
