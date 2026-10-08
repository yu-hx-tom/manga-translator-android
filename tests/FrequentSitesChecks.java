package cn.local.manga;

import org.json.*;

import java.io.*;
import java.nio.file.*;

public final class FrequentSitesChecks {
    static int checks;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    static JSONObject find(JSONArray items, String key) {
        for (int i = 0; i < items.length(); i++)
            if (items.getJSONObject(i).getString("key").equals(key)) return items.getJSONObject(i);
        return null;
    }

    public static void main(String[] args) throws Exception {
        File file = new File(args[0], "sites-fixture-" + System.nanoTime() + ".json");
        LibraryStore store = new LibraryStore(file);
        check(store.frequentSites().length() == 3, "starter sites before history");
        store.visit("第一章", "https://www.comic.test/chapter/1", 1);
        store.visit("第二章", "https://comic.test/chapter/2", 2);
        store.visit("移动页", "http://m.comic.test/search?q=3D", 3);
        check(store.frequentSites().length() == 1, "pages, www and mobile host merged");
        check(
                find(store.frequentSites(), "comic.test").getLong("count") == 3,
                "visits accumulated across pages");
        check(
                find(store.frequentSites(), "comic.test")
                        .getString("url")
                        .equals("http://m.comic.test/"),
                "automatic opens site root not chapter");
        store.visit("漫画首页", "https://comic.test/", 4);
        check(
                find(store.frequentSites(), "comic.test").getString("title").equals("漫画首页"),
                "root title preferred over chapter title");
        for (int i = 0; i < 6; i++) store.visit("其他网站", "https://other.test/page", 5 + i);
        check(
                store.frequentSites().getJSONObject(0).getString("key").equals("other.test"),
                "repeated same-page visits affect rank");
        store.editShortcut("comic.test", "我的漫画", "https://comic.test/library", true, false);
        check(
                store.frequentSites().getJSONObject(0).getString("title").equals("我的漫画"),
                "pinned before frequent");
        store.visit("另一章", "https://comic.test/chapter/99", 30);
        check(
                find(store.frequentSites(), "comic.test").getString("url").endsWith("/library"),
                "edited URL retained after later visits");
        store = new LibraryStore(file);
        check(
                find(store.frequentSites(), "comic.test").optBoolean("pinned"),
                "pin survives reopen");
        check(
                find(store.frequentSites(), "other.test").getLong("count") == 6,
                "counts survive reopen");
        store.editShortcut("other.test", "其他", "https://other.test/", false, true);
        store.visit("又一页", "https://other.test/new", 31);
        check(find(store.frequentSites(), "other.test") == null, "removed site stays hidden");
        store.editShortcut(null, "恢复", "https://other.test/", true, false);
        check(
                find(store.frequentSites(), "other.test") != null,
                "manual add restores removed site");
        store.editShortcut("comic.test", "换网站", "https://new.test/", true, false);
        check(
                find(store.frequentSites(), "comic.test") == null
                        && find(store.frequentSites(), "new.test") != null,
                "editing domain hides former duplicate");
        check(
                !LibraryStore.siteKey("https://one.co.uk/a")
                        .equals(LibraryStore.siteKey("https://two.co.uk/b")),
                "unrelated co.uk domains stay separate");
        boolean invalid = false;
        try {
            store.editShortcut(null, "bad", "javascript:alert(1)", true, false);
        } catch (IllegalArgumentException expected) {
            invalid = true;
        }
        check(invalid, "reject non-web shortcut");
        for (int i = 0; i < 15; i++)
            store.editShortcut(null, "固定 " + i, "https://pin" + i + ".test/", true, false);
        check(
                store.frequentSites().length() >= 15,
                "new pin not silently hidden by automatic limit");
        File legacy = new File(args[0], "legacy-sites-" + System.nanoTime() + ".json");
        Files.writeString(
                legacy.toPath(),
                "{\"version\":1,\"entries\":[{\"url\":\"https://old.test/page\",\"title\":\"旧页面\",\"bookmarkTitle\":\"收藏\",\"visitedAt\":1,\"bookmarkedAt\":2}]}");
        LibraryStore old = new LibraryStore(legacy);
        check(old.frequentSites().length() == 1, "legacy history seeds automatic sites");
        old.visit("新页面", "https://old.test/page", 3);
        old = new LibraryStore(legacy);
        check(old.list(true, "", 0, 10).size() == 1, "legacy bookmark preserved after upgrade");
        check(
                old.frequentSites().getJSONObject(0).getLong("count") == 2,
                "legacy count baseline then increment");
        System.out.println("PASS " + checks + " frequent-site persistence and aggregation checks");
    }
}
