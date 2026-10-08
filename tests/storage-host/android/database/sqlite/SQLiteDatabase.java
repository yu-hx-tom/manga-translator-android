package android.database.sqlite;

import android.database.*;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Host adapter executes real SQL in host SQLite, not Android SQLite. No UI or device claim. */
public final class SQLiteDatabase implements AutoCloseable {
    public static final int CREATE_IF_NECESSARY = 0x10000000,
            ENABLE_WRITE_AHEAD_LOGGING = 0x20000000,
            OPEN_READONLY = 1;

    public interface CursorFactory {}

    private final Process process;
    private final BufferedWriter input;
    private final BufferedReader output;
    private final String path;
    private boolean transaction, successful, closed;
    public static long mutations;
    public static String failSqlContains;
    public static int failMigrationStep = -1;

    private SQLiteDatabase(String path, int flags) {
        try {
            this.path = path;
            process =
                    new ProcessBuilder(
                                    System.getProperty("storage.python"),
                                    "-u",
                                    System.getProperty("storage.bridge"),
                                    path,
                                    (flags & OPEN_READONLY) != 0 ? "1" : "0")
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
            input =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            output =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            if ((flags & ENABLE_WRITE_AHEAD_LOGGING) != 0)
                query("PRAGMA journal_mode=WAL", new Object[0]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static SQLiteDatabase openDatabase(
            String path, CursorFactory factory, int flags, DatabaseErrorHandler handler) {
        return new SQLiteDatabase(path, flags);
    }

    public String getPath() {
        return path;
    }

    public void setForeignKeyConstraintsEnabled(boolean enabled) {
        execSQL("PRAGMA foreign_keys=" + (enabled ? "ON" : "OFF"));
    }

    public synchronized Cursor rawQuery(String sql, String[] args) {
        return query(sql, args == null ? new Object[0] : args);
    }

    public synchronized void execSQL(String sql) {
        execSQL(sql, new Object[0]);
    }

    public synchronized void execSQL(String sql, Object[] args) {
        if (sql.startsWith("UPDATE migration SET step=")
                && args.length > 0
                && ((Number) args[0]).intValue() == failMigrationStep) {
            failMigrationStep = -1;
            throw new RuntimeException("Injected migration checkpoint failure");
        }
        if (failSqlContains != null && sql.contains(failSqlContains)) {
            failSqlContains = null;
            throw new RuntimeException("Injected SQL failure before " + sql);
        }
        mutations++;
        query(sql, args).close();
    }

    private Cursor query(String sql, Object[] args) {
        try {
            JSONObject request = new JSONObject().put("sql", sql).put("args", new JSONArray(args));
            input.write(request.toString());
            input.newLine();
            input.flush();
            String line = output.readLine();
            if (line == null) throw new IOException("Host SQLite connection closed");
            JSONObject result = new JSONObject(line);
            if (!result.getBoolean("ok"))
                throw new IOException(result.getString("error") + " SQL=" + sql);
            JSONArray rows = result.getJSONArray("rows");
            return new Cursor() {
                int row = -1;

                public boolean moveToFirst() {
                    row = 0;
                    return row < rows.length();
                }

                public boolean moveToNext() {
                    row++;
                    return row < rows.length();
                }

                private Object value(int column) {
                    try {
                        return rows.getJSONArray(row).get(column);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                public String getString(int c) {
                    Object v = value(c);
                    return v == JSONObject.NULL ? null : v.toString();
                }

                public long getLong(int c) {
                    return Long.parseLong(getString(c));
                }

                public int getInt(int c) {
                    return (int) getLong(c);
                }

                public boolean isNull(int c) {
                    return value(c) == JSONObject.NULL;
                }

                public void close() {}
            };
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void beginTransaction() {
        if (transaction) throw new IllegalStateException("Unexpected nested transaction");
        execSQL("BEGIN IMMEDIATE");
        transaction = true;
        successful = false;
    }

    public synchronized void setTransactionSuccessful() {
        if (!transaction) throw new IllegalStateException();
        successful = true;
    }

    public synchronized void endTransaction() {
        if (!transaction) throw new IllegalStateException();
        try {
            execSQL(successful ? "COMMIT" : "ROLLBACK");
        } finally {
            transaction = false;
            successful = false;
        }
    }

    public boolean inTransaction() {
        return transaction;
    }

    public int getVersion() {
        try (Cursor c = rawQuery("PRAGMA user_version", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    public void setVersion(int value) {
        execSQL("PRAGMA user_version=" + value);
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            input.write("{\"close\":true}\n");
            input.flush();
            output.readLine();
            process.waitFor();
            input.close();
            output.close();
        } catch (Exception e) {
            process.destroy();
            throw new RuntimeException(e);
        }
    }
}
