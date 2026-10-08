package cn.local.manga;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RenderedPageCacheChecks {
    static int count;

    static void ok(boolean value, String label) {
        count++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rendered-cache-check-");
        RenderedPageCache cache = new RenderedPageCache(root.toFile());
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
        String key = RenderedPageCache.key("pixels-a", "settings-a");
        PageOutcome complete = new PageOutcome(3, 3, 0, 0, "complete");
        ok(cache.read(key) == null, "first visit misses");
        cache.write(key, png, complete);
        RenderedPageCache.Entry hit = new RenderedPageCache(root.toFile()).read(key);
        ok(
                hit != null && Arrays.equals(hit.png, png) && hit.outcome.succeeded == 3,
                "new reader restores full PNG and outcome");
        ok(
                cache.read(RenderedPageCache.key("pixels-b", "settings-a")) == null,
                "changed pixels cannot reuse old page");
        ok(
                cache.read(RenderedPageCache.key("pixels-a", "settings-b")) == null,
                "changed settings cannot reuse old page");
        png[10] = 4;
        cache.write(key, png, complete);
        ok(cache.read(key).png[10] == 4, "manual retry replaces existing page atomically");
        cache.write(key, png, new PageOutcome(3, 2, 1, 0, "partial"));
        ok(cache.read(key) == null, "partial retry invalidates prior complete page");
        cache.write(key, png, complete);
        Path data = root.resolve("rendered-pages-v1").resolve(key + ".page");
        byte[] damaged = Files.readAllBytes(data);
        damaged[damaged.length - 1] ^= 1;
        Files.write(data, damaged);
        ok(cache.read(key) == null && !Files.exists(data), "corruption is rejected and deleted");
        cache.write(key, png, complete);
        Files.write(data, Arrays.copyOf(Files.readAllBytes(data), 16));
        ok(cache.read(key) == null, "truncated entry misses");
        cache.write(key, new byte[10], complete);
        ok(cache.read(key) == null, "non-PNG not cached");
        cache.write(key, png, PageOutcome.unknown());
        ok(cache.read(key) == null, "unknown outcome not reused");
        boolean rejected = false;
        try {
            cache.read("../escape");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        ok(rejected, "untrusted file key rejected");
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        System.out.println(count + " checks passed");
    }
}
