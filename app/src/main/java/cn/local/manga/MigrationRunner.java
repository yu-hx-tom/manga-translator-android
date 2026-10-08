package cn.local.manga;

/** Restartable migration order. Steps must be idempotent; cutover is the last durable action. */
final class MigrationRunner {
    static final int BACKUP = 0, IMPORT = 1, VERIFY = 2, CUTOVER = 3, DONE = 4;

    interface Ledger {
        int completed() throws Exception;

        void completed(int count) throws Exception;

        void failure(int step, Exception error) throws Exception;
    }

    interface Step {
        void run(int step) throws Exception;
    }

    static void run(Ledger ledger, Step action) throws Exception {
        int step = ledger.completed();
        if (step < 0 || step > DONE) throw new IllegalStateException("不支持的迁移步骤");
        while (step < DONE) {
            try {
                action.run(step);
                ledger.completed(step + 1);
                step++;
            } catch (Exception error) {
                try {
                    ledger.failure(step, error);
                } catch (Exception secondary) {
                    error.addSuppressed(secondary);
                }
                throw error;
            }
        }
    }

    private MigrationRunner() {}
}
