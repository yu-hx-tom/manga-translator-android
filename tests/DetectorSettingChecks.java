package cn.local.manga;

/** Host-only production configuration checks. Android persistence is not exercised. */
public final class DetectorSettingChecks {
    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
        checks++;
    }

    public static void main(String[] args) throws Exception {
        AppSettings settings = new AppSettings();
        check(DetectorModels.PP_ID.equals(settings.detectorModel));
        check(DetectorModels.IDS.length == 1);
        java.lang.reflect.Method validate = AppSettings.class.getDeclaredMethod("validateOptions");
        validate.setAccessible(true);
        for (String id : DetectorModels.IDS) {
            settings.detectorModel = id;
            validate.invoke(settings);
            check(DetectorModels.get(id).id.equals(id));
            check(DetectorModels.label(id).length() > 0);
        }
        for (String invalid : new String[] {null, "", "rtdetr_unknown"}) {
            settings.detectorModel = invalid;
            boolean rejected = false;
            try {
                validate.invoke(settings);
            } catch (java.lang.reflect.InvocationTargetException expected) {
                rejected = true;
            }
            check(rejected);
        }
        System.out.println(
                "DetectorSettingChecks: "
                        + checks
                        + " checks passed (production options; Android persistence/UI not"
                        + " executed)");
    }
}
