package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PipelineChecks {
    static int checks;

    static synchronized void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length > 0 ? args[0] : "pipeline-proof");
        Files.createDirectories(output);
        ExecutorService workers = Executors.newCachedThreadPool(),
                serverWorkers = Executors.newCachedThreadPool();
        AtomicInteger active = new AtomicInteger(),
                peak = new AtomicInteger(),
                pixelActive = new AtomicInteger(),
                pixelPeak = new AtomicInteger();
        CountDownLatch ten = new CountDownLatch(10);
        TranslationStages stages = new TranslationStages(10, 64, () -> 64);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext(
                "/translate",
                exchange -> {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    ten.countDown();
                    try {
                        ten.await(5, TimeUnit.SECONDS);
                        Thread.sleep(80);
                        byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                        exchange.close();
                    }
                });
        server.start();
        long started = System.nanoTime();
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int id = 0; id < 10; id++) {
                final int page = id;
                jobs.add(
                        workers.submit(
                                () -> {
                                    try {
                                        Path prepared =
                                                output.resolve("mock-page-" + page + ".txt");
                                        try (TranslationStages.Lease lease =
                                                stages.pixels(32, () -> false)) {
                                            int n = pixelActive.incrementAndGet();
                                            pixelPeak.accumulateAndGet(n, Math::max);
                                            try {
                                                Files.writeString(
                                                        prepared, "prepared page " + page);
                                                Thread.sleep(40);
                                            } finally {
                                                pixelActive.decrementAndGet();
                                            }
                                        }
                                        stages.request(
                                                () -> {
                                                    HttpURLConnection c =
                                                            (HttpURLConnection)
                                                                    new URL(
                                                                                    "http://127.0.0.1:"
                                                                                            + server.getAddress()
                                                                                                    .getPort()
                                                                                            + "/translate")
                                                                            .openConnection();
                                                    c.setConnectTimeout(3000);
                                                    c.setReadTimeout(7000);
                                                    try {
                                                        return c.getInputStream().readAllBytes();
                                                    } finally {
                                                        c.disconnect();
                                                    }
                                                },
                                                () -> false);
                                        try (TranslationStages.Lease lease =
                                                stages.pixels(32, () -> false)) {
                                            ok(
                                                    Files.readString(prepared)
                                                            .equals("prepared page " + page),
                                                    "render reads prepared file");
                                        }
                                        Files.delete(prepared);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }
            ok(ten.await(6, TimeUnit.SECONDS), "ten real localhost HTTP requests overlap");
            ok(stages.reservedBytes() == 0, "all network waits release their pixel reservations");
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
            ok(peak.get() >= 10, "HTTP handler overlap peak is at least ten");
            ok(pixelPeak.get() <= 2, "pixel-heavy preparation remains separately limited");
            ok(
                    stages.networkActive() == 0 && stages.reservedBytes() == 0,
                    "all stage permits released");
            TranslationStages.Lease held = stages.pixels(64, () -> false);
            AtomicBoolean cancel = new AtomicBoolean();
            Future<Boolean> waiting =
                    workers.submit(
                            () -> {
                                try {
                                    stages.pixels(32, cancel::get);
                                    return false;
                                } catch (CancellationException e) {
                                    return true;
                                }
                            });
            Thread.sleep(100);
            cancel.set(true);
            ok(waiting.get(2, TimeUnit.SECONDS), "cancel interrupts memory backpressure wait");
            held.close();
            ok(stages.reservedBytes() == 0, "cancelled waiter does not leak pixel budget");
            AtomicLong idleClock = new AtomicLong();
            TranslationStages shortage = new TranslationStages(10, 64, () -> 0, idleClock::get);
            Future<Boolean> noProgress =
                    workers.submit(
                            () -> {
                                try {
                                    shortage.pixels(32, () -> false);
                                    return false;
                                } catch (Exception e) {
                                    return e.getMessage() != null
                                            && e.getMessage().contains("内存不足");
                                }
                            });
            Thread.sleep(150);
            idleClock.set(15001);
            ok(
                    noProgress.get(2, TimeUnit.SECONDS),
                    "persistent real-memory shortage with no lease owner exits instead of"
                            + " deadlocking");
            AtomicLong clock = new AtomicLong();
            TranslationStages rate = new TranslationStages(10, 64, () -> 64, clock::get);
            try {
                rate.request(
                        () -> {
                            throw new Exception("HTTP 429");
                        },
                        () -> false);
                throw new AssertionError();
            } catch (Exception expected) {
                ok(expected.getMessage().contains("429"), "rate error is propagated");
            }
            AtomicInteger called = new AtomicInteger();
            Future<Integer> after =
                    workers.submit(() -> rate.request(called::incrementAndGet, () -> false));
            Thread.sleep(150);
            ok(called.get() == 0, "already prepared jobs honor shared rate cooldown");
            clock.set(30000);
            ok(after.get(2, TimeUnit.SECONDS) == 1, "request resumes after cooldown");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String report =
                    "{\"checksPassed\":"
                            + checks
                            + ",\"peakActualHttpRequests\":"
                            + peak.get()
                            + ",\"peakPixelPreparationStages\":"
                            + pixelPeak.get()
                            + ",\"pixelReservationAtAllHttpWaits\":0,\"elapsedMs\":"
                            + elapsed
                            + ",\"paidApiUsed\":false,\"scope\":\"Actual shared TranslationStages"
                            + " class with localhost HTTP and disk prepared fixtures; Android"
                            + " Bitmap/ONNX not executed\"}";
            Files.writeString(output.resolve("pipeline_result.json"), report);
            System.out.println(report);
        } finally {
            server.stop(0);
            workers.shutdownNow();
            serverWorkers.shutdownNow();
        }
    }
}
