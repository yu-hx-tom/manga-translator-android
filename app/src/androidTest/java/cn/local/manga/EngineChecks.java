package cn.local.manga;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Small on-device checks. No external API calls or real user credentials are used. */
public final class EngineChecks {
    private EngineChecks() {}
    public static String run(Context context) throws Exception {
        int checks = 0;
        AppSettings settings = new AppSettings();
        ensure(settings.apiKey.isEmpty() && settings.baseUrl.isEmpty(), "默认配置不能携带地址或凭据"); checks++;
        for (String invalid : new String[]{"", "file:///tmp/test", "ftp://example.com", "http://user:pass@localhost/v1", "http://localhost/v1?key=test", "http://localhost:99999/v1"}) {
            boolean rejected = false;
            try { AppSettings.validateUrl(invalid); } catch (Exception expected) { rejected = true; }
            ensure(rejected, "无效地址必须拒绝"); checks++;
        }
        AppSettings.validateUrl("http://192.168.1.7:50254/v1"); checks++;
        AppSettings.validateUrl("https://example.com/v1"); checks++;

        Region first = new Region("a", new Rect(0, 0, 12, 30), Arrays.asList(new Rect(1, 1, 10, 29)), true);
        Region second = new Region("b", new Rect(14, 0, 26, 30), Arrays.asList(new Rect(15, 1, 24, 29)), true);
        JSONObject good = response(item("a", "你好", false), item("b", "", true));
        ensure(PartialTranslations.read(good,Arrays.asList(first,second)).values.size()==2,"完整译文应接受");checks++;
        ensure(PartialTranslations.read(response(item("a","甲",false)),Arrays.asList(first,second)).values.size()==1,"遗漏一段应保留有效段");checks++;
        ensure(PartialTranslations.read(response(item("a","甲",false),item("a","乙",false),item("b","丙",false)),Arrays.asList(first,second)).values.size()==1,"重复编号只拒绝对应段");checks++;
        ensure(PartialTranslations.read(response(item("a","",false),item("b","乙",false)),Arrays.asList(first,second)).values.size()==1,"无效译文只拒绝对应段");checks++;

        // Use an isolated preference name while exercising the real Android Keystore path.
        String preferencePrefix = "engine_check_" + System.nanoTime() + "_";
        Context sandbox = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return context.getSharedPreferences(preferencePrefix + name, mode);
            }
        };
        SharedPreferences prefs = sandbox.getSharedPreferences("api_settings", Context.MODE_PRIVATE);
        try {
            AppSettings local = new AppSettings();
            local.baseUrl = "http://192.168.1.7:50254/v1";
            local.apiKey = "test-only-not-a-real-key-12345";
            local.requestTimeoutSeconds=240;local.maxRetries=3;local.retryIntervalSeconds=8;local.rateLimitWaitSeconds=45;
            local.textModel="selected-text-model";local.imageModel="selected-image-model";
            local.save(sandbox);
            AppSettings restored=AppSettings.load(sandbox);
            ensure(local.apiKey.equals(restored.apiKey), "加密后必须能够读取原 Key"); checks++;
            ensure(restored.requestTimeoutSeconds==240&&restored.maxRetries==3&&restored.retryIntervalSeconds==8&&restored.rateLimitWaitSeconds==45,"等待及重试参数必须持久化");checks++;
            ensure(restored.textModel.equals(local.textModel)&&restored.imageModel.equals(local.imageModel),"选中的两种模型必须持久化");checks++;
            ensure(!prefs.getAll().toString().contains(local.apiKey), "Preferences 不得出现明文 Key"); checks++;
            ensure(!prefs.getString("key_ciphertext", "").isEmpty(), "必须保存密文"); checks++;
        } finally { prefs.edit().clear().commit(); context.deleteSharedPreferences(preferencePrefix + "api_settings"); }

        TranslationEngine engine = new TranslationEngine(context);
        Method identity = TranslationEngine.class.getDeclaredMethod("identity", Bitmap.class, Region.class, AppSettings.class);
        identity.setAccessible(true);
        Bitmap small = Bitmap.createBitmap(12, 30, Bitmap.Config.ARGB_8888);
        small.eraseColor(Color.WHITE);
        settings.baseUrl = "http://192.168.1.7:50254/v1"; settings.apiKey = "account-one";
        String original = (String) identity.invoke(engine, small, first, settings);
        ensure(original.matches("[a-f0-9]{64}"), "缓存只包含摘要"); checks++;
        settings.apiKey = "account-two";
        ensure(!original.equals(identity.invoke(engine, small, first, settings)), "不同凭据必须隔离缓存"); checks++;
        settings.apiKey = "account-one"; settings.textPrompt += "new";
        ensure(!original.equals(identity.invoke(engine, small, first, settings)), "提示词变更必须隔离缓存"); checks++;
        settings.textPrompt = AppSettings.DEFAULT_TEXT_PROMPT; settings.baseUrl = "http://192.168.1.8:50254/v1";
        ensure(!original.equals(identity.invoke(engine, small, first, settings)), "不同服务必须隔离缓存"); checks++;
        Method writeText=TranslationEngine.class.getDeclaredMethod("writeText",String.class,JSONObject.class);
        Method cachedText=TranslationEngine.class.getDeclaredMethod("cachedText",String.class);
        writeText.setAccessible(true);cachedText.setAccessible(true);
        String cacheKey="engine-check-skip-"+System.nanoTime();
        java.io.File cacheFile=new java.io.File(new java.io.File(context.getCacheDir(),"translations"),cacheKey+".json");
        try{
            JSONObject skipValue=item("a","",true);
            writeText.invoke(engine,cacheKey,skipValue);
            ensure(!cacheFile.exists(),"skip响应不应写入持久缓存");checks++;
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(cacheFile)){stream.write(skipValue.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            ensure(cachedText.invoke(engine,cacheKey)==null,"旧skip缓存不应阻止用户手动重试");checks++;
            cacheFile.delete();writeText.invoke(engine,cacheKey,item("a","你好",false));
            ensure(cachedText.invoke(engine,cacheKey)!=null,"正常译文字缓存仍保留");checks++;
        }finally{cacheFile.delete();}
        small.recycle();

        Bitmap source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Canvas art = new Canvas(source);
        Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
        art.drawRect(32, 22, 247, 177, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int y = 40; y < 140; y += 15) { art.drawRect(70, y, 76, y + 7, paint); art.drawRect(180, y, 186, y + 7, paint); }
        Region test = new Region("test", new Rect(30, 20, 250, 180), Arrays.asList(new Rect(68, 38, 81, 140), new Rect(178, 38, 191, 140)), true);
        Bitmap cropped = Bitmap.createBitmap(source, 30, 20, 220, 160);
        Bitmap translated = source.copy(Bitmap.Config.ARGB_8888, true);
        Method render = TranslationEngine.class.getDeclaredMethod("renderLocal", Bitmap.class, Bitmap.class, Region.class, Rect.class, String.class, List.class,WhiteBubbleCleaner.Mask.class);
        render.setAccessible(true);
        ensure(((int[])render.invoke(null, translated, cropped, test, test.box, "你好", Arrays.asList(test),null)).length==4, "纯白气泡应本地去字并返回文字边界"); checks++;
        ensure(translated.getPixel(71, 41) == Color.WHITE, "原文字笔画应去除"); checks++;
        boolean framePreserved = true, exteriorPreserved = true;
        for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) {
            if (!test.box.contains(x, y) && source.getPixel(x, y) != translated.getPixel(x, y)) exteriorPreserved = false;
            if ((x < 45 || x > 235 || y < 30 || y > 165) && source.getPixel(x, y) != translated.getPixel(x, y)) framePreserved = false;
        }
        ensure(exteriorPreserved, "文字框外必须像素一致"); checks++;
        ensure(framePreserved, "气泡边线必须保留"); checks++;
        Region selectedOnly=new Region("selected",test.box,Arrays.asList(test.lines.get(0)),true);
        Region deselected=new Region("deselected",test.box,Arrays.asList(test.lines.get(1)),true);
        Bitmap guarded=source.copy(Bitmap.Config.ARGB_8888,true);boolean crossRejected=false;
        try{render.invoke(null,guarded,cropped,selectedOnly,selectedOnly.box,"你好",Arrays.asList(selectedOnly,deselected),null);}
        catch(InvocationTargetException expected){crossRejected=expected.getCause() instanceof Exception;}
        ensure(crossRejected,"未选中的段也必须参加防误擦保护");checks++;
        ensure(guarded.sameAs(source),"拒绝跨段回填时不得提交半张结果");checks++;
        guarded.recycle();
        source.recycle(); cropped.recycle(); translated.recycle();engine.close();
        return "EngineChecks: " + checks + " 项通过（未请求外部 API）";
    }
    private static JSONObject item(String id, String zh, boolean skip) throws Exception { return new JSONObject().put("id", id).put("zh", zh).put("skip", skip); }
    private static JSONObject response(JSONObject... items) throws Exception { JSONArray array = new JSONArray(); for (JSONObject item : items) array.put(item); return new JSONObject().put("translations", array); }
    private static void ensure(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
