package android.webkit;

public final class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();
    public static String cookie = "session=one";

    public static CookieManager getInstance() {
        return INSTANCE;
    }

    public String getCookie(String url) {
        return cookie;
    }
}
