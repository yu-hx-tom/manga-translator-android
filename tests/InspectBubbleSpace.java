package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Read-only diagnostics of the production safe-space selection. */
public final class InspectBubbleSpace {
    public static void main(String[] args) throws Exception {
        JSONObject plan = json(Paths.get(args[0]));
        Set<String> selected = new HashSet<>(Arrays.asList(args[1].split(",")));
        Method method =
                BubbleLayout.class.getDeclaredMethod(
                        "space",
                        boolean[].class,
                        int.class,
                        int.class,
                        int.class,
                        int[][].class,
                        boolean.class);
        method.setAccessible(true);
        for (Object p : plan.getJSONArray("pages")) {
            JSONObject page = (JSONObject) p;
            BufferedImage image = null;
            for (Object raw : page.getJSONArray("regions")) {
                JSONObject row = (JSONObject) raw;
                String id = row.getString("id");
                if (!selected.contains(id)) continue;
                if (image == null)
                    image = ImageIO.read(Paths.get(page.getString("sourcePage")).toFile());
                List<Region> regions =
                        predict(
                                image,
                                json(
                                        Paths.get(page.getString("sourcePage"))
                                                .getParent()
                                                .resolve("检测原始结果.json")));
                Region region = null;
                for (Region r : regions) if (r.id.equals(row.getString("regionId"))) region = r;
                Geometry g = new Geometry(image, region);
                boolean[] safe =
                        WhiteBubbleCleaner.excludeForeign(
                                g.mask.interior,
                                g.w,
                                g.h,
                                RtDetrRegions.foreignLines(
                                        regions, region.id, g.roi.left, g.roi.top));
                Object result =
                        method.invoke(null, safe, g.w, g.h, g.estimate, g.lines, region.vertical);
                JSONObject output = new JSONObject().put("id", id);
                for (Field field : result.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    output.put(field.getName(), JSONObject.wrap(field.get(result)));
                }
                System.out.println(output);
            }
        }
    }
}
