package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import javax.imageio.ImageIO;

/** Exact erasure and paper-domain regression on existing images; no API or drawing. */
public final class CleanupMaskSnapshot {
    static String digest(boolean[] values) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        for (boolean v : values) d.update((byte) (v ? 1 : 0));
        return HexFormat.of().formatHex(d.digest());
    }

    static String digest(int[] values) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        for (int v : values) {
            d.update((byte) (v >>> 24));
            d.update((byte) (v >>> 16));
            d.update((byte) (v >>> 8));
            d.update((byte) v);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]), output = Paths.get(args[1]);
        int pageCount = Integer.parseInt(args[2]);
        JSONArray rows = new JSONArray();
        int local = 0;
        for (int page = 1; page <= pageCount; page++) {
            Path dir = input.resolve(String.format("逐页/第%02d页", page));
            BufferedImage source = ImageIO.read(dir.resolve("原图.png").toFile());
            var regions = readRegions(source, dir);
            for (Region r : regions) {
                Geometry g = new Geometry(source, r);
                WhiteBubbleCleaner.Mask m = g.mask;
                int[][] foreign = RtDetrRegions.foreignLines(regions, r.id, g.roi.left, g.roi.top);
                boolean[] erase = WhiteBubbleCleaner.excludeForeign(m.erase, g.w, g.h, foreign);
                int[] clean = g.pixels.clone();
                for (int p = 0; p < clean.length; p++)
                    if (erase[p]) clean[p] = m.fillColors == null ? 0xffffffff : m.fillColors[p];
                boolean accepted =
                        m.whiteBackground
                                && m.backgroundKind
                                        == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER;
                if (accepted) local++;
                rows.put(
                        new JSONObject()
                                .put("id", String.format("P%02d_", page) + r.id)
                                .put("local", accepted)
                                .put("backgroundKind", m.backgroundKind.toString())
                                .put("evidence", m.evidence)
                                .put("eraseSha256", digest(erase))
                                .put("interiorSha256", digest(m.interior))
                                .put("cleanPixelsSha256", digest(clean)));
            }
            source.flush();
        }
        save(
                output,
                new JSONObject()
                        .put("regions", rows)
                        .put("regionCount", rows.length())
                        .put("local", local)
                        .put("networkCalls", 0)
                        .put("androidVerified", false));
        System.out.println("MASK SNAPSHOT regions=" + rows.length() + " local=" + local);
    }
}
