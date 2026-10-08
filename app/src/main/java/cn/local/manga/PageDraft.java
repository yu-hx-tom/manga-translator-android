package cn.local.manga;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything needed to re-typeset one translated text page locally: the exact source PNG the
 * renderer used, the detected regions (+ protection list), and per region the original/machine text
 * and status. Cleanup masks computed during translation are kept as cleanup-&lt;index&gt;.bin when
 * available; missing masks are recomputed with the same function, so a draft never depends on them.
 *
 * <p>Directory layout: draft.json, source.png, cleanup-N.bin (optional).
 */
final class PageDraft {
    static final int SCHEMA = 1;

    /**
     * Typesetter algorithm generation; drafts from another generation still open, the editor just
     * re-lays them out.
     */
    static final String ENGINE = "typeset-v1";

    static final String JSON = "draft.json", SOURCE = "source.png";

    static final class Item {
        final int index;
        final Region region;
        final String original, machine, status;

        Item(int index, Region region, String original, String machine, String status) {
            this.index = index;
            this.region = region;
            this.original = original;
            this.machine = machine;
            this.status = status;
        }

        /** True when translation produced text for this region (it was drawn on the page). */
        boolean translated() {
            return !machine.isEmpty();
        }
    }

    final File dir;
    final int width, height;
    final int inPlaceColor;
    final List<Item> items;
    final List<Region> protection;

    private PageDraft(
            File dir,
            int width,
            int height,
            List<Item> items,
            List<Region> protection,
            int inPlaceColor) {
        this.dir = dir;
        this.width = width;
        this.height = height;
        this.inPlaceColor = inPlaceColor;
        this.items = Collections.unmodifiableList(items);
        this.protection = Collections.unmodifiableList(protection);
    }

    File source() {
        return new File(dir, SOURCE);
    }

    File mask(int index) {
        return new File(dir, CleanupPlan.fileName(index));
    }

    List<Region> regions() {
        List<Region> out = new ArrayList<>();
        for (Item item : items) out.add(item.region);
        return out;
    }

    Item item(String id) {
        for (Item item : items) if (item.region.id.equals(id)) return item;
        return null;
    }

    PageDraft withEdits(java.util.Map<String, PageComposer.Edit> edits) {
        List<Item> expanded = new ArrayList<>(items);
        for (java.util.Map.Entry<String, PageComposer.Edit> e : edits.entrySet())
            if (e.getValue().format.added
                    && item(e.getKey()) == null
                    && e.getValue().format.box != null)
                expanded.add(
                        new Item(
                                expanded.size(),
                                new Region(
                                        e.getKey(),
                                        e.getValue().format.box,
                                        Collections.emptyList(),
                                        false),
                                "",
                                "",
                                "manual"));
        return new PageDraft(dir, width, height, expanded, protection, inPlaceColor);
    }

    static PageDraft read(File dir) throws Exception {
        File file = new File(dir, JSON);
        if (!file.isFile() || file.length() > 4L * 1024 * 1024)
            throw new java.io.IOException("草稿缺失");
        if (!new File(dir, SOURCE).isFile()) throw new java.io.IOException("草稿原图缺失");
        JSONObject json =
                new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (json.optInt("schema") != SCHEMA) throw new java.io.IOException("草稿版本不受支持");
        int width = json.getInt("width"), height = json.getInt("height");
        if (width < 1 || height < 1) throw new java.io.IOException("草稿尺寸无效");
        List<Item> items = new ArrayList<>();
        JSONArray rows = json.getJSONArray("regions");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            items.add(
                    new Item(
                            i,
                            region(row),
                            row.optString("original"),
                            row.optString("machine"),
                            row.optString("status")));
        }
        List<Region> protection = new ArrayList<>();
        JSONArray guards = json.optJSONArray("protection");
        if (guards != null)
            for (int i = 0; i < guards.length(); i++)
                protection.add(region(guards.getJSONObject(i)));
        else for (Item item : items) protection.add(item.region);
        // Old drafts were rendered red; preserve their saved appearance when reopened.
        return new PageDraft(
                dir, width, height, items, protection, json.optInt("inPlaceColor", 0xffff0000));
    }

    /** draft.json body for a freshly rendered page. */
    static JSONObject describe(
            int width,
            int height,
            List<Region> regions,
            List<Region> protection,
            TranslationTranscript transcript)
            throws Exception {
        JSONArray rows = new JSONArray();
        for (Region region : regions) {
            TranslationTranscript.Row row = null;
            for (TranslationTranscript.Row candidate : transcript.rows)
                if (candidate.id.equals(region.id)) {
                    row = candidate;
                    break;
                }
            String status = row == null ? "failed" : row.status;
            boolean drawn = "translated".equals(status) || "in_place".equals(status);
            rows.put(
                    json(region)
                            .put(
                                    "original",
                                    row == null || row.originalText == null ? "" : row.originalText)
                            .put("machine", drawn && row.zh != null ? row.zh : "")
                            .put("status", status));
        }
        JSONArray guards = new JSONArray();
        for (Region region : protection) guards.put(json(region));
        return new JSONObject()
                .put("schema", SCHEMA)
                .put("engine", ENGINE)
                .put("width", width)
                .put("height", height)
                .put("regions", rows)
                .put("protection", guards)
                .put("inPlaceColor", 0xff000000);
    }

    static JSONObject json(Region region) throws Exception {
        JSONArray lines = new JSONArray();
        for (Rect line : region.lines) lines.put(rect(line));
        JSONObject out =
                new JSONObject()
                        .put("id", region.id)
                        .put("box", rect(region.box))
                        .put("lines", lines)
                        .put("vertical", region.vertical);
        if (region.contextBox != null) out.put("context", rect(region.contextBox));
        return out;
    }

    static Region region(JSONObject json) throws Exception {
        List<Rect> lines = new ArrayList<>();
        JSONArray rows = json.optJSONArray("lines");
        if (rows != null)
            for (int i = 0; i < rows.length(); i++) lines.add(rect(rows.getJSONArray(i)));
        JSONArray context = json.optJSONArray("context");
        return new Region(
                json.getString("id"),
                rect(json.getJSONArray("box")),
                lines,
                json.optBoolean("vertical"),
                context == null ? null : rect(context));
    }

    private static JSONArray rect(Rect r) {
        return new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom);
    }

    private static Rect rect(JSONArray a) throws Exception {
        return new Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3));
    }
}
