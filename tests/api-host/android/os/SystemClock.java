package android.os;

/** Host transport test only: Android's monotonic clock mapped to the JVM clock. */
public final class SystemClock {
    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000;
    }
}
