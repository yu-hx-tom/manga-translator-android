package cn.local.manga;

import org.json.*;

import java.nio.charset.StandardCharsets;

/** Host proofs for canonical text and disabled diagnostics; no Android runtime claim. */
public final class PerformanceDiagnosticsHostChecks {
    public static void main(String[] args) throws Exception {
        int count = 0;
        JSONObject a = new JSONObject("{\"z\":[{\"b\":2,\"a\":1}],\"a\":\"测\\n试\"}");
        JSONObject b = new JSONObject("{\"a\":\"测\\n试\",\"z\":[{\"a\":1,\"b\":2}]}");
        if (!PerformanceDiagnostics.canonical(a).equals(PerformanceDiagnostics.canonical(b)))
            throw new AssertionError("Nested ordering");
        count++;
        if (PerformanceDiagnostics.canonical(new JSONArray("[1,2]"))
                .equals(PerformanceDiagnostics.canonical(new JSONArray("[2,1]"))))
            throw new AssertionError("Array order lost");
        count++;
        if (!PerformanceDiagnostics.sha("abc".getBytes(StandardCharsets.UTF_8))
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            throw new AssertionError("SHA256");
        count++;
        if (PerformanceDiagnostics.enabled()
                || PerformanceDiagnostics.begin("disabled") != null
                || PerformanceDiagnostics.clock() != 0)
            throw new AssertionError("Disabled default");
        count++;
        // These would fail on null if disabled mode touched images or disk.
        PerformanceDiagnostics.bind(null, null);
        PerformanceDiagnostics.file("disabled", null);
        PerformanceDiagnostics.draft(null);
        PerformanceDiagnostics.pixels("disabled", null);
        PerformanceDiagnostics.encoded("disabled", null);
        count++;
        int[] pixels = {0xff123456, 0xffabcdef, 0xff010203, 0xfffefdfc};
        android.graphics.Bitmap bitmap = new android.graphics.Bitmap(2, 2, pixels);
        java.nio.ByteBuffer expected = java.nio.ByteBuffer.allocate(24).putInt(2).putInt(2);
        for (int pixel : pixels) expected.putInt(pixel);
        if (!PerformanceDiagnostics.pixelHash(bitmap)
                .equals(PerformanceDiagnostics.sha(expected.array())))
            throw new AssertionError("Pixel byte order/dimensions");
        count++;
        if (PerformanceDiagnostics.pixelHash(bitmap)
                .equals(
                        PerformanceDiagnostics.pixelHash(
                                new android.graphics.Bitmap(4, 1, pixels))))
            throw new AssertionError("Dimensions not hashed");
        count++;
        java.io.ByteArrayOutputStream direct = new java.io.ByteArrayOutputStream(),
                observed = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, direct);
        java.lang.reflect.Field flag = PerformanceDiagnostics.class.getDeclaredField("enabled");
        flag.setAccessible(true);
        flag.setBoolean(null, true);
        try {
            if (!PerformanceDiagnostics.compress(
                            "fixture",
                            bitmap,
                            android.graphics.Bitmap.CompressFormat.PNG,
                            100,
                            observed)
                    || !java.util.Arrays.equals(direct.toByteArray(), observed.toByteArray()))
                throw new AssertionError("Counting wrapper changes bytes");
            count++;
        } finally {
            flag.setBoolean(null, false);
        }
        java.io.File dir = new java.io.File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new AssertionError("fixture directory");
        java.lang.reflect.Field
                directory = PerformanceDiagnostics.class.getDeclaredField("directory"),
                run = PerformanceDiagnostics.class.getDeclaredField("run");
        directory.setAccessible(true);
        run.setAccessible(true);
        directory.set(null, dir);
        java.lang.reflect.Method append =
                PerformanceDiagnostics.class.getDeclaredMethod("append", byte[].class);
        append.setAccessible(true);
        String row =
                new JSONObject()
                                .put("run", run.get(null))
                                .put("fixture", "x".repeat(1024))
                                .toString()
                        + "\n";
        byte[] chunk = row.repeat(3000).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) append.invoke(null, (Object) chunk);
        if (new java.io.File(dir, "current.jsonl").length()
                        + new java.io.File(dir, "previous.jsonl").length()
                > 8L * 1024 * 1024) throw new AssertionError("Rotation bound");
        count++;
        java.lang.reflect.Field evicted = PerformanceDiagnostics.class.getDeclaredField("evicted");
        evicted.setAccessible(true);
        if (evicted.getLong(null) != 3000) throw new AssertionError("Lost records not counted");
        count++;
        System.out.println(count + " checks passed (host only)");
    }
}
