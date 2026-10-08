package cn.local.manga;

import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only, cancellable census. Hashes bytes without decoding or exporting image/text content. */
final class StorageCensus {
    private static final class Group {
        long bytes, copies;
        final Set<String> identities = new HashSet<>(), directories = new TreeSet<>();
    }

    static JSONObject scan(Map<String, File> roots, BooleanSupplier cancelled) throws Exception {
        long started = System.nanoTime();
        Map<String, long[]> directories = new TreeMap<>();
        Map<String, Group> hashes = new HashMap<>();
        Set<String> physical = new HashSet<>();
        long[] totals = {0, 0, 0, 0, 0};
        for (Map.Entry<String, File> root : roots.entrySet())
            if (root.getValue().isDirectory())
                Files.walkFileTree(
                        root.getValue().toPath(),
                        new SimpleFileVisitor<Path>() {
                            public FileVisitResult preVisitDirectory(
                                    Path dir, BasicFileAttributes attrs) {
                                check(cancelled);
                                return FileVisitResult.CONTINUE;
                            }

                            public FileVisitResult visitFile(Path path, BasicFileAttributes before)
                                    throws IOException {
                                check(cancelled);
                                if (!before.isRegularFile()) return FileVisitResult.CONTINUE;
                                String
                                        relative =
                                                root.getKey()
                                                        + "/"
                                                        + root.getValue()
                                                                .toPath()
                                                                .relativize(path)
                                                                .toString()
                                                                .replace(File.separatorChar, '/'),
                                        parent = relative.substring(0, relative.lastIndexOf('/'));
                                long[] count =
                                        directories.computeIfAbsent(parent, k -> new long[2]);
                                count[0]++;
                                count[1] += before.size();
                                totals[0]++;
                                totals[1] += before.size();
                                String identity =
                                        before.fileKey() == null
                                                ? path.toAbsolutePath().toString()
                                                : before.fileKey().toString();
                                if (before.fileKey() == null) totals[4]++;
                                if (physical.add(identity)) totals[2] += before.size();
                                MessageDigest digest = StorageFiles.digest();
                                try (InputStream in =
                                        new BufferedInputStream(
                                                new FileInputStream(path.toFile()))) {
                                    byte[] bytes = new byte[65536];
                                    int n;
                                    while ((n = in.read(bytes)) != -1) {
                                        check(cancelled);
                                        digest.update(bytes, 0, n);
                                    }
                                } catch (NoSuchFileException | FileNotFoundException changed) {
                                    totals[3]++;
                                    return FileVisitResult.CONTINUE;
                                }
                                BasicFileAttributes after;
                                try {
                                    after =
                                            Files.readAttributes(
                                                    path,
                                                    BasicFileAttributes.class,
                                                    LinkOption.NOFOLLOW_LINKS);
                                } catch (NoSuchFileException changed) {
                                    totals[3]++;
                                    return FileVisitResult.CONTINUE;
                                }
                                if (before.size() != after.size()
                                        || !before.lastModifiedTime()
                                                .equals(after.lastModifiedTime())) {
                                    totals[3]++;
                                    return FileVisitResult.CONTINUE;
                                }
                                String hash = StorageFiles.hex(digest.digest());
                                Group group = hashes.computeIfAbsent(hash, k -> new Group());
                                group.bytes = before.size();
                                group.copies++;
                                group.identities.add(identity);
                                group.directories.add(parent);
                                return FileVisitResult.CONTINUE;
                            }

                            public FileVisitResult visitFileFailed(Path path, IOException e)
                                    throws IOException {
                                if (e instanceof NoSuchFileException) {
                                    totals[3]++;
                                    return FileVisitResult.CONTINUE;
                                }
                                throw e;
                            }
                        });
        JSONArray dirs = new JSONArray(), dupes = new JSONArray();
        for (Map.Entry<String, long[]> d : directories.entrySet())
            dirs.put(
                    new JSONObject()
                            .put("directory", d.getKey())
                            .put("files", d.getValue()[0])
                            .put("bytes", d.getValue()[1]));
        long duplicate = 0, physicalDuplicate = 0;
        List<Map.Entry<String, Group>> groups = new ArrayList<>(hashes.entrySet());
        groups.sort(
                Comparator.comparingLong(
                                (Map.Entry<String, Group> e) ->
                                        (e.getValue().identities.size() - 1) * e.getValue().bytes)
                        .reversed());
        int matching = 0;
        for (Map.Entry<String, Group> item : groups) {
            Group g = item.getValue();
            if (g.copies < 2) continue;
            matching++;
            duplicate += (g.copies - 1) * g.bytes;
            physicalDuplicate += (g.identities.size() - 1) * g.bytes;
            if (dupes.length() < 500)
                dupes.put(
                        new JSONObject()
                                .put("sha256", item.getKey())
                                .put("size", g.bytes)
                                .put("copies", g.copies)
                                .put("physicalCopies", g.identities.size())
                                .put("directories", new JSONArray(g.directories)));
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new JSONObject()
                .put("kind", "storage_census")
                .put("version", 1)
                .put("files", totals[0])
                .put("logicalBytes", totals[1])
                .put("physicalBytes", totals[2])
                .put("duplicateLogicalBytes", duplicate)
                .put("duplicatePhysicalBytes", physicalDuplicate)
                .put("changedFiles", totals[3])
                .put("unknownFileIdentity", totals[4])
                .put("elapsedMs", elapsed)
                .put("hashBytesPerSecond", elapsed == 0 ? 0 : totals[1] * 1000 / elapsed)
                .put("directories", dirs)
                .put("duplicateGroups", dupes)
                .put("duplicateGroupsTruncated", matching > 500)
                .put("complete", totals[3] == 0)
                .put("contentExported", false);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("存储普查已取消");
    }

    private StorageCensus() {}
}
