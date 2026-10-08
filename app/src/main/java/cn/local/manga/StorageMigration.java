package cn.local.manga;

/** SQL adapter for the host-tested restartable migration order. */
final class StorageMigration implements MigrationRunner.Ledger {
    final StorageDatabase store;
    final String id;

    StorageMigration(StorageDatabase store, String id, String checksum, long count)
            throws Exception {
        this.store = store;
        this.id = id;
        store.db.execSQL(
                "INSERT OR IGNORE INTO migration(id,step,state,started_at,source_count,checksum)"
                        + " VALUES(?,0,'pending',?,?,?)",
                new Object[] {id, System.currentTimeMillis(), count, checksum});
        String expected = store.scalar("SELECT checksum FROM migration WHERE id=?", id);
        if (!checksum.equals(expected)) throw new java.io.IOException("迁移源已变化，保留源和已写入数据，需检查后再继续");
    }

    public int completed() {
        return (int) store.number("SELECT step FROM migration WHERE id=?", id);
    }

    public void completed(int step) {
        store.db.execSQL(
                "UPDATE migration SET step=?,state=?,finished_at=?,error='' WHERE id=?",
                new Object[] {
                    step,
                    step == MigrationRunner.DONE ? "done" : "running",
                    step == MigrationRunner.DONE ? System.currentTimeMillis() : 0,
                    id
                });
    }

    public void failure(int step, Exception error) {
        store.db.execSQL(
                "UPDATE migration SET state='failed',error=? WHERE id=?",
                new Object[] {"步骤 " + step + "：" + StorageDatabase.message(error), id});
    }

    void verified(long count) {
        store.db.execSQL(
                "UPDATE migration SET target_count=? WHERE id=?", new Object[] {count, id});
    }

    static void issue(StorageDatabase store, String id, Exception error) {
        store.meta("migration_error/" + id, StorageDatabase.message(error));
    }

    static void resolved(StorageDatabase store, String id) {
        store.db.execSQL("DELETE FROM meta WHERE key=?", new Object[] {"migration_error/" + id});
    }
}
