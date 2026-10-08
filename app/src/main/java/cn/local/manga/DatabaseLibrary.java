package cn.local.manga;

import android.content.Context;
import android.database.Cursor;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** BrowserLibrary's existing queue serializes migration before any newly acknowledged change. */
final class DatabaseLibrary implements LibraryStore.Persistence {
    private final Context app;
    private JSONObject previous;
    private long generation;
    private boolean readOnly;

    DatabaseLibrary(Context context) {
        app = context.getApplicationContext();
    }

    public JSONObject load() throws Exception {
        previous =
                StorageDatabase.call(
                        app,
                        store -> {
                            generation = StorageDatabase.generation;
                            try {
                                migrate(store);
                            } catch (Exception error) {
                                StorageMigration.issue(store, "library-v1", error);
                                if ("db".equals(store.meta("library_authority"))) throw error;
                                StorageDatabase.problem =
                                        "浏览记录迁移未完成，旧记录只读保留：" + StorageDatabase.message(error);
                                readOnly = true;
                                File source = new File(store.files, "browsing-library.json");
                                if (!source.isFile() || source.length() > LibraryStore.MAX_BYTES)
                                    throw error;
                                return new JSONObject(
                                        new String(
                                                Files.readAllBytes(source.toPath()),
                                                StandardCharsets.UTF_8));
                            }
                            return read(store);
                        });
        return StorageJson.copy(previous);
    }

    public void save(JSONObject data) throws Exception {
        StorageJson.validateLibrary(data);
        if (readOnly) throw new IOException("浏览记录迁移未完成，未保存本次更改；请在存储自检中重试");
        StorageDatabase.call(
                app,
                store -> {
                    if (generation != StorageDatabase.generation)
                        throw new IOException("数据库已恢复，请刷新后重试");
                    if (!"db".equals(store.meta("library_authority")))
                        throw new IOException("浏览记录迁移尚未完成");
                    store.db.beginTransaction();
                    try {
                        writeDelta(store, previous, data);
                        store.db.setTransactionSuccessful();
                    } finally {
                        store.db.endTransaction();
                    }
                    return null;
                });
        previous = StorageJson.copy(data);
    }

    private static void migrate(StorageDatabase store) throws Exception {
        if ("db".equals(store.meta("library_authority"))) {
            StorageMigration.resolved(store, "library-v1");
            return;
        }
        File source = new File(store.files, "browsing-library.json");
        if (source.length() > LibraryStore.MAX_BYTES) throw new IOException("浏览记录文件过大，旧文件保留");
        JSONObject data =
                source.isFile()
                        ? new JSONObject(
                                new String(
                                        Files.readAllBytes(source.toPath()),
                                        StandardCharsets.UTF_8))
                        : StorageJson.emptyLibrary();
        StorageJson.validateLibrary(data);
        StorageQuota.requireMigrationSpace(store, source.length());
        String checksum = source.isFile() ? StorageFiles.hash(source) : "empty";
        StorageMigration migration =
                new StorageMigration(
                        store, "library-v1", checksum, data.getJSONArray("entries").length());
        MigrationRunner.run(
                migration,
                step -> {
                    if (step == MigrationRunner.BACKUP && source.isFile()) {
                        File backup =
                                new File(
                                        store.files,
                                        "migration-backup/library-v1/browsing-library.json");
                        if (!backup.isFile()) StorageFiles.copy(source, backup);
                        if (!checksum.equals(StorageFiles.hash(backup)))
                            throw new IOException("迁移元数据备份不一致");
                    } else if (step == MigrationRunner.IMPORT) {
                        store.db.beginTransaction();
                        try {
                            writeDelta(store, StorageJson.emptyLibrary(), data);
                            store.db.setTransactionSuccessful();
                        } finally {
                            store.db.endTransaction();
                        }
                    } else if (step == MigrationRunner.VERIFY) {
                        JSONObject actual = read(store);
                        JSONObject expected = StorageJson.copy(data);
                        if (!expected.has("shortcuts")) expected.put("shortcuts", new JSONArray());
                        StorageJson.same(expected, actual, "浏览记录、收藏、常用网站全部字段");
                        if (source.isFile() && !checksum.equals(StorageFiles.hash(source)))
                            throw new IOException("核对期间浏览记录源已变化");
                        migration.verified(data.getJSONArray("entries").length());
                    } else if (step == MigrationRunner.CUTOVER) {
                        store.db.beginTransaction();
                        try {
                            store.meta("library_authority", "db");
                            if (source.isFile())
                                store.db.execSQL(
                                        "INSERT OR REPLACE INTO"
                                            + " legacy_verified(path,migration_id,checksum,bytes)"
                                            + " VALUES(?,?,?,?)",
                                        new Object[] {
                                            "browsing-library.json",
                                            "library-v1",
                                            checksum,
                                            source.length()
                                        });
                            migration.completed(MigrationRunner.DONE);
                            store.db.setTransactionSuccessful();
                        } finally {
                            store.db.endTransaction();
                        }
                    }
                });
        StorageMigration.resolved(store, "library-v1");
        store.backup(true);
    }

    static JSONObject read(StorageDatabase store) throws Exception {
        String envelope = store.meta("library_envelope");
        JSONObject out =
                envelope == null ? new JSONObject().put("version", 1) : new JSONObject(envelope);
        JSONArray rows = new JSONArray(), shortcuts = new JSONArray();
        try (Cursor c =
                store.db.rawQuery(
                        "SELECT extra_json FROM history_entry ORDER BY position,url", null)) {
            while (c.moveToNext()) rows.put(new JSONObject(c.getString(0)));
        }
        try (Cursor c =
                store.db.rawQuery("SELECT json FROM shortcut ORDER BY position,key", null)) {
            while (c.moveToNext()) shortcuts.put(new JSONObject(c.getString(0)));
        }
        return out.put("entries", rows).put("shortcuts", shortcuts);
    }

    private static Map<String, String> rows(JSONArray rows, String key) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows != null)
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                out.put(row.getString(key), StorageJson.canonical(row));
            }
        return out;
    }

    private static void writeDelta(StorageDatabase store, JSONObject before, JSONObject after)
            throws Exception {
        Map<String, String> old = rows(before.optJSONArray("entries"), "url"),
                now = rows(after.getJSONArray("entries"), "url");
        for (String key : old.keySet())
            if (!now.containsKey(key))
                store.db.execSQL("DELETE FROM history_entry WHERE url=?", new Object[] {key});
        long nextPosition = store.number("SELECT COALESCE(MAX(position),-1)+1 FROM history_entry");
        for (Map.Entry<String, String> item : now.entrySet()) {
            if (!item.getValue().equals(old.get(item.getKey()))) {
                JSONObject row = new JSONObject(item.getValue());
                long visited = row.getLong("visitedAt");
                String existing =
                        store.scalar(
                                "SELECT position FROM history_entry WHERE url=?", item.getKey());
                long position = existing == null ? nextPosition++ : Long.parseLong(existing);
                store.db.execSQL(
                        "INSERT OR REPLACE INTO"
                            + " history_entry(url,title,bookmark_title,visited_at,bookmarked_at,visits,position,extra_json)"
                            + " VALUES(?,?,?,?,?,?,?,?)",
                        new Object[] {
                            item.getKey(),
                            row.getString("title"),
                            row.optString("bookmarkTitle"),
                            visited,
                            row.getLong("bookmarkedAt"),
                            row.optLong("visits", visited > 0 ? 1 : 0),
                            position,
                            item.getValue()
                        });
            }
        }
        old = rows(before.optJSONArray("shortcuts"), "key");
        now = rows(after.optJSONArray("shortcuts"), "key");
        for (String key : old.keySet())
            if (!now.containsKey(key))
                store.db.execSQL("DELETE FROM shortcut WHERE key=?", new Object[] {key});
        nextPosition = store.number("SELECT COALESCE(MAX(position),-1)+1 FROM shortcut");
        for (Map.Entry<String, String> item : now.entrySet()) {
            if (!item.getValue().equals(old.get(item.getKey()))) {
                String existing =
                        store.scalar("SELECT position FROM shortcut WHERE key=?", item.getKey());
                long position = existing == null ? nextPosition++ : Long.parseLong(existing);
                store.db.execSQL(
                        "INSERT OR REPLACE INTO shortcut(key,json,position) VALUES(?,?,?)",
                        new Object[] {item.getKey(), item.getValue(), position});
            }
        }
        JSONObject envelope = StorageJson.copy(after);
        envelope.remove("entries");
        envelope.remove("shortcuts");
        String value = StorageJson.canonical(envelope);
        if (!value.equals(store.meta("library_envelope"))) store.meta("library_envelope", value);
    }
}
