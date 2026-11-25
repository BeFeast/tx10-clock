package com.befeast.tx10clock.linux;

import android.graphics.Canvas;

import com.befeast.tx10clock.BurnInOffset;
import com.befeast.tx10clock.ClockConfig;
import com.befeast.tx10clock.ClockRenderer;
import com.befeast.tx10clock.ConfigStore;
import com.befeast.tx10clock.ExternalConfig;
import com.befeast.tx10clock.TimeSource;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Linux (X11) kiosk runtime for the tx10-clock renderer.
 *
 * <p>{@link ClockRenderer} runs unmodified against the android.graphics shims
 * over Java2D ({@code android/graphics} in this module). Configuration keeps
 * the platform file contract: {@code config.json} in the config directory
 * (env {@code TX10CLOCK_CONFIG_DIR}, default {@code ~/.tx10-clock}), polled for
 * changes each tick; {@code status.json} mirrors the Android app's verifier
 * contract.
 *
 * <p>Modes:
 * <ul>
 *   <li>default — fullscreen kiosk window on the default screen;</li>
 *   <li>{@code --shot <file> [width height] [ISO-8601 instant]} — headless
 *       offscreen render of one frame to a PNG (verification/golden lever
 *       using the exact display render path);</li>
 *   <li>{@code --snap <file>} — capture the current default screen to a PNG
 *       via {@link java.awt.Robot} (no external screenshot tooling needed on
 *       the kiosk host).</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, AWTException {
        if (args.length >= 2 && "--shot".equals(args[0])) {
            runShot(args);
            return;
        }
        if (args.length >= 2 && "--snap".equals(args[0])) {
            runSnap(args[1]);
            return;
        }
        SwingUtilities.invokeLater(Main::runKiosk);
    }

    // ------------------------------------------------------------------
    // Screen-capture mode (kiosk verification without external tooling)
    // ------------------------------------------------------------------

    private static void runSnap(String outPath) throws IOException, AWTException {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        BufferedImage capture = new Robot(device).createScreenCapture(bounds);
        File out = new File(outPath);
        ImageIO.write(capture, "png", out);
        System.out.println("snap=" + out.getAbsolutePath()
                + " " + capture.getWidth() + "x" + capture.getHeight());
    }

    // ------------------------------------------------------------------
    // Headless single-frame mode (verification lever)
    // ------------------------------------------------------------------

    private static void runShot(String[] args) throws IOException {
        File out = new File(args[1]);
        int width = args.length >= 4 ? Integer.parseInt(args[2]) : 1920;
        int height = args.length >= 4 ? Integer.parseInt(args[3]) : 1080;
        ZonedDateTime now = args.length >= 5
                ? ZonedDateTime.parse(args[4])
                : ZonedDateTime.now();

        Runtime rt = new Runtime(configDir());
        ClockConfig config = ClockConfig.fromExternal(rt.store.current());
        ClockRenderer renderer = new ClockRenderer(config);
        TimeSource time = TimeSource.fixed(now);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        int[] shift = burnInTranslation(config, now);
        g.translate(shift[0], shift[1]);
        Canvas canvas = new Canvas();
        canvas.bind(g, width, height);
        renderer.render(canvas, width, height, time.now());
        g.dispose();
        ImageIO.write(image, "png", out);
        System.out.println("shot=" + out.getAbsolutePath() + " " + width + "x" + height);
    }

    // ------------------------------------------------------------------
    // Kiosk mode
    // ------------------------------------------------------------------

    private static void runKiosk() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("uncaught on " + t + ": " + e);
            e.printStackTrace();
        });
        Runtime rt;
        try {
            rt = new Runtime(configDir());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize config dir", e);
        }
        ClockConfig config = ClockConfig.fromExternal(rt.store.current());
        ClockRenderer[] renderer = {new ClockRenderer(config)};

        Canvas canvas = new Canvas();
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                ZonedDateTime now = rt.time.now();
                // Same whole-composition burn-in shift as Android ClockView.onDraw;
                // the background fill inside render() covers the shift.
                int[] shift = burnInTranslation(renderer[0].config(), now);
                g.translate(shift[0], shift[1]);
                canvas.bind(g, getWidth(), getHeight());
                renderer[0].render(canvas, getWidth(), getHeight(), now);
                g.dispose();
            }
        };
        panel.setOpaque(true);
        panel.setBackground(Color.BLACK);

        JFrame frame = new JFrame("tx10-clock");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Any sliver the AWT window may not cover (X11 fullscreen quirks) must
        // read as part of the clock, i.e. black.
        frame.getContentPane().setBackground(Color.BLACK);
        frame.setBackground(Color.BLACK);
        frame.setUndecorated(true);
        frame.setContentPane(panel);
        frame.setCursor(blankCursor());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });

        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        device.setFullScreenWindow(frame);
        if (device.getFullScreenWindow() != frame) {
            // Exclusive fullscreen unavailable: fall back to a maximized window.
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
        }
        // Force exact screen coverage: X11 AWT fullscreen may leave edge
        // slivers if the window is laid out from stale bounds.
        frame.setBounds(device.getDefaultConfiguration().getBounds());
        frame.validate();
        panel.revalidate();
        System.out.println("kiosk: fullscreen=" + (device.getFullScreenWindow() == frame)
                + " frame=" + frame.getBounds() + " panel=" + panel.getSize());

        // One tick per second: hands/seconds advance, config polled, status written.
        new Timer(1000, e -> {
            try {
                long mtime = rt.store.configFile().lastModified();
                if (mtime != rt.lastConfigMtime) {
                    rt.lastConfigMtime = mtime;
                    ExternalConfig external = rt.store.reload();
                    ClockConfig next = ClockConfig.fromExternal(external);
                    rt.time = timeSourceFor(external);
                    renderer[0] = new ClockRenderer(next);
                    publishStatus(rt);
                }
            } catch (RuntimeException ignored) {
                // Keep the last known good frame; the next tick retries.
            }
            panel.repaint();
        }).start();

        publishStatus(rt);
        panel.repaint();
    }

    // ------------------------------------------------------------------
    // Shared runtime state
    // ------------------------------------------------------------------

    /** Per-process store + time source with Android-equivalent contracts. */
    private static final class Runtime {
        final ConfigStore store;
        TimeSource time;
        long lastConfigMtime = -1;

        Runtime(File dir) throws IOException {
            store = new ConfigStore(dir);
            lastConfigMtime = store.configFile().lastModified();
            time = timeSourceFor(store.current());
        }
    }

    private static void publishStatus(Runtime rt) {
        try {
            rt.store.writeStatus(false);
        } catch (IOException ignored) {
            // status.json is a verifier nicety; the display must keep running.
        }
    }

    private static TimeSource timeSourceFor(ExternalConfig config) {
        ZoneId zone = config.resolveZone(ZoneId.systemDefault());
        return TimeSource.systemInZone(zone);
    }
    /**
     * Same bounded burn-in translation as Android {@code ClockView
     * .burnInTranslation}: (0,0) when disabled, otherwise the deterministic
     * {@link BurnInOffset} per-axis value.
     */
    private static int[] burnInTranslation(ClockConfig config, ZonedDateTime now) {
        if (!config.burnInEnabled || config.burnInMaxShiftPx <= 0) {
            return new int[]{0, 0};
        }
        BurnInOffset offset = BurnInOffset.at(now, config.burnInMaxShiftPx);
        return new int[]{offset.x, offset.y};
    }

    private static File configDir() {
        String env = System.getenv("TX10CLOCK_CONFIG_DIR");
        File dir = env != null && !env.isEmpty()
                ? new File(env)
                : new File(System.getProperty("user.home"), ".tx10-clock");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create config dir " + dir);
        }
        return dir;
    }

    private static Cursor blankCursor() {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadless()) {
            return Cursor.getDefaultCursor();
        }
        Image image = new BufferedImage(1, 1, Transparency.TRANSLUCENT);
        return Toolkit.getDefaultToolkit().createCustomCursor(image, new Point(0, 0), "blank");
    }
}
