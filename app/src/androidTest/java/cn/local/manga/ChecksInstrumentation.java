package cn.local.manga;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Run with: adb shell am instrument -w cn.local.manga.test/cn.local.manga.ChecksInstrumentation */
public final class ChecksInstrumentation extends Instrumentation {
    private String suite;

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        suite = args == null ? null : args.getString("suite");
        start();
    }

    @Override
    public void onStart() {
        Bundle results = new Bundle();
        try {
            if ("v116".equals(suite)) {
                results.putString(
                        "stream", "\nPASS: " + Version116Checks.run(getTargetContext()) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("diagnostics".equals(suite)) {
                results.putString("stream", "\nPASS: " + PerformanceDiagnosticsChecks.run() + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("ui110".equals(suite)) {
                results.putString("stream", "\nPASS: " + Ui110Checks.run(this) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("ui-shell".equals(suite)) {
                results.putString("stream", "\nPASS: " + UiShellChecks.run(this) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("refactor".equals(suite)) {
                results.putString(
                        "stream", "\nPASS: " + RefactorChecks.run(getTargetContext()) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("workbench".equals(suite)) {
                results.putString(
                        "stream", "\nPASS: " + WorkbenchChecks.run(getTargetContext()) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if ("rtdetr".equals(suite)) {
                results.putString(
                        "stream",
                        "\nPASS: " + RtDetrInstrumentation.run(getTargetContext()) + "\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            DetectorChecks.run(getTargetContext());
            String engine = EngineChecks.run(getTargetContext());
            String browser = BrowserImageLoaderChecks.run(getTargetContext());
            String refactor = RefactorChecks.run(getTargetContext());
            String workbench = WorkbenchChecks.run(getTargetContext());
            results.putString(
                    "stream",
                    "\nPASS: offline detector/grouping + "
                            + engine
                            + " + "
                            + browser
                            + " + "
                            + refactor
                            + " + "
                            + workbench
                            + "\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable e) {
            results.putString(
                    "stream",
                    "\nFAIL: " + e.getClass().getSimpleName() + " " + e.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
