package cn.local.manga;

import org.json.*;

import java.lang.reflect.*;
import java.nio.file.*;

public final class FreshCacheChecks {
    static int count;

    static void ok(boolean v, String why) {
        if (!v) throw new AssertionError(why);
        count++;
    }

    public static void main(String[] args) throws Exception {
        try (TranslationEngine engine =
                new TranslationEngine(new android.content.Context(Paths.get(args[0]).toFile()))) {
            Method read =
                    TranslationEngine.class.getDeclaredMethod(
                            "cachedText", String.class, boolean.class);
            read.setAccessible(true);
            Method write =
                    TranslationEngine.class.getDeclaredMethod(
                            "writeText", String.class, JSONObject.class);
            write.setAccessible(true);
            JSONObject old =
                    new JSONObject().put("id", "one").put("zh", "原有有效译文").put("skip", false);
            JSONObject other =
                    new JSONObject().put("id", "two").put("zh", "其他页的译文").put("skip", false);
            write.invoke(engine, "one", old);
            write.invoke(engine, "two", other);
            ok(
                    ((JSONObject) read.invoke(engine, "one", false))
                            .getString("zh")
                            .equals("原有有效译文"),
                    "normal mode reuses valid translation");
            ok(
                    read.invoke(engine, "one", true) == null,
                    "forced mode bypasses existing translation");
            ok(
                    ((JSONObject) read.invoke(engine, "one", false))
                            .getString("zh")
                            .equals("原有有效译文"),
                    "bypass does not destroy fallback cache before a successful reply");
            ok(
                    ((JSONObject) read.invoke(engine, "two", false))
                            .getString("zh")
                            .equals("其他页的译文"),
                    "unrelated page cache remains");
            write.invoke(
                    engine,
                    "one",
                    new JSONObject().put("id", "one").put("zh", "新译文").put("skip", false));
            ok(
                    ((JSONObject) read.invoke(engine, "one", false)).getString("zh").equals("新译文"),
                    "successful fresh reply replaces this cached translation");
            ok(
                    ((JSONObject) read.invoke(engine, "two", false))
                            .getString("zh")
                            .equals("其他页的译文"),
                    "commit does not alter another identity");
            ok(
                    read.invoke(engine, "missing", true) == null
                            && read.invoke(engine, "missing", false) == null,
                    "uncached translations requested in either mode");
        }
        System.out.println("FreshCacheChecks: " + count + " checks passed");
    }
}
