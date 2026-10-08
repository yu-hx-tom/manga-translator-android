package cn.local.manga;

/** Arithmetic and deletion gates are pure Java for boundary/interleaving tests. */
final class StoragePolicy {
    static final long MIB = 1024L * 1024, MIN_FREE = 300 * MIB;

    static long cacheBudget(long available) {
        return Math.min(2048 * MIB, Math.max(0, available) / 4);
    }

    static long sessionBudget(long available) {
        return Math.min(800 * MIB, Math.max(0, available) / 5);
    }

    static boolean canReserve(long available, long reserved) {
        return reserved >= 0 && available >= MIN_FREE && reserved <= available - MIN_FREE;
    }

    static boolean collectable(
            long references,
            boolean leased,
            boolean pending,
            boolean backup,
            long lastAccess,
            long now) {
        return references == 0
                && !leased
                && !pending
                && !backup
                && lastAccess <= now
                && now - lastAccess >= BlobStore.GRACE_MS;
    }

    private StoragePolicy() {}
}
