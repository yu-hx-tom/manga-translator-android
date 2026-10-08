package cn.local.manga;

import android.content.Context;
import android.database.Cursor;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** One quota owner. Database accounting is corrected by a bounded-frequency directory census. */
final class StorageQuota {
    static volatile long availableBytes = -1;
    private static long lastCleanup;
    static final String[] LABELS = {"可再生缓存", "浏览器会话", "用户数据（不清理）", "旧版数据（已迁移）", "备份、旧缓存与其他"};

    static void space(File directory) {
        try {
            availableBytes = new StatFs(directory.getPath()).getAvailableBytes();
        } catch (Exception e) {
            availableBytes = -1;
        }
    }

    static boolean canReserve(Context c, long bytes) {
        space(c.getFilesDir());
        return StoragePolicy.canReserve(availableBytes, bytes);
    }

    static void requireSpace(Context c) throws IOException {
        if (!StorageDatabase.ready) throw new IOException("存储不可用，已暂停新翻译，请运行存储自检");
        if (!canReserve(c, 0)) throw new IOException("可用空间不足 300 MB 或无法确认空间，已暂停新翻译，请先清理存储");
    }

    static void requireMigrationSpace(StorageDatabase s, long sourceBytes) throws IOException {
        space(s.files);
        long reserve =
                sourceBytes < 0 || sourceBytes > (Long.MAX_VALUE - 16 * StoragePolicy.MIB) / 2
                        ? Long.MAX_VALUE
                        : sourceBytes * 2 + 16 * StoragePolicy.MIB;
        if (!StoragePolicy.canReserve(availableBytes, reserve))
            throw new IOException(
                    "迁移空间不足：需为独立复制和视图副本预留约 "
                            + (reserve / StoragePolicy.MIB)
                            + " MB，另保留 300 MB；旧数据未修改");
    }

    static String admissionProblem() {
        return !StorageDatabase.ready
                ? "存储尚未准备好，请稍后重试或运行存储自检"
                : availableBytes < StoragePolicy.MIN_FREE
                        ? "可用空间不足 300 MB，已暂停新翻译，请清理存储"
                        : "已有翻译任务或正在清理，请先等待完成";
    }

    static String category(String relative) {
        if (relative.startsWith("cache/")) {
            String rest = relative.substring(6);
            return rest.startsWith("translations-v2/")
                            || rest.startsWith("detections-v2/")
                            || rest.startsWith("browser-pages-v2/")
                            || rest.startsWith("text-jobs/")
                            || rest.startsWith("import-")
                            || rest.startsWith("session-import-")
                    ? "cache"
                    : "other";
        }
        if (relative.startsWith("files/projects/") || relative.startsWith("files/local-comics/"))
            return "user";
        if (relative.startsWith("files/browser-sessions/")) return "other";
        if (relative.startsWith("files/page-drafts-v2/")
                || relative.startsWith("files/session-views-v2/")) return "alias";
        if (relative.startsWith("files/page-drafts/")) return "other";
        return "other";
    }

    static void note(File file) {
        StorageDatabase.dispatch(
                s -> {
                    track(s, file);
                    maintain(s, false);
                    return null;
                });
    }

    static void track(StorageDatabase s, File file) throws Exception {
        String path = relative(s, file);
        if (path == null) return;
        if (!file.isFile()) {
            s.db.execSQL("DELETE FROM file_usage WHERE path=?", new Object[] {path});
            return;
        }
        if (Files.isSymbolicLink(file.toPath())) return;
        String category = category(path);
        long bytes = file.length();
        if (category.equals("alias") || path.startsWith("cache/browser-pages-v2/"))
            try {
                if (android.system.Os.stat(file.getPath()).st_nlink > 1) bytes = 0;
            } catch (android.system.ErrnoException ignored) {
            }
        if (category.equals("alias")) {
            String[] parts = path.split("/", 4);
            boolean session = path.startsWith("files/session-views-v2/");
            category =
                    parts.length > 2
                                    && "db"
                                            .equals(
                                                    s.meta(
                                                            (session
                                                                            ? "session_authority/"
                                                                            : "draft_authority/")
                                                                    + parts[2]))
                            ? (session ? "session" : "cache")
                            : "other";
        }
        s.db.execSQL(
                "INSERT OR REPLACE INTO file_usage(path,category,bytes,last_access_at)"
                        + " VALUES(?,?,?,?)",
                new Object[] {path, category, bytes, file.lastModified()});
    }

    private static String relative(StorageDatabase s, File file) throws IOException {
        Path actual = file.getCanonicalFile().toPath();
        for (File root : new File[] {s.files, s.cache}) {
            Path base = root.getCanonicalFile().toPath();
            if (actual.startsWith(base) && !actual.equals(base))
                return (root.equals(s.files) ? "files/" : "cache/")
                        + base.relativize(actual).toString().replace(File.separatorChar, '/');
        }
        return null;
    }

    static void reconcile(StorageDatabase s, boolean force) throws Exception {
        long now = System.currentTimeMillis(), last = 0;
        try {
            last = Long.parseLong(s.meta("quota_reconcile"));
        } catch (Exception ignored) {
        }
        if (!force && now - last < 24 * 60 * 60 * 1000L) return;
        Set<String> seen = new HashSet<>();
        s.db.beginTransaction();
        try {
            for (File root : new File[] {s.files, s.cache})
                if (root.isDirectory())
                    Files.walkFileTree(
                            root.toPath(),
                            new SimpleFileVisitor<Path>() {
                                public FileVisitResult preVisitDirectory(
                                        Path dir, BasicFileAttributes attrs) {
                                    return dir.getFileName().toString().equals("blobs")
                                            ? FileVisitResult.SKIP_SUBTREE
                                            : FileVisitResult.CONTINUE;
                                }

                                public FileVisitResult visitFile(
                                        Path path, BasicFileAttributes attrs) throws IOException {
                                    if (attrs.isRegularFile())
                                        try {
                                            String relative = relative(s, path.toFile());
                                            if (relative != null) {
                                                track(s, path.toFile());
                                                seen.add(relative);
                                            }
                                        } catch (Exception e) {
                                            throw new IOException(e);
                                        }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
            List<String> old = new ArrayList<>();
            try (Cursor c = s.db.rawQuery("SELECT path FROM file_usage", null)) {
                while (c.moveToNext()) if (!seen.contains(c.getString(0))) old.add(c.getString(0));
            }
            for (String path : old)
                s.db.execSQL("DELETE FROM file_usage WHERE path=?", new Object[] {path});
            try (Cursor c = s.db.rawQuery("SELECT path FROM legacy_verified", null)) {
                while (c.moveToNext()) {
                    String path = "files/" + c.getString(0);
                    s.db.execSQL(
                            "UPDATE file_usage SET category='legacy' WHERE path=? OR path LIKE ?",
                            new Object[] {path, path + "/%"});
                }
            }
            s.meta("quota_reconcile", Long.toString(now));
            s.db.setTransactionSuccessful();
        } finally {
            s.db.endTransaction();
        }
        space(s.files);
    }

    static long[] sizes(StorageDatabase s) {
        long[] result = new long[LABELS.length];
        String[] cats = {"cache", "session", "user", "legacy", "other"};
        for (int i = 0; i < cats.length; i++)
            result[i] =
                    s.number(
                            "SELECT COALESCE(SUM(bytes),0) FROM file_usage WHERE category=?",
                            cats[i]);
        String user =
                "EXISTS(SELECT 1 FROM blob_ref r WHERE r.hash=b.hash AND"
                        + " r.owner_type='project_page')";
        String sessions =
                "EXISTS(SELECT 1 FROM blob_ref r JOIN session_page p ON"
                        + " r.owner_id=p.session_id||'/'||p.page_id JOIN meta m ON"
                        + " m.key='session_authority/'||p.session_id WHERE r.hash=b.hash AND"
                        + " r.owner_type='session_page' AND m.value='db')";
        String caches =
                "EXISTS(SELECT 1 FROM blob_ref r WHERE r.hash=b.hash AND (r.owner_type='cache' AND"
                        + " EXISTS(SELECT 1 FROM cache_entry c WHERE c.key=r.owner_id) OR"
                        + " r.owner_type='draft' AND EXISTS(SELECT 1 FROM meta m WHERE"
                        + " m.key='draft_authority/'||r.owner_id AND m.value='db')))";
        result[2] += s.number("SELECT COALESCE(SUM(size),0) FROM blob b WHERE " + user);
        result[1] +=
                s.number(
                        "SELECT COALESCE(SUM(size),0) FROM blob b WHERE NOT "
                                + user
                                + " AND "
                                + sessions);
        result[0] +=
                s.number(
                        "SELECT COALESCE(SUM(size),0) FROM blob b WHERE NOT "
                                + user
                                + " AND NOT "
                                + sessions
                                + " AND "
                                + caches);
        result[4] +=
                s.number(
                        "SELECT COALESCE(SUM(size),0) FROM blob b WHERE NOT "
                                + user
                                + " AND NOT "
                                + sessions
                                + " AND NOT "
                                + caches);
        result[2] +=
                s.databaseFile.length()
                        + new File(s.databaseFile.getPath() + "-wal").length()
                        + new File(s.databaseFile.getPath() + "-shm").length();
        File[] extra = s.databaseFile.getParentFile().listFiles(File::isFile);
        if (extra != null)
            for (File file : extra)
                if (!file.getName().startsWith("manga.db")) result[4] += file.length();
        return result;
    }

    static void maintain(StorageDatabase s, boolean force) throws Exception {
        space(s.files);
        long now = System.currentTimeMillis();
        if (!force && now - lastCleanup < 60_000) return;
        if (!CacheStorage.beginClear()) return;
        try {
            lastCleanup = now;
            long[] sizes = sizes(s);
            while (sizes[0] > StoragePolicy.cacheBudget(availableBytes)) {
                String kind = null, key = null;
                try (Cursor c =
                        s.db.rawQuery(
                                "SELECT kind,id FROM (SELECT 'cache' AS kind,key AS"
                                    + " id,last_access_at AS age FROM cache_entry UNION ALL SELECT"
                                    + " 'draft',id,updated_at FROM draft WHERE EXISTS(SELECT 1 FROM"
                                    + " meta m WHERE m.key='draft_authority/'||draft.id AND"
                                    + " m.value='db') UNION ALL SELECT 'loose',path,last_access_at"
                                    + " FROM file_usage WHERE category='cache' AND path LIKE"
                                    + " 'cache/%' AND path NOT LIKE 'cache/browser-pages-v2/%')"
                                    + " ORDER BY age LIMIT 1",
                                null)) {
                    if (c.moveToFirst()) {
                        kind = c.getString(0);
                        key = c.getString(1);
                    }
                }
                if ("cache".equals(kind)) removeCache(s, key);
                else if ("draft".equals(kind)) removeDraft(s, key);
                else if ("loose".equals(kind)) deleteAccounted(s, key);
                else break;
                sizes = sizes(s);
            }
            // Low space must never silently discard a session.
            if (availableBytes >= StoragePolicy.MIN_FREE)
                while (sizes[1] > StoragePolicy.sessionBudget(availableBytes)) {
                    String id =
                            s.scalar(
                                    "SELECT id FROM session WHERE EXISTS(SELECT 1 FROM meta m WHERE"
                                            + " m.key='session_authority/'||session.id AND"
                                            + " m.value='db') ORDER BY last_access_at LIMIT 1");
                    if (id == null) break;
                    removeSession(s, id);
                    sizes = sizes(s);
                }
            pruneViews(s);
            new BlobStore(s).reconcile(false);
            new BlobStore(s).collect(now);
            space(s.files);
        } finally {
            CacheStorage.endClear();
        }
    }

    private static boolean removeLooseCache(StorageDatabase s) throws Exception {
        String path =
                s.scalar(
                        "SELECT path FROM file_usage WHERE category='cache' ORDER BY last_access_at"
                                + " LIMIT 1");
        if (path == null) return false;
        deleteAccounted(s, path);
        return true;
    }

    private static void deleteAccounted(StorageDatabase s, String path) throws Exception {
        if (!category(path).equals("cache") && !category(path).equals("alias"))
            throw new IOException("拒绝清理非缓存路径");
        File file =
                path.startsWith("cache/")
                        ? StorageFiles.viewChild(s.cache, path.substring(6))
                        : path.startsWith("files/")
                                ? StorageFiles.viewChild(s.files, path.substring(6))
                                : null;
        if (file == null) throw new IOException("清理路径不在白名单内");
        Files.deleteIfExists(file.toPath());
        s.db.execSQL("DELETE FROM file_usage WHERE path=?", new Object[] {path});
    }

    static void removeCache(StorageDatabase s, String key) throws Exception {
        String data = s.scalar("SELECT metadata FROM cache_entry WHERE key=?", key);
        String alias = data == null ? null : new JSONObject(data).optString("alias", null);
        s.db.beginTransaction();
        try {
            new BlobStore(s).releaseOwner("cache", key);
            s.db.execSQL("DELETE FROM cache_entry WHERE key=?", new Object[] {key});
            s.db.setTransactionSuccessful();
        } finally {
            s.db.endTransaction();
        }
        if (alias != null) deleteAccounted(s, "cache/" + alias);
    }

    static void removeDraft(StorageDatabase s, String id) throws Exception {
        s.db.beginTransaction();
        try {
            new BlobStore(s).releaseOwner("draft", id);
            s.db.execSQL("DELETE FROM draft WHERE id=?", new Object[] {id});
            s.meta("draft_authority/" + id, "deleted");
            s.db.setTransactionSuccessful();
        } finally {
            s.db.endTransaction();
        }
        deleteOwnedTree(s, new File(new File(s.files, StoredDrafts.ROOT), id));
    }

    static void activateDraft(StorageDatabase s, String id) {
        s.db.execSQL(
                "UPDATE file_usage SET category='cache' WHERE path LIKE ?",
                new Object[] {"files/" + StoredDrafts.ROOT + "/" + id + "/%"});
    }

    static void removeSession(StorageDatabase s, String id) throws Exception {
        List<String> pages = new ArrayList<>();
        try (Cursor c =
                s.db.rawQuery(
                        "SELECT page_id FROM session_page WHERE session_id=?", new String[] {id})) {
            while (c.moveToNext()) pages.add(c.getString(0));
        }
        s.db.beginTransaction();
        try {
            for (String page : pages)
                new BlobStore(s).releaseOwner("session_page", id + "/" + page);
            s.db.execSQL("DELETE FROM session WHERE id=?", new Object[] {id});
            s.meta("session_authority/" + id, "deleted");
            s.db.setTransactionSuccessful();
        } finally {
            s.db.endTransaction();
        }
        deleteOwnedTree(s, new File(new File(s.files, "session-views-v2"), id));
    }

    static void deleteOwnedTree(StorageDatabase s, File directory) throws Exception {
        String path = relative(s, directory);
        if (path == null
                || !(path.startsWith("files/page-drafts-v2/")
                        || path.startsWith("files/session-views-v2/")))
            throw new IOException("只允许清理新版缓存视图");
        deleteTree(directory);
        s.db.execSQL(
                "DELETE FROM file_usage WHERE path=? OR path LIKE ?",
                new Object[] {path, path + "/%"});
    }

    /** Called only under the cleanup mutex, so an editor/importer cannot hold an old view. */
    static void pruneViews(StorageDatabase s) throws Exception {
        Set<String> activeAliases = new HashSet<>();
        try (Cursor c = s.db.rawQuery("SELECT metadata FROM cache_entry WHERE kind='web'", null)) {
            while (c.moveToNext()) {
                String path = new JSONObject(c.getString(0)).optString("alias", null);
                if (path != null) activeAliases.add("cache/" + path);
            }
        }
        List<String> abandoned = new ArrayList<>();
        try (Cursor c =
                s.db.rawQuery(
                        "SELECT path,last_access_at FROM file_usage WHERE path LIKE"
                                + " 'cache/browser-pages-v2/%'",
                        null)) {
            while (c.moveToNext())
                if (!activeAliases.contains(c.getString(0))
                        && System.currentTimeMillis() - c.getLong(1) >= BlobStore.GRACE_MS)
                    abandoned.add(c.getString(0));
        }
        for (String path : abandoned) deleteAccounted(s, path);
        File[] drafts = new File(s.files, StoredDrafts.ROOT).listFiles(File::isDirectory);
        if (drafts != null)
            for (File directory : drafts) {
                if (!PageDraftStore.validKey(directory.getName())) continue;
                String json = s.scalar("SELECT json FROM draft WHERE id=?", directory.getName());
                pruneGenerations(
                        s,
                        directory,
                        json == null ? null : StoredDrafts.generation(new JSONObject(json)));
            }
        File[] sessions = new File(s.files, "session-views-v2").listFiles(File::isDirectory);
        if (sessions != null)
            for (File directory : sessions) {
                String id = directory.getName();
                if (s.number("SELECT count(*) FROM session WHERE id=?", id) == 0) {
                    deleteOwnedTree(s, directory);
                    continue;
                }
                File[] pages = new File(directory, "pages").listFiles(File::isDirectory);
                if (pages == null) continue;
                for (File page : pages) {
                    JSONObject entries = new JSONObject();
                    try (Cursor c =
                            s.db.rawQuery(
                                    "SELECT r.role,r.hash,r.kind,b.size FROM blob_ref r JOIN blob b"
                                        + " ON b.hash=r.hash WHERE r.owner_type='session_page' AND"
                                        + " r.owner_id=? AND r.role LIKE 'draft/%'",
                                    new String[] {id + "/" + page.getName()})) {
                        while (c.moveToNext())
                            entries.put(
                                    c.getString(0).substring(6),
                                    new JSONObject()
                                            .put("hash", c.getString(1))
                                            .put("kind", c.getString(2))
                                            .put("size", c.getLong(3)));
                    }
                    pruneGenerations(
                            s,
                            page,
                            entries.length() == 0
                                    ? null
                                    : StoredDrafts.generation(
                                            new JSONObject()
                                                    .put("version", 1)
                                                    .put("files", entries)));
                }
            }
    }

    private static void pruneGenerations(StorageDatabase s, File root, String keep)
            throws Exception {
        File[] generations = root.listFiles(File::isDirectory);
        if (generations != null)
            for (File directory : generations)
                if (directory.getName().matches("[0-9a-f]{64}")
                        && !directory.getName().equals(keep)) deleteOwnedTree(s, directory);
    }

    static void deleteTree(File directory) throws IOException {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(
                directory.toPath(),
                new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(path);
                        return FileVisitResult.CONTINUE;
                    }

                    public FileVisitResult postVisitDirectory(Path path, IOException failure)
                            throws IOException {
                        if (failure != null) throw failure;
                        Files.delete(path);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    static void clearSelected(StorageDatabase s, boolean caches, boolean sessions, boolean legacy)
            throws Exception {
        // The caller already holds CacheStorage.beginClear, also protecting import/export/readers.
        if (caches) {
            List<String> keys = new ArrayList<>();
            try (Cursor c = s.db.rawQuery("SELECT key FROM cache_entry", null)) {
                while (c.moveToNext()) keys.add(c.getString(0));
            }
            for (String key : keys) removeCache(s, key);
            keys.clear();
            try (Cursor c =
                    s.db.rawQuery(
                            "SELECT id FROM draft WHERE EXISTS(SELECT 1 FROM meta m WHERE"
                                    + " m.key='draft_authority/'||draft.id AND m.value='db')",
                            null)) {
                while (c.moveToNext()) keys.add(c.getString(0));
            }
            for (String key : keys) removeDraft(s, key);
            for (String name :
                    new String[] {
                        "browser-pages-v2",
                        "translations-v2",
                        "detections-v2",
                        "browser-pages",
                        "browser-originals-v1",
                        "rendered-pages-v1",
                        "translations",
                        "detections-v1",
                        "text-jobs"
                    }) {
                File dir = StorageFiles.child(s.cache, name);
                deleteTree(dir);
            }
        }
        if (sessions) {
            List<String> ids = new ArrayList<>();
            try (Cursor c =
                    s.db.rawQuery(
                            "SELECT id FROM session WHERE EXISTS(SELECT 1 FROM meta m WHERE"
                                    + " m.key='session_authority/'||session.id AND m.value='db')",
                            null)) {
                while (c.moveToNext()) ids.add(c.getString(0));
            }
            for (String id : ids) removeSession(s, id);
        }
        if (legacy) {
            List<String[]> paths = new ArrayList<>();
            try (Cursor c =
                    s.db.rawQuery(
                            "SELECT v.path,v.checksum FROM legacy_verified v JOIN migration m ON"
                                    + " m.id=v.migration_id WHERE m.state='done'",
                            null)) {
                while (c.moveToNext()) paths.add(new String[] {c.getString(0), c.getString(1)});
            }
            for (String[] path : paths) {
                if (!(path[0].equals("browsing-library.json")
                        || path[0].startsWith("browser-sessions/")
                        || path[0].startsWith("page-drafts/"))) throw new IOException("旧数据清理路径无效");
                File file = StorageFiles.child(s.files, path[0]);
                if (!file.exists()) continue;
                String current =
                        file.isDirectory() ? StoredDrafts.treeHash(file) : StorageFiles.hash(file);
                if (!path[1].equals(current)) throw new IOException("旧数据迁移后发生变化，保留不清理");
                deleteTree(file);
            }
        }
        pruneViews(s);
        new BlobStore(s).reconcile(false);
        new BlobStore(s).collect(System.currentTimeMillis());
        reconcile(s, true);
    }

    // ---------------------------------------------------------------- 1.1.5: views as links,
    // cleanup of 「备份、旧缓存与其他」
    private static final String[] LEGACY_CACHES = {
        "browser-pages",
        "browser-originals-v1",
        "rendered-pages-v1",
        "translations",
        "detections-v1",
        "text-jobs"
    };

    /**
     * Views are disposable projections of the blob store; deleting them is always safe and they are
     * rebuilt on demand. Caller holds CacheStorage.beginClear.
     */
    static void resetViews(StorageDatabase s) throws Exception {
        for (File root :
                new File[] {
                    new File(s.files, StoredDrafts.ROOT),
                    new File(s.files, "session-views-v2"),
                    new File(s.cache, "browser-pages-v2")
                }) {
            File[] children = root.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        s.db.execSQL(
                "DELETE FROM file_usage WHERE path LIKE 'files/"
                        + StoredDrafts.ROOT
                        + "/%' OR path LIKE 'files/session-views-v2/%' OR path LIKE"
                        + " 'cache/browser-pages-v2/%'");
    }

    /**
     * Can this device create symbolic links in app storage? (Hard links are denied to normal apps.)
     */
    static boolean symlinksSupported(StorageDatabase s) {
        File dir = new File(s.files, "storage-v2"),
                target = new File(dir, "link-probe-" + UUID.randomUUID()),
                link = new File(dir, target.getName() + ".lnk");
        try {
            StorageFiles.mkdir(dir);
            Files.write(target.toPath(), new byte[] {1, 2, 3});
            android.system.Os.symlink(target.getCanonicalPath(), link.getPath());
            return Arrays.equals(Files.readAllBytes(link.toPath()), new byte[] {1, 2, 3});
        } catch (Exception unsupported) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(link.toPath());
                Files.deleteIfExists(target.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Once after upgrading: blobs become read-only and the full copies made by 1.1.2-1.1.4 are
     * replaced by on-demand links.
     */
    static void relinkViewsOnce(StorageDatabase s) throws Exception {
        if (s.meta("views_relinked_v1") != null) return;
        if (!CacheStorage.beginClear())
            return; // A task is already running; try again at the next start.
        try {
            File[] shards = new File(s.files, "blobs").listFiles(File::isDirectory);
            if (shards != null)
                for (File shard : shards) {
                    File[] objects =
                            shard.listFiles(f -> f.isFile() && f.getName().matches("[0-9a-f]{64}"));
                    if (objects != null)
                        for (File object : objects) object.setWritable(false, false);
                }
            boolean links = symlinksSupported(s);
            if (links) resetViews(s);
            s.meta("views_relinked_v1", links ? "links" : "copies");
            reconcile(s, true);
        } finally {
            CacheStorage.endClear();
        }
    }

    private static boolean unmigratedSession(StorageDatabase s, File dir) {
        return new File(dir, "project.json").isFile()
                && s.meta("session_authority/" + dir.getName()) == null;
    }

    private static boolean unmigratedDraft(StorageDatabase s, File dir) {
        String key = dir.getName();
        return PageDraftStore.validKey(key)
                && s.meta("draft_authority/" + key) == null
                && s.number(
                                "SELECT count(*) FROM legacy_verified WHERE path=?",
                                "page-drafts/" + key)
                        == 0;
    }

    /**
     * Old-format sessions/drafts whose migration never completed (still importable through the
     * legacy reader).
     */
    static long unmigratedBytes(StorageDatabase s) {
        long total = 0;
        File[] sessions = new File(s.files, "browser-sessions").listFiles(File::isDirectory);
        if (sessions != null)
            for (File d : sessions) if (unmigratedSession(s, d)) total += PageDraftStore.size(d);
        File[] drafts = new File(s.files, "page-drafts").listFiles(File::isDirectory);
        if (drafts != null)
            for (File d : drafts) if (unmigratedDraft(s, d)) total += PageDraftStore.size(d);
        return total;
    }

    /**
     * 「备份、旧缓存与其他」. Everything removed is regenerable or a superseded safety copy: pre-1.1.2 cache
     * folders, import temp folders, link/copy views, database backups (a fresh one is taken first,
     * the older ones go) and objects only those backups protected. Unmigrated old-format
     * sessions/drafts are removed only when the user ticks that separate option. Caller holds
     * CacheStorage.beginClear.
     */
    static void clearOther(StorageDatabase s, boolean unmigrated) throws Exception {
        for (String name : LEGACY_CACHES) deleteTree(StorageFiles.child(s.cache, name));
        File[] temps =
                s.cache.listFiles(
                        f ->
                                f.isDirectory()
                                        && f.getName().matches("(?:session-import-|import-).+"));
        if (temps != null) for (File t : temps) deleteTree(t);
        resetViews(s);
        if (unmigrated) {
            File[] sessions = new File(s.files, "browser-sessions").listFiles(File::isDirectory);
            if (sessions != null)
                for (File d : sessions) if (unmigratedSession(s, d)) deleteTree(d);
            File[] drafts = new File(s.files, "page-drafts").listFiles(File::isDirectory);
            if (drafts != null) for (File d : drafts) if (unmigratedDraft(s, d)) deleteTree(d);
        }
        // Take the new backup first, so a failure here leaves the old backups untouched.
        s.backup(true);
        File root = new File(s.files, "db-backup");
        File[] kept =
                root.listFiles(
                        f ->
                                f.getName().endsWith(".db")
                                        && new File(f.getPath() + ".verified").isFile());
        if (kept != null && kept.length > 0) {
            Arrays.sort(kept, Comparator.comparing(File::getName).reversed());
            String newest = kept[0].getName();
            File[] all = root.listFiles(File::isFile);
            if (all != null)
                for (File f : all)
                    if (!f.getName().equals(newest) && !f.getName().equals(newest + ".verified"))
                        Files.delete(f.toPath());
            File[] preserved =
                    s.databaseFile
                            .getParentFile()
                            .listFiles(
                                    f ->
                                            f.getName().startsWith("manga.preserved-")
                                                    || f.getName().startsWith("manga.restore-"));
            if (preserved != null) for (File f : preserved) Files.delete(f.toPath());
            s.refreshBackupReferences();
        }
        BlobStore blobs = new BlobStore(s);
        blobs.reconcile(true);
        blobs.collect(System.currentTimeMillis(), 0);
        reconcile(s, true);
    }

    private StorageQuota() {}
}
