package cn.local.manga;

import java.util.ArrayList;
import java.util.List;

/**
 * Font-independent production cell layout. Both Android Canvas and host review consume these exact
 * cells.
 */
final class BubbleLayout {
    static final class Plan {
        final int font, step;
        final int[][] cells;
        final int[] codepoints;
        final float[] fonts;

        Plan(int font, int step, int[][] cells, int[] codepoints) {
            this(font, step, cells, codepoints, null);
        }

        Plan(int font, int step, int[][] cells, int[] codepoints, float[] fonts) {
            this.font = font;
            this.step = step;
            this.cells = cells;
            this.codepoints = codepoints;
            this.fonts = fonts;
        }

        float cellFont(int index) {
            return fonts == null ? font : fonts[index];
        }

        int cellStep(int index) {
            return cells[index][2] - cells[index][0];
        }
    }

    static Plan plan(String text, boolean[] interior, int w, int h, boolean vertical, int estimate)
            throws Exception {
        return plan(text, interior, w, h, vertical, estimate, null);
    }

    static Plan plan(
            String text,
            boolean[] interior,
            int w,
            int h,
            boolean vertical,
            int estimate,
            int[][] seeds)
            throws Exception {
        return plan(text, interior, w, h, vertical, estimate, seeds, 1f);
    }

    /**
     * {@code scale} multiplies only the preferred (largest) lettering size; used by the workbench's
     * font control. The fit search still never leaves the safe interior, so enlarging stops at the
     * bubble's capacity. scale=1 performs exactly the original arithmetic (x*1f==x).
     */
    static Plan plan(
            String text,
            boolean[] interior,
            int w,
            int h,
            boolean vertical,
            int estimate,
            int[][] seeds,
            float scale)
            throws Exception {
        boolean compactMarks = text.codePoints().anyMatch(Character::isLetterOrDigit);
        List<Integer> explicitBreaks = new ArrayList<>();
        int count = 0;
        for (String part : text.split("\\R+")) {
            String stripped = part.replaceAll("\\s+", "");
            int length = stripped.codePointCount(0, stripped.length());
            if (length > 0) {
                count += length;
                explicitBreaks.add(count);
            }
        }
        text = text.replaceAll("\\s+", "");
        if (vertical)
            text =
                    text.replace('「', '﹁')
                            .replace('」', '﹂')
                            .replace('“', '﹁')
                            .replace('”', '﹂')
                            .replace('—', '丨')
                            .replace('…', '︙');
        int[] points = text.codePoints().toArray();
        if (points.length == 0) throw new Exception("译文为空");
        Space space = space(interior, w, h, estimate, seeds, vertical);
        int largest =
                Math.max(8, Math.round(preferredFont(estimate, seeds, points.length) * scale));
        Plan best = fit(points, space, vertical, largest, explicitBreaks, compactMarks, scale);
        if (best != null) return best;
        throw new Exception("译文放不进可靠气泡内部，已保留原文");
    }

    /** Font scale comes from source lettering, never the page/crop width or a fixed pixel cap. */
    static int sourceFont(int[][] lines, int[] fallback) {
        List<Integer> sizes = new ArrayList<>();
        if (lines != null)
            for (int[] line : lines)
                if (line != null && line.length == 4 && line[2] > line[0] && line[3] > line[1])
                    sizes.add(Math.min(line[2] - line[0], line[3] - line[1]));
        if (sizes.isEmpty())
            return Math.max(1, Math.min(fallback[2] - fallback[0], fallback[3] - fallback[1]));
        sizes.sort(Integer::compareTo);
        return sizes.get(sizes.size() / 2);
    }

    static float preferredFont(int sourceFont, int[][] lines, int characters) {
        float sourceCharacters = 0;
        if (lines != null)
            for (int[] line : lines)
                if (line != null && line.length == 4 && line[2] > line[0] && line[3] > line[1])
                    sourceCharacters +=
                            Math.max(line[2] - line[0], line[3] - line[1])
                                    / (float)
                                            Math.max(
                                                    1,
                                                    Math.min(line[2] - line[0], line[3] - line[1]));
        // A shorter translation may use more of its original space without turning a small aside
        // into a display heading. Long translations shrink only when their safe grid requires it.
        float growth = (float) Math.sqrt(Math.max(1, sourceCharacters / Math.max(1, characters)));
        return Math.max(1, sourceFont) * Math.min(1.45f, growth);
    }

    private static Plan fit(
            int[] points,
            Space space,
            boolean vertical,
            int largest,
            List<Integer> explicitBreaks,
            boolean compactMarks,
            float scale) {
        List<int[]> units = units(points);
        List<int[]> cells = new ArrayList<>();
        List<Float> fonts = new ArrayList<>();
        float remainingWeight = 0;
        for (Lobe l : space.lobes) remainingWeight += l.weight;
        int unitStart = 0, maximumFont = 0, maximumStep = 0;
        for (int index = 0; index < space.lobes.size() && unitStart < units.size(); index++) {
            Lobe lobe = space.lobes.get(index);
            int unitEnd = units.size(),
                    remainingLobes =
                            Math.min(space.lobes.size() - index - 1, units.size() - unitStart - 1);
            if (remainingLobes > 0) {
                float total = 0;
                for (int i = unitStart; i < units.size(); i++)
                    total += unitAdvance(points, units.get(i), compactMarks);
                float wanted = total * lobe.weight / remainingWeight,
                        best = Float.MAX_VALUE,
                        used = 0;
                for (int end = unitStart + 1; end <= units.size() - remainingLobes; end++) {
                    used += unitAdvance(points, units.get(end - 1), compactMarks);
                    int last = points[units.get(end - 1)[1] - 1];
                    float bonus =
                            "。！？!?♥♡❤".indexOf(last) >= 0
                                    ? 2f
                                    : "，；：、,;:".indexOf(last) >= 0 ? .25f : 0;
                    float cost = Math.abs(used - wanted) - bonus;
                    if (cost < best) {
                        best = cost;
                        unitEnd = end;
                    }
                }
            }
            if (explicitBreaks.size() == space.lobes.size()) {
                int boundary = explicitBreaks.get(index);
                for (int i = unitStart; i < units.size(); i++)
                    if (units.get(i)[1] == boundary) {
                        unitEnd = i + 1;
                        break;
                    }
            }
            int start = units.get(unitStart)[0], end = units.get(unitEnd - 1)[1];
            int[] part = java.util.Arrays.copyOfRange(points, start, end);
            int preferred = largest;
            if (space.sourceLobes) {
                int[][] lines = lobe.lines.toArray(new int[0][]);
                preferred =
                        Math.max(
                                8,
                                Math.round(
                                        preferredFont(
                                                        sourceFont(lines, lobe.source),
                                                        lines,
                                                        part.length)
                                                * scale));
            }
            Plan plan = fitLobe(part, space, lobe, vertical, preferred, compactMarks);
            if (plan == null) return null;
            for (int i = 0; i < plan.cells.length; i++) {
                cells.add(plan.cells[i]);
                fonts.add(plan.cellFont(i));
            }
            maximumFont = Math.max(maximumFont, plan.font);
            maximumStep = Math.max(maximumStep, plan.step);
            unitStart = unitEnd;
            remainingWeight -= lobe.weight;
        }
        if (cells.size() != points.length) return null;
        float[] sizes = new float[fonts.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = fonts.get(i);
        return new Plan(maximumFont, maximumStep, cells.toArray(new int[0][]), points, sizes);
    }

    static float glyphAdvance(int point) {
        return point == '…' || point == '︙'
                ? .5f
                : "♥♡❤".indexOf(point) >= 0 ? .65f : "，。！？；：、,.!?;:".indexOf(point) >= 0 ? .5f : 1f;
    }

    static List<int[]> units(int[] points) {
        List<int[]> result = new ArrayList<>();
        for (int start = 0; start < points.length; ) {
            int end = start + 1;
            while (end < points.length && trailingMark(points[end])) end++;
            result.add(new int[] {start, end});
            start = end;
        }
        return result;
    }

    private static float unitAdvance(int[] points, int[] unit, boolean compactMarks) {
        float total = 0;
        for (int i = unit[0]; i < unit[1]; i++) total += compactMarks ? glyphAdvance(points[i]) : 1;
        return total;
    }

    private static Plan fitLobe(
            int[] points,
            Space space,
            Lobe lobe,
            boolean vertical,
            int maximum,
            boolean compactMarks) {
        int low = 8, high = Math.max(8, Math.min(maximum, Math.max(space.w, space.h))), best = 0;
        Plan plan = null;
        while (low <= high) {
            int font = (low + high) / 2;
            Plan candidate = gridPlan(points, space, lobe, vertical, font, compactMarks);
            if (candidate == null) high = font - 1;
            else {
                best = font;
                plan = candidate;
                low = font + 1;
            }
        }
        return best == 0 ? null : plan;
    }

    /**
     * Optimise glyph capacity at this font; a maximum-area rectangle can discard a tall narrow
     * lobe.
     */
    private static Plan gridPlan(
            int[] points,
            Space space,
            Lobe lobe,
            boolean vertical,
            int font,
            boolean compactMarks) {
        int step = font;
        List<int[]> units = units(points);
        int[] advances = new int[points.length], unitSizes = new int[units.size()];
        for (int i = 0; i < points.length; i++)
            advances[i] =
                    Math.max(
                            1,
                            (int) Math.ceil(step * (compactMarks ? glyphAdvance(points[i]) : 1)));
        for (int i = 0; i < units.size(); i++)
            for (int j = units.get(i)[0]; j < units.get(i)[1]; j++) unitSizes[i] += advances[j];
        int extent = vertical ? space.h : space.w;
        int[] linesAt = new int[extent + 1], longestAt = new int[extent + 1];
        for (int limit = 1; limit <= extent; limit++) {
            int lines = 1, length = 0, longest = 0;
            boolean valid = true;
            for (int size : unitSizes) {
                if (size > limit) {
                    valid = false;
                    break;
                }
                if (length + size > limit) {
                    longest = Math.max(longest, length);
                    lines++;
                    length = 0;
                }
                length += size;
            }
            if (valid) {
                linesAt[limit] = lines;
                longestAt[limit] = Math.max(longest, length);
            }
        }
        int[] heights = new int[space.w], stack = new int[space.w + 1], best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestLines = Integer.MAX_VALUE, bestArea = 0;
        int[] source = lobe.source;
        double sx = (source[0] + source[2]) / 2.0, sy = (source[1] + source[3]) / 2.0;
        for (int y = 0; y < space.h; y++) {
            int top = -1;
            for (int x = 0; x < space.w; x++) {
                int p = y * space.w + x;
                heights[x] =
                        space.safe[p] && (space.owner == null || space.owner[p] == lobe.label)
                                ? heights[x] + 1
                                : 0;
            }
            for (int x = 0; x <= space.w; x++) {
                int value = x == space.w ? 0 : heights[x];
                while (top >= 0 && heights[stack[top]] > value) {
                    int height = heights[stack[top--]],
                            left = top < 0 ? 0 : stack[top] + 1,
                            width = x - left,
                            limit = vertical ? height : width,
                            lines = linesAt[limit];
                    if (lines == 0 || (vertical ? width : height) < lines * step) continue;
                    int bw = vertical ? lines * step : longestAt[limit],
                            bh = vertical ? longestAt[limit] : lines * step;
                    double cx = Math.max(left + bw / 2.0, Math.min(x - bw / 2.0, sx)),
                            cy =
                                    Math.max(
                                            y + 1 - height + bh / 2.0,
                                            Math.min(y + 1 - bh / 2.0, sy));
                    if (Math.abs(cx - sx) > Math.max(step * .5, (source[2] - source[0]) * .5)
                            || Math.abs(cy - sy)
                                    > Math.max(step * .5, (source[3] - source[1]) * .5)) continue;
                    double distance = (cx - sx) * (cx - sx) + (cy - sy) * (cy - sy);
                    int area = width * height;
                    if (distance < bestDistance - .01
                            || Math.abs(distance - bestDistance) < .01
                                    && (lines < bestLines
                                            || lines == bestLines && area > bestArea)) {
                        bestDistance = distance;
                        bestLines = lines;
                        bestArea = area;
                        best = new int[] {left, y + 1 - height, x, y + 1};
                    }
                }
                stack[++top] = x;
            }
        }
        if (best == null)
            return contourColumns(
                    points,
                    space,
                    lobe,
                    vertical,
                    font,
                    step,
                    units,
                    advances,
                    unitSizes,
                    compactMarks);
        int limit = vertical ? best[3] - best[1] : best[2] - best[0];
        List<int[]> chunks = new ArrayList<>();
        int start = 0, length = 0;
        for (int i = 0; i < units.size(); i++) {
            if (length + unitSizes[i] > limit) {
                chunks.add(new int[] {start, i, length});
                start = i;
                length = 0;
            }
            length += unitSizes[i];
        }
        chunks.add(new int[] {start, units.size(), length});
        int[][] cells = new int[points.length][];
        float[] fonts = new float[points.length];
        int across = chunks.size() * step;
        int left = clamp((int) Math.round(sx - across / 2.0), best[0], best[2] - across),
                top = clamp((int) Math.round(sy - across / 2.0), best[1], best[3] - across);
        for (int line = 0; line < chunks.size(); line++) {
            int[] chunk = chunks.get(line);
            int along =
                    vertical
                            ? clamp(
                                    (int) Math.round(sy - chunk[2] / 2.0),
                                    best[1],
                                    best[3] - chunk[2])
                            : clamp(
                                    (int) Math.round(sx - chunk[2] / 2.0),
                                    best[0],
                                    best[2] - chunk[2]);
            for (int i = chunk[0]; i < chunk[1]; i++)
                for (int j = units.get(i)[0]; j < units.get(i)[1]; j++) {
                    int size = advances[j],
                            x =
                                    vertical
                                            ? left
                                                    + (chunks.size() - 1 - line) * step
                                                    + (step - size) / 2
                                            : along,
                            y = vertical ? along : top + line * step + (step - size) / 2;
                    cells[j] = new int[] {x, y, x + size, y + size};
                    fonts[j] = font * (compactMarks ? glyphAdvance(points[j]) : 1);
                    along += size;
                }
        }
        return new Plan(font, step, cells, points, fonts);
    }

    /**
     * Curved balloons permit different safe lengths per column; no glyph crosses the paper contour.
     */
    private static Plan contourColumns(
            int[] points,
            Space space,
            Lobe lobe,
            boolean vertical,
            int font,
            int step,
            List<int[]> units,
            int[] advances,
            int[] unitSizes,
            boolean compactMarks) {
        int across = vertical ? space.w : space.h, along = vertical ? space.h : space.w;
        if (step > across) return null;
        int[] source = lobe.source;
        double sa = vertical ? (source[1] + source[3]) / 2.0 : (source[0] + source[2]) / 2.0;
        int[][] prefix = new int[along][across + 1];
        for (int a = 0; a < along; a++)
            for (int c = 0; c < across; c++) {
                int p = vertical ? a * space.w + c : c * space.w + a;
                prefix[a][c + 1] =
                        prefix[a][c]
                                + (space.safe[p]
                                                && (space.owner == null
                                                        || space.owner[p] == lobe.label)
                                        ? 0
                                        : 1);
            }
        int[][] runs = new int[across - step + 1][];
        for (int c = 0; c < runs.length; c++) {
            int start = -1;
            double distance = Double.MAX_VALUE;
            for (int a = 0; a <= along; a++) {
                boolean safe = a < along && prefix[a][c + step] == prefix[a][c];
                if (safe) {
                    if (start < 0) start = a;
                } else if (start >= 0) {
                    double near = Math.max(0, Math.max(start - sa, sa - a));
                    int[] prior = runs[c];
                    if (near < distance
                            || near == distance
                                    && (prior == null || a - start > prior[1] - prior[0])) {
                        distance = near;
                        runs[c] = new int[] {start, a};
                    }
                    start = -1;
                }
            }
        }
        Plan best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestColumns = Integer.MAX_VALUE;
        for (int first = 0; first < runs.length; first++) {
            int cursor = 0,
                    column = 0,
                    left = Integer.MAX_VALUE,
                    top = Integer.MAX_VALUE,
                    right = 0,
                    bottom = 0;
            int[][] cells = new int[points.length][];
            while (cursor < units.size()) {
                int c = vertical ? first - column * step : first + column * step;
                if (c < 0 || c >= runs.length || runs[c] == null) break;
                int[] run = runs[c];
                int end = cursor, length = 0;
                while (end < units.size() && length + unitSizes[end] <= run[1] - run[0])
                    length += unitSizes[end++];
                if (end == cursor) break;
                int a = clamp((int) Math.round(sa - length / 2.0), run[0], run[1] - length);
                for (int i = cursor; i < end; i++)
                    for (int j = units.get(i)[0]; j < units.get(i)[1]; j++) {
                        int size = advances[j],
                                x = vertical ? c + (step - size) / 2 : a,
                                y = vertical ? a : c + (step - size) / 2;
                        cells[j] = new int[] {x, y, x + size, y + size};
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x + size);
                        bottom = Math.max(bottom, y + size);
                        a += size;
                    }
                cursor = end;
                column++;
            }
            if (cursor != units.size()) continue;
            double cx = (left + right) / 2.0,
                    cy = (top + bottom) / 2.0,
                    sx = (source[0] + source[2]) / 2.0,
                    sy = (source[1] + source[3]) / 2.0;
            if (Math.abs(cx - sx) > Math.max(step * .5, (source[2] - source[0]) * .5)
                    || Math.abs(cy - sy) > Math.max(step * .5, (source[3] - source[1]) * .5))
                continue;
            double distance = (cx - sx) * (cx - sx) + (cy - sy) * (cy - sy);
            if (distance < bestDistance - .01
                    || Math.abs(distance - bestDistance) < .01 && column < bestColumns) {
                float[] fonts = new float[points.length];
                for (int i = 0; i < fonts.length; i++)
                    fonts[i] = font * (compactMarks ? glyphAdvance(points[i]) : 1);
                best = new Plan(font, step, cells, points, fonts);
                bestDistance = distance;
                bestColumns = column;
            }
        }
        return best;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static boolean trailingMark(int point) {
        return "，。！？；：、,.!?;:♥♡❤…︙﹂」”".indexOf(point) >= 0;
    }

    private static final class Space {
        final boolean sourceLobes;
        final boolean[] safe;
        final int[] owner;
        final int w, h;
        final List<Lobe> lobes;

        Space(List<Lobe> lobes, boolean[] safe, int[] owner, int w, int h) {
            this.lobes = lobes;
            this.safe = safe;
            this.owner = owner;
            this.w = w;
            this.h = h;
            this.sourceLobes = lobes.size() > 1;
        }
    }

    private static Space space(
            boolean[] interior, int w, int h, int estimate, int[][] seeds, boolean vertical)
            throws Exception {
        int[] distance = new int[interior.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = y * w + x;
                distance[p] =
                        !interior[p]
                                ? 0
                                : Math.min(
                                                x == 0 ? 0 : distance[p - 1],
                                                y == 0 ? 0 : distance[p - w])
                                        + 1;
            }
        for (int y = h - 1; y >= 0; y--)
            for (int x = w - 1; x >= 0; x--) {
                int p = y * w + x;
                if (distance[p] > 0)
                    distance[p] =
                            Math.min(
                                    distance[p],
                                    Math.min(
                                                    x == w - 1 ? 0 : distance[p + 1],
                                                    y == h - 1 ? 0 : distance[p + w])
                                            + 1);
            }
        boolean[] safe = new boolean[interior.length];
        int inset = Math.max(2, estimate / 8);
        for (int i = 0; i < safe.length; i++) safe[i] = distance[i] > inset;
        Space lobes = sourceLobes(distance, safe, w, h, estimate, inset, seeds, vertical);
        if (lobes != null) return lobes;
        int[] r = largest(safe, w, h);
        List<Lobe> single = new ArrayList<>();
        if (r != null) {
            Lobe lobe = new Lobe(1);
            lobe.weight = 1;
            if (seeds != null)
                for (int[] seed : seeds) {
                    lobe.source = union(lobe.source, seed);
                    lobe.lines.add(seed);
                }
            if (lobe.source == null) lobe.source = r;
            single.add(lobe);
        }
        return new Space(single, safe, null, w, h);
    }

    private static final class Lobe {
        final int label;
        int[] source;
        float weight;
        final List<int[]> lines = new ArrayList<>();

        Lobe(int label) {
            this.label = label;
        }
    }

    /** A split needs both a paper neck and original text in each resulting body. */
    private static Space sourceLobes(
            int[] distance,
            boolean[] safe,
            int w,
            int h,
            int estimate,
            int inset,
            int[][] seeds,
            boolean vertical) {
        if (seeds == null || seeds.length < 2 || seeds.length > 32) return null;
        Space stepped = steppedLobes(safe, w, h, estimate, seeds, vertical);
        int[] anchors = new int[seeds.length];
        int maximumPeak = 0;
        for (int i = 0; i < seeds.length; i++) {
            int[] r = seeds[i];
            int best = -1;
            long nearest = Long.MAX_VALUE;
            for (int y = Math.max(0, r[1]); y < Math.min(h, r[3]); y++)
                for (int x = Math.max(0, r[0]); x < Math.min(w, r[2]); x++) {
                    int p = y * w + x;
                    long dx = x * 2L - r[0] - r[2],
                            dy = y * 2L - r[1] - r[3],
                            near = dx * dx + dy * dy;
                    if (best < 0
                            || distance[p] > distance[best]
                            || distance[p] == distance[best] && near < nearest) {
                        best = p;
                        nearest = near;
                    }
                }
            if (best < 0 || distance[best] <= inset + 2) return null;
            anchors[i] = best;
            maximumPeak = Math.max(maximumPeak, distance[best]);
        }
        int[] owner = new int[safe.length], queue = new int[safe.length];
        int upper = maximumPeak - 2, stride = Math.max(1, (upper - inset) / 24);
        for (int threshold = inset + 1; threshold <= upper; threshold += stride) {
            java.util.Arrays.fill(owner, 0);
            List<int[]> bodies = new ArrayList<>();
            bodies.add(null);
            for (int start = 0; start < safe.length; start++)
                if (safe[start] && distance[start] > threshold && owner[start] == 0) {
                    int label = bodies.size(),
                            head = 0,
                            tail = 0,
                            left = w,
                            top = h,
                            right = 0,
                            bottom = 0;
                    queue[tail++] = start;
                    owner[start] = label;
                    while (head < tail) {
                        int p = queue[head++], x = p % w, y = p / w;
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x + 1);
                        bottom = Math.max(bottom, y + 1);
                        if (x > 0 && distance[p - 1] > threshold && owner[p - 1] == 0) {
                            owner[p - 1] = label;
                            queue[tail++] = p - 1;
                        }
                        if (x + 1 < w && distance[p + 1] > threshold && owner[p + 1] == 0) {
                            owner[p + 1] = label;
                            queue[tail++] = p + 1;
                        }
                        if (y > 0 && distance[p - w] > threshold && owner[p - w] == 0) {
                            owner[p - w] = label;
                            queue[tail++] = p - w;
                        }
                        if (y + 1 < h && distance[p + w] > threshold && owner[p + w] == 0) {
                            owner[p + w] = label;
                            queue[tail++] = p + w;
                        }
                    }
                    bodies.add(new int[] {left, top, right, bottom, tail});
                }
            List<Lobe> lobes = new ArrayList<>();
            int[] mapping = new int[bodies.size()];
            boolean valid = true;
            for (int i = 0; i < anchors.length; i++) {
                int label = owner[anchors[i]];
                if (label == 0
                        || distance[anchors[i]] - threshold < 3
                        || bodies.get(label)[4] < Math.max(16, estimate * estimate * .08)) continue;
                if (mapping[label] == 0) {
                    mapping[label] = lobes.size() + 1;
                    lobes.add(new Lobe(mapping[label]));
                }
            }
            if (!valid || lobes.size() < 2 || lobes.size() > 8) continue;
            int head = 0, tail = 0;
            for (int p = 0; p < owner.length; p++) {
                owner[p] = mapping[owner[p]];
                if (owner[p] > 0) queue[tail++] = p;
            }
            // Grow each proven core inside the original safe paper only; neighbouring text remains
            // excluded.
            while (head < tail) {
                int p = queue[head++], x = p % w, y = p / w, label = owner[p];
                if (x > 0 && safe[p - 1] && owner[p - 1] == 0) {
                    owner[p - 1] = label;
                    queue[tail++] = p - 1;
                }
                if (x + 1 < w && safe[p + 1] && owner[p + 1] == 0) {
                    owner[p + 1] = label;
                    queue[tail++] = p + 1;
                }
                if (y > 0 && safe[p - w] && owner[p - w] == 0) {
                    owner[p - w] = label;
                    queue[tail++] = p - w;
                }
                if (y + 1 < h && safe[p + w] && owner[p + w] == 0) {
                    owner[p + w] = label;
                    queue[tail++] = p + w;
                }
            }
            for (int i = 0; i < anchors.length; i++) {
                int label = owner[anchors[i]];
                if (label == 0) {
                    valid = false;
                    break;
                }
                Lobe lobe = lobes.get(label - 1);
                int[] r = seeds[i];
                lobe.weight +=
                        Math.max(r[2] - r[0], r[3] - r[1])
                                / (float) Math.max(1, Math.min(r[2] - r[0], r[3] - r[1]));
                lobe.source = union(lobe.source, r);
                lobe.lines.add(r);
            }
            boolean[] region = new boolean[safe.length];
            for (Lobe lobe : lobes) {
                for (int p = 0; p < region.length; p++) region[p] = owner[p] == lobe.label;
                if (largest(region, w, h) == null || lobe.source == null) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;
            if (stepped != null && stepped.lobes.size() > lobes.size()) return stepped;
            readingOrder(lobes, vertical);
            return new Space(lobes, safe, owner, w, h);
        }
        return stepped;
    }

    private static int[] union(int[] a, int[] b) {
        return a == null
                ? b.clone()
                : new int[] {
                    Math.min(a[0], b[0]),
                    Math.min(a[1], b[1]),
                    Math.max(a[2], b[2]),
                    Math.max(a[3], b[3])
                };
    }

    private static void readingOrder(List<Lobe> lobes, boolean vertical) {
        lobes.sort(
                (a, b) ->
                        Integer.compare(
                                a.source[1] + (a.source[3] - a.source[1]) / 4,
                                b.source[1] + (b.source[3] - b.source[1]) / 4));
        for (int first = 0; first < lobes.size(); ) {
            int end = first + 1;
            int[] r = lobes.get(first).source;
            int bottom = r[3] - (r[3] - r[1]) / 4;
            while (end < lobes.size()) {
                int[] next = lobes.get(end).source;
                if (next[1] + (next[3] - next[1]) / 4 >= bottom) break;
                bottom = Math.max(bottom, next[3] - (next[3] - next[1]) / 4);
                end++;
            }
            lobes.subList(first, end)
                    .sort(
                            (a, b) ->
                                    vertical
                                            ? Integer.compare(b.source[0], a.source[0])
                                            : Integer.compare(a.source[0], b.source[0]));
            first = end;
        }
    }

    private static int[] paperRun(boolean[] safe, int w, int h, int[] box, boolean vertical) {
        int cx = Math.max(0, Math.min(w - 1, (box[0] + box[2]) / 2)),
                cy = Math.max(0, Math.min(h - 1, (box[1] + box[3]) / 2));
        if (!safe[cy * w + cx]) return null;
        int start = vertical ? cy : cx, end = start;
        while (start > 0 && safe[vertical ? (start - 1) * w + cx : cy * w + start - 1]) start--;
        while (end + 1 < (vertical ? h : w)
                && safe[vertical ? (end + 1) * w + cx : cy * w + end + 1]) end++;
        return new int[] {start, end + 1};
    }

    /**
     * Wide joins may not separate under erosion: source columns and both paper edges must agree on
     * the step.
     */
    private static Space steppedLobes(
            boolean[] safe, int w, int h, int estimate, int[][] seeds, boolean vertical) {
        if (!vertical) return null;
        List<int[]> groups = new ArrayList<>();
        for (int[] r : seeds) {
            int match = -1;
            for (int i = 0; i < groups.size(); i++) {
                int[] g = groups.get(i);
                int size = Math.min(r[2] - r[0], g[2] - g[0]),
                        gap = Math.max(0, Math.max(r[0], g[0]) - Math.min(r[2], g[2]));
                if (gap <= size * .4
                        && Math.min(r[3], g[3]) > Math.max(r[1], g[1])
                        && (Math.abs(r[1] - g[1]) <= size * .75
                                || Math.abs(r[3] - g[3]) <= size * .75)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) groups.add(r.clone());
            else groups.set(match, union(groups.get(match), r));
        }
        if (groups.size() < 2 || groups.size() > 8) return null;
        boolean[] active = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++)
            for (int j = i + 1; j < groups.size(); j++) {
                int[] a = groups.get(i), b = groups.get(j);
                int size = Math.min(estimate, Math.min(a[2] - a[0], b[2] - b[0]));
                boolean proof = false;
                int dt = b[1] - a[1], db = b[3] - a[3];
                int[] ay = paperRun(safe, w, h, a, true), by = paperRun(safe, w, h, b, true);
                if (Math.abs(dt) >= size * .7
                        && Math.abs(db) >= size * .7
                        && Integer.signum(dt) == Integer.signum(db)
                        && ay != null
                        && by != null)
                    proof =
                            (by[0] - ay[0]) * Integer.signum(dt) >= size * .4
                                    && (by[1] - ay[1]) * Integer.signum(dt) >= size * .4;
                int yOverlap = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1])),
                        xGap = Math.max(0, Math.max(a[0], b[0]) - Math.min(a[2], b[2]));
                int[] ax = paperRun(safe, w, h, a, false), bx = paperRun(safe, w, h, b, false);
                if (yOverlap <= Math.min(a[3] - a[1], b[3] - b[1]) * .4 && ax != null && bx != null)
                    proof |=
                            Math.min(ax[1] - ax[0], bx[1] - bx[0])
                                    < Math.max(ax[1] - ax[0], bx[1] - bx[0]) * .7;
                if (xGap >= size * .25 && ay != null && by != null)
                    proof |=
                            Math.min(ay[1] - ay[0], by[1] - by[0])
                                    < Math.max(ay[1] - ay[0], by[1] - by[0]) * .7;
                if (proof) {
                    active[i] = true;
                    active[j] = true;
                }
            }
        int count = 0;
        for (boolean yes : active) if (yes) count++;
        if (count < 2) return null;
        List<Lobe> lobes = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++)
            if (active[i]) {
                Lobe l = new Lobe(lobes.size() + 1);
                l.source = groups.get(i);
                lobes.add(l);
            }
        int[] owner = new int[safe.length], queue = new int[safe.length];
        int tail = 0;
        for (int p = 0; p < safe.length; p++)
            if (safe[p]) {
                int x = p % w, y = p / w;
                for (Lobe l : lobes) {
                    int[] r = l.source;
                    if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) {
                        owner[p] = l.label;
                        queue[tail++] = p;
                        break;
                    }
                }
            }
        int head = 0;
        while (head < tail) {
            int p = queue[head++], x = p % w, y = p / w, label = owner[p];
            int[] near = {
                x > 0 ? p - 1 : -1,
                x + 1 < w ? p + 1 : -1,
                y > 0 ? p - w : -1,
                y + 1 < h ? p + w : -1
            };
            for (int q : near)
                if (q >= 0 && safe[q] && owner[q] == 0) {
                    owner[q] = label;
                    queue[tail++] = q;
                }
        }
        for (int[] r : seeds) {
            int p =
                    Math.max(0, Math.min(h - 1, (r[1] + r[3]) / 2)) * w
                            + Math.max(0, Math.min(w - 1, (r[0] + r[2]) / 2));
            int label = owner[p];
            if (label == 0) return null;
            lobes.get(label - 1).weight +=
                    Math.max(r[2] - r[0], r[3] - r[1])
                            / (float) Math.max(1, Math.min(r[2] - r[0], r[3] - r[1]));
            lobes.get(label - 1).lines.add(r);
        }
        boolean[] region = new boolean[safe.length];
        for (Lobe l : lobes) {
            for (int p = 0; p < safe.length; p++) region[p] = owner[p] == l.label;
            if (largest(region, w, h) == null || l.weight == 0) return null;
        }
        readingOrder(lobes, vertical);
        return new Space(lobes, safe, owner, w, h);
    }

    static boolean touchesForeignLines(Plan plan, int[][] foreignLines) {
        for (int[] cell : plan.cells)
            for (int[] line : foreignLines)
                if (cell[0] < line[2]
                        && cell[2] > line[0]
                        && cell[1] < line[3]
                        && cell[3] > line[1]) return true;
        return false;
    }

    private static int[] largest(boolean[] mask, int w, int h) {
        int[] heights = new int[w], stack = new int[w + 1], best = null;
        int area = 0;
        for (int y = 0; y < h; y++) {
            int top = -1;
            for (int x = 0; x < w; x++) heights[x] = mask[y * w + x] ? heights[x] + 1 : 0;
            for (int x = 0; x <= w; x++) {
                int value = x == w ? 0 : heights[x];
                while (top >= 0 && heights[stack[top]] > value) {
                    int height = heights[stack[top--]],
                            left = top < 0 ? 0 : stack[top] + 1,
                            size = height * (x - left);
                    if (size > area) {
                        area = size;
                        best = new int[] {left, y + 1 - height, x, y + 1};
                    }
                }
                stack[++top] = x;
            }
        }
        return best;
    }
}
