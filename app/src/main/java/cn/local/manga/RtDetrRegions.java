package cn.local.manga;

import android.graphics.Rect;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Converts paragraph detections to conservative ink anchors. No OCR or second model is called. */
public final class RtDetrRegions {
    private static final class Candidate {
        final float score;
        final Rect box;

        Candidate(float score, Rect box) {
            this.score = score;
            this.box = box;
        }
    }

    private RtDetrRegions() {}

    /**
     * All input and output coordinates belong to this RGB working image; caller owns any rescaling.
     */
    public static List<Region> fromPredictions(
            int width, int height, int[] rgb, long[] labels, float[][] boxes, float[] scores) {
        if (width <= 0 || height <= 0 || (long) width * height != rgb.length)
            throw new IllegalArgumentException("Invalid detector image");
        List<Candidate> candidates = new ArrayList<>();
        List<Rect> bubbles = new ArrayList<>();
        int count = Math.min(labels.length, Math.min(boxes.length, scores.length));
        for (int i = 0; i < count; i++) {
            if ((i & 127) == 0) check();
            // Bubble class 0 is never a translation region or an erasure mask.
            if ((labels[i] < 0 || labels[i] > 2)
                    || !Float.isFinite(scores[i])
                    || scores[i] < .3f
                    || scores[i] > 1
                    || boxes[i] == null
                    || boxes[i].length < 4) continue;
            float[] b = boxes[i];
            boolean finite = true;
            for (int k = 0; k < 4; k++) finite &= Float.isFinite(b[k]);
            if (!finite || b[2] <= b[0] || b[3] <= b[1]) continue;
            int left = (int) Math.floor(Math.max(0, Math.min(width, b[0]))),
                    top = (int) Math.floor(Math.max(0, Math.min(height, b[1])));
            int right = (int) Math.ceil(Math.max(0, Math.min(width, b[2]))),
                    bottom = (int) Math.ceil(Math.max(0, Math.min(height, b[3])));
            if (right - left < 3 || bottom - top < 3) continue;
            Rect rect = new Rect(left, top, right, bottom);
            if (labels[i] == 0) bubbles.add(rect);
            else candidates.add(new Candidate(scores[i], rect));
        }
        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : candidates) {
            check();
            boolean duplicate = false;
            // Classes 1/2 describe text context; the same text can be predicted under both.
            for (Candidate previous : kept)
                if (duplicate(candidate.box, previous.box)) {
                    duplicate = true;
                    break;
                }
            if (!duplicate) kept.add(candidate);
        }
        kept.sort(
                Comparator.comparingInt((Candidate c) -> c.box.top)
                        .thenComparing((a, b) -> Integer.compare(b.box.right, a.box.right)));
        Paper paper = new Paper(rgb, width, height);
        List<Region> groups = new ArrayList<>();
        List<Integer> memberships = new ArrayList<>();
        for (Candidate candidate : kept) {
            check();
            Rect box = candidate.box;
            boolean vertical = (box.right - box.left) < (box.bottom - box.top) * 1.4;
            List<Rect> lines = inkLines(rgb, width, box, vertical);
            int member = paper.member(box);
            Rect hint = member == 0 ? context(box, bubbles) : paper.bounds.get(member);
            int same = -1;
            if (member != 0)
                for (int j = 0; j < groups.size(); j++)
                    if (memberships.get(j) == member && groups.get(j).vertical == vertical) {
                        same = j;
                        break;
                    }
            if (same >= 0) {
                Region previous = groups.get(same);
                List<Rect> merged = new ArrayList<>(previous.lines);
                merged.addAll(lines);
                groups.set(same, new Region("", union(previous.box, box), merged, vertical, hint));
            } else {
                groups.add(new Region("", box, lines, vertical, hint));
                memberships.add(member);
            }
        }
        List<Region> result = new ArrayList<>();
        int id = 1;
        for (Region group : groups)
            result.add(
                    new Region(
                            "rt_" + (id++),
                            group.box,
                            group.lines,
                            group.vertical,
                            group.contextBox));
        return result;
    }

    private static Rect union(Rect a, Rect b) {
        return new Rect(
                Math.min(a.left, b.left),
                Math.min(a.top, b.top),
                Math.max(a.right, b.right),
                Math.max(a.bottom, b.bottom));
    }

    /**
     * Pixel-connected closed paper, not model bubble rectangles, establishes a shared translation
     * unit.
     */
    private static final class Paper {
        final int width;
        final int[] labels;
        final Map<Integer, Rect> bounds = new HashMap<>();

        Paper(int[] rgb, int width, int height) {
            this.width = width;
            int n = rgb.length;
            labels = new int[n];
            int[] queue = new int[n];
            int label = 0;
            for (int i = 0; i < n; i++) {
                int c = rgb[i], r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                labels[i] =
                        (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 20
                                        && (r * 299 + g * 587 + b * 114) / 1000 >= 225)
                                ? 0
                                : -1;
            }
            for (int seed = 0; seed < n; seed++) {
                if ((seed & 4095) == 0) check();
                if (labels[seed] != 0) continue;
                int head = 0, end = 1, left = width, top = height, right = 0, bottom = 0;
                boolean edge = false;
                queue[0] = seed;
                labels[seed] = ++label;
                while (head < end) {
                    if ((head & 4095) == 0) check();
                    int p = queue[head++], x = p % width, y = p / width;
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y + 1);
                    edge |= x == 0 || y == 0 || x + 1 == width || y + 1 == height;
                    if (x > 0 && labels[p - 1] == 0) {
                        labels[p - 1] = label;
                        queue[end++] = p - 1;
                    }
                    if (x + 1 < width && labels[p + 1] == 0) {
                        labels[p + 1] = label;
                        queue[end++] = p + 1;
                    }
                    if (y > 0 && labels[p - width] == 0) {
                        labels[p - width] = label;
                        queue[end++] = p - width;
                    }
                    if (y + 1 < height && labels[p + width] == 0) {
                        labels[p + width] = label;
                        queue[end++] = p + width;
                    }
                }
                if (!edge && end >= 30 && end < n * .65)
                    bounds.put(label, new Rect(left, top, right, bottom));
            }
        }

        int member(Rect box) {
            Map<Integer, Integer> hits = new HashMap<>();
            int white = 0, best = 0, count = 0;
            for (int y = box.top; y < box.bottom; y++)
                for (int x = box.left; x < box.right; x++) {
                    int label = labels[y * width + x];
                    if (label <= 0) continue;
                    white++;
                    if (bounds.containsKey(label)) {
                        int value = hits.getOrDefault(label, 0) + 1;
                        hits.put(label, value);
                        if (value > count) {
                            count = value;
                            best = label;
                        }
                    }
                }
            return count >= Math.max(8, (box.right - box.left) * (box.bottom - box.top) * .25)
                            && count >= white * .75
                    ? best
                    : 0;
        }
    }

    private static boolean duplicate(Rect a, Rect b) {
        long intersection =
                (long) Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left))
                        * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        long aa = (long) (a.right - a.left) * (a.bottom - a.top),
                bb = (long) (b.right - b.left) * (b.bottom - b.top);
        return intersection > 0
                && (intersection >= .65 * (aa + bb - intersection)
                        || (intersection >= .92 * Math.min(aa, bb)
                                && Math.min(aa, bb) >= .5 * Math.max(aa, bb)));
    }

    private static Rect context(Rect text, List<Rect> bubbles) {
        long area = (long) (text.right - text.left) * (text.bottom - text.top),
                best = Long.MAX_VALUE;
        Rect chosen = null;
        for (Rect bubble : bubbles) {
            long size = (long) (bubble.right - bubble.left) * (bubble.bottom - bubble.top);
            long overlap =
                    (long)
                                    Math.max(
                                            0,
                                            Math.min(text.right, bubble.right)
                                                    - Math.max(text.left, bubble.left))
                            * Math.max(
                                    0,
                                    Math.min(text.bottom, bubble.bottom)
                                            - Math.max(text.top, bubble.top));
            if (overlap >= area * .9
                    && size >= area
                    && size <= Math.max(4000, area * 16)
                    && size < best) {
                chosen = bubble;
                best = size;
            }
        }
        return chosen;
    }

    static Rect renderBounds(Region source, int width, int height) {
        int left = width, top = height, right = 0, bottom = 0;
        List<Integer> sizes = new ArrayList<>();
        for (Rect line : source.lines)
            if (line.right > line.left && line.bottom > line.top) {
                left = Math.min(left, line.left);
                top = Math.min(top, line.top);
                right = Math.max(right, line.right);
                bottom = Math.max(bottom, line.bottom);
                sizes.add(Math.min(line.right - line.left, line.bottom - line.top));
            }
        if (sizes.isEmpty()) {
            // Frame-connected handwriting may have no separate line anchors.
            // Include the detected balloon and exterior paper for boundary proof;
            // this is analysis context, never an instruction to erase the crop.
            int narrow = Math.max(1, Math.min(source.box.width(), source.box.height()));
            int padding = Math.max(16, Math.min(128, narrow / 2));
            left = source.box.left - padding;
            top = source.box.top - padding;
            right = source.box.right + padding;
            bottom = source.box.bottom + padding;
            if (source.contextBox != null) {
                int margin = Math.max(8, Math.min(24, narrow / 6));
                left = Math.min(left, source.contextBox.left - margin);
                top = Math.min(top, source.contextBox.top - margin);
                right = Math.max(right, source.contextBox.right + margin);
                bottom = Math.max(bottom, source.contextBox.bottom + margin);
            }
            Rect expanded =
                    new Rect(
                            Math.max(0, left),
                            Math.max(0, top),
                            Math.min(width, right),
                            Math.min(height, bottom));
            return (long) expanded.width() * expanded.height() <= 4_000_000
                    ? expanded
                    : new Rect(source.box);
        }
        sizes.sort(Integer::compareTo);
        int padding = Math.max(3, sizes.get(sizes.size() / 2) * 3);
        left -= padding;
        top -= padding;
        right += padding;
        bottom += padding;
        if (source.contextBox != null) {
            int margin = Math.max(3, sizes.get(sizes.size() / 2) / 2);
            left = Math.min(left, source.contextBox.left - margin);
            top = Math.min(top, source.contextBox.top - margin);
            right = Math.max(right, source.contextBox.right + margin);
            bottom = Math.max(bottom, source.contextBox.bottom + margin);
        }
        return new Rect(
                Math.max(0, left),
                Math.max(0, top),
                Math.min(width, right),
                Math.min(height, bottom));
    }

    static int[][] foreignLines(List<Region> regions, String currentId, int offsetX, int offsetY) {
        List<int[]> result = new ArrayList<>();
        for (Region other : regions)
            if (!other.id.equals(currentId)) {
                // Unresolved paragraph boxes are protection-only exclusions, never erasure or font
                // anchors.
                List<Rect> protectedBoxes =
                        other.lines.isEmpty() ? Collections.singletonList(other.box) : other.lines;
                for (Rect line : protectedBoxes)
                    result.add(
                            new int[] {
                                line.left - offsetX,
                                line.top - offsetY,
                                line.right - offsetX,
                                line.bottom - offsetY
                            });
            }
        return result.toArray(new int[0][]);
    }

    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("已取消");
    }

    private static List<Rect> inkLines(int[] rgb, int stride, Rect box, boolean vertical) {
        List<Rect> result = inkLinesExact(rgb, stride, box, vertical, false);
        if (!result.isEmpty()) return result;
        // A clipped bubble edge or a flat gray panel can bridge otherwise separate text columns.
        // Exclude only proven boundary content from anchor analysis; its pixels remain protected.
        result = inkLinesExact(rgb, stride, box, vertical, false, true);
        if (!result.isEmpty()) return result;
        // Retry only unresolved edge-clipped glyphs. Existing reliable anchors must never expand
        // into nearby art.
        Rect guard =
                new Rect(
                        Math.max(0, box.left - 3),
                        Math.max(0, box.top - 3),
                        Math.min(stride, box.right + 3),
                        Math.min(rgb.length / stride, box.bottom + 3));
        result = inkLinesExact(rgb, stride, guard, vertical, false);
        return result.isEmpty() ? inkLinesExact(rgb, stride, box, vertical, true) : result;
    }

    private static List<Rect> inkLinesExact(
            int[] rgb, int stride, Rect box, boolean vertical, boolean fragmentedGlyphs) {
        return inkLinesExact(rgb, stride, box, vertical, fragmentedGlyphs, false);
    }

    private static List<Rect> inkLinesExact(
            int[] rgb,
            int stride,
            Rect box,
            boolean vertical,
            boolean fragmentedGlyphs,
            boolean protectBoundary) {
        int w = box.right - box.left, h = box.bottom - box.top, n = w * h;
        List<Rect> result = new ArrayList<>();
        if (n > 1_000_000) return result;
        byte[] ink = new byte[n];
        int dark = 0;
        for (int y = 0; y < h; y++) {
            if ((y & 31) == 0) check();
            for (int x = 0; x < w; x++) {
                int c = rgb[(y + box.top) * stride + x + box.left];
                int gray =
                        (((c >>> 16) & 255) * 299 + ((c >>> 8) & 255) * 587 + (c & 255) * 114)
                                / 1000;
                if (gray < 160) {
                    ink[y * w + x] = 1;
                    dark++;
                }
            }
        }
        if (dark < 4 || dark > n * .48) return result;
        boolean[] outerInk = protectBoundary ? outerConnectedInk(rgb, stride, box) : null;
        int[] queue = new int[n];
        List<Integer> sizes = new ArrayList<>(), tinySizes = new ArrayList<>();
        List<int[]> weightedSizes = new ArrayList<>(), components = new ArrayList<>();
        int accepted = 0;
        // Remove connected background/border components; keep small detached glyph strokes and
        // punctuation.
        for (int seed = 0; seed < n; seed++)
            if (ink[seed] == 1) {
                int head = 0, end = 1, left = w, top = h, right = 0, bottom = 0;
                queue[0] = seed;
                ink[seed] = 2;
                while (head < end) {
                    if ((head & 4095) == 0) check();
                    int p = queue[head++], x = p % w, y = p / w;
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y + 1);
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++)
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++)
                            if (ink[yy * w + xx] == 1) {
                                ink[yy * w + xx] = 2;
                                queue[end++] = yy * w + xx;
                            }
                }
                int cw = right - left, ch = bottom - top;
                boolean spans =
                        (left == 0 && right == w && w > 15) || (top == 0 && bottom == h && h > 15);
                boolean art = end > n * .30 && cw > w * .6 && ch > h * .6;
                boolean border =
                        (cw <= 2 && ch > h * .75 && (left <= 1 || right >= w - 1))
                                || (ch <= 2 && cw > w * .75 && (top <= 1 || bottom >= h - 1));
                // Curved balloon sides can be thicker than two pixels and miss the crop edge by a
                // pixel.
                border |=
                        ch >= Math.max(24, cw * 6)
                                        && ch > h * .70
                                        && (left < w * .15 || right > w * .85)
                                || cw >= Math.max(24, ch * 6)
                                        && cw > w * .70
                                        && (top < h * .15 || bottom > h * .85);
                boolean exterior = outerInk != null && outerInk[seed];
                if (protectBoundary
                        && !exterior
                        && (left == 0 || top == 0 || right == w || bottom == h)
                        && cw >= 8
                        && ch >= 8
                        && end >= Math.max(48, n * .04)) {
                    int[] shades = new int[20];
                    int black = 0, flat = 0;
                    for (int i = 0; i < end; i++) {
                        int p = queue[i], c = rgb[(box.top + p / w) * stride + box.left + p % w];
                        int red = (c >>> 16) & 255,
                                green = (c >>> 8) & 255,
                                blue = c & 255,
                                v = (red * 299 + green * 587 + blue * 114) / 1000;
                        if (v < 40) black++;
                        if (v >= 40
                                && v < 152
                                && Math.max(red, Math.max(green, blue))
                                                - Math.min(red, Math.min(green, blue))
                                        <= 20) flat = Math.max(flat, ++shades[v / 8]);
                    }
                    // A large almost-uniform gray block is not antialiased black letter ink.
                    exterior = black < end * .15 && flat >= end * .65;
                }
                boolean keep = end >= 1 && !spans && !art && !border && !exterior;
                for (int i = 0; i < end; i++) ink[queue[i]] = (byte) (keep ? 3 : 4);
                if (keep) {
                    accepted += end;
                    tinySizes.add(Math.max(cw, ch));
                    if (Math.max(cw, ch) >= 3) {
                        sizes.add(Math.max(cw, ch));
                        weightedSizes.add(new int[] {Math.max(cw, ch), end});
                    }
                    if (protectBoundary) {
                        int[] component = new int[end + 4];
                        component[0] = left;
                        component[1] = top;
                        component[2] = right;
                        component[3] = bottom;
                        System.arraycopy(queue, 0, component, 4, end);
                        components.add(component);
                    }
                }
            }
        if (accepted < 4) return result;
        if (sizes.isEmpty()) sizes.addAll(tinySizes);
        if (sizes.isEmpty()) return result;
        sizes.sort(Integer::compareTo);
        int character =
                Math.max(
                        3,
                        sizes.get(
                                fragmentedGlyphs
                                        ? Math.min(
                                                sizes.size() - 1,
                                                (int) Math.ceil(sizes.size() * .75))
                                        : sizes.size() / 2));
        if (!fragmentedGlyphs && !weightedSizes.isEmpty()) {
            // Many punctuation dots or detached glyph strokes must not outvote the body text's ink.
            weightedSizes.sort(Comparator.comparingInt(value -> value[0]));
            int total = 0, seen = 0;
            for (int[] value : weightedSizes) total += value[1];
            for (int[] value : weightedSizes) {
                seen += value[1];
                if (seen * 2 >= total) {
                    character = Math.max(3, value[0]);
                    break;
                }
            }
        }
        int primary = vertical ? w : h, secondary = vertical ? h : w;
        int[] projection = new int[primary];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) if (ink[y * w + x] == 3) projection[vertical ? x : y]++;
        int minimumProjection = Math.max(2, secondary / 40);
        int[] trailing = null;
        if (protectBoundary && hasWideRun(projection, minimumProjection, character * 2.5)) {
            for (int i = components.size() - 1; i >= 0; i--) {
                int[] c = components.get(i);
                int cw = c[2] - c[0],
                        ch = c[3] - c[1],
                        alongStart = vertical ? c[1] : c[0],
                        alongEnd = vertical ? c[3] : c[2];
                if (cw > character * 1.5
                        || ch > character * 1.5
                        || alongStart < secondary - character * 1.5
                        || alongEnd < secondary - character * .5
                        || accepted < (c.length - 4) * 4) continue;
                int[] reduced = projection.clone();
                for (int k = 4; k < c.length; k++) {
                    int p = c[k];
                    reduced[vertical ? p % w : p / w]--;
                    ink[p] = 5;
                }
                if (twoBodyRuns(ink, w, h, vertical, reduced, minimumProjection, character)) {
                    projection = reduced;
                    trailing = c;
                    break;
                }
                for (int k = 4; k < c.length; k++) ink[c[k]] = 3;
            }
        }
        for (int p = 0; p < primary; p++) if (projection[p] < minimumProjection) projection[p] = 0;
        // A one-pixel paper gap can already separate Japanese columns or ruby at screenshot
        // resolution.
        int gap = 0;
        List<int[]> runs = new ArrayList<>();
        int widest = 0;
        for (int at = 0; at < primary; ) {
            while (at < primary && projection[at] == 0) at++;
            if (at == primary) break;
            int start = at, end = at + 1;
            for (at++; at < primary; at++) {
                if (projection[at] > 0) end = at + 1;
                else if (at - end >= gap) break;
            }
            int lo = secondary, hi = 0, count = 0;
            for (int p = start; p < end; p++)
                for (int s = 0; s < secondary; s++) {
                    int x = vertical ? p : s, y = vertical ? s : p;
                    if (ink[y * w + x] == 3) {
                        lo = Math.min(lo, s);
                        hi = Math.max(hi, s + 1);
                        count++;
                    }
                }
            if (count < 4 || hi <= lo || end - start > character * 2.5) continue;
            int l = vertical ? start : lo,
                    t = vertical ? lo : start,
                    r = vertical ? end : hi,
                    b = vertical ? hi : end;
            runs.add(new int[] {l, t, r, b});
            widest = Math.max(widest, end - start);
        }
        if (trailing != null) runs.add(Arrays.copyOf(trailing, 4));
        for (int[] run : runs) {
            int l = run[0], t = run[1], r = run[2], b = run[3];
            // A thin full-height side run beside wider body columns is a balloon edge, not
            // ruby/body ink.
            if (vertical && b - t >= h * .85 && r - l < widest * .75 && (l <= 1 || r >= w - 1))
                continue;
            if (!vertical && r - l >= w * .85 && b - t < widest * .75 && (t <= 1 || b >= h - 1))
                continue;
            // Nearby ruby is included in the API paragraph and closed-bubble cleanup, but must not
            // set the body font.
            if (widest >= 5 && (vertical ? r - l : b - t) < widest * .55) continue;
            // Include nearby paper around each ink column/row: tightly cropped antialias pixels are
            // not gray paper.
            int normal = Math.max(2, Math.min(5, (int) Math.ceil(character * .4))),
                    along = Math.max(1, Math.min(2, character / 6));
            int px = vertical ? normal : along, py = vertical ? along : normal;
            result.add(
                    new Rect(
                            Math.max(0, box.left + l - px),
                            Math.max(0, box.top + t - py),
                            Math.min(stride, box.left + r + px),
                            Math.min(rgb.length / stride, box.top + b + py)));
        }
        if (result.size() > 40) return new ArrayList<>();
        result.sort(
                (a, b) ->
                        vertical ? Integer.compare(b.left, a.left) : Integer.compare(a.top, b.top));
        return result;
    }

    private static boolean hasWideRun(int[] projection, int minimum, double limit) {
        int run = 0;
        for (int count : projection) {
            run = count >= minimum ? run + 1 : 0;
            if (run > limit) return true;
        }
        return false;
    }

    /**
     * A detached trailing symbol can bridge columns; give the actual symbol its own tight ink
     * anchor.
     */
    private static boolean twoBodyRuns(
            byte[] ink,
            int w,
            int h,
            boolean vertical,
            int[] projection,
            int minimum,
            int character) {
        int count = 0, secondary = vertical ? h : w;
        for (int at = 0; at < projection.length; ) {
            while (at < projection.length && projection[at] < minimum) at++;
            if (at == projection.length) break;
            int start = at;
            while (at < projection.length && projection[at] >= minimum) at++;
            int end = at;
            if (end - start < Math.max(3, character * .55) || end - start > character * 1.8)
                return false;
            int lo = secondary, hi = 0;
            for (int p = start; p < end; p++)
                for (int s = 0; s < secondary; s++) {
                    int x = vertical ? p : s, y = vertical ? s : p;
                    if (ink[y * w + x] == 3) {
                        lo = Math.min(lo, s);
                        hi = Math.max(hi, s + 1);
                    }
                }
            if (hi - lo < character * 2) return false;
            count++;
        }
        return count >= 2;
    }

    /**
     * Dark components reaching well beyond the detector crop are boundary/art protection, not glyph
     * anchors.
     */
    private static boolean[] outerConnectedInk(int[] rgb, int stride, Rect box) {
        int width = box.right - box.left,
                height = box.bottom - box.top,
                padding = Math.max(12, Math.min(48, Math.min(width, height)));
        int left = Math.max(0, box.left - padding),
                top = Math.max(0, box.top - padding),
                right = Math.min(stride, box.right + padding),
                bottom = Math.min(rgb.length / stride, box.bottom + padding);
        int w = right - left, h = bottom - top;
        byte[] ink = new byte[w * h];
        int[] queue = new int[ink.length];
        int head = 0, end = 0;
        for (int y = 0; y < h; y++) {
            if ((y & 31) == 0) check();
            for (int x = 0; x < w; x++) {
                int c = rgb[(top + y) * stride + left + x],
                        v =
                                (((c >>> 16) & 255) * 299
                                                + ((c >>> 8) & 255) * 587
                                                + (c & 255) * 114)
                                        / 1000;
                if (v >= 160) continue;
                int p = y * w + x;
                ink[p] = 1;
                if (x == 0 || y == 0 || x + 1 == w || y + 1 == h) {
                    ink[p] = 2;
                    queue[end++] = p;
                }
            }
        }
        while (head < end) {
            if ((head & 4095) == 0) check();
            int p = queue[head++], x = p % w, y = p / w;
            for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++)
                for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++)
                    if (ink[yy * w + xx] == 1) {
                        ink[yy * w + xx] = 2;
                        queue[end++] = yy * w + xx;
                    }
        }
        boolean[] protectedInk = new boolean[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                protectedInk[y * width + x] =
                        ink[(box.top - top + y) * w + box.left - left + x] == 2;
        return protectedInk;
    }
}
