package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Synthetic-only transaction and per-target evidence checks; never an API-quality test. */
public final class GroupedBackgroundReviewChecks {
    static int passed = 0;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    static BufferedImage cloneImage(BufferedImage source) {
        return image(source.getRGB(0, 0, 120, 100, null, 0, 120), 120, 100);
    }

    static JSONObject member(String id, int[] b) {
        return new JSONObject()
                .put("id", id)
                .put("absoluteBox", new JSONArray(b))
                .put("targetBox", new JSONArray(b))
                .put("vertical", true);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        BufferedImage source = new BufferedImage(120, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 100);
        g.setColor(Color.BLACK);
        g.fillRect(17, 15, 10, 18);
        g.fillRect(89, 58, 9, 16);
        g.fillRect(51, 25, 11, 10);
        g.dispose();
        JSONObject a = member("member_a", new int[] {10, 10, 35, 40}),
                b = member("member_b", new int[] {80, 50, 110, 85}),
                skip = member("member_skip", new int[] {48, 20, 68, 40});
        JSONArray members = new JSONArray().put(a).put(b),
                plannedMembers = new JSONArray().put(a).put(b).put(skip),
                targets =
                        new JSONArray()
                                .put(a.getJSONArray("targetBox"))
                                .put(b.getJSONArray("targetBox"));
        JSONObject active =
                new JSONObject()
                        .put("id", "SYNTHETIC_GROUP")
                        .put("page", 1)
                        .put("width", 120)
                        .put("height", 100)
                        .put("sourceSize", new JSONArray(new int[] {120, 100}))
                        .put("sourceCrop", new JSONArray(new int[] {0, 0, 120, 100}))
                        .put("members", members)
                        .put("targetBoxes", targets)
                        .put("protectedBoxes", new JSONArray().put(skip.getJSONArray("targetBox")));
        JSONObject planned = new JSONObject(active.toString()).put("members", plannedMembers);
        Map<String, JSONObject> texts = new LinkedHashMap<>();
        texts.put(
                "member_a",
                new JSONObject().put("id", "member_a").put("zh", "甲").put("skip", false));
        texts.put(
                "member_b",
                new JSONObject().put("id", "member_b").put("zh", "乙").put("skip", false));
        texts.put("member_skip", new JSONObject().put("id", "member_skip").put("skip", true));
        GroupedBackgroundReview.checkMemberTargets(active, texts);
        passed++;
        GroupedBackgroundReview.excludedMembersProtected(planned, active);
        passed++;
        Map<String, JSONObject> missing = new LinkedHashMap<>(texts);
        missing.remove("member_b");
        boolean refused = false;
        try {
            GroupedBackgroundReview.checkMemberTargets(active, missing);
        } catch (Exception expected) {
            refused = true;
        }
        check(
                refused,
                "A missing member translation must reject the whole active group before cleanup");
        JSONObject unprotected =
                new JSONObject(active.toString()).put("protectedBoxes", new JSONArray());
        refused = false;
        try {
            GroupedBackgroundReview.excludedMembersProtected(planned, unprotected);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "A skipped member must remain explicitly protected");
        Path requestDir = root.resolve("requests/SYNTHETIC_GROUP");
        Files.createDirectories(requestDir);
        Path input = requestDir.resolve("input.png"),
                output = requestDir.resolve("synthetic_output.png");
        ImageIO.write(source, "png", input.toFile());
        active.put("inputPng", relative(root, input)).put("inputSha256", hash(input));
        BufferedImage returned = cloneImage(source);
        g = returned.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(10, 10, 25, 30);
        g.fillRect(80, 50, 30, 35);
        g.dispose();
        ImageIO.write(returned, "png", output.toFile());
        active.put("sourcePage", input.toString());
        save(
                requestDir.resolve("段落检测.json"),
                new JSONObject()
                        .put(
                                "regions",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", "rt_1")
                                                        .put(
                                                                "box",
                                                                new JSONObject()
                                                                        .put("x", 20)
                                                                        .put("y", 20)
                                                                        .put("width", 5)
                                                                        .put("height", 5)))
                                        .put(
                                                new JSONObject()
                                                        .put("id", "rt_2")
                                                        .put(
                                                                "box",
                                                                new JSONObject()
                                                                        .put("x", 52)
                                                                        .put("y", 26)
                                                                        .put("width", 6)
                                                                        .put("height", 5)))));
        refused = false;
        try {
            GroupedBackgroundReview.validateParagraphProtection(active);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Missing explicit self mapping must be rejected");
        active.put("explicitSelfRegionIds", new JSONArray());
        refused = false;
        try {
            GroupedBackgroundReview.validateParagraphProtection(active);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "A fully contained paragraph is not implicitly the same lettering");
        active.put("explicitSelfRegionIds", new JSONArray().put("rt_1"));
        JSONObject protection = GroupedBackgroundReview.validateParagraphProtection(active);
        check(
                protection.getJSONArray("nonSelfIntersectingParagraphsProtected").length() == 1,
                "Explicit self mapping must preserve every other paragraph");
        JSONObject unknownSelf =
                new JSONObject(active.toString())
                        .put("explicitSelfRegionIds", new JSONArray().put("rt_missing"));
        refused = false;
        try {
            GroupedBackgroundReview.validateParagraphProtection(unknownSelf);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Unknown self region ids must be rejected");
        JSONObject response =
                new JSONObject()
                        .put("success", true)
                        .put("status", "success")
                        .put("fixtureOnly", true)
                        .put("origin", "synthetic_test")
                        .put("actualRequests", 0)
                        .put("inputSha256", hash(input))
                        .put("outputSha256", hash(output))
                        .put("returnedPath", output.toString())
                        .put("width", 120)
                        .put("height", 100)
                        .put("targetBoxes", targets)
                        .put("protectedBoxes", active.getJSONArray("protectedBoxes"));
        save(requestDir.resolve("request_result.json"), response);
        JSONObject report = new JSONObject().put("id", "SYNTHETIC_GROUP");
        Map<String, JSONObject> rows =
                GroupedBackgroundReview.prepareMemberReports(
                        planned, texts, GroupedBackgroundReview.indexed(members));
        BufferedImage current = cloneImage(source);
        GroupedBackgroundReview.applyGroup(root, source, current, active, report, texts, rows);
        check(
                report.getBoolean("applied"),
                "Two independent targets should stage and commit together");
        check(
                rows.get("member_a").getInt("targetEffectiveChangedOverDelta8") > 8
                        && rows.get("member_b").getInt("targetEffectiveChangedOverDelta8") > 8,
                "Per-member changes must be counted independently");
        check(
                rows.get("member_a").getString("visualStatus").equals("pending"),
                "Pixel change must not be called visual acceptance");
        Map<String, JSONObject> groups = new LinkedHashMap<>();
        groups.put("SYNTHETIC_GROUP", active);
        JSONObject scope =
                GroupedBackgroundReview.verifyPageScope(
                        source, current, java.util.List.of(planned), groups);
        check(
                scope.getInt("outsideEditableUnionChangedPixels") == 0,
                "All pixels outside union must remain exact");
        check(
                rows.get("member_skip").getString("status").equals("model_skip"),
                "Skipped lettering must not be labeled as translated");
        // Only first target changes: the group pixel guard accepts, while member_b must remain
        // visibly unverified.
        returned = cloneImage(source);
        g = returned.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(10, 10, 25, 30);
        g.dispose();
        ImageIO.write(returned, "png", output.toFile());
        response.put("outputSha256", hash(output));
        save(requestDir.resolve("request_result.json"), response);
        rows =
                GroupedBackgroundReview.prepareMemberReports(
                        planned, texts, GroupedBackgroundReview.indexed(members));
        report = new JSONObject().put("id", "SYNTHETIC_GROUP");
        GroupedBackgroundReview.applyGroup(
                root, source, cloneImage(source), active, report, texts, rows);
        check(report.getBoolean("applied"), "Group guard follows production semantics");
        check(
                rows.get("member_b").getInt("targetEffectiveChangedOverDelta8") == 0,
                "Untouched second target must expose zero change");
        check(
                rows.get("member_b")
                        .getString("targetPixelChangeEvidence")
                        .equals("insufficient_change_requires_visual_review"),
                "Untouched target must be flagged for per-target review");
        // A later member fails layout: the first staged glyph must never reach the page.
        JSONObject blocked =
                new JSONObject(active.toString())
                        .put(
                                "protectedBoxes",
                                new JSONArray()
                                        .put(skip.getJSONArray("targetBox"))
                                        .put(new JSONArray(new int[] {79, 49, 111, 86})));
        response.put("protectedBoxes", blocked.getJSONArray("protectedBoxes"));
        save(requestDir.resolve("request_result.json"), response);
        current = cloneImage(source);
        rows =
                GroupedBackgroundReview.prepareMemberReports(
                        planned, texts, GroupedBackgroundReview.indexed(members));
        refused = false;
        try {
            GroupedBackgroundReview.applyGroup(
                    root,
                    source,
                    current,
                    blocked,
                    new JSONObject().put("id", "SYNTHETIC_GROUP"),
                    texts,
                    rows);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Protected second target must reject group layout");
        check(
                Arrays.equals(
                        source.getRGB(0, 0, 120, 100, null, 0, 120),
                        current.getRGB(0, 0, 120, 100, null, 0, 120)),
                "No partial commit may survive a later member failure");
        Path snapshot = root.resolve("frozen/texts.json");
        JSONObject frozenMember =
                new JSONObject()
                        .put("id", "fixture_a")
                        .put("zh", "甲")
                        .put("sourceText", "a")
                        .put("translationRecord", "synthetic_response.json");
        JSONObject snapshotJson =
                new JSONObject().put("translations", new JSONArray().put(frozenMember));
        save(snapshot, snapshotJson);
        JSONObject manifest =
                new JSONObject()
                        .put("textTranslationsFile", relative(root, snapshot))
                        .put("textTranslationsSha256", hash(snapshot))
                        .put(
                                "regions",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put(
                                                                "members",
                                                                new JSONArray()
                                                                        .put(frozenMember))));
        GroupedBackgroundReview.frozenTranslations(root, manifest);
        passed++;
        JSONObject altered = GroupedBackgroundReview.cloneJson(manifest);
        altered.getJSONArray("regions")
                .getJSONObject(0)
                .getJSONArray("members")
                .getJSONObject(0)
                .put("zh", "乙");
        refused = false;
        try {
            GroupedBackgroundReview.frozenTranslations(root, altered);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Frozen embedded translation must match its immutable snapshot");
        Files.writeString(snapshot, "{}");
        refused = false;
        try {
            GroupedBackgroundReview.frozenTranslations(root, manifest);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Changed snapshot bytes must be rejected before rendering");
        rows =
                GroupedBackgroundReview.prepareMemberReports(
                        planned, texts, GroupedBackgroundReview.indexed(members));
        report = new JSONObject();
        GroupedBackgroundReview.awaitingImage(active, report, rows, true);
        check(
                report.getString("status").equals("pending_image")
                        && report.getBoolean("externalQueuePendingMerge"),
                "Separate queue output must remain pending until merged");
        check(
                !rows.get("member_a").getBoolean("cleanupApplied")
                        && !rows.get("member_a").getBoolean("layoutApplied"),
                "Pending target must preserve its original without temporary failure glyphs");
        check(
                rows.get("member_skip").getString("status").equals("model_skip"),
                "Pending group must not reclassify excluded skip members");
        save(
                root.resolve("结果.json"),
                new JSONObject()
                        .put("passed", passed)
                        .put("failed", 0)
                        .put("syntheticOnly", true)
                        .put("realApiRequests", 0)
                        .put("visualAcceptance", false)
                        .put(
                                "scope",
                                "Multi-target union protection, independent target evidence,"
                                    + " skipped target protection, immutable translation identity,"
                                    + " and all-or-nothing group layout transaction"));
        System.out.println("GROUPED BACKGROUND CHECKS " + passed + " passed");
    }
}
