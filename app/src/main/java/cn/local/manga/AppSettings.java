package cn.local.manga;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * User configuration. The credential is encrypted with this installation's Android Keystore key.
 */
public class AppSettings {
    public static final String DEFAULT_TEXT_PROMPT =
            "将图片中的文字翻译成自然、简洁的简体中文，适合原位置嵌字。自行判断原文语言和阅读顺序，完整理解每个裁切中的一段文字；注音只辅助阅读，不重复翻译。正文、旁白和拟声词均可翻译，保持人名一致。不根据画面猜剧情或编造原文；非文字、页码或确实看不清时标记"
                + " skip。只给译文，不附解释。";
    public static final String DEFAULT_IMAGE_PROMPT =
            "将输入漫画裁切中的文字替换成自然简洁的简体中文。自行判断原文语言和阅读顺序，完整理解同一段的文字，再在原文字位置嵌入中文。只去除原文字笔画及其注音；白底气泡直接沿用原白底，禁止修复或重画整个气泡。文字在画面上时，只补全原字笔画遮挡的小块背景，不重画人物、线条、网点或边框。不加矩形底板、不扩图、不裁图，严格保持原画面比例和位置。返回完整的同一张裁切译图，不要多张图或解释。";
    public String baseUrl = "";
    public String apiKey = "";
    public String textModel = "gpt-6-astra";
    public String imageModel = "gpt-image-2.5";
    public String textPrompt = DEFAULT_TEXT_PROMPT;
    public String imagePrompt = DEFAULT_IMAGE_PROMPT;
    public String mode = "text";
    public String detectorModel = DetectorModels.DEFAULT_ID;
    public int textConcurrency = 10;
    public int requestTimeoutSeconds = 180;
    public int maxRetries = 1;
    public int retryIntervalSeconds = 5;
    public int rateLimitWaitSeconds = 30;
    public String reasoningEffort = "low";
    public String serviceTier = "auto";
    public int inPlaceColor = 0xff000000;
    public boolean keyUnavailable;
    // Stable for this loaded settings/task session; never an identity of another client.
    final String apiSessionId = java.util.UUID.randomUUID().toString();
    private static final String ALIAS = "manga_api_credential_v1";

    static String migrateBuiltInTextPrompt(String stored) {
        return "你是漫画日译中译者。将裁切中的日文翻译成自然、简洁的简体中文，适合原位置嵌字。保持人名统一，竖排按从右到左阅读。每个裁切已经是一段文字，不能把一句话分成多个返回项。正文、旁白和拟声词均可翻译；页码、背景线条、注音重复或无法辨认的内容标记 skip。禁止凭画面编造。"
                                .equals(stored)
                        || "将日文漫画文字译成简洁自然的简体中文，适合原位置嵌字。先看清全部原字；竖排从右到左阅读，小假名注音只辅助阅读，不重复翻译。每个裁切是一段，完整翻译正文、旁白或拟声词，保持人名一致。不要根据人物画面猜剧情或编造原文；非文字、页码或确实看不清时标记 skip。只给译文，不附解释。"
                                .equals(stored)
                ? DEFAULT_TEXT_PROMPT
                : stored;
    }

    static String migrateBuiltInImagePrompt(String stored) {
        return "将输入漫画裁切中的日文替换成自然简洁的简体中文。先完整理解同一段的文字，再在原文字位置嵌入中文。只去除原文字笔画及其注音；白底气泡直接沿用原白底，禁止修复或重画整个气泡。文字在画面上时，只补全原字笔画遮挡的小块背景，不重画人物、线条、网点或边框。不加矩形底板、不扩图、不裁图，严格保持原画面比例和位置。返回完整的同一张裁切译图，不要多张图或解释。"
                        .equals(stored)
                ? DEFAULT_IMAGE_PROMPT
                : stored;
    }

    public static AppSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.baseUrl = p.getString("baseUrl", s.baseUrl);
        s.textModel = p.getString("textModel", s.textModel);
        s.imageModel = p.getString("imageModel", s.imageModel);
        s.textPrompt = migrateBuiltInTextPrompt(p.getString("textPrompt", s.textPrompt));
        s.imagePrompt = migrateBuiltInImagePrompt(p.getString("imagePrompt", s.imagePrompt));
        s.mode = p.getString("mode", s.mode);
        s.detectorModel = p.getString("detectorModel", s.detectorModel);
        if (!DetectorModels.isValid(s.detectorModel)) s.detectorModel = DetectorModels.DEFAULT_ID;
        s.textConcurrency = p.getInt("textConcurrency", s.textConcurrency);
        s.requestTimeoutSeconds = p.getInt("requestTimeoutSeconds", s.requestTimeoutSeconds);
        s.maxRetries = p.getInt("maxRetries", s.maxRetries);
        s.retryIntervalSeconds = p.getInt("retryIntervalSeconds", s.retryIntervalSeconds);
        s.rateLimitWaitSeconds = p.getInt("rateLimitWaitSeconds", s.rateLimitWaitSeconds);
        s.inPlaceColor = 0xff000000 | p.getInt("inPlaceColor", s.inPlaceColor);
        s.reasoningEffort = p.getString("reasoningEffort", s.reasoningEffort);
        s.serviceTier =
                migrateServiceTier(
                        p.getString("serviceTier", s.serviceTier),
                        p.contains("fastEnabled") ? p.getBoolean("fastEnabled", false) : null);
        String encrypted = p.getString("key_ciphertext", "");
        if (!encrypted.isEmpty()) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                byte[] iv = Base64.decode(p.getString("key_iv", ""), Base64.NO_WRAP);
                c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                s.apiKey =
                        new String(
                                c.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                                StandardCharsets.UTF_8);
            } catch (Exception unavailable) {
                s.keyUnavailable = true;
            }
        }
        return s;
    }

    public void save(Context context) throws Exception {
        normalize();
        // Permit an empty endpoint while editing, but requests always call validate().
        if (!baseUrl.isEmpty()) validateUrl(baseUrl);
        validateOptions();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        String encrypted =
                Base64.encodeToString(
                        c.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        boolean saved =
                context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("baseUrl", baseUrl)
                        .putString("textModel", textModel)
                        .putString("imageModel", imageModel)
                        .putString("textPrompt", textPrompt)
                        .putString("imagePrompt", imagePrompt)
                        .putString("mode", mode)
                        .putString("detectorModel", detectorModel)
                        .putInt("textConcurrency", textConcurrency)
                        .putString("reasoningEffort", reasoningEffort)
                        .putInt("requestTimeoutSeconds", requestTimeoutSeconds)
                        .putInt("maxRetries", maxRetries)
                        .putInt("retryIntervalSeconds", retryIntervalSeconds)
                        .putInt("rateLimitWaitSeconds", rateLimitWaitSeconds)
                        .putInt("inPlaceColor", inPlaceColor)
                        .putString("serviceTier", serviceTier)
                        .remove("fastEnabled")
                        .putString("key_ciphertext", encrypted)
                        .putString("key_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .remove("apiKey")
                        .commit();
        if (!saved) throw new Exception("配置未能保存，请检查手机存储空间");
    }

    public void validate() throws Exception {
        validateConnection();
        if ("image".equals(mode) ? imageModel.isEmpty() : textModel.isEmpty())
            throw new Exception("请填写当前模式的模型名称");
        if (!"image".equals(mode) && !"text".equals(mode)) throw new Exception("未知翻译模式，请重新选择");
    }

    public void validateConnection() throws Exception {
        normalize();
        validateOptions();
        if (baseUrl.isEmpty()) throw new Exception("请先填写 API 地址，例如 http://电脑局域网IP:50254/v1");
        validateUrl(baseUrl);
        if (apiKey.isEmpty()) throw new Exception("请先填写 API Key");
        if (apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0)
            throw new Exception("API Key 包含换行，请重新粘贴");
    }

    private void validateOptions() throws Exception {
        if (!DetectorModels.isValid(detectorModel)) throw new Exception("请选择有效的本地检测模型");
        validateRequestOptions();
        if (textConcurrency < 1 || textConcurrency > 32)
            throw new Exception("文字网络并发请填写 1–32；默认 10，实际受接口限流影响");
        if (!java.util.Arrays.asList("omit", "none", "minimal", "low", "medium", "high", "xhigh")
                .contains(reasoningEffort)) throw new Exception("请选择有效的文字推理强度");
        if (!java.util.Arrays.asList("auto", "default", "priority").contains(serviceTier))
            throw new Exception("请选择有效的服务级别");
    }

    void validateRequestOptions() throws Exception {
        if (requestTimeoutSeconds < 10 || requestTimeoutSeconds > 600)
            throw new Exception("单次请求超时请填写 10–600 秒的整数");
        if (maxRetries < 0 || maxRetries > 5) throw new Exception("最大额外重试次数请填写 0–5 次的整数");
        if (retryIntervalSeconds < 1 || retryIntervalSeconds > 120)
            throw new Exception("重试间隔请填写 1–120 秒的整数");
        if (rateLimitWaitSeconds < 1 || rateLimitWaitSeconds > 300)
            throw new Exception("限流等待请填写 1–300 秒的整数");
    }

    static int parseInteger(String text, String name, int minimum, int maximum) throws Exception {
        try {
            String value = text == null ? "" : text.trim();
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) {
            throw new Exception(name + "请填写 " + minimum + "–" + maximum + " 的整数");
        }
    }

    static int parseInPlaceColor(String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (!value.matches("#?[0-9a-fA-F]{6}")) throw new Exception("嵌字颜色请填写六位颜色值，例如 #000000");
        return 0xff000000 | Integer.parseInt(value.replace("#", ""), 16);
    }

    static String formatInPlaceColor(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xffffff);
    }

    public String requestedServiceTier() {
        return serviceTier;
    }

    private static String migrateServiceTier(String stored, Boolean legacyFast) {
        // Old installations used the flag as the authority, including inconsistent saved pairs.
        if (legacyFast != null)
            return legacyFast ? "priority" : "default".equals(stored) ? "default" : "auto";
        return "fast".equals(stored) ? "priority" : stored;
    }

    private void normalize() {
        inPlaceColor |= 0xff000000;
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        textModel = textModel == null ? "" : textModel.trim();
        imageModel = imageModel == null ? "" : imageModel.trim();
        serviceTier = migrateServiceTier(serviceTier, null);
        if (textPrompt == null || textPrompt.trim().isEmpty()) textPrompt = DEFAULT_TEXT_PROMPT;
        if (imagePrompt == null || imagePrompt.trim().isEmpty()) imagePrompt = DEFAULT_IMAGE_PROMPT;
    }

    public static void validateUrl(String value) throws Exception {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || uri.getPort() > 65535
                    || uri.getPort() == 0) throw new Exception();
        } catch (Exception e) {
            throw new Exception("API 地址必须是完整的 http:// 或 https:// 地址，不能包含账户、查询参数或片段");
        }
    }

    /**
     * The settings that change a rendered page, in the one order every page-level cache key uses.
     * Changing these fields requires a new page-cache generation. 1.1.6 appends the in-place color
     * and updates both browser and completed-page generations to exclude legacy red output. Service
     * tier and request/retry tuning are deliberately absent: they never change the output.
     */
    JSONArray renderFields() {
        return new JSONArray()
                .put(baseUrl)
                .put(apiKey)
                .put(mode)
                .put(textModel)
                .put(imageModel)
                .put(textPrompt)
                .put(imagePrompt)
                .put(reasoningEffort)
                .put(detectorModel)
                .put("in-place-color-v1")
                .put(inPlaceColor);
    }

    /** Compact form of {@link #renderFields()}: equal strings mean "same translated output". */
    String renderFingerprint() {
        return renderFields().toString();
    }

    /** A complete user-editable snapshot; session IDs and diagnostics are not settings. */
    JSONObject snapshot() throws Exception {
        return new JSONObject()
                .put("baseUrl", baseUrl)
                .put("apiKey", apiKey)
                .put("mode", mode)
                .put("textModel", textModel)
                .put("imageModel", imageModel)
                .put("reasoningEffort", reasoningEffort)
                .put("serviceTier", serviceTier)
                .put("textPrompt", textPrompt)
                .put("imagePrompt", imagePrompt)
                .put("detectorModel", detectorModel)
                .put("textConcurrency", textConcurrency)
                .put("requestTimeoutSeconds", requestTimeoutSeconds)
                .put("maxRetries", maxRetries)
                .put("retryIntervalSeconds", retryIntervalSeconds)
                .put("rateLimitWaitSeconds", rateLimitWaitSeconds)
                .put("inPlaceColor", inPlaceColor);
    }

    static AppSettings fromSnapshot(JSONObject value) throws Exception {
        AppSettings s = new AppSettings();
        s.baseUrl = value.getString("baseUrl");
        s.apiKey = value.getString("apiKey");
        s.mode = value.getString("mode");
        s.textModel = value.getString("textModel");
        s.imageModel = value.getString("imageModel");
        s.reasoningEffort = value.getString("reasoningEffort");
        s.serviceTier =
                migrateServiceTier(
                        value.getString("serviceTier"),
                        value.has("fastEnabled") ? value.getBoolean("fastEnabled") : null);
        s.textPrompt = value.getString("textPrompt");
        s.imagePrompt = value.getString("imagePrompt");
        s.detectorModel = value.getString("detectorModel");
        if (!DetectorModels.isValid(s.detectorModel)) s.detectorModel = DetectorModels.DEFAULT_ID;
        s.textConcurrency = value.getInt("textConcurrency");
        s.requestTimeoutSeconds = value.getInt("requestTimeoutSeconds");
        s.maxRetries = value.getInt("maxRetries");
        s.retryIntervalSeconds = value.getInt("retryIntervalSeconds");
        s.rateLimitWaitSeconds = value.getInt("rateLimitWaitSeconds");
        s.inPlaceColor = 0xff000000 | value.optInt("inPlaceColor", s.inPlaceColor);
        s.validate();
        return s;
    }

    static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }
}
