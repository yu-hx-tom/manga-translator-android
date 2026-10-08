package cn.local.manga;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Tests the production cache allowlist and admission gate; no Android/WebView emulation. */
public final class CacheStorageChecks {
    static int checks;

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    static void write(File file, String value) throws Exception {
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), value);
    }

    public static void main(String[] args) throws Exception {
        File report = new File(args[0]).getCanonicalFile(),
                root = new File(report, "cache-check-" + UUID.randomUUID()).getCanonicalFile();
        if (!root.toPath().startsWith(report.toPath())) throw new IOException("Unsafe test root");
        File cache = new File(root, "cache"), files = new File(root, "files");
        cache.mkdirs();
        files.mkdirs();
        try {
            Map<File, String> keep = new LinkedHashMap<>();
            for (String name :
                    new String[] {
                        "browsing-library.json",
                        "diagnostics/current.jsonl",
                        "diagnostics/previous.jsonl",
                        "projects/p/project.json",
                        "projects/p/draft.json",
                        "projects/p/pages/p0001/draft/source.png",
                        "projects/p/pages/p0001/draft/clean.png",
                        "projects/p/pages/p0001/draft/rendered.png",
                        "projects/p/cover.jpg",
                        "my-export.cbz"
                    }) keep.put(new File(files, name), "protected:" + name);
            keep.put(
                    new File(root, "shared_prefs/browser-search-history.xml"),
                    "site terms and omnibox queries");
            keep.put(new File(root, "shared_prefs/settings.xml"), "key and configuration");
            keep.put(new File(root, "shared_prefs/api-presets.xml"), "presets");
            keep.put(new File(root, "app_webview/Cookies"), "login");
            keep.put(
                    new File(cache, "unknown-cache/records"),
                    "unknown data is not automatically deleted");
            for (Map.Entry<File, String> e : keep.entrySet()) write(e.getKey(), e.getValue());
            File temporary = new File(cache, "import-" + UUID.randomUUID());
            temporary.mkdirs();
            File sessionTemporary = new File(cache, "session-import-" + UUID.randomUUID());
            sessionTemporary.mkdirs();
            for (int group = 0; group < CacheStorage.LABELS.length; group++)
                for (File f : CacheStorage.targets(cache, files, group))
                    write(new File(f, "payload.bin"), "cached:" + f.getName());
            long[] before = CacheStorage.sizes(cache, files);
            check(
                    before.length == 4 && Arrays.stream(before).allMatch(n -> n > 0),
                    "all four cache categories measured");
            CacheStorage.Result basic =
                    CacheStorage.clear(cache, files, new boolean[] {true, false, true, true});
            check(
                    basic.failures == 0 && basic.bytes == before[0] + before[2] + before[3],
                    "default cleanup reports deleted bytes exactly");
            check(
                    CacheStorage.size(new File(files, "browser-sessions")) > 0
                            && CacheStorage.size(new File(files, "page-drafts")) > 0,
                    "default preserves sessions and translation drafts");
            check(
                    !temporary.exists() && !sessionTemporary.exists(),
                    "temporary import directories removed");
            CacheStorage.Result sessions =
                    CacheStorage.clear(cache, files, new boolean[] {false, true, false, false});
            check(
                    sessions.failures == 0 && sessions.bytes == before[1],
                    "explicit selection removes sessions and drafts");
            for (Map.Entry<File, String> e : keep.entrySet())
                check(
                        Files.readString(e.getKey().toPath()).equals(e.getValue()),
                        "retained exact bytes: " + root.toPath().relativize(e.getKey().toPath()));
            check(
                    Arrays.stream(CacheStorage.sizes(cache, files)).sum() == 0,
                    "cache empty after all categories cleared");
            CacheStorage.Result again =
                    CacheStorage.clear(cache, files, new boolean[] {true, true, true, true});
            check(again.bytes == 0 && again.failures == 0, "repeat cleanup is harmless");
            check(CacheStorage.beginUse(), "work admitted when idle");
            check(!CacheStorage.beginClear(), "cleanup blocked while work active");
            check(CacheStorage.beginUse(), "nested browser and translation work counted");
            CacheStorage.endUse();
            check(!CacheStorage.beginClear(), "remaining worker blocks cleanup");
            CacheStorage.endUse();
            check(CacheStorage.beginClear(), "cleanup admitted after work exits");
            check(
                    !CacheStorage.beginUse() && !CacheStorage.beginClear(),
                    "new jobs and duplicate cleanup blocked");
            CacheStorage.endClear();
            check(CacheStorage.beginUse(), "work resumes after cleanup");
            CacheStorage.endUse();
            java.util.concurrent.CountDownLatch
                    entered = new java.util.concurrent.CountDownLatch(1),
                    exit = new java.util.concurrent.CountDownLatch(1);
            Thread worker =
                    new Thread(
                            () -> {
                                if (!CacheStorage.beginUse()) throw new AssertionError();
                                entered.countDown();
                                try {
                                    exit.await();
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    CacheStorage.endUse();
                                }
                            });
            worker.start();
            check(
                    entered.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            && !CacheStorage.beginClear(),
                    "background worker excludes cleanup");
            exit.countDown();
            worker.join(3000);
            check(
                    !worker.isAlive() && CacheStorage.beginClear(),
                    "cross-thread release permits cleanup");
            CacheStorage.endClear();
            String links = "not supported on this host";
            try {
                Path redirect = new File(files, "browser-sessions").toPath();
                Files.createSymbolicLink(redirect, new File(files, "projects").toPath());
                CacheStorage.Result r =
                        CacheStorage.clear(cache, files, new boolean[] {false, true, false, false});
                check(r.failures == 1 && r.bytes == 0, "redirected cache target rejected");
                check(
                        keep.get(new File(files, "projects/p/project.json"))
                                .equals(
                                        Files.readString(
                                                new File(files, "projects/p/project.json")
                                                        .toPath())),
                        "redirect never touches projects");
                Files.delete(redirect);
                Path alias = new File(root, "data-alias").toPath();
                Files.createSymbolicLink(alias, files.toPath());
                write(new File(files, "page-drafts/one"), "alias cache");
                r =
                        CacheStorage.clear(
                                cache, alias.toFile(), new boolean[] {false, true, false, false});
                check(r.failures == 0 && r.bytes == 11, "trusted data-root alias accepted");
                Files.delete(alias);
                links = "passed";
            } catch (UnsupportedOperationException | FileSystemException unavailable) {
                System.out.println(
                        "Symlink test unavailable: " + unavailable.getClass().getSimpleName());
            }
            String result =
                    "CacheStorageChecks: "
                            + checks
                            + " checks passed; symlinks: "
                            + links
                            + "; Android UI/WebView not executed";
            Files.writeString(new File(report, "缓存清理测试.txt").toPath(), result);
            System.out.println(result);
        } finally {
            // Walk without following links; every generated test path stays under the checked root.
            try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.startsWith(root.toPath())) throw new IOException("Unsafe test cleanup");
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
