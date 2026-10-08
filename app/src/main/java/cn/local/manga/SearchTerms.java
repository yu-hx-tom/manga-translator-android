package cn.local.manga;

import org.json.JSONArray;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded MRU terms, shared by the omnibox and origin-scoped website search. */
final class SearchTerms {
    static List<String> read(String json, int limit) {
        ArrayList<String> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(json);
            for (int i = 0; i < values.length() && result.size() < limit; i++) {
                Object raw = values.opt(i);
                if (!(raw instanceof String)) continue;
                String term = ((String) raw).trim();
                if (!term.isEmpty() && term.length() <= 256 && !result.contains(term))
                    result.add(term);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static String add(String json, String value, int limit) {
        List<String> values = read(json, limit);
        String term = value == null ? "" : value.trim();
        if (!term.isEmpty() && term.length() <= 256) {
            values.remove(term);
            values.add(0, term);
        }
        return new JSONArray(values.subList(0, Math.min(limit, values.size()))).toString();
    }

    static String remove(String json, String value, int limit) {
        List<String> values = read(json, limit);
        values.remove(value);
        return new JSONArray(values).toString();
    }

    static boolean matchesNavigation(String url, String origin, String field, String term) {
        if (term == null || term.isEmpty() || term.length() > 256) return false;
        try {
            URI next = new URI(url), source = new URI(origin);
            if (!Objects.equals(next.getScheme(), source.getScheme())
                    || !Objects.equals(next.getHost(), source.getHost())
                    || next.getPort() != source.getPort()
                    || next.getRawQuery() == null) return false;
            for (String pair : next.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
                if (!((!field.isEmpty() && key.equals(field))
                        || key.matches(
                                "(?i)q|s|query|search|search_query|keyword|keywords|wd|word")))
                    continue;
                if (URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()).trim().equals(term))
                    return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
