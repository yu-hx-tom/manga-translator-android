package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.concurrent.CancellationException;

/** Offline checks: no real sites, login sessions, API keys or HTTP requests. */
public final class BrowserImageLoaderChecks {
    private BrowserImageLoaderChecks() {}

    public static String run(Context context) throws Exception {
        int count = 0;
        for (String address :
                new String[] {
                    null,
                    "",
                    "file:///sdcard/image.png",
                    "content://photos/1",
                    "javascript:alert(1)",
                    "blob:https://example.org/1",
                    "ftp://example.org/image.png",
                    "http://user:password@example.org/image.png",
                    "https://example.org:70000/image.png"
                }) {
            boolean rejected = false;
            try {
                BrowserImageLoader.networkUrl(address);
            } catch (Exception expected) {
                rejected = true;
            }
            require(rejected, "must reject non-network or credential-bearing image URL");
            count++;
        }
        require(
                BrowserImageLoader.networkUrl(
                                "https://example.org/image.png?signature=private#fragment")
                        .toString()
                        .equals("https://example.org/image.png?signature=private"),
                "drop fragment, retain signed image query");
        count++;
        require(
                "https://example.org/chapter/2?session=x"
                        .equals(
                                BrowserImageLoader.referer(
                                        "https://example.org/chapter/2?session=x#part",
                                        "https://example.org/image.png")),
                "same-origin referer");
        count++;
        require(
                "https://example.org/"
                        .equals(
                                BrowserImageLoader.referer(
                                        "https://example.org/chapter/2?session=x",
                                        "https://cdn.example.net/image.png")),
                "cross-origin referer must hide page path and query");
        count++;
        require(
                BrowserImageLoader.referer(
                                "https://example.org/secret", "http://cdn.example.net/image.png")
                        == null,
                "no https downgrade referer");
        count++;
        require(
                BrowserImageLoader.referer("file:///private", "https://example.net/image.png")
                        == null,
                "no local page referer");
        count++;
        require(
                "https://example.org:443/chapter"
                        .equals(
                                BrowserImageLoader.referer(
                                        "https://example.org:443/chapter",
                                        "https://example.org/image.png")),
                "normalized default-port same origin");
        count++;
        for (String host :
                new String[] {
                    "0.0.0.0",
                    "127.0.0.1",
                    "10.10.1.1",
                    "172.16.0.2",
                    "192.168.1.1",
                    "169.254.169.254",
                    "100.64.0.1",
                    "::1",
                    "fe80::1",
                    "fd00::1",
                    "224.0.0.1"
                }) {
            require(
                    BrowserImageLoader.isPrivateOrLocalAddress(InetAddress.getByName(host)),
                    "local/private address must be restricted");
            count++;
        }
        for (String host :
                new String[] {
                    "1.1.1.1", "8.8.8.8", "198.18.0.1", "198.19.255.254", "2606:4700:4700::1111"
                }) {
            require(
                    !BrowserImageLoader.isPrivateOrLocalAddress(InetAddress.getByName(host)),
                    "public and VPN fake-IP addresses must remain usable");
            count++;
        }
        InetAddress[] local = {InetAddress.getByName("192.168.1.4")};
        InetAddress[] publicIp = {InetAddress.getByName("1.1.1.1")};
        require(
                !BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("http://192.168.1.4/image.png"),
                        BrowserImageLoader.networkUrl("https://example.org/"),
                        local,
                        publicIp),
                "public page must not read LAN image");
        count++;
        require(
                BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("http://192.168.1.4/image.png"),
                        BrowserImageLoader.networkUrl("http://192.168.1.4/chapter"),
                        local,
                        local),
                "internal same-origin browser test remains usable");
        count++;
        require(
                !BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("http://192.168.1.4:50254/image.png"),
                        BrowserImageLoader.networkUrl("http://192.168.1.4/chapter"),
                        local,
                        local),
                "internal different port is not same origin");
        count++;
        require(
                !BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("http://example.org/image.png"),
                        BrowserImageLoader.networkUrl("http://example.org/chapter"),
                        local,
                        publicIp),
                "public page DNS classification cannot authorize local destination");
        count++;
        require(
                !BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("http://example.org/image.png"),
                        null,
                        local,
                        null),
                "missing origin cannot authorize local destination");
        count++;
        require(
                BrowserImageLoader.permitsDestination(
                        BrowserImageLoader.networkUrl("https://cdn.example.org/image.png"),
                        BrowserImageLoader.networkUrl("https://example.org/"),
                        new InetAddress[] {InetAddress.getByName("198.18.0.1")},
                        null),
                "VPN fake-IP route stays enabled");
        count++;

        for (int[] original :
                new int[][] {
                    {1000, 2000},
                    {6000, 6000},
                    {1000, 30000},
                    {100000, 1},
                    {Integer.MAX_VALUE, Integer.MAX_VALUE}
                }) {
            int[] size = BrowserImageLoader.boundedSize(original[0], original[1]);
            require(
                    size[0] > 0
                            && size[1] > 0
                            && size[0] <= BrowserImageLoader.MAX_SIDE
                            && size[1] <= BrowserImageLoader.MAX_SIDE
                            && (long) size[0] * size[1] <= BrowserImageLoader.MAX_PIXELS,
                    "bounded decoded image dimensions");
            count++;
        }
        int[] unchanged = BrowserImageLoader.boundedSize(1024, 2048);
        require(unchanged[0] == 1024 && unchanged[1] == 2048, "preserve normal manga resolution");
        count++;

        Bitmap source = Bitmap.createBitmap(37, 53, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.rgb(25, 70, 135));
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded);
        String data =
                "data:image/png;base64,"
                        + Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP);
        Bitmap decoded = BrowserImageLoader.decodeDataUrl(data);
        require(
                decoded.getWidth() == 37
                        && decoded.getHeight() == 53
                        && decoded.getPixel(10, 10) == source.getPixel(10, 10),
                "PNG data image round-trip");
        count++;
        require(
                decoded.getConfig() != Bitmap.Config.HARDWARE,
                "software bitmap usable by detector");
        count++;
        decoded.recycle();
        source.recycle();
        for (String invalid :
                new String[] {
                    "data:text/html;base64,PHNjcmlwdD4=",
                    "data:image/svg+xml;base64,PHN2Zz4=",
                    "data:image/png,raw",
                    "data:image/png;base64,",
                    "data:image/png;base64,@@@@",
                    "data:image/png;base64,SGVsbG8="
                }) {
            boolean rejected = false;
            try {
                Bitmap unexpected = BrowserImageLoader.decodeDataUrl(invalid);
                unexpected.recycle();
            } catch (Exception expected) {
                rejected = true;
            }
            require(rejected, "invalid data image must fail");
            count++;
        }
        boolean cancelled = false;
        try {
            BrowserImageLoader.load(context, data, "https://example.org/", "test", () -> true);
        } catch (CancellationException expected) {
            cancelled = true;
        }
        require(cancelled, "pre-cancelled load must not decode or request");
        count++;
        return "BrowserImageLoaderChecks: " + count + " 项通过（无网络请求）";
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
