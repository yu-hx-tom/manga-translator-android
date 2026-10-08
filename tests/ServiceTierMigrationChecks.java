package cn.local.manga;

import android.content.*;

import org.json.JSONObject;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Production preference/preset readers, with an in-memory SharedPreferences adapter. */
public final class ServiceTierMigrationChecks {
    static int checks;

    static void ok(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static AppSettings load(Path root, Map<String, Object> values) {
        SharedPreferences preferences =
                (SharedPreferences)
                        Proxy.newProxyInstance(
                                ServiceTierMigrationChecks.class.getClassLoader(),
                                new Class[] {SharedPreferences.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("contains"))
                                        return values.containsKey(args[0]);
                                    if (method.getName().equals("getString")
                                            || method.getName().equals("getBoolean")
                                            || method.getName().equals("getInt"))
                                        return values.getOrDefault(args[0], args[1]);
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return AppSettings.load(
                new Context(root.toFile()) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return preferences;
                    }
                });
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        AppSettings valid = new AppSettings();
        valid.baseUrl = "https://example.invalid/v1";
        valid.apiKey = "fixture-unused-key";
        JSONObject template = valid.snapshot();
        for (String tier : List.of("auto", "default", "priority", "fast"))
            for (boolean oldFlag : new boolean[] {false, true}) {
                String expected =
                        oldFlag ? "priority" : tier.equals("default") ? "default" : "auto";
                JSONObject legacy =
                        new JSONObject(template.toString())
                                .put("serviceTier", tier)
                                .put("fastEnabled", oldFlag);
                AppSettings preset = AppSettings.fromSnapshot(legacy);
                ok(
                        preset.requestedServiceTier().equals(expected),
                        "legacy preset keeps effective tier " + tier + "/" + oldFlag);
                ok(
                        !preset.snapshot().has("fastEnabled"),
                        "resaved snapshot has one canonical tier field");
                AppSettings preferences =
                        load(root, Map.of("serviceTier", tier, "fastEnabled", oldFlag));
                ok(
                        preferences.requestedServiceTier().equals(expected),
                        "legacy active preferences keep effective tier " + tier + "/" + oldFlag);
            }
        for (String tier : List.of("auto", "default", "priority", "fast")) {
            String expected = tier.equals("fast") ? "priority" : tier;
            AppSettings preset =
                    AppSettings.fromSnapshot(
                            new JSONObject(template.toString()).put("serviceTier", tier));
            ok(
                    preset.serviceTier.equals(expected)
                            && preset.requestedServiceTier().equals(expected),
                    "canonical snapshot and legacy fast alias normalize " + tier);
            ok(
                    load(root, Map.of("serviceTier", tier)).serviceTier.equals(expected),
                    "canonical active preference loads " + tier);
        }
        ok(load(root, Map.of()).serviceTier.equals("auto"), "new install remains automatic");
        boolean invalid = false;
        try {
            AppSettings.fromSnapshot(
                    new JSONObject(template.toString()).put("serviceTier", "unrecognized"));
        } catch (Exception expected) {
            invalid = true;
        }
        ok(invalid, "unknown canonical tier is rejected");
        ok(
                Arrays.stream(AppSettings.class.getFields())
                        .noneMatch(f -> f.getName().equals("fastEnabled")),
                "runtime no longer has conflicting service-tier authorities");
        System.out.println(
                "ServiceTierMigrationChecks: "
                        + checks
                        + " checks passed (production settings readers; no Android Keystore or UI"
                        + " runtime)");
    }
}
