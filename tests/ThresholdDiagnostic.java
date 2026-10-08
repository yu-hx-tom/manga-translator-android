package cn.local.manga;

import java.util.Arrays;

/**
 * Closed neutral light-background segmentation. Boundary-touching areas are never treated as
 * balloons.
 */
final class ThresholdDiagnostic {
    static final class Mask {
        final boolean[] erase, interior;
        final boolean whiteBackground, texturedBackground;
        final int pixels;
        final int[] fillColors;

        Mask(
                boolean[] erase,
                boolean[] interior,
                boolean valid,
                int pixels,
                int[] fillColors,
                boolean texturedBackground) {
            this.erase = erase;
            this.interior = interior;
            this.whiteBackground = valid;
            this.pixels = pixels;
            this.fillColors = fillColors;
            this.texturedBackground = texturedBackground;
        }
    }

    static Mask find(int[] pixels, int width, int height, int[][] lines, int characterSize) {
        if (pixels.length != Math.multiplyExact(width, height) || pixels.length > 4_000_000)
            throw new IllegalArgumentException("Invalid cleanup crop");
        int lightGray = 0, total = 0;
        for (int[] r : lines)
            for (int y = Math.max(0, r[1]); y < Math.min(height, r[3]); y++)
                for (int x = Math.max(0, r[0]); x < Math.min(width, r[2]); x++) {
                    int color = pixels[y * width + x], v = brightness(color);
                    total++;
                    if (v >= 160 && v < 225 && neutral(color)) lightGray++;
                }
        // Gray-paper captions need their midtone paper connected. Closure is still mandatory; no
        // border is invented.
        return findAtThreshold(
                pixels, width, height, lines, characterSize, lightGray > total * .12 ? 160 : 225);
    }

    /**
     * RT ink anchors include antialias gray: first prove closed white paper with unchanged strict
     * guards.
     */
    static Mask findPreferWhitePaper(
            int[] pixels, int width, int height, int[][] lines, int characterSize) {
        Mask white = findAtThreshold(pixels, width, height, lines, characterSize, 225);
        if (white.whiteBackground) return white;
        Mask paper = find(pixels, width, height, lines, characterSize);
        return paper.whiteBackground || paper.texturedBackground
                ? paper
                : findLocalWhiteText(pixels, width, height, lines, characterSize, paper);
    }

    /**
     * A caption on plain white paper needs glyph-local cleanup, even without an enclosing balloon
     * outline.
     */
    private static Mask findLocalWhiteText(
            int[] pixels, int width, int height, int[][] lines, int characterSize, Mask refused) {
        int n = pixels.length;
        boolean[] scope = new boolean[n];
        int count = 0, white = 0, dark = 0, min = 255, max = 0;
        long shade = 0;
        for (int[] r : lines)
            for (int y = Math.max(1, r[1] - 2); y < Math.min(height - 1, r[3] + 2); y++)
                for (int x = Math.max(1, r[0] - 2); x < Math.min(width - 1, r[2] + 2); x++) {
                    int p = y * width + x;
                    if (scope[p]) continue;
                    scope[p] = true;
                    count++;
                    int value = brightness(pixels[p]);
                    if (!neutral(pixels[p])) return refused;
                    if (value >= 245) {
                        white++;
                        shade += value;
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    } else dark++;
                }
        if (count < 16 || dark < 4 || white < count * .55 || max - min > 10) return refused;
        boolean[] originalScope = scope.clone();
        byte[] seen = new byte[n];
        int[] queue = new int[n];
        boolean[] erase = new boolean[n];
        int removed = 0, protectedDark = 0;
        for (int seed = 0; seed < n; seed++)
            if (originalScope[seed] && seen[seed] == 0 && brightness(pixels[seed]) < 245) {
                int head = 0, end = 1, left = width, top = height, right = 0, bottom = 0;
                boolean contained = true;
                queue[0] = seed;
                seen[seed] = 1;
                while (head < end) {
                    int p = queue[head++], x = p % width, y = p / width;
                    contained &= originalScope[p] && neutral(pixels[p]);
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(height - 1, y + 1); yy++)
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                            int q = yy * width + xx;
                            if (seen[q] == 0 && brightness(pixels[q]) < 245) {
                                seen[q] = 1;
                                queue[end++] = q;
                            }
                        }
                }
                // A connected frame/illustration extends outside the localized text scope and is
                // never cleared.
                if (contained
                        && end <= Math.max(16, characterSize * characterSize * 2)
                        && right - left <= Math.max(5, characterSize * 3)
                        && bottom - top <= Math.max(5, characterSize * 3)) {
                    for (int i = 0; i < end; i++) erase[queue[i]] = true;
                    removed += end;
                } else {
                    // The same ROI may include hair or an outline connected to outside artwork.
                    // Keep that entire component and reserve a paper guard around it for layout.
                    for (int i = 0; i < end; i++) {
                        int p = queue[i], x = p % width, y = p / width;
                        if (originalScope[p]) protectedDark++;
                        for (int yy = Math.max(0, y - 2); yy <= Math.min(height - 1, y + 2); yy++)
                            for (int xx = Math.max(0, x - 2);
                                    xx <= Math.min(width - 1, x + 2);
                                    xx++) scope[yy * width + xx] = false;
                    }
                }
            }
        if (removed < 4 || removed < dark * .50 || removed < (dark - protectedDark) * .98)
            return refused;
        int fill = (int) Math.round((double) shade / white);
        int[] colors = null;
        if (fill < 255) {
            colors = new int[n];
            java.util.Arrays.fill(colors, 0xff000000 | (fill << 16) | (fill << 8) | fill);
        }
        return new Mask(erase, scope, true, removed, colors, false);
    }

    static boolean[] excludeForeign(
            boolean[] interior, int width, int height, int[][] foreignLines) {
        boolean[] safe = interior.clone();
        for (int[] r : foreignLines)
            for (int y = Math.max(0, r[1] - 2); y < Math.min(height, r[3] + 2); y++) {
                int left = Math.max(0, Math.min(width, r[0] - 2)),
                        right = Math.max(0, Math.min(width, r[2] + 2));
                if (right > left) Arrays.fill(safe, y * width + left, y * width + right, false);
            }
        return safe;
    }

    private static Mask findAtThreshold(
            int[] pixels, int width, int height, int[][] lines, int characterSize, int threshold) {
        int n = Math.multiplyExact(width, height);
        if (pixels.length != n || n > 4_000_000)
            throw new IllegalArgumentException("Invalid cleanup crop");
        byte[] gray = new byte[n];
        boolean[] core = new boolean[n], inside = new boolean[n], erase = new boolean[n];
        for (int i = 0; i < n; i++)
            gray[i] = (byte) (neutral(pixels[i]) ? brightness(pixels[i]) : 0);
        int coreCount = 0;
        boolean[] textScope = threshold < 225 ? new boolean[n] : null;
        if (textScope != null)
            for (int[] r : lines) {
                int margin = Math.max(2, (int) (characterSize * .65));
                for (int y = Math.max(0, r[1] - margin); y < Math.min(height, r[3] + margin); y++) {
                    int left = Math.max(0, Math.min(width, r[0] - margin)),
                            right = Math.max(0, Math.min(width, r[2] + margin));
                    if (right > left)
                        Arrays.fill(textScope, y * width + left, y * width + right, true);
                }
            }
        // Detector boxes are unclipped and padded, not exact ink bounds. Use an inset seed for
        // confidence only.
        // Segmentation, erase and layout retain their actual closed-pixel boundary.
        for (int[] r : lines) {
            int inset = Math.max(1, (int) (Math.min(r[2] - r[0], r[3] - r[1]) * .15));
            for (int y = Math.max(0, r[1] + inset); y < Math.min(height, r[3] - inset); y++)
                for (int x = Math.max(0, r[0] + inset); x < Math.min(width, r[2] - inset); x++) {
                    int p = y * width + x;
                    if (!core[p]) {
                        core[p] = true;
                        coreCount++;
                    }
                }
        }
        int[] labels = new int[n], queue = new int[n];
        int label = 0, selectedWhite = 0;
        boolean[] chosen = new boolean[n + 1], exterior = new boolean[n + 1];
        // A single linear flood pass also rejects exterior whitespace; no repeated RGB conversions.
        for (int seed = 0; seed < n; seed++)
            if (labels[seed] == 0 && (gray[seed] & 255) >= threshold) {
                int head = 0, end = 1, hits = 0;
                boolean edge = false;
                queue[0] = seed;
                labels[seed] = ++label;
                while (head < end) {
                    int p = queue[head++], x = p % width, y = p / width;
                    if (core[p]) hits++;
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) edge = true;
                    if (x > 0) end = push(p - 1, label, end, labels, gray, queue, threshold);
                    if (x + 1 < width)
                        end = push(p + 1, label, end, labels, gray, queue, threshold);
                    if (y > 0) end = push(p - width, label, end, labels, gray, queue, threshold);
                    if (y + 1 < height)
                        end = push(p + width, label, end, labels, gray, queue, threshold);
                }
                exterior[label] = edge;
                if (!edge
                        && hits >= Math.max(4, characterSize)
                        && end >= Math.max(30, characterSize * characterSize)) {
                    chosen[label] = true;
                    selectedWhite += end;
                    for (int i = 0; i < end; i++) inside[queue[i]] = true;
                }
            }
        // Non-white islands surrounded by selected interior are text, including joined kana and
        // ruby.
        // Outline/illustration components have exterior contact and are preserved irrespective of
        // size.
        for (int seed = 0; seed < n; seed++)
            if (labels[seed] == 0) {
                int head = 0,
                        end = 1,
                        contact = 0,
                        outside = 0,
                        nearText = 0,
                        minX = width,
                        maxX = 0,
                        minY = height,
                        maxY = 0;
                boolean edge = false;
                queue[0] = seed;
                labels[seed] = -1;
                while (head < end) {
                    int p = queue[head++], x = p % width, y = p / width;
                    if (textScope != null && textScope[p]) nearText++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) edge = true;
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(height - 1, y + 1); yy++)
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                            int q = yy * width + xx;
                            if (labels[q] > 0) {
                                if (chosen[labels[q]]) contact++;
                                else if (exterior[labels[q]]) outside++;
                            } else if (labels[q] == 0) {
                                labels[q] = -1;
                                queue[end++] = q;
                            }
                        }
                }
                boolean monochrome = true;
                for (int i = 0; i < end; i++)
                    if (!neutral(pixels[queue[i]])) {
                        monochrome = false;
                        break;
                    }
                boolean localized =
                        textScope == null
                                || (nearText >= end * .98
                                        && Math.max(maxX - minX + 1, maxY - minY + 1)
                                                <= characterSize * 5
                                        && Math.min(maxX - minX + 1, maxY - minY + 1)
                                                <= characterSize * 2);
                if (!edge
                        && localized
                        && monochrome
                        && contact > 0
                        && outside == 0
                        && end < selectedWhite * .4) {
                    for (int i = 0; i < end; i++) {
                        int p = queue[i];
                        erase[p] = true;
                        inside[p] = true;
                    }
                }
            }
        // Fill the counters inside erased glyphs as well; they are bounded white islands, not
        // exterior.
        int head = 0, end = 0;
        for (int p = 0; p < n; p++) if (inside[p]) queue[end++] = p;
        while (head < end) {
            int p = queue[head++], x = p % width, y = p / width;
            for (int d = 0; d < 4; d++) {
                int q =
                        d == 0
                                ? (x > 0 ? p - 1 : -1)
                                : d == 1
                                        ? (x + 1 < width ? p + 1 : -1)
                                        : d == 2
                                                ? (y > 0 ? p - width : -1)
                                                : (y + 1 < height ? p + width : -1);
                if (q >= 0 && !inside[q] && labels[q] > 0 && !exterior[labels[q]]) {
                    inside[q] = true;
                    queue[end++] = q;
                }
            }
        }
        boolean[] expanded = erase.clone();
        int fringe = threshold < 225 ? Math.max(2, Math.min(4, characterSize / 8)) : 2;
        for (int p = 0; p < n; p++)
            if (erase[p]) {
                int x = p % width, y = p / width;
                for (int yy = Math.max(0, y - fringe); yy <= Math.min(height - 1, y + fringe); yy++)
                    for (int xx = Math.max(0, x - fringe);
                            xx <= Math.min(width - 1, x + fringe);
                            xx++) {
                        int q = yy * width + xx;
                        if (inside[q] && (threshold < 225 || (gray[q] & 255) < 250))
                            expanded[q] = true;
                    }
            }
        erase = expanded;
        int covered = 0, changed = 0;
        for (int p = 0; p < n; p++) {
            if (core[p] && inside[p]) covered++;
            if (erase[p]) changed++;
        }
        System.out.println(
                "mask threshold="
                        + threshold
                        + " core="
                        + coreCount
                        + " inside="
                        + covered
                        + " erased="
                        + changed
                        + " paper="
                        + selectedWhite);
        boolean valid = coreCount > 0 && covered >= coreCount * .96 && changed > 0;
        if (!valid) {
            Arrays.fill(erase, false);
            Arrays.fill(inside, false);
            changed = 0;
        }
        if (valid && threshold < 225) {
            int pairs = 0, edges = 0;
            // Abrupt paper changes imply halftone/artwork under a transparent caption.
            // Nearest-paper filling would make blotches.
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int p = y * width + x;
                    if (!inside[p] || erase[p]) continue;
                    for (int d = 0; d < 2; d++) {
                        int q =
                                d == 0
                                        ? (x + 1 < width ? p + 1 : -1)
                                        : (y + 1 < height ? p + width : -1);
                        if (q >= 0 && inside[q] && !erase[q]) {
                            pairs++;
                            if (Math.abs(brightness(pixels[p]) - brightness(pixels[q])) > 18)
                                edges++;
                        }
                    }
                }
            if (edges >= 10 && edges > pairs * .01) {
                Arrays.fill(erase, false);
                return new Mask(erase, inside, false, 0, null, true);
            }
        }
        int[] fillColors = null;
        if (valid && threshold < 225) {
            // Carry the nearest original paper shade across removed strokes, preserving
            // gray/gradient caption backgrounds.
            fillColors = new int[n];
            head = 0;
            end = 0;
            for (int p = 0; p < n; p++)
                if (inside[p] && !erase[p]) {
                    fillColors[p] = pixels[p];
                    queue[end++] = p;
                }
            while (head < end) {
                int p = queue[head++], x = p % width, y = p / width;
                for (int d = 0; d < 4; d++) {
                    int q =
                            d == 0
                                    ? (x > 0 ? p - 1 : -1)
                                    : d == 1
                                            ? (x + 1 < width ? p + 1 : -1)
                                            : d == 2
                                                    ? (y > 0 ? p - width : -1)
                                                    : (y + 1 < height ? p + width : -1);
                    if (q >= 0 && inside[q] && fillColors[q] == 0) {
                        fillColors[q] = fillColors[p];
                        queue[end++] = q;
                    }
                }
            }
        }
        return new Mask(erase, inside, valid, changed, fillColors, false);
    }

    private static int push(
            int p, int label, int end, int[] labels, byte[] gray, int[] queue, int threshold) {
        if (labels[p] == 0 && (gray[p] & 255) >= threshold) {
            labels[p] = label;
            queue[end++] = p;
        }
        return end;
    }

    /**
     * Refuse a cleanup that would remove another detected region's original ink, even if it was
     * already rendered.
     */
    static boolean touchesForeignInk(
            int[] original, int width, int height, Mask mask, int[][] foreignLines) {
        for (int[] line : foreignLines)
            for (int y = Math.max(0, line[1]); y < Math.min(height, line[3]); y++)
                for (int x = Math.max(0, line[0]); x < Math.min(width, line[2]); x++) {
                    int p = y * width + x;
                    if (mask.erase[p] && brightness(original[p]) < 225) return true;
                }
        return false;
    }

    /**
     * Complex-background overlay is restricted to detected ink boxes, never inferred from exterior
     * white.
     */
    static boolean[] complexTextArea(int[] pixels, int w, int h, int[][] lines) {
        boolean[] area = new boolean[pixels.length];
        int count = 0, white = 0, colored = 0;
        for (int[] r : lines)
            for (int y = Math.max(0, r[1]); y < Math.min(h, r[3]); y++)
                for (int x = Math.max(0, r[0]); x < Math.min(w, r[2]); x++) {
                    int p = y * w + x;
                    if (area[p]) continue;
                    area[p] = true;
                    count++;
                    int c = pixels[p],
                            red = (c >>> 16) & 255,
                            green = (c >>> 8) & 255,
                            blue = c & 255;
                    if (brightness(c) > 225) white++;
                    if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue))
                            > 25) colored++;
                }
        return count > 0 && (white < count * .35 || colored > count * .20) ? area : null;
    }

    static void apply(int[] pixels, Mask mask) {
        for (int i = 0; i < pixels.length; i++)
            if (mask.erase[i])
                pixels[i] = mask.fillColors == null ? 0xffffffff : mask.fillColors[i];
    }

    private static boolean neutral(int c) {
        int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 20;
    }

    private static int brightness(int c) {
        return (((c >>> 16) & 255) * 299 + ((c >>> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }
}
