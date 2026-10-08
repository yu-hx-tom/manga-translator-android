package cn.local.manga;

import android.content.Context;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Integration tests execute production storage repositories against host SQLite + synthetic files.
 */
public final class Storage112Checks {
    private static int checks;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Action action, String message) throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, message);
    }

    private static byte[] png(int color) throws Exception {
        android.graphics.Bitmap bitmap = new android.graphics.Bitmap(16, 12, new int[192]);
        int[] pixels = new int[192];
        Arrays.fill(pixels, color);
        bitmap = new android.graphics.Bitmap(16, 12, pixels);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        check(
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out),
                "synthetic PNG fixture");
        return out.toByteArray();
    }

    private static void write(File file, byte[] data) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), data);
    }

    private static void write(File file, JSONObject data) throws Exception {
        write(file, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject draft() {
        try {
            return new JSONObject()
                    .put("schema", 1)
                    .put("width", 16)
                    .put("height", 12)
                    .put("engine", "typeset-v1")
                    .put(
                            "regions",
                            new JSONArray()
                                    .put(
                                            new JSONObject()
                                                    .put("id", "r1")
                                                    .put(
                                                            "box",
                                                            new JSONArray(new int[] {1, 1, 8, 10}))
                                                    .put("lines", new JSONArray())
                                                    .put("original", "synthetic")
                                                    .put("machine", "测试")
                                                    .put("status", "translated")))
                    .put("protection", new JSONArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void restart(Context c) throws Exception {
        StorageDatabase.call(
                c,
                s -> {
                    s.db.close();
                    java.lang.reflect.Field f = StorageDatabase.class.getDeclaredField("instance");
                    f.setAccessible(true);
                    f.set(null, null);
                    StorageDatabase.ready = false;
                    return null;
                });
    }

    public static void main(String[] args) throws Exception {
        try {
            run(new File(args[0]));
            System.out.println(
                    checks
                            + " storage checks passed (host SQLite/filesystem; Android runtime NOT"
                            + " verified)");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(File root) throws Exception {
        root.mkdirs();
        Context context = new Context(root);
        check(
                !new PageOutcome(12, 9, 2, 1, "").pageFailed(),
                "partially translated page is not failed");
        check(new PageOutcome(12, 0, 12, 0, "").pageFailed(), "whole-page translation failure");
        check(
                new PageOutcome(12, 0, 0, 12, "").pageFailed(),
                "all skipped with no translated content");
        check(!new PageOutcome(0, 0, 0, 0, "").pageFailed(), "no text is not an invented failure");
        check(!PageOutcome.unknown().pageFailed(), "unknown metadata is not invented failure");
        check(new PageOutcome(12, 9, 2, 1, "").incomplete(), "partial retry information retained");
        for (int interrupted = 0; interrupted < 4; interrupted++) {
            final int at = interrupted;
            int[] progress = {0}, fail = {1};
            Set<Integer> effects = new HashSet<>();
            MigrationRunner.Ledger ledger =
                    new MigrationRunner.Ledger() {
                        public int completed() {
                            return progress[0];
                        }

                        public void completed(int value) throws Exception {
                            if (value == at + 1 && fail[0]-- > 0)
                                throw new IOException("crash before checkpoint");
                            progress[0] = value;
                        }

                        public void failure(int step, Exception e) {}
                    };
            rejects(
                    () ->
                            MigrationRunner.run(
                                    ledger,
                                    step -> {
                                        if (step == 3)
                                            check(effects.contains(2), "verify precedes cutover");
                                        effects.add(step);
                                    }),
                    "interrupt each migration step");
            MigrationRunner.run(ledger, effects::add);
            check(
                    progress[0] == 4 && effects.size() == 4,
                    "resumed migration is complete and idempotent");
            MigrationRunner.run(
                    ledger,
                    step -> {
                        throw new AssertionError("completed migration repeated");
                    });
            checks++;
        }
        check(!StoragePolicy.canReserve(299 * StoragePolicy.MIB, 0), "low free space rejected");
        check(
                StoragePolicy.canReserve(332 * StoragePolicy.MIB, 32 * StoragePolicy.MIB),
                "inclusive free-space boundary");
        check(
                !StoragePolicy.canReserve(Long.MAX_VALUE, Long.MAX_VALUE),
                "reservation overflow avoided");
        check(
                StoragePolicy.cacheBudget(20L * 1024 * StoragePolicy.MIB)
                        == 2048 * StoragePolicy.MIB,
                "global cache cap");
        check(
                StoragePolicy.sessionBudget(20L * 1024 * StoragePolicy.MIB)
                        == 800 * StoragePolicy.MIB,
                "global session cap");
        JSONObject library =
                StorageJson.emptyLibrary()
                        .put(
                                "future",
                                new JSONObject().put("keep", new JSONArray().put("x").put(8)));
        JSONArray history = library.getJSONArray("entries"),
                shortcuts = library.getJSONArray("shortcuts");
        for (int i = 0; i < 1000; i++)
            history.put(
                    new JSONObject()
                            .put("url", "https://example.test/page/" + i)
                            .put("title", "标题 " + i)
                            .put("bookmarkTitle", i < 50 ? "收藏 " + i : "")
                            .put("visitedAt", i + 1)
                            .put("bookmarkedAt", i < 50 ? i + 1 : 0)
                            .put("visits", i + 3)
                            .put("unknown", new JSONObject().put("nested", i)));
        for (int i = 0; i < 20; i++)
            shortcuts.put(
                    new JSONObject()
                            .put("key", "site" + i + ".test")
                            .put("url", "https://site" + i + ".test/")
                            .put("title", "网站 " + i)
                            .put("pinned", i < 5)
                            .put("hidden", i == 19)
                            .put("unknown", new JSONObject().put("nested", i)));
        File old = new File(context.getFilesDir(), "browsing-library.json");
        write(old, library);
        String oldHash = StorageFiles.hash(old);
        LibraryStore store = new LibraryStore(new DatabaseLibrary(context));
        if (!StorageDatabase.problem.isEmpty())
            System.out.println("Storage problem after library load: " + StorageDatabase.problem);
        check(store.list(true, "", 0, 101).size() == 50, "all bookmarks migrated");
        check(store.list(false, "", 0, 101).size() == 101, "history paging unchanged");
        StorageDatabase.call(
                context,
                s -> {
                    StorageJson.same(
                            library,
                            DatabaseLibrary.read(s),
                            "1000 history/50 bookmarks/20 shortcuts + unknown fields");
                    check(
                            s.number("SELECT count(*) FROM history_entry") == 1000,
                            "SQL record count");
                    check(s.number("SELECT count(*) FROM shortcut") == 20, "SQL shortcut count");
                    check("db".equals(s.meta("library_authority")), "verified library cutover");
                    return null;
                });
        check(oldHash.equals(StorageFiles.hash(old)), "legacy library unchanged by migration");
        long before = android.database.sqlite.SQLiteDatabase.mutations;
        store.visit("新标题", "https://example.test/page/55", 5000);
        long writes = android.database.sqlite.SQLiteDatabase.mutations - before;
        check(writes < 10, "one visit performs incremental SQL, not rewriting 1000 records");
        store.rename("https://example.test/page/1", "重命名收藏");
        store.editShortcut("site2.test", "重命名网站", "https://site2.test/", true, false);
        StorageDatabase.call(
                context,
                s -> {
                    JSONObject changed = DatabaseLibrary.read(s);
                    check(
                            changed.getJSONObject("future").getJSONArray("keep").length() == 2,
                            "unknown envelope survives normal writes");
                    check(
                            changed.getJSONArray("shortcuts")
                                            .getJSONObject(2)
                                            .getJSONObject("unknown")
                                            .getInt("nested")
                                    == 2,
                            "shortcut unknown fields preserved");
                    check(
                            s.number(
                                            "SELECT visits FROM history_entry WHERE url=?",
                                            "https://example.test/page/55")
                                    == 59,
                            "visits preserved and incremented once");
                    return null;
                });
        check(oldHash.equals(StorageFiles.hash(old)), "normal writes never touch legacy library");
        LibraryStore reopened = new LibraryStore(new DatabaseLibrary(context));
        check(reopened.list(true, "重命名收藏", 0, 10).size() == 1, "renamed bookmark survives reopen");
        byte[] image = png(0xffaabbcc), image2 = png(0xff334455);
        String imageHash = StorageFiles.hex(StorageFiles.digest().digest(image));
        StorageDatabase.call(
                context,
                s -> {
                    BlobStore blobs = new BlobStore(s);
                    try (BlobStore.Prepared one =
                                    blobs.put(image, "lossless-original", "image/png");
                            BlobStore.Prepared two =
                                    blobs.put(image, "lossless-rendered", "image/png")) {
                        s.db.beginTransaction();
                        try {
                            one.reference("draft", "fixture", "source");
                            two.reference("draft", "fixture", "result");
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                        check(
                                one.hash.equals(two.hash),
                                "same byte content deduplicated across roles");
                        check(
                                s.number("SELECT count(*) FROM blob WHERE hash=?", imageHash) == 1,
                                "one physical object row");
                        check(
                                s.number("SELECT count(*) FROM blob_ref WHERE hash=?", imageHash)
                                        == 2,
                                "two independent references");
                        rejects(
                                () -> blobs.resolve("draft", "fixture", "source", "lossy-display"),
                                "quality role mismatch rejected");
                    }
                    rejects(
                            () ->
                                    blobs.put(
                                            out -> {
                                                out.write(1);
                                                throw new IOException("interrupted writer");
                                            },
                                            "metadata",
                                            "application/octet-stream"),
                            "mid-write fails without publishing reference");
                    BlobStore.Prepared incomplete =
                            blobs.put(
                                    new byte[] {9, 8, 7, 6, 5},
                                    "metadata",
                                    "application/octet-stream");
                    String hash = incomplete.hash;
                    s.db.beginTransaction();
                    try {
                        incomplete.reference("draft", "rolled-back", "content");
                    } finally {
                        s.db.endTransaction();
                    }
                    check(
                            s.number("SELECT count(*) FROM blob_ref WHERE owner_id='rolled-back'")
                                    == 0,
                            "owner rollback leaves no reference");
                    check(
                            s.number("SELECT count(*) FROM blob_pending WHERE hash=?", hash) == 1,
                            "rollback keeps pending protection");
                    blobs.collect(System.currentTimeMillis() + 2 * 60 * 60 * 1000L);
                    check(blobs.file(hash).isFile(), "lease protects object past pending grace");
                    incomplete.close();
                    s.backup(true);
                    s.db.beginTransaction();
                    try {
                        blobs.releaseOwner("draft", "fixture");
                        s.db.setTransactionSuccessful();
                    } finally {
                        s.db.endTransaction();
                    }
                    blobs.collect(System.currentTimeMillis() + 2 * 60 * 60 * 1000L);
                    check(
                            blobs.file(imageHash).isFile(),
                            "backup protects otherwise unreferenced image");
                    return null;
                });
        SourceImageCache sourceCache = new SourceImageCache(context);
        sourceCache.store("a".repeat(64), image, "etag-a", "", "", "", () -> false);
        SourceImageCache.Entry entry = sourceCache.find("a".repeat(64), () -> false);
        check(
                entry != null && Arrays.equals(image, sourceCache.read(entry, () -> false)),
                "conditional original cache through blob store");
        sourceCache.store("b".repeat(64), image, "etag-b", "", "", "", () -> false);
        sourceCache.remove("a".repeat(64));
        check(
                sourceCache.find("b".repeat(64), () -> false) != null,
                "removing one key preserves other same-content owner");
        sourceCache.store("b".repeat(64), image, "etag-b", "", "no-store", "", () -> false);
        check(
                sourceCache.find("b".repeat(64), () -> false) == null,
                "no-store invalidates reusable cache");
        PageOutcome complete = new PageOutcome(1, 1, 0, 0, "synthetic");
        RenderedPageCache rendered = new RenderedPageCache(context);
        rendered.write("c".repeat(64), image, complete);
        check(
                Arrays.equals(image, rendered.read("c".repeat(64)).png),
                "lossless rendered-cache byte identity");
        rendered.write("c".repeat(64), image, new PageOutcome(2, 1, 1, 0, "partial"));
        check(
                rendered.read("c".repeat(64)) == null,
                "partial reply does not poison complete-page cache");
        File draftDir = new File(root, "synthetic-draft");
        write(new File(draftDir, PageDraft.SOURCE), image);
        write(new File(draftDir, PageDraft.JSON), draft());
        write(new File(draftDir, "rendered.png"), image2);
        String draftHash = StoredDrafts.treeHash(draftDir), draftKey = "d".repeat(64);
        check(StoredDrafts.commit(context, draftDir, draftKey), "draft committed with references");
        File found = StoredDrafts.find(context, draftKey);
        check(
                draftHash.equals(StoredDrafts.treeHash(found)),
                "draft aliases preserve every source byte");
        android.system.Os.failLinks = true;
        String alternate = "e".repeat(64);
        check(
                StoredDrafts.commit(context, draftDir, alternate),
                "hard-link failure has safe copy fallback");
        check(
                draftHash.equals(StoredDrafts.treeHash(StoredDrafts.find(context, alternate))),
                "copy fallback byte identity");
        android.system.Os.failLinks = false;
        String url = "https://example.test/chapter",
                imageUrl = "https://example.test/001.png",
                sid = StoredSessions.id(url),
                pid = StoredSessions.id(imageUrl);
        File legacySession = new File(context.getFilesDir(), "browser-sessions/" + sid),
                legacyPage = new File(legacySession, "pages/" + pid);
        write(new File(legacyPage, "original.png"), image);
        write(new File(legacyPage, "rendered.png"), image2);
        PageDraftStore.copyTree(draftDir, new File(legacyPage, "draft"));
        JSONObject page =
                new JSONObject()
                        .put("id", pid)
                        .put("label", "00001")
                        .put("kind", "editable")
                        .put("status", "partial")
                        .put("image", "rendered.png")
                        .put("original", "original.png")
                        .put("reviewed", false)
                        .put("edits", new JSONObject())
                        .put("unknown", new JSONObject().put("preserved", true));
        JSONObject session =
                new JSONObject()
                        .put("schema", 1)
                        .put("schemaVersion", 2)
                        .put("id", sid)
                        .put("title", "合成会话")
                        .put("sourceKind", "web")
                        .put("sourceKey", url)
                        .put("created", 1)
                        .put("updated", 2)
                        .put("pages", new JSONArray().put(page))
                        .put("unknown", "keep");
        write(new File(legacySession, "project.json"), session);
        String legacyHash = StoredDrafts.treeHash(legacySession);
        List<ComicProject> sessions = StoredSessions.list(context);
        check(
                sessions.size() == 1 && sessions.get(0).databaseSession,
                "legacy session cut over once");
        ComicProject migrated = sessions.get(0);
        check(
                Arrays.equals(
                        image2,
                        Files.readAllBytes(migrated.imageFile(migrated.pages.get(0)).toPath())),
                "migrated rendered bytes unchanged");
        check(
                draftHash.equals(StoredDrafts.treeHash(migrated.draftDir(migrated.pages.get(0)))),
                "session draft remains readable with exact files");
        check(
                legacyHash.equals(StoredDrafts.treeHash(legacySession)),
                "old session never rewritten");
        File newResult = new File(root, "result.png");
        write(newResult, image);
        StoredSessions.record(context, url, "new title", imageUrl, 0, newResult, draftKey);
        check(
                StoredSessions.list(context).get(0).pages.size() == 1,
                "same URL/page merges without duplicate page");
        StoredSessions.record(
                context, url, "new title", "https://example.test/002.png", 1, newResult, draftKey);
        check(
                StoredSessions.list(context).get(0).pages.size() == 2,
                "new page appends to migrated session");
        check(
                legacyHash.equals(StoredDrafts.treeHash(legacySession)),
                "new session writes preserve legacy files");
        File project = new File(context.getFilesDir(), "projects/do-not-delete/important.bin");
        write(project, new byte[] {3, 1, 4, 1, 5});
        check(CacheStorage.beginClear(), "exclusive explicit cleanup acquired");
        try {
            StorageDatabase.call(
                    context,
                    s -> {
                        StorageQuota.clearSelected(s, true, false, false);
                        return null;
                    });
        } finally {
            CacheStorage.endClear();
        }
        check(project.isFile(), "cache cleanup preserves project user data");
        check(legacySession.isDirectory(), "cache cleanup preserves old sessions");
        migrated = StoredSessions.list(context).get(0);
        check(
                migrated.pages.size() == 2 && migrated.imageFile(migrated.pages.get(0)).isFile(),
                "session survives cache cleanup");
        File independent = new File(root, "independent-import");
        SessionRepository.snapshotFiles(migrated, independent, (done, total) -> {}, () -> false);
        check(
                PageDraft.read(new File(independent, pid)).source().isFile(),
                "session imports independent editable draft after cache clear");
        StorageDatabase.call(
                context,
                s -> {
                    s.backup(true);
                    return null;
                });
        File[] backups =
                new File(context.getFilesDir(), "db-backup")
                        .listFiles(
                                f ->
                                        f.getName().endsWith(".db")
                                                && new File(f.getPath() + ".verified").isFile());
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed());
        check(backups.length <= 3 && backups.length > 0, "three verified backups retained");
        String backupName = backups[0].getName();
        reopened.visit("later", "https://example.test/after-backup", 9000);
        check(
                new LibraryStore(new DatabaseLibrary(context))
                                .list(false, "after-backup", 0, 10)
                                .size()
                        == 1,
                "post-backup write persisted");
        StorageDatabase.restore(context, backupName);
        check(
                new LibraryStore(new DatabaseLibrary(context))
                        .list(false, "after-backup", 0, 10)
                        .isEmpty(),
                "confirmed restore returns to selected snapshot");
        rejects(
                () -> reopened.visit("stale", "https://example.test/stale", 9001),
                "pre-restore in-memory writer cannot overwrite restored state");
        check(
                context.getDatabasePath("manga.db")
                                .getParentFile()
                                .listFiles(f -> f.getName().startsWith("manga.preserved-"))
                                .length
                        > 0,
                "restore preserves replaced database");
        android.os.StatFs.availableOverride = 100 * StoragePolicy.MIB;
        rejects(() -> StorageQuota.requireSpace(context), "low-space new task rejected");
        android.os.StatFs.availableOverride = -1;
        Map<String, File> censusRoots = new LinkedHashMap<>();
        File census = new File(root, "census-only");
        write(new File(census, "a/content.bin"), new byte[100]);
        write(new File(census, "b/content.bin"), new byte[100]);
        censusRoots.put("fixture", census);
        JSONObject counts = StorageCensus.scan(censusRoots, () -> false);
        check(
                counts.getLong("duplicatePhysicalBytes") == 100,
                "census reports actual duplicate bytes");
        check(
                counts.getLong("duplicateLogicalBytes") == 100 && counts.getLong("files") == 2,
                "census counts complete files");
        rejects(() -> StorageCensus.scan(censusRoots, () -> true), "census cancellation");
        android.os.Looper.simulateMain = true;
        rejects(
                () -> StorageDatabase.call(context, s -> null),
                "main-thread database access forbidden");
        android.os.Looper.simulateMain = false;
        restart(context);
        check(
                new LibraryStore(new DatabaseLibrary(context)).list(true, "", 0, 101).size() == 50,
                "process restart preserves database authority");
        StorageDatabase.call(
                context,
                s -> {
                    s.checkDatabase();
                    check(
                            s.number(
                                            "SELECT count(*) FROM blob_ref r LEFT JOIN blob b ON"
                                                    + " b.hash=r.hash WHERE b.hash IS NULL")
                                    == 0,
                            "final foreign reference integrity");
                    return null;
                });
        recoveryChecks(context, root, image, image2, draftDir);
        JSONObject report =
                new JSONObject()
                        .put("passed", true)
                        .put("checks", checks)
                        .put("hostSQLite", true)
                        .put("androidSQLiteVerified", false)
                        .put("androidDirectoryFsyncVerified", false)
                        .put("realPhoneAcceptance", false)
                        .put("imageFixtures", "synthetic solid-color PNG only");
        write(new File(root, "result.json"), report);
    }

    private static String newestBackup(Context c) {
        File[] backups =
                new File(c.getFilesDir(), "db-backup")
                        .listFiles(
                                f ->
                                        f.getName().endsWith(".db")
                                                && new File(f.getPath() + ".verified").isFile());
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed());
        return backups[0].getName();
    }

    private static void recoveryChecks(
            Context c, File root, byte[] image, byte[] image2, File draftDir) throws Exception {
        PageCacheStore web = new PageCacheStore(c);
        File alias = new File(c.getCacheDir(), "browser-pages-v2/synthetic/repair.png");
        PageOutcome outcome = new PageOutcome(2, 1, 1, 0, "partial");
        web.save(alias, image, outcome);
        File stale = new File(alias.getParentFile(), "interrupted.tmp");
        write(stale, image2);
        Files.move(stale.toPath(), alias.toPath(), StandardCopyOption.REPLACE_EXISTING);
        check(
                Arrays.equals(image, web.read(alias, true)),
                "DB content wins over a stale alias after interruption");
        check(
                Arrays.equals(image, Files.readAllBytes(web.committedFile(alias).toPath())),
                "session source repaired to exactly the committed cache content");
        Files.delete(alias.toPath());
        check(web.committedFile(alias).isFile(), "missing disposable alias is rebuilt");
        rejects(
                () -> web.save(alias, new byte[] {1, 2, 3}, outcome),
                "invalid PNG cannot replace a committed page");
        check(
                Arrays.equals(image, web.read(alias, true)),
                "invalid replacement preserves prior cache");
        android.database.sqlite.SQLiteDatabase.failSqlContains =
                "INSERT OR REPLACE INTO cache_entry";
        rejects(
                () -> web.save(alias, image2, outcome),
                "owner SQL failure rolls back a cache replacement");
        check(
                Arrays.equals(image, web.read(alias, true)),
                "rolled-back owner transaction retains old blob and metadata");
        StorageDatabase.call(
                c,
                s -> {
                    BlobStore blobs = new BlobStore(s);
                    long now = System.currentTimeMillis();
                    byte[] bytes = {4, 8, 15, 16, 23, 42};
                    String hash = StorageFiles.hex(StorageFiles.digest().digest(bytes));
                    File orphan = blobs.file(hash);
                    StorageFiles.write(orphan, out -> out.write(bytes));
                    check(
                            orphan.setLastModified(now - 2 * BlobStore.PENDING_MS),
                            "age orphan fixture");
                    blobs.reconcile(true);
                    blobs.collect(now);
                    check(
                            !orphan.exists(),
                            "startup reconciliation collects an aged unregistered object");
                    String missing;
                    try (BlobStore.Prepared prepared =
                            blobs.put(
                                    new byte[] {24, 22, 19, 17},
                                    "metadata",
                                    "application/octet-stream")) {
                        missing = prepared.hash;
                        s.db.beginTransaction();
                        try {
                            prepared.reference("draft", "missing-check", "content");
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                    }
                    File saved = new File(root, "missing-preserved.bin");
                    Files.move(blobs.file(missing).toPath(), saved.toPath());
                    blobs.reconcile(true);
                    check(
                            s.number("SELECT missing FROM blob WHERE hash=?", missing) == 1,
                            "missing object flagged");
                    check(
                            s.number("SELECT count(*) FROM blob_ref WHERE hash=?", missing) == 1,
                            "missing object does not drop its reference");
                    rejects(
                            () -> blobs.resolve("draft", "missing-check", "content"),
                            "missing object read fails explicitly");
                    Files.move(saved.toPath(), blobs.file(missing).toPath());
                    blobs.reconcile(true);
                    check(
                            s.number("SELECT missing FROM blob WHERE hash=?", missing) == 0,
                            "restored file clears missing flag");
                    android.system.Os.failSync = true;
                    try {
                        rejects(
                                () ->
                                        blobs.put(
                                                new byte[] {70, 71, 72},
                                                "metadata",
                                                "application/octet-stream"),
                                "directory sync failure refuses publication");
                    } finally {
                        android.system.Os.failSync = false;
                    }
                    check(
                            s.number("SELECT count(*) FROM blob_ref WHERE owner_id='unpublished'")
                                    == 0,
                            "sync failure creates no owner references");
                    try (BlobStore.Prepared prepared =
                            blobs.put(
                                    new byte[] {50, 51, 52, 53},
                                    "metadata",
                                    "application/octet-stream")) {
                        s.db.execSQL(
                                "INSERT INTO blob_gc(hash,queued_at) VALUES(?,?)",
                                new Object[] {prepared.hash, now});
                        s.db.beginTransaction();
                        try {
                            prepared.reference("project_page", "protected-project", "content");
                            s.db.setTransactionSuccessful();
                        } finally {
                            s.db.endTransaction();
                        }
                        blobs.collect(now + 2 * BlobStore.PENDING_MS);
                        check(
                                blobs.file(prepared.hash).isFile()
                                        && s.number(
                                                        "SELECT count(*) FROM blob_gc WHERE hash=?",
                                                        prepared.hash)
                                                == 0,
                                "re-reference cancels a queued delete");
                    }
                    android.os.StatFs.availableOverride = 301 * StoragePolicy.MIB;
                    try {
                        rejects(
                                () -> StorageQuota.requireMigrationSpace(s, 10 * StoragePolicy.MIB),
                                "migration checks copy reserve before import");
                    } finally {
                        android.os.StatFs.availableOverride = -1;
                    }
                    return null;
                });
        String key = "f".repeat(64);
        StoredDrafts.commit(c, draftDir, key);
        File first = StoredDrafts.find(c, key);
        JSONObject altered = draft().put("futureRevision", 2);
        write(new File(draftDir, PageDraft.JSON), altered);
        StoredDrafts.commit(c, draftDir, key);
        File current = StoredDrafts.find(c, key);
        check(!current.equals(first), "new draft content gets a new immutable generation");
        check(CacheStorage.beginClear(), "view pruning is exclusive");
        try {
            StorageDatabase.call(
                    c,
                    s -> {
                        StorageQuota.pruneViews(s);
                        return null;
                    });
        } finally {
            CacheStorage.endClear();
        }
        check(
                !first.exists() && current.isDirectory(),
                "obsolete alias generations removed while current view remains");
        StorageDatabase.call(
                c,
                s -> {
                    s.backup(true);
                    return null;
                });
        String earlier = newestBackup(c);
        byte[] future = {90, 91, 92, 93, 94};
        String futureHash = StorageFiles.hex(StorageFiles.digest().digest(future));
        StoredCache.write(c, "source", "future", future, new JSONObject(), null);
        StorageDatabase.call(
                c,
                s -> {
                    s.backup(true);
                    return null;
                });
        String later = newestBackup(c);
        StorageDatabase.restore(c, earlier);
        StorageDatabase.call(
                c,
                s -> {
                    check(
                            s.number("SELECT count(*) FROM blob WHERE hash=?", futureHash) == 0,
                            "older snapshot predates new content");
                    check(
                            s.number("SELECT count(*) FROM backup_ref WHERE hash=?", futureHash)
                                    > 0,
                            "restore refreshes references from newer retained backups");
                    new BlobStore(s).reconcile(true);
                    new BlobStore(s).collect(System.currentTimeMillis() + 2 * BlobStore.PENDING_MS);
                    check(
                            new BlobStore(s).file(futureHash).isFile(),
                            "GC preserves newer snapshot content after older restore");
                    return null;
                });
        StorageDatabase.restore(c, later);
        check(
                Arrays.equals(future, StoredCache.read(c, "source", "future", 100).bytes),
                "newer snapshot remains restorable after older restore and GC");
        File verification = new File(c.getFilesDir(), "db-backup/" + later + ".verified");
        byte[] original = Files.readAllBytes(verification.toPath());
        write(verification, new byte[] {1});
        StorageDatabase.call(
                c,
                s -> {
                    s.refreshBackupReferences();
                    check(
                            !s.backupProtectionReady,
                            "bad backup disables collection conservatively");
                    check(
                            new BlobStore(s).collect(Long.MAX_VALUE) == 0,
                            "no GC while backup protection is uncertain");
                    return null;
                });
        write(verification, original);
        StorageDatabase.call(
                c,
                s -> {
                    s.refreshBackupReferences();
                    check(s.backupProtectionReady, "verified backup protection can recover");
                    return null;
                });
        JSONObject census =
                new JSONObject()
                        .put("kind", "storage_census")
                        .put("complete", true)
                        .put("fixture", true);
        StorageDiagnostics.write(c, Collections.singletonList(census));
        File firstCensus = new File(c.getFilesDir(), "diagnostics/storage-first-census.jsonl");
        String baseline = StorageFiles.hash(firstCensus);
        StorageDiagnostics.failure(c, new java.util.concurrent.CancellationException("cancelled"));
        check(
                baseline.equals(StorageFiles.hash(firstCensus)),
                "cancelled census preserves earlier comparison evidence");
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        StorageDiagnostics.export(firstCensus.getParentFile(), logs);
        check(
                logs.toString(StandardCharsets.UTF_8).contains("storage_census")
                        && logs.toString(StandardCharsets.UTF_8).contains("storage_check_error"),
                "export includes both prior census and latest failure");
        restart(c);
        Context invalid = new Context(new File(root, "invalid-library"));
        JSONObject bad = StorageJson.emptyLibrary();
        bad.getJSONArray("entries")
                .put(
                        new JSONObject()
                                .put("url", "https://invalid.test/")
                                .put("title", "x".repeat(513))
                                .put("visitedAt", 1)
                                .put("bookmarkedAt", 1)
                                .put("visits", 1));
        File old = new File(invalid.getFilesDir(), "browsing-library.json");
        write(old, bad);
        String unchanged = StorageFiles.hash(old);
        LibraryStore readOnly = new LibraryStore(new DatabaseLibrary(invalid));
        check(
                readOnly.list(true, "", 0, 10).size() == 1,
                "failed migration keeps old data readable");
        rejects(
                () -> readOnly.visit("changed", "https://invalid.test/", 3),
                "read-only fallback never acknowledges an unpersisted write");
        check(unchanged.equals(StorageFiles.hash(old)), "failed migration never edits legacy data");
        String diagnostic = StorageDiagnostics.run(invalid, false, () -> false);
        check(diagnostic.contains("存在未完成项目"), "migration failure makes storage diagnostics fail");
        restart(invalid);
        Context interrupted = new Context(new File(root, "cutover-interruption"));
        write(
                new File(interrupted.getFilesDir(), "browsing-library.json"),
                StorageJson.emptyLibrary());
        android.database.sqlite.SQLiteDatabase.failMigrationStep = MigrationRunner.DONE;
        new LibraryStore(new DatabaseLibrary(interrupted));
        StorageDatabase.call(
                interrupted,
                s -> {
                    check(
                            s.meta("library_authority") == null,
                            "cutover checkpoint failure rolls back authority in the same"
                                    + " transaction");
                    check(
                            s.number("SELECT step FROM migration WHERE id='library-v1'") == 3,
                            "failed cutover resumes from completed verification");
                    return null;
                });
        restart(interrupted);
        new LibraryStore(new DatabaseLibrary(interrupted));
        StorageDatabase.call(
                interrupted,
                s -> {
                    check(
                            "db".equals(s.meta("library_authority"))
                                    && "done"
                                            .equals(
                                                    s.scalar(
                                                            "SELECT state FROM migration WHERE"
                                                                    + " id='library-v1'")),
                            "restart atomically completes cutover and ledger");
                    return null;
                });
        restart(interrupted);
        new LibraryStore(new DatabaseLibrary(c));
        StorageDatabase.call(
                c,
                s -> {
                    s.checkDatabase();
                    return null;
                });
        StorageDatabase.call(
                c,
                s -> {
                    File oldest = new File(s.cache, "translations-v2/quota-old.json"),
                            newest = new File(s.cache, "translations-v2/quota-new.json"),
                            user = new File(s.files, "projects/quota-protected.json");
                    write(oldest, new byte[] {1});
                    write(newest, new byte[] {2});
                    write(user, new byte[] {3});
                    // Deliberately synthetic accounting exercises the eviction policy without
                    // hundreds of MB of fixtures.
                    s.db.execSQL(
                            "INSERT OR REPLACE INTO file_usage(path,category,bytes,last_access_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] {
                                "cache/translations-v2/quota-old.json",
                                "cache",
                                160 * StoragePolicy.MIB,
                                1
                            });
                    s.db.execSQL(
                            "INSERT OR REPLACE INTO file_usage(path,category,bytes,last_access_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] {
                                "cache/translations-v2/quota-new.json",
                                "cache",
                                160 * StoragePolicy.MIB,
                                2
                            });
                    s.db.execSQL(
                            "INSERT OR REPLACE INTO file_usage(path,category,bytes,last_access_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] {
                                "files/projects/quota-protected.json",
                                "user",
                                2L * 1024 * StoragePolicy.MIB,
                                0
                            });
                    android.os.StatFs.availableOverride = 1024 * StoragePolicy.MIB;
                    try {
                        StorageQuota.maintain(s, true);
                    } finally {
                        android.os.StatFs.availableOverride = -1;
                    }
                    check(
                            !oldest.exists() && newest.exists(),
                            "global quota evicts the oldest eligible cache first");
                    check(
                            user.isFile(),
                            "user data excluded even when accounting exceeds cache budget");
                    long sessions = s.number("SELECT count(*) FROM session");
                    check(sessions > 0, "session fixture exists for low-space protection");
                    s.db.execSQL(
                            "INSERT OR REPLACE INTO file_usage(path,category,bytes,last_access_at)"
                                    + " VALUES(?,?,?,?)",
                            new Object[] {
                                "files/session-views-v2/quota-fixture",
                                "session",
                                512 * StoragePolicy.MIB,
                                0
                            });
                    android.os.StatFs.availableOverride = 100 * StoragePolicy.MIB;
                    try {
                        StorageQuota.maintain(s, true);
                    } finally {
                        android.os.StatFs.availableOverride = -1;
                    }
                    check(
                            s.number("SELECT count(*) FROM session") == sessions,
                            "low free space never evicts an over-budget session");
                    StorageQuota.reconcile(s, true);
                    String pending = "unverified-migration";
                    s.db.execSQL(
                            "INSERT INTO"
                                + " session(id,source_key,title,created_at,updated_at,last_access_at,extra_json)"
                                + " VALUES(?,?,?,0,0,0,'{}')",
                            new Object[] {pending, "https://pending.test/", "待核对"});
                    s.db.execSQL(
                            "INSERT INTO draft(id,json,updated_at) VALUES(?,'{}',0)",
                            new Object[] {pending});
                    check(
                            CacheStorage.beginClear(),
                            "unfinished migration cleanup test acquires mutex");
                    try {
                        StorageQuota.clearSelected(s, true, true, false);
                    } finally {
                        CacheStorage.endClear();
                    }
                    check(
                            s.number("SELECT count(*) FROM session WHERE id=?", pending) == 1
                                    && s.meta("session_authority/" + pending) == null,
                            "cleanup leaves unverified session migration eligible to resume");
                    check(
                            s.number("SELECT count(*) FROM draft WHERE id=?", pending) == 1
                                    && s.meta("draft_authority/" + pending) == null,
                            "cleanup leaves unverified draft migration eligible to resume");
                    return null;
                });
    }
}
