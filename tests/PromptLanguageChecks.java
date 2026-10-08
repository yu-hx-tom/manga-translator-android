package cn.local.manga;

import org.json.*;

import java.nio.file.*;

public class PromptLanguageChecks {
    static int checks;

    static void ok(boolean v, String why) {
        if (!v) throw new AssertionError(why);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        String shipped = Files.readString(Paths.get(args[0]));
        var text =
                java.util.regex.Pattern.compile("DEFAULT_TEXT_PROMPT = \"(.*?)\";")
                        .matcher(shipped);
        text.find();
        String oldText = text.group(1);
        var image =
                java.util.regex.Pattern.compile("DEFAULT_IMAGE_PROMPT = \"(.*?)\";")
                        .matcher(shipped);
        image.find();
        String oldImage = image.group(1);
        ok(
                !AppSettings.DEFAULT_TEXT_PROMPT.contains("日文")
                        && !AppSettings.DEFAULT_TEXT_PROMPT.contains("日译"),
                "text prompt has no Japanese-only source restriction");
        ok(
                !AppSettings.DEFAULT_IMAGE_PROMPT.contains("日文"),
                "image prompt has no Japanese-only source restriction");
        ok(
                AppSettings.DEFAULT_TEXT_PROMPT.contains("原文语言")
                        && AppSettings.DEFAULT_TEXT_PROMPT.contains("简体中文"),
                "source language automatic and Chinese output explicit");
        ok(
                AppSettings.DEFAULT_IMAGE_PROMPT.contains("原文语言")
                        && AppSettings.DEFAULT_IMAGE_PROMPT.contains("简体中文"),
                "image instruction covers automatic source language and Chinese output");
        ok(
                AppSettings.migrateBuiltInTextPrompt(oldText)
                        .equals(AppSettings.DEFAULT_TEXT_PROMPT),
                "shipped old default text prompt migrates");
        ok(
                AppSettings.migrateBuiltInImagePrompt(oldImage)
                        .equals(AppSettings.DEFAULT_IMAGE_PROMPT),
                "shipped old default image prompt migrates");
        ok(
                AppSettings.migrateBuiltInTextPrompt("自定义:翻译成繁体中文").equals("自定义:翻译成繁体中文"),
                "custom text prompt preserved");
        ok(
                AppSettings.migrateBuiltInImagePrompt("custom image prompt")
                        .equals("custom image prompt"),
                "custom image prompt preserved");
        ok(
                AppSettings.migrateBuiltInTextPrompt(AppSettings.DEFAULT_TEXT_PROMPT)
                        .equals(AppSettings.DEFAULT_TEXT_PROMPT),
                "new default remains stable");
        ok(
                AppSettings.DEFAULT_TEXT_PROMPT.contains("不根据画面")
                        && AppSettings.DEFAULT_IMAGE_PROMPT.contains("禁止修复或重画整个气泡"),
                "language-neutral prompts retain source fidelity and background guards");
        System.out.println(
                "PromptLanguageChecks: "
                        + checks
                        + " checks passed (actual defaults and migration helpers)");
    }
}
