package cn.local.manga;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** Validate each region independently. A corrupt entry never discards a different valid region. */
final class PartialTranslations {
    static final class Reply {
        final Map<String, JSONObject> values = new LinkedHashMap<>();
        final Map<String, JSONObject> observations = new LinkedHashMap<>();
        final Map<String, String> failures = new LinkedHashMap<>();
    }

    static Reply read(JSONObject response, List<Region> regions) {
        Reply out = new Reply();
        Set<String> known = new HashSet<>(), seen = new HashSet<>();
        for (Region r : regions) {
            known.add(r.id);
            out.failures.put(r.id, "模型遗漏此段编号");
        }
        JSONArray items = response.optJSONArray("translations");
        if (items == null) {
            for (String id : known) out.failures.put(id, "模型未返回 translations 列表");
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !(item.opt("id") instanceof String)) continue;
            String id = item.optString("id");
            if (!known.contains(id)) continue;
            if (!seen.add(id)) {
                out.values.remove(id);
                out.observations.remove(id);
                out.failures.put(id, "模型返回重复编号，无法确定对应译文");
                continue;
            }
            JSONObject clean = normalized(item, false);
            out.observations.put(id, clean);
            if (valid(item)) {
                out.values.put(id, clean);
                out.failures.remove(id);
            } else out.failures.put(id, "模型返回无效译文（空文本、类型错误或超长）");
        }
        return out;
    }

    static boolean valid(JSONObject item) {
        if (!(item.opt("skip") instanceof Boolean) || !(item.opt("zh") instanceof String))
            return false;
        String text = item.optString("zh").trim();
        return text.length() <= 1000 && (item.optBoolean("skip") || !text.isEmpty());
    }

    /**
     * Recognition is optional evidence: a bad/missing original never invalidates a usable
     * translation.
     */
    static JSONObject normalized(JSONObject item, boolean fromCache) {
        try {
            JSONObject clean =
                    new JSONObject()
                            .put("id", item.optString("id"))
                            .put("zh", valid(item) ? item.optString("zh").trim() : "")
                            .put("skip", item.optBoolean("skip"));
            Object raw = item.opt("originalText");
            String original = "", status;
            if (raw instanceof String
                    && ((String) raw).length() <= TranslationTranscript.MAX_ORIGINAL) {
                original = ((String) raw).trim();
                status =
                        original.isEmpty()
                                ? (item.optBoolean("skip") ? "unreadable" : "missing")
                                : "available";
            } else
                status = item.has("originalText") ? "invalid" : fromCache ? "old_cache" : "missing";
            // Only our own stored marker survives a cache read, never a model-provided status.
            if (fromCache && original.isEmpty()) {
                String saved = item.optString("originalStatus");
                if (java.util.Arrays.asList("old_cache", "missing", "invalid", "unreadable")
                        .contains(saved)) status = saved;
            }
            return clean.put("originalText", original).put("originalStatus", status);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static JSONArray retryContent(JSONArray original, List<Region> batch, Set<String> missing)
            throws Exception {
        if (original.length() != 1 + batch.size() * 2) throw new Exception("补发裁切清单不完整");
        JSONArray subset = new JSONArray().put(original.get(0));
        for (int i = 0; i < batch.size(); i++)
            if (missing.contains(batch.get(i).id)) {
                subset.put(original.get(1 + i * 2));
                subset.put(original.get(2 + i * 2));
            }
        String instructionType =
                "input_text".equals(original.getJSONObject(0).optString("type"))
                        ? "input_text"
                        : "text";
        subset.put(
                new JSONObject()
                        .put("type", instructionType)
                        .put("text", "本次仅补发未完成段落。必须为上述每个裁切 id 返回一个结果，不要遗漏；译文尽量简短，适合原框嵌字。"));
        return subset;
    }
}
