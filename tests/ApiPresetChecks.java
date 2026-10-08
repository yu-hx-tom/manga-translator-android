package cn.local.manga;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.lang.reflect.*;
import java.util.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Actual preset CRUD + AES/GCM on JVM. Android SharedPreferences interface uses an atomic memory
 * adapter.
 */
public final class ApiPresetChecks {
    static int checks;
    static final Map<String, String> disk = new HashMap<>();
    static boolean rejectCommit;

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    interface Action {
        void run() throws Exception;
    }

    static void rejected(Action action, String why) throws Exception {
        Map<String, String> before = new HashMap<>(disk);
        boolean rejected = false;
        try {
            action.run();
        } catch (Exception expected) {
            rejected = true;
            check(
                    !String.valueOf(expected.getMessage()).contains("test-secret"),
                    "error message must not contain credentials");
        }
        check(rejected, why);
        check(before.equals(disk), "failed operation preserves committed presets");
    }

    static SharedPreferences preferences() {
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        ApiPresetChecks.class.getClassLoader(),
                        new Class[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getString":
                                    return disk.getOrDefault((String) args[0], (String) args[1]);
                                case "edit":
                                    {
                                        Map<String, String> pending = new HashMap<>();
                                        return Proxy.newProxyInstance(
                                                ApiPresetChecks.class.getClassLoader(),
                                                new Class[] {SharedPreferences.Editor.class},
                                                (editor, operation, values) -> {
                                                    if (operation.getName().equals("putString")) {
                                                        pending.put(
                                                                (String) values[0],
                                                                (String) values[1]);
                                                        return editor;
                                                    }
                                                    if (operation.getName().equals("commit")) {
                                                        if (rejectCommit) return false;
                                                        disk.putAll(pending);
                                                        return true;
                                                    }
                                                    throw new UnsupportedOperationException(
                                                            operation.getName());
                                                });
                                    }
                                default:
                                    throw new UnsupportedOperationException(method.getName());
                            }
                        });
    }

    static void sameSettings(AppSettings expected, AppSettings actual) throws Exception {
        for (Field field : AppSettings.class.getFields())
            if (!Modifier.isStatic(field.getModifiers())
                    && !field.getName().equals("keyUnavailable"))
                check(
                        Objects.equals(field.get(expected), field.get(actual)),
                        "restores setting " + field.getName());
        check(
                !expected.apiSessionId.equals(actual.apiSessionId),
                "loaded preset receives a fresh request session ID");
    }

    public static void main(String[] args) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        SharedPreferences preferences = preferences();
        ApiPresets store = new ApiPresets(preferences, key);
        check(store.list().isEmpty(), "new installation has no presets");
        AppSettings text = new AppSettings();
        text.baseUrl = "https://example.invalid/v1/";
        text.apiKey = "test-secret-text";
        text.textModel = "gpt-test";
        text.imageModel = "image-test";
        text.reasoningEffort = "high";
        text.serviceTier = "priority";
        text.textConcurrency = 3;
        text.maxRetries = 2;
        text.requestTimeoutSeconds = 300;
        text.retryIntervalSeconds = 9;
        text.rateLimitWaitSeconds = 45;
        text.textPrompt = "自定义正文翻译提示词";
        text.imagePrompt = "自定义图像翻译提示词";
        text.detectorModel = DetectorModels.IDS[0];
        String textId = store.save(null, " 文字配置 ", text);
        check(store.list().get(0).name.equals("文字配置"), "preset name trimmed");
        sameSettings(text, store.load(textId));
        String firstCipher = disk.values().iterator().next();
        check(
                !firstCipher.contains(text.apiKey)
                        && !firstCipher.contains(text.baseUrl)
                        && !firstCipher.contains("文字配置")
                        && !firstCipher.contains(text.textModel),
                "entire snapshot and name encrypted at rest");
        AppSettings image = new AppSettings();
        image.baseUrl = "http://192.0.2.1:8080/v1";
        image.apiKey = "test-secret-image";
        image.mode = "image";
        image.textModel = "text-other";
        image.imageModel = "image-other";
        image.reasoningEffort = "omit";
        image.serviceTier = "default";
        image.maxRetries = 0;
        image.textPrompt = "其他文字提示词";
        image.textConcurrency = 32;
        String imageId = store.save(null, "图像配置", image);
        check(store.list().size() == 2, "two independent presets");
        sameSettings(image, store.load(imageId));
        sameSettings(text, store.load(textId));
        text.apiKey = "test-secret-updated";
        text.reasoningEffort = "none";
        String updated = store.save(textId, "文字配置", text);
        check(updated.equals(textId) && store.list().size() == 2, "overwrite keeps ID and count");
        sameSettings(text, store.load(textId));
        sameSettings(image, store.load(imageId));
        String beforeRename = store.load(textId).snapshot().toString();
        store.rename(textId, "正文备用");
        check(store.list().get(0).name.equals("正文备用"), "rename applied");
        check(
                store.load(textId).snapshot().toString().equals(beforeRename),
                "rename preserves all settings");
        rejected(
                () -> store.save(null, "正文备用", image), "duplicate name rejects implicit overwrite");
        rejected(() -> store.rename(imageId, "正文备用"), "rename cannot collide");
        rejected(() -> store.save(null, "  ", image), "empty name rejected");
        rejected(() -> store.save(null, "x".repeat(81), image), "overlong name rejected");
        rejected(() -> store.save("missing", "不存在", image), "unknown update ID rejected");
        rejected(() -> store.delete("missing"), "unknown delete ID rejected");
        AppSettings invalid = AppSettings.fromSnapshot(image.snapshot());
        invalid.baseUrl = "https://user:pass@example.invalid/v1";
        rejected(() -> store.save(null, "无效地址", invalid), "invalid connection cannot enter preset");
        SecretKey wrong = generator.generateKey();
        ApiPresets wrongKey = new ApiPresets(preferences, wrong);
        rejected(() -> wrongKey.list(), "wrong installation key cannot decrypt");
        rejected(
                () -> wrongKey.save(null, "错误密钥", image),
                "failed decrypt cannot replace existing presets");
        String snapshot = disk.values().iterator().next();
        rejectCommit = true;
        rejected(() -> store.rename(textId, "保存失败"), "storage commit failure reported");
        rejectCommit = false;
        check(
                snapshot.equals(disk.values().iterator().next()),
                "commit failure leaves encrypted record intact");
        ApiPresets reopened = new ApiPresets(preferences, key);
        sameSettings(text, reopened.load(textId));
        sameSettings(image, reopened.load(imageId));
        store.delete(textId);
        check(
                store.list().size() == 1 && store.list().get(0).id.equals(imageId),
                "delete affects only selected preset");
        sameSettings(image, store.load(imageId));
        String last = disk.values().iterator().next();
        store.rename(imageId, "图像配置");
        check(
                !last.equals(disk.values().iterator().next()),
                "every write uses fresh random GCM nonce");
        JSONObject broken = new JSONObject(disk.values().iterator().next());
        String ciphertext = broken.getString("ciphertext");
        broken.put(
                "ciphertext", (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1));
        disk.replaceAll((k, v) -> broken.toString());
        rejected(() -> store.load(imageId), "tampered encrypted record rejected");
        rejected(
                () -> store.save(null, "新配置", image), "tampered record never silently overwritten");
        System.out.println(
                "ApiPresetChecks: "
                        + checks
                        + " checks passed (production CRUD/AES-GCM; atomic SharedPreferences"
                        + " adapter, no Android Keystore or UI runtime)");
    }
}
