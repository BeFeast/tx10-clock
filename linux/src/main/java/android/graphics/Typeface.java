package android.graphics;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Linux shim over AWT fonts. Maps the Android family names ClockRenderer uses
 * ("sans-serif", "sans-serif-light", "sans-serif-medium") to the Roboto TTFs
 * installed via the font-roboto package (or found under TX10CLOCK_FONT_DIR),
 * so glyphs match the Android build. Falls back to the JDK logical font when a
 * Roboto file is unavailable.
 */
public final class Typeface {

    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;

    private static volatile Map<String, Font> robotoFiles;

    private final Font base;
    private final boolean syntheticBold;

    private Typeface(Font base, boolean syntheticBold) {
        this.base = base;
        this.syntheticBold = syntheticBold;
    }

    public static Typeface create(String familyName, int style) {
        boolean bold = (style & BOLD) != 0;
        String family = familyName == null ? "sans-serif" : familyName;
        Font regular;
        Font boldFace;
        switch (family) {
            case "sans-serif-light":
                regular = roboto("Roboto-Light.ttf");
                boldFace = roboto("Roboto-Medium.ttf");
                break;
            case "sans-serif-medium":
                regular = roboto("Roboto-Medium.ttf");
                boldFace = roboto("Roboto-Bold.ttf");
                break;
            case "sans-serif-thin":
                regular = roboto("Roboto-Thin.ttf");
                boldFace = roboto("Roboto-Light.ttf");
                break;
            case "sans-serif-condensed":
            case "sans-serif-black":
            case "sans-serif":
            default:
                regular = roboto("Roboto-Regular.ttf");
                boldFace = roboto("Roboto-Bold.ttf");
                break;
        }
        if (regular == null) {
            return new Typeface(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 12), false);
        }
        if (bold && boldFace != null) {
            return new Typeface(boldFace, false);
        }
        return new Typeface(regular, bold);
    }

    Font awtBase() {
        return base;
    }

    boolean usesSyntheticBold() {
        return syntheticBold;
    }

    /** Resolve a Roboto TTF by file name from the scanned font roots, or {@code null}. */
    private static Font roboto(String fileName) {
        String override = System.getenv("TX10CLOCK_FONT_DIR");
        if (override != null && !override.isEmpty()) {
            File direct = new File(override, fileName);
            if (direct.isFile()) {
                return fromFile(direct);
            }
        }
        Map<String, Font> files = files();
        Font f = files.get(fileName);
        return f != null ? f : files.get(fallbackName(fileName));
    }

    /**
     * Roboto ships one variable or many static TTFs depending on the package;
     * a missing weight falls back to the closest always-present file.
     */
    private static String fallbackName(String fileName) {
        switch (fileName) {
            case "Roboto-Bold.ttf":
            case "Roboto-Medium.ttf":
            case "Roboto-Light.ttf":
            case "Roboto-Thin.ttf":
                return "Roboto-Regular.ttf";
            default:
                return "Roboto-Regular.ttf";
        }
    }

    private static Map<String, Font> files() {
        Map<String, Font> local = robotoFiles;
        if (local != null) {
            return local;
        }
        synchronized (Typeface.class) {
            if (robotoFiles == null) {
                Map<String, Font> m = new ConcurrentHashMap<>();
                scanInto(m, new File("/usr/share/fonts"));
                scanInto(m, new File("/usr/local/share/fonts"));
                robotoFiles = m;
            }
            return robotoFiles;
        }
    }

    private static void scanInto(Map<String, Font> into, File dir) {
        scanInto(into, dir, 0);
    }

    private static void scanInto(Map<String, Font> into, File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 5) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                scanInto(into, f, depth + 1);
            } else if (f.getName().startsWith("Roboto-") && f.getName().endsWith(".ttf")) {
                try {
                    into.putIfAbsent(f.getName(), Font.createFont(Font.TRUETYPE_FONT, f));
                } catch (FontFormatException | IOException ignored) {
                    // Unreadable TTF: leave the entry absent so the fallback applies.
                }
            }
        }
    }

    private static Font fromFile(File file) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file);
        } catch (FontFormatException | IOException e) {
            return null;
        }
    }
}
