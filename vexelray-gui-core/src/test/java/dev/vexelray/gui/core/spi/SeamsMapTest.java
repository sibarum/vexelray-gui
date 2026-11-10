package dev.vexelray.gui.core.spi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code docs/reference/seams.md}'s table names every public method of {@link Seams}, so the map of which seam is whose
 * cannot fall behind the class (Rasterware FN-34: the seam a widget needed was on it all along, and nothing said so).
 * A method added here without a row there fails this, naming it. The statics, {@code install} and {@code of}, are how
 * the seams are reached rather than seams, and are not asked for.
 */
class SeamsMapTest {

    private static final Path MAP = Path.of("..", "docs", "reference", "seams.md");

    @Test
    void theMapNamesEverySeam() throws IOException {
        String page = Files.readString(MAP);
        int from = page.indexOf("## What is on it");
        int to = page.indexOf("\n## ", from + 1);
        String table = page.substring(from, to < 0 ? page.length() : to);
        Set<String> named = new TreeSet<>();
        Matcher m = Pattern.compile("`([a-zA-Z]+)(\\([^`]*\\))?`").matcher(table);
        while (m.find()) {
            named.add(m.group(1));
        }

        Set<String> missing = new TreeSet<>();
        for (Method method : Seams.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                    && !named.contains(method.getName())) {
                missing.add(method.getName());
            }
        }
        assertEquals(Set.of(), missing, "public on Seams and not in seams.md's table (" + MAP + ")");
    }
}
