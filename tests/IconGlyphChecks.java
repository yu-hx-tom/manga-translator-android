import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Only UI icon literals are banned; normal explanatory punctuation is preserved. */
public final class IconGlyphChecks {
    private static final String GLYPHS = "◎▣✎⌂↻✕→⋮⋯‹›↶↷⌃▾☆★↺◷↗⌖●＋⚙📂📁✍️🔍✓○";
    private static final Set<String> PROSE = Set.of("处理顺序：检测 → 识读 → 翻译 → 嵌字", "A → B");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        boolean warn = Arrays.asList(args).contains("--warn");
        ArrayList<String> failures = new ArrayList<>();
        Pattern strings = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
        try (var files = Files.walk(root.resolve("app/src/main"))) {
            for (Path file :
                    (Iterable<Path>)
                            files.filter(
                                            p ->
                                                    p.toString().endsWith(".java")
                                                            || p.getFileName()
                                                                    .toString()
                                                                    .equals("browser_home.html"))
                                    ::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.stripLeading().startsWith("//") || line.stripLeading().startsWith("*"))
                        continue;
                    Matcher m = strings.matcher(line);
                    while (m.find()) {
                        String value = m.group(1).trim();
                        if (value.isEmpty() || PROSE.contains(value)) continue;
                        int first = value.codePointAt(0);
                        boolean prefix = GLYPHS.codePoints().anyMatch(x -> x == first);
                        boolean only =
                                value.codePoints()
                                        .allMatch(
                                                x ->
                                                        Character.isWhitespace(x)
                                                                || GLYPHS.codePoints()
                                                                        .anyMatch(g -> g == x));
                        boolean suffix =
                                value.length() < 32
                                        && GLYPHS.indexOf(value.charAt(value.length() - 1)) >= 0;
                        if (prefix || only || suffix)
                            failures.add(
                                    root.relativize(file)
                                            + ":"
                                            + (i + 1)
                                            + " icon literal: "
                                            + value);
                    }
                    if (file.toString().endsWith(".html")
                            && (line.contains("content:'⌖")
                                    || line.contains(">＋<")
                                    || line.contains(">→<")
                                    || line.contains(">›<")))
                        failures.add(root.relativize(file) + ":" + (i + 1) + " HTML glyph icon");
                }
            }
        }
        for (String failure : failures) System.out.println(failure);
        System.out.println(
                "IconGlyphChecks: "
                        + failures.size()
                        + " findings"
                        + (warn ? " (migration warning mode)" : ""));
        if (!warn && !failures.isEmpty())
            throw new AssertionError("Replace icon glyphs with supplied vector resources");
    }
}
