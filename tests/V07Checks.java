package cn.local.manga;

import org.json.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

import javax.imageio.ImageIO;

public final class V07Checks {
    static int checks, pages, regions, fallbacks;

    static void ok(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
        checks++;
    }

    static int[] box(JSONObject r) {
        int x = r.getInt("x"), y = r.getInt("y");
        return new int[] {x, y, x + r.getInt("width"), y + r.getInt("height")};
    }

    static int[] clipped(int w, int h, int[] anchor) {
        return new int[] {
            Math.max(0, Math.min(w, anchor[0])),
            Math.max(0, Math.min(h, anchor[1])),
            Math.max(0, Math.min(w, anchor[2])),
            Math.max(0, Math.min(h, anchor[3]))
        };
    }

    static void validate(NearbyTextLayout.Plan p, int w, int h, int[] anchor, String text) {
        ok(
                Arrays.equals(p.points, text.replaceAll("\\s+", "").codePoints().toArray()),
                "all codepoints kept in exact order");
        ok(
                Arrays.equals(p.box, clipped(w, h, anchor)),
                "fallback stays exactly in original text box clipped to page");
        ok(p.box[0] >= 0 && p.box[1] >= 0 && p.box[2] <= w && p.box[3] <= h, "bounds stay on page");
        ok(p.font > 0 && p.step > 0 && p.columns > 0, "positive font and grid");
        boolean onPage = true;
        for (int i = 0; i < p.points.length; i++)
            onPage &=
                    p.cellLeft(i) >= p.box[0] - .001f
                            && p.cellTop(i) >= p.box[1] - .001f
                            && p.cellLeft(i) + p.step <= p.box[2] + .001f
                            && p.cellTop(i) + p.step <= p.box[3] + .001f;
        ok(onPage, "every vertical cell fully on page, no text truncation");
        boolean ordered = true;
        for (int i = 1; i < p.points.length; i++) {
            if (i % p.rows() != 0)
                ordered &=
                        Math.abs(p.cellLeft(i) - p.cellLeft(i - 1)) < .001f
                                && p.cellTop(i) > p.cellTop(i - 1);
            else
                ordered &=
                        p.cellLeft(i) < p.cellLeft(i - 1)
                                && Math.abs(p.cellTop(i) - p.cellTop(0)) < .001f;
        }
        ok(ordered, "vertical cells read top to bottom and then right to left");
    }

    public static void main(String[] args) throws Exception {
        ok(BrowserAddress.resolve("").equals(BrowserAddress.HOME), "empty input home");
        ok(
                BrowserAddress.resolve("  猫 漫画 & a+b  ")
                        .equals(
                                "https://www.google.com/search?q=%E7%8C%AB+%E6%BC%AB%E7%94%BB+%26+a%2Bb"),
                "Unicode query properly escaped");
        ok(
                BrowserAddress.resolve("example.com/read?x=1#p")
                        .equals("https://example.com/read?x=1#p"),
                "bare domain navigation");
        ok(
                BrowserAddress.resolve("localhost:8080/read").equals("https://localhost:8080/read"),
                "local host with port");
        ok(
                BrowserAddress.resolve("http://192.168.1.1:8080/")
                        .equals("http://192.168.1.1:8080/"),
                "explicit HTTP retained");
        ok(BrowserAddress.resolve("https://例子.测试/漫画").contains("xn--"), "IDN URL accepted");
        for (String bad :
                new String[] {
                    "javascript:alert(1)",
                    "file:///a",
                    "data:text/html,test",
                    "content://image",
                    "ftp://example.com",
                    "https://user:password@example.com",
                    "https://example.com:99999"
                }) {
            try {
                BrowserAddress.resolve(bad);
                throw new AssertionError("unsafe/invalid scheme accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
        for (int[] dims : new int[][] {{1, 1}, {19, 19}, {320, 480}, {1200, 1800}})
            for (String text : new String[] {"完整译文", "字".repeat(1000), "标点，空 格\n𠮷😀！"}) {
                int[] anchor = new int[] {0, 0, Math.max(1, dims[0] / 8), Math.max(1, dims[1] / 8)};
                NearbyTextLayout.Plan plan =
                        NearbyTextLayout.plan(
                                dims[0],
                                dims[1],
                                anchor,
                                List.of(),
                                List.of(),
                                text,
                                (x, y) -> 0xff777777);
                validate(plan, dims[0], dims[1], anchor, text);
            }
        int[] fixedAnchor = {400, 500, 500, 600};
        NearbyTextLayout.Plan one =
                NearbyTextLayout.plan(
                        900, 1200, fixedAnchor, List.of(), List.of(), "原框嵌字", (x, y) -> 0xffffffff);
        NearbyTextLayout.Plan two =
                NearbyTextLayout.plan(
                        900,
                        1200,
                        fixedAnchor,
                        List.of(),
                        Collections.singletonList(one.box),
                        "保留完整新译文",
                        (x, y) -> 0xffffffff);
        ok(
                Arrays.equals(one.box, fixedAnchor) && Arrays.equals(two.box, fixedAnchor),
                "same anchor never relocates to avoid an earlier translation");
        for (int[] anchor :
                new int[][] {
                    {-20, -30, 35, 75},
                    {305, 455, 370, 550},
                    {120, 8, 123, 472},
                    {20, 40, 300, 44},
                    {0, 0, 320, 480}
                }) {
            String text = "窄框长译文𠮷😀，".repeat(256);
            int[] originalAnchor = anchor.clone();
            NearbyTextLayout.Plan plan =
                    NearbyTextLayout.plan(
                            320, 480, anchor, List.of(), List.of(), text, (x, y) -> 0xff000000);
            validate(plan, 320, 480, anchor, text);
            ok(
                    Arrays.equals(anchor, originalAnchor),
                    "clipping and long-text layout never mutate detector coordinates");
        }
        String fixedText = "这段长译文必须留在原框𠮷😀，从上到下再向左。";
        NearbyTextLayout.Plan baseline =
                NearbyTextLayout.plan(
                        900,
                        1200,
                        fixedAnchor,
                        List.of(),
                        List.of(),
                        fixedText,
                        (x, y) -> 0xffffffff);
        for (int shade : new int[] {0xff000000, 0xff777777, 0xffffffff}) {
            NearbyTextLayout.Plan blocked =
                    NearbyTextLayout.plan(
                            900,
                            1200,
                            fixedAnchor,
                            Arrays.asList(
                                    new int[] {380, 480, 520, 620}, new int[] {0, 0, 900, 1200}),
                            Arrays.asList(fixedAnchor, new int[] {0, 0, 900, 1200}),
                            fixedText,
                            (x, y) -> shade);
            validate(blocked, 900, 1200, fixedAnchor, fixedText);
            boolean same =
                    blocked.columns == baseline.columns
                            && Math.abs(blocked.step - baseline.step) < .001f;
            for (int i = 0; i < blocked.points.length; i++)
                same &=
                        Math.abs(blocked.cellLeft(i) - baseline.cellLeft(i)) < .001f
                                && Math.abs(blocked.cellTop(i) - baseline.cellTop(i)) < .001f;
            ok(
                    same,
                    "neighbor boxes, existing translations and background brightness never displace"
                            + " original-box text");
        }
        Thread.currentThread().interrupt();
        try {
            NearbyTextLayout.plan(
                    100, 100, new int[] {0, 0, 50, 50}, List.of(), List.of(), "取消", (x, y) -> 0);
            throw new AssertionError("must cancel");
        } catch (CancellationException expected) {
            checks++;
        } finally {
            Thread.interrupted();
        }
        try (var dirs = Files.list(Paths.get(args[0], "逐页"))) {
            for (Path dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                BufferedImage image = ImageIO.read(dir.resolve("原图.png").toFile());
                JSONObject record = new JSONObject(Files.readString(dir.resolve("回填记录.json")));
                List<int[]> occupied = new ArrayList<>(), foreign = new ArrayList<>();
                for (Object v :
                        new JSONObject(Files.readString(dir.resolve("段落检测.json")))
                                .getJSONArray("regions"))
                    foreign.add(box(((JSONObject) v).getJSONObject("box")));
                for (Object v : record.getJSONArray("paragraphs")) {
                    JSONObject item = (JSONObject) v;
                    if ("cleaned_layout".equals(item.getString("status")))
                        occupied.add(box(item.getJSONObject("textLayer")));
                }
                for (Object v : record.getJSONArray("paragraphs")) {
                    JSONObject item = (JSONObject) v;
                    if (!"nearby_fallback".equals(item.getString("status"))) continue;
                    int[] anchor = box(item.getJSONObject("sourceBox"));
                    List<int[]> others = new ArrayList<>();
                    for (int[] b : foreign) if (!Arrays.equals(b, anchor)) others.add(b);
                    String text = item.getString("zh");
                    NearbyTextLayout.Plan plan =
                            NearbyTextLayout.plan(
                                    image.getWidth(),
                                    image.getHeight(),
                                    anchor,
                                    others,
                                    occupied,
                                    text,
                                    image::getRGB);
                    validate(plan, image.getWidth(), image.getHeight(), anchor, text);
                    occupied.add(plan.box);
                    fallbacks++;
                }
                pages++;
                regions += record.getInt("validTranslations");
            }
        }
        Path source = Paths.get(args[1]);
        String engine = Files.readString(source.resolve("TranslationEngine.java"));
        ok(
                !engine.contains("constrainReply(") && !engine.contains("textCapacity("),
                "App has no dormant layout-capacity rejection path");
        ok(
                engine.indexOf("for(Region region:pending)")
                        > engine.indexOf("renderLocal(result.image"),
                "fallback commits after all normal cleanup");
        ok(
                !new PageOutcome(2, 2, 0, 0, 2, "原框嵌字").incomplete(),
                "successful fallback does not trigger incomplete retry");
        ok(
                PageOutcome.decode(new PageOutcome(2, 2, 0, 0, 2, "原框嵌字").encode())
                                .preservedOriginal
                        == 2,
                "fallback counters survive cache");
        JSONObject report =
                new JSONObject()
                        .put("checks", checks)
                        .put("pages", pages)
                        .put("existingValidTranslations", regions)
                        .put("realFallbackLayouts", fallbacks)
                        .put("allCodepointsKept", true)
                        .put("allFallbacksRemainInOriginalBox", true)
                        .put("readingOrder", "top-to-bottom, right-to-left")
                        .put("androidRuntimeVerified", false)
                        .put("paidApiUsed", false)
                        .put("visualTranslationReview", false);
        Files.writeString(Paths.get(args[2]), report.toString(2));
        System.out.println(report);
    }
}
