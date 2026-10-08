package cn.local.manga;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Admission owns no Bitmap. Priorities are sampled again as the viewport moves. */
final class TranslationStages {
    private final LongSupplier available;
    private final long maximum;
    private final int target;
    private final LongSupplier clock;
    private static final Object PIXEL_LOCK = new Object();
    private static final List<Waiter> PIXEL_WAITERS = new ArrayList<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final IntSupplier DEFAULT_PRIORITY = () -> 2;
    private static final ThreadLocal<RequestContext> REQUEST_CONTEXT = new ThreadLocal<>();
    private static long reserved;
    private final List<Waiter> networkWaiters = new ArrayList<>(),
            detectionWaiters = new ArrayList<>();
    private int networkActive;
    private boolean detectionActive;
    private int networkLimit;
    private long cooldownUntil, recoverAt;

    TranslationStages(int concurrency, long maximum, LongSupplier available) {
        this(concurrency, maximum, available, () -> System.nanoTime() / 1_000_000);
    }

    TranslationStages(int concurrency, long maximum, LongSupplier available, LongSupplier clock) {
        this.maximum = maximum;
        this.available = available;
        this.clock = clock;
        target = Math.max(1, concurrency);
        networkLimit = target;
    }

    private static final class Waiter {
        final IntSupplier priority;
        final long sequence = SEQUENCE.getAndIncrement(), started = System.nanoTime();

        Waiter(IntSupplier priority) {
            this.priority = priority;
        }

        long rank() {
            return Math.max(
                    0,
                    (long) priority.getAsInt()
                            - (System.nanoTime() - started) / TimeUnit.SECONDS.toNanos(15));
        }
    }

    // ponytail: at most target+2 jobs; scanning this tiny list also permits live viewport
    // priorities.
    private static boolean first(Waiter candidate, List<Waiter> waiters) {
        long rank = candidate.rank();
        for (Waiter other : waiters) {
            long otherRank = other.rank();
            if (otherRank < rank || (otherRank == rank && other.sequence < candidate.sequence))
                return false;
        }
        return true;
    }

    final class Lease implements AutoCloseable {
        private long bytes;

        Lease(long bytes) {
            this.bytes = bytes;
        }

        public void close() {
            synchronized (PIXEL_LOCK) {
                reserved -= bytes;
                bytes = 0;
                PIXEL_LOCK.notifyAll();
            }
        }
    }

    Lease pixels(long bytes, BooleanSupplier cancelled) throws Exception {
        return pixels(bytes, cancelled, DEFAULT_PRIORITY);
    }

    Lease pixels(long bytes, BooleanSupplier cancelled, IntSupplier priority) throws Exception {
        if (bytes < 0) throw new IllegalArgumentException("negative pixel reservation");
        if (bytes > maximum) throw new Exception("图片过大，无法在手机可用内存中处理");
        long idleSince = -1;
        Waiter waiter = new Waiter(priority);
        synchronized (PIXEL_LOCK) {
            PIXEL_WAITERS.add(waiter);
            try {
                while (true) {
                    check(cancelled);
                    if (first(waiter, PIXEL_WAITERS)
                            && bytes <= Math.max(0, available.getAsLong() - reserved)) {
                        reserved += bytes;
                        return new Lease(bytes);
                    }
                    // No owner can free a reservation: persistent real-heap shortage must
                    // terminate.
                    if (reserved == 0) {
                        long now = clock.getAsLong();
                        if (idleSince < 0) idleSince = now;
                        if (now - idleSince >= 15000) throw new Exception("手机当前可用内存不足，请稍后重试或缩小图片");
                    } else idleSince = -1;
                    PIXEL_LOCK.wait(100);
                }
            } finally {
                PIXEL_WAITERS.remove(waiter);
                PIXEL_LOCK.notifyAll();
            }
        }
    }

    /** Bind a page without holding an HTTP slot across its batches or retry delays. */
    <T> T withRequests(Callable<T> action, BooleanSupplier cancelled, IntSupplier priority)
            throws Exception {
        RequestContext previous = REQUEST_CONTEXT.get();
        REQUEST_CONTEXT.set(new RequestContext(this, cancelled, priority));
        try {
            check(cancelled);
            return action.call();
        } finally {
            if (previous == null) REQUEST_CONTEXT.remove();
            else REQUEST_CONTEXT.set(previous);
        }
    }

    private static final class RequestContext {
        final TranslationStages stages;
        final BooleanSupplier cancelled;
        final IntSupplier priority;
        long throttleWaitMs = 30000;

        RequestContext(TranslationStages stages, BooleanSupplier cancelled, IntSupplier priority) {
            this.stages = stages;
            this.cancelled = cancelled;
            this.priority = priority;
        }
    }

    private static final java.util.concurrent.Semaphore IMAGE_REQUEST =
            new java.util.concurrent.Semaphore(1, true);

    /** Image envelopes/base64 need a separate budget even though uploads are streamed from disk. */
    static <T> T withImageRequest(Callable<T> action, BooleanSupplier cancelled) throws Exception {
        boolean entered = false;
        TranslationStages.Lease lease = null;
        try {
            while (!(entered =
                    IMAGE_REQUEST.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)))
                check(cancelled);
            check(cancelled);
            RequestContext context = REQUEST_CONTEXT.get();
            if (context != null)
                lease =
                        context.stages.pixels(
                                96L * 1024 * 1024,
                                () -> cancelled.getAsBoolean() || context.cancelled.getAsBoolean(),
                                context.priority);
            return action.call();
        } finally {
            if (lease != null) lease.close();
            if (entered) IMAGE_REQUEST.release();
        }
    }

    /** Called by ApiClient for one actual connection, after any previous retry wait. */
    static <T> T requestAttempt(Callable<T> action, BooleanSupplier cancelled) throws Exception {
        RequestContext context = REQUEST_CONTEXT.get();
        if (context != null) context.throttleWaitMs = 30000;
        return context == null
                ? action.call()
                : context.stages.request(
                        action,
                        () -> cancelled.getAsBoolean() || context.cancelled.getAsBoolean(),
                        context.priority);
    }

    /**
     * The transport reports this attempt's snapshotted settings and Retry-After before throwing.
     */
    static void throttleWaitForAttempt(long milliseconds) {
        RequestContext context = REQUEST_CONTEXT.get();
        if (context != null) context.throttleWaitMs = Math.max(0, milliseconds);
    }

    <T> T request(Callable<T> action, BooleanSupplier cancelled) throws Exception {
        return request(action, cancelled, DEFAULT_PRIORITY);
    }

    <T> T request(Callable<T> action, BooleanSupplier cancelled, IntSupplier priority)
            throws Exception {
        Waiter waiter = new Waiter(priority);
        boolean entered = false;
        try {
            synchronized (this) {
                networkWaiters.add(waiter);
                while (true) {
                    check(cancelled);
                    long now = clock.getAsLong();
                    if (now >= recoverAt && now >= cooldownUntil && networkLimit < target) {
                        networkLimit++;
                        recoverAt = now + 1000;
                    }
                    if (now >= cooldownUntil
                            && networkActive < networkLimit
                            && first(waiter, networkWaiters)) {
                        networkActive++;
                        entered = true;
                        networkWaiters.remove(waiter);
                        notifyAll();
                        break;
                    }
                    wait(100);
                }
            }
            check(cancelled);
            return action.call();
        } catch (Exception e) {
            if (ApiClient.isThrottle(e))
                synchronized (this) {
                    RequestContext context = REQUEST_CONTEXT.get();
                    long delay =
                            context != null && context.stages == this
                                    ? context.throttleWaitMs
                                    : 30000;
                    networkLimit = Math.max(1, networkLimit / 2);
                    cooldownUntil = Math.max(cooldownUntil, clock.getAsLong() + delay);
                    recoverAt = cooldownUntil + 1000;
                }
            throw e;
        } finally {
            synchronized (this) {
                networkWaiters.remove(waiter);
                if (entered) networkActive--;
                notifyAll();
            }
        }
    }

    <T> T detection(Callable<T> action, BooleanSupplier cancelled, IntSupplier priority)
            throws Exception {
        Waiter waiter = new Waiter(priority);
        boolean entered = false;
        try {
            synchronized (this) {
                detectionWaiters.add(waiter);
                while (true) {
                    check(cancelled);
                    if (!detectionActive && first(waiter, detectionWaiters)) {
                        detectionActive = true;
                        entered = true;
                        detectionWaiters.remove(waiter);
                        break;
                    }
                    wait(100);
                }
            }
            check(cancelled);
            return action.call();
        } finally {
            synchronized (this) {
                detectionWaiters.remove(waiter);
                if (entered) detectionActive = false;
                notifyAll();
            }
        }
    }

    synchronized int networkActive() {
        return networkActive;
    }

    long reservedBytes() {
        synchronized (PIXEL_LOCK) {
            return reserved;
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new CancellationException();
    }
}
