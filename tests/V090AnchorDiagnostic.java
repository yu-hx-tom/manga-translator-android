package cn.local.manga;

import android.graphics.Rect;

import org.json.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;

/** Compares existing production ink-anchor paths using saved model boxes; no model inference. */
public final class V090AnchorDiagnostic {
    static JSONArray boxes(List<Rect> boxes) {
        JSONArray a = new JSONArray();
        for (Rect b : boxes) a.put(new JSONArray(new int[] {b.left, b.top, b.right, b.bottom}));
        return a;
    }

    static List<Region> predict(BufferedImage image, JSONObject prediction) {
        JSONArray raw = prediction.getJSONArray("boxes"),
                types = prediction.getJSONArray("box_types"),
                confidence = prediction.getJSONArray("scores");
        long[] labels = new long[raw.length()];
        float[][] bounds = new float[raw.length()][4];
        float[] scores = new float[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            String type = types.getString(i);
            labels[i] =
                    type.equals("bubble")
                            ? 0
                            : type.equals("text_bubble") ? 1 : type.equals("text_free") ? 2 : -1;
            scores[i] = confidence.getFloat(i);
            for (int k = 0; k < 4; k++) bounds[i][k] = raw.getJSONArray(i).getFloat(k);
        }
        return RtDetrRegions.fromPredictions(
                image.getWidth(),
                image.getHeight(),
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                labels,
                bounds,
                scores);
    }

    public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]), out = Paths.get(args[1]);
        Files.createDirectories(out);
        Set<String> selected =
                new HashSet<>(
                        Arrays.asList(
                                "P03_rt_6 P04_rt_5 P05_rt_8 P07_rt_8 P11_rt_12 P12_rt_4 P13_rt_4 P14_rt_4 P17_rt_3 P18_rt_2 P18_rt_14 P19_rt_2 P19_rt_11 P20_rt_8 P24_rt_1 P25_rt_7 P28_rt_1"
                                        .split(" ")));
        Method exact =
                RtDetrRegions.class.getDeclaredMethod(
                        "inkLinesExact",
                        int[].class,
                        int.class,
                        Rect.class,
                        boolean.class,
                        boolean.class);
        exact.setAccessible(true);
        JSONArray records = new JSONArray();
        for (int page = 1; page <= 30; page++) {
            String prefix = String.format("P%02d_", page);
            if (selected.stream().noneMatch(k -> k.startsWith(prefix))) continue;
            Path folder = source.resolve(String.format("逐页/第%02d页", page));
            BufferedImage image = ImageIO.read(folder.resolve("原图.png").toFile());
            int width = image.getWidth(), height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (Region region :
                    predict(
                            image,
                            new JSONObject(Files.readString(folder.resolve("检测原始结果.json"))))) {
                String key = prefix + region.id;
                if (!selected.contains(key)) continue;
                @SuppressWarnings("unchecked")
                List<Rect> alternate =
                        (List<Rect>)
                                exact.invoke(
                                        null, pixels, width, region.box, region.vertical, true);
                JSONObject record =
                        new JSONObject()
                                .put("id", key)
                                .put("target", boxes(Collections.singletonList(region.box)).get(0))
                                .put("current", boxes(region.lines))
                                .put("fragmented", boxes(alternate));
                records.put(record);
                System.out.println(record);
                int x = Math.max(0, region.box.left - 15),
                        y = Math.max(0, region.box.top - 15),
                        right = Math.min(width, region.box.right + 15),
                        bottom = Math.min(height, region.box.bottom + 15);
                BufferedImage canvas =
                        new BufferedImage((right - x) * 2, bottom - y, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = canvas.createGraphics();
                g.drawImage(image, 0, 0, right - x, bottom - y, x, y, right, bottom, null);
                g.drawImage(
                        image,
                        right - x,
                        0,
                        (right - x) * 2,
                        bottom - y,
                        x,
                        y,
                        right,
                        bottom,
                        null);
                g.setColor(Color.RED);
                for (Rect r : region.lines)
                    g.drawRect(r.left - x, r.top - y, r.right - r.left, r.bottom - r.top);
                g.setColor(Color.BLUE);
                for (Rect r : alternate)
                    g.drawRect(
                            right - x + r.left - x, r.top - y, r.right - r.left, r.bottom - r.top);
                g.dispose();
                ImageIO.write(canvas, "png", out.resolve(key + ".png").toFile());
            }
        }
        Files.writeString(out.resolve("分位路径对照.json"), records.toString(2));
    }
}
