package com.befeast.tx10clock;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Clock #54: for now every user-visible text on the device is English. No Cyrillic in the app's text
 * resources, its manifest, or any Java string literal (comments are not user-visible). Calendar event
 * titles and the configured shiftKeywords are user data and never live in these sources.
 */
public class UserVisibleTextTest {

    /** Java string literals that may contain Cyrillic, e.g. for keyword parsing. None today. */
    private static final Set<String> ALLOWED_LITERALS = Collections.emptySet();

    private static final Path MAIN = Paths.get("src", "main");

    @Test
    public void noCyrillicInTextResourcesOrTheManifest() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN.resolve("res"))) {
            files = walk.filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList());
        }
        files.add(MAIN.resolve("AndroidManifest.xml"));
        assertTrue("found the resources", files.size() > 1);
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (hasCyrillic(decodeResource(lines.get(i)))) {
                    found.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertTrue("Cyrillic in user-visible resources:\n" + String.join("\n", found), found.isEmpty());
    }

    @Test
    public void noCyrillicInJavaStringLiterals() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN.resolve("java"))) {
            files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue("found the sources", !files.isEmpty());
        for (Path file : files) {
            for (String literal : literals(unicodeEscapes(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)))) {
                if (hasCyrillic(literal) && !ALLOWED_LITERALS.contains(literal)) {
                    found.add(file.getFileName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue("Cyrillic in Java string literals:\n" + String.join("\n", found), found.isEmpty());
    }

    @Test
    public void encodedCyrillicIsFoundToo() {
        // javac translates backslash-u escapes before lexing; aapt2 decodes them and XML character references.
        assertTrue(literals(unicodeEscapes("String a = \"\\u0421\";")).stream().anyMatch(UserVisibleTextTest::hasCyrillic));
        assertTrue(literals(unicodeEscapes("char c = '\\u0421';")).stream().anyMatch(UserVisibleTextTest::hasCyrillic));
        assertTrue(literals(unicodeEscapes("String a = \"\\\\u0421\";")).stream().noneMatch(UserVisibleTextTest::hasCyrillic));
        assertTrue(hasCyrillic(decodeResource("<string name=\"a\">&#1057;</string>")));
        assertTrue(hasCyrillic(decodeResource("<string name=\"a\">&#x421;</string>")));
        assertTrue(hasCyrillic(decodeResource("<string name=\"a\">\\u0421</string>")));
    }

    @Test
    public void theLiteralScannerSeesStringsAndSkipsComments() {
        String source = "// \"коммент\"\n/* \"блок\" */ String a = \"plain\"; char c = '\"'; String b = \"esc \\\" кир\";";
        assertTrue(literals(source).contains("plain"));
        assertTrue(literals(source).stream().anyMatch(s -> s.contains("кир")));
        assertTrue(literals(source).stream().noneMatch(s -> s.contains("коммент") || s.contains("блок")));
    }

    static boolean hasCyrillic(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC
                || Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY);
    }

    /** Java's \\uXXXX translation before lexing: only where the backslash itself is not escaped. */
    static String unicodeEscapes(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length() && src.charAt(i + 1) == 'u' && backslashesBefore(src, i) % 2 == 0) {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) == 'u') {
                    j++;
                }
                if (j + 4 <= src.length() && src.substring(j, j + 4).matches("[0-9a-fA-F]{4}")) {
                    out.append((char) Integer.parseInt(src.substring(j, j + 4), 16));
                    i = j + 4;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int backslashesBefore(String s, int i) {
        int n = 0;
        while (i - 1 - n >= 0 && s.charAt(i - 1 - n) == '\\') {
            n++;
        }
        return n;
    }

    /** What aapt2 shows for a resource line: XML numeric character references and \\uXXXX decoded. */
    static String decodeResource(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);|\\\\u([0-9a-fA-F]{4})").matcher(line);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int cp = m.group(2) != null ? Integer.parseInt(m.group(2), 16)
                    : m.group(1).startsWith("x") ? Integer.parseInt(m.group(1).substring(1), 16) : Integer.parseInt(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The contents of the string and char literals of a Java source, comments skipped. */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                StringBuilder ch = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    ch.append(src.charAt(i));
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.add(ch.toString());
            } else if (c == '"') {
                StringBuilder lit = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        lit.append(src.charAt(i)).append(src.charAt(i + 1));
                        i += 2;
                    } else {
                        lit.append(src.charAt(i++));
                    }
                }
                i++;
                out.add(lit.toString());
            } else {
                i++;
            }
        }
        return out;
    }
}
