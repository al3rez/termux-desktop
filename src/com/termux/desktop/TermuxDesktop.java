package com.termux.desktop;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Swing shell around Termux's TerminalEmulator + the Java2D port of its renderer. */
public final class TermuxDesktop extends JComponent implements TerminalSessionClient {

    static final String DEFAULT_FONT_PATH = System.getProperty("user.home") + "/.local/share/fonts/oplus/OplusOSUI-Regular.ttf";
    static final int DEFAULT_FONT_SIZE = 20;

    private final long startupNanos;
    private boolean startupReported;
    private Java2DRenderer renderer;
    private TerminalSession session;
    private int textSize;
    private final Font baseFont;
    private JFrame frame;
    private final Timer synchronizedOutputTimer;
    private boolean synchronizedOutputRepaintPending;
    private int scrollOffset;
    private int mouseButtonDown = -1;

    public static void main(String[] args) throws Exception {
        long startupNanos = System.nanoTime();
        String fontPath = args.length > 0 ? args[0] : DEFAULT_FONT_PATH;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_FONT_SIZE;
        Font font = loadFont(fontPath, size);

        SwingUtilities.invokeLater(() -> {
            try {
                openWindow(font, size, startupNanos);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
        });
    }

    TermuxDesktop(Font font, int size) {
        this(font, size, 0);
    }

    private TermuxDesktop(Font font, int size, long startupNanos) {
        baseFont = font;
        textSize = size;
        this.startupNanos = startupNanos;
        renderer = new Java2DRenderer(textSize, baseFont);
        setPreferredSize(new Dimension((int) (renderer.getFontWidth() * 90), renderer.getFontLineSpacing() * 28));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setBackground(Color.BLACK);
        synchronizedOutputTimer = new Timer(150, e -> {
            if (synchronizedOutputRepaintPending) {
                synchronizedOutputRepaintPending = false;
                repaint();
            }
        });
        synchronizedOutputTimer.setRepeats(false);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (c == KeyEvent.CHAR_UNDEFINED || e.isControlDown() || e.isAltDown()) return;
                if (c == '\n' || c == '\b' || c == '\t' || c == 27 || c == 127) return; // handled in keyPressed
                session.writeString(String.valueOf(c));
            }
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeToComponent();
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TerminalEmulator emulator = emulator();
                int button = terminalMouseButton(e.getButton());
                if (emulator == null || button < 0) return;
                if (emulator.isMouseTrackingActive()) {
                    mouseButtonDown = button;
                    emulator.sendMouseEvent(button, mouseColumn(e), mouseRow(e), true);
                } else if (e.getButton() == MouseEvent.BUTTON2) {
                    onPasteTextFromClipboard(session);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                TerminalEmulator emulator = emulator();
                int button = mouseButtonDown >= 0 ? mouseButtonDown : terminalMouseButton(e.getButton());
                if (emulator != null && emulator.isMouseTrackingActive() && button >= 0)
                    emulator.sendMouseEvent(button, mouseColumn(e), mouseRow(e), false);
                mouseButtonDown = -1;
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                TerminalEmulator emulator = emulator();
                if (emulator == null || !emulator.isMouseTrackingActive() || mouseButtonDown < 0) return;
                // Bit 5 is the xterm motion flag; the vendored emulator accepts
                // it for all three buttons, while 32 remains its left-motion constant.
                emulator.sendMouseEvent(mouseButtonDown | TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED,
                    mouseColumn(e), mouseRow(e), true);
            }
        });

        addMouseWheelListener(this::handleMouseWheel);
    }

    static Font loadFont(String fontPath, int size) throws Exception {
        File fontFile = new File(fontPath);
        return fontFile.exists()
            ? Font.createFont(Font.TRUETYPE_FONT, fontFile)
            : new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    static JFrame openWindow(Font font, int size) throws Exception {
        return openWindow(font, size, 0);
    }

    private static JFrame openWindow(Font font, int size, long startupNanos) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Terminal windows must be opened on the EDT");
        }

        TermuxDesktop term = new TermuxDesktop(font, size, startupNanos);
        JFrame frame = new JFrame("termux-desktop");
        java.awt.Image icon = loadAppIcon();
        if (icon != null) frame.setIconImage(icon);
        term.frame = frame;
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                term.finishSession();
            }
        });
        frame.add(term);
        frame.pack();
        term.startShell();
        frame.setVisible(true);
        term.requestFocusInWindow();
        return frame;
    }

    private static java.awt.Image appIcon;

    /** The Termux launcher icon: from the classpath (jarred out/ dir), or the installed hicolor copy. */
    private static java.awt.Image loadAppIcon() {
        if (appIcon != null) return appIcon;
        try {
            java.net.URL res = TermuxDesktop.class.getResource("/termux-desktop.png");
            if (res != null) return appIcon = javax.imageio.ImageIO.read(res);
            File installed = new File(System.getProperty("user.home") + "/.local/share/icons/hicolor/192x192/apps/termux-desktop.png");
            if (installed.exists()) return appIcon = javax.imageio.ImageIO.read(installed);
        } catch (Exception ignored) {
        }
        return null;
    }

    private void finishSession() {
        if (session != null) session.finish();
    }

    void startShell() throws Exception {
        session = new TerminalSession(this);
        String shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        session.initializeEmulator(cols(), rows(), (int) renderer.getFontWidth(), renderer.getFontLineSpacing(),
            new String[]{shell, "-l"});
    }

    private int cols() { return Math.max(4, (int) (getWidth() / renderer.getFontWidth())); }
    private int rows() { return Math.max(4, getHeight() / renderer.getFontLineSpacing()); }

    private void resizeToComponent() {
        if (session != null && session.mEmulator != null) {
            session.updateSize(cols(), rows(), (int) renderer.getFontWidth(), renderer.getFontLineSpacing());
            repaint();
        }
    }

    private void changeFontSize(int delta) {
        textSize = Math.max(6, textSize + delta);
        renderer = new Java2DRenderer(textSize, baseFont);
        resizeToComponent();
    }

    private TerminalEmulator emulator() {
        return session == null ? null : session.mEmulator;
    }

    private int mouseColumn(MouseEvent e) {
        return (int) Math.floor(e.getX() / renderer.getFontWidth()) + 1;
    }

    private int mouseRow(MouseEvent e) {
        return e.getY() / renderer.getFontLineSpacing() + 1;
    }

    private static int terminalMouseButton(int awtButton) {
        switch (awtButton) {
            case MouseEvent.BUTTON1: return TerminalEmulator.MOUSE_LEFT_BUTTON;
            case MouseEvent.BUTTON2: return 1;
            case MouseEvent.BUTTON3: return 2;
            default: return -1;
        }
    }

    private void handleMouseWheel(MouseWheelEvent e) {
        TerminalEmulator emulator = emulator();
        if (emulator == null || e.getWheelRotation() == 0) return;

        int rotation = e.getWheelRotation();
        int button = rotation < 0 ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON;
        if (emulator.isMouseTrackingActive()) {
            for (int i = 0; i < Math.abs(rotation); i++)
                emulator.sendMouseEvent(button, mouseColumn(e), mouseRow(e), true);
            return;
        }

        if (emulator.isAlternateBufferActive()) {
            int keyCode = rotation < 0 ? android.view.KeyEvent.KEYCODE_DPAD_UP : android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            String code = KeyHandler.getCode(keyCode, 0, emulator.isCursorKeysApplicationMode(), emulator.isKeypadApplicationMode());
            if (code != null) {
                for (int i = 0; i < Math.abs(rotation); i++) session.writeString(code);
            }
            return;
        }

        int oldOffset = scrollOffset;
        int maximumOffset = emulator.getScreen().getActiveTranscriptRows();
        scrollOffset = Math.max(0, Math.min(maximumOffset, scrollOffset - rotation));
        if (scrollOffset != oldOffset) repaint();
    }

    private void armSynchronizedOutputRepaint() {
        synchronizedOutputRepaintPending = true;
        synchronizedOutputTimer.restart();
    }

    private void requestRepaint(TerminalSession s) {
        if (s != null && s.mEmulator != null && s.mEmulator.isSynchronizedOutput()) {
            armSynchronizedOutputRepaint();
        } else {
            repaint();
        }
    }

    private void handleKey(KeyEvent e) {
        if (session == null) return;

        // ctrl+shift+C/V copy-paste, ctrl+alt +/- zoom (desktop conveniences)
        if (e.isControlDown() && e.isAltDown() && (e.getKeyCode() == KeyEvent.VK_EQUALS || e.getKeyCode() == KeyEvent.VK_PLUS)) { changeFontSize(2); return; }
        if (e.isControlDown() && e.isAltDown() && e.getKeyCode() == KeyEvent.VK_MINUS) { changeFontSize(-2); return; }
        if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_V) { onPasteTextFromClipboard(session); return; }

        if (e.isAltDown() && !e.isControlDown()) {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                byte[] charBytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                byte[] altBytes = new byte[charBytes.length + 1];
                altBytes[0] = 0x1b;
                System.arraycopy(charBytes, 0, altBytes, 1, charBytes.length);
                session.write(altBytes, 0, altBytes.length);
                return;
            }
        }

        int androidKey = mapKey(e.getKeyCode());
        if (androidKey != -1) {
            int keyMode = 0;
            if (e.isControlDown()) keyMode |= KeyHandler.KEYMOD_CTRL;
            if (e.isAltDown()) keyMode |= KeyHandler.KEYMOD_ALT;
            if (e.isShiftDown()) keyMode |= KeyHandler.KEYMOD_SHIFT;
            TerminalEmulator em = session.mEmulator;
            String code = KeyHandler.getCode(androidKey, keyMode, em.isCursorKeysApplicationMode(), em.isKeypadApplicationMode());
            if (code != null) {
                session.writeString(code);
                return;
            }
        }

        if (e.isControlDown()) {
            int c = e.getKeyCode();
            if (c >= KeyEvent.VK_A && c <= KeyEvent.VK_Z) {
                session.write(new byte[]{(byte) (c - KeyEvent.VK_A + 1)}, 0, 1);
            } else if (c == KeyEvent.VK_SPACE) {
                session.write(new byte[]{0}, 0, 1);
            } else if (c == KeyEvent.VK_OPEN_BRACKET || e.getKeyChar() == '[') {
                session.write(new byte[]{0x1b}, 0, 1);
            } else if (c == KeyEvent.VK_BACK_SLASH || e.getKeyChar() == '\\') {
                session.write(new byte[]{0x1c}, 0, 1);
            } else if (c == KeyEvent.VK_CLOSE_BRACKET || e.getKeyChar() == ']') {
                session.write(new byte[]{0x1d}, 0, 1);
            } else if (c == KeyEvent.VK_CIRCUMFLEX || e.getKeyChar() == '^') {
                session.write(new byte[]{0x1e}, 0, 1);
            } else if (c == KeyEvent.VK_UNDERSCORE || e.getKeyChar() == '_') {
                session.write(new byte[]{0x1f}, 0, 1);
            }
        }
    }

    private static int mapKey(int vk) {
        switch (vk) {
            case KeyEvent.VK_ENTER: return android.view.KeyEvent.KEYCODE_ENTER;
            case KeyEvent.VK_BACK_SPACE: return android.view.KeyEvent.KEYCODE_DEL;
            case KeyEvent.VK_TAB: return android.view.KeyEvent.KEYCODE_TAB;
            case KeyEvent.VK_ESCAPE: return android.view.KeyEvent.KEYCODE_ESCAPE;
            case KeyEvent.VK_UP: return android.view.KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.VK_DOWN: return android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.VK_LEFT: return android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.VK_RIGHT: return android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.VK_HOME: return android.view.KeyEvent.KEYCODE_MOVE_HOME;
            case KeyEvent.VK_END: return android.view.KeyEvent.KEYCODE_MOVE_END;
            case KeyEvent.VK_PAGE_UP: return android.view.KeyEvent.KEYCODE_PAGE_UP;
            case KeyEvent.VK_PAGE_DOWN: return android.view.KeyEvent.KEYCODE_PAGE_DOWN;
            case KeyEvent.VK_DELETE: return android.view.KeyEvent.KEYCODE_FORWARD_DEL;
            case KeyEvent.VK_INSERT: return android.view.KeyEvent.KEYCODE_INSERT;
            case KeyEvent.VK_F1: return android.view.KeyEvent.KEYCODE_F1;
            case KeyEvent.VK_F2: return android.view.KeyEvent.KEYCODE_F2;
            case KeyEvent.VK_F3: return android.view.KeyEvent.KEYCODE_F3;
            case KeyEvent.VK_F4: return android.view.KeyEvent.KEYCODE_F4;
            case KeyEvent.VK_F5: return android.view.KeyEvent.KEYCODE_F5;
            case KeyEvent.VK_F6: return android.view.KeyEvent.KEYCODE_F6;
            case KeyEvent.VK_F7: return android.view.KeyEvent.KEYCODE_F7;
            case KeyEvent.VK_F8: return android.view.KeyEvent.KEYCODE_F8;
            case KeyEvent.VK_F9: return android.view.KeyEvent.KEYCODE_F9;
            case KeyEvent.VK_F10: return android.view.KeyEvent.KEYCODE_F10;
            case KeyEvent.VK_F11: return android.view.KeyEvent.KEYCODE_F11;
            case KeyEvent.VK_F12: return android.view.KeyEvent.KEYCODE_F12;
            default: return -1;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!startupReported && startupNanos != 0) {
            startupReported = true;
            System.err.println("startup-ms: " + ((System.nanoTime() - startupNanos) / 1_000_000L));
        }
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        if (session == null || session.mEmulator == null) return;
        g2.setColor(new Color(session.mEmulator.mColors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND], true));
        g2.fillRect(0, 0, getWidth(), getHeight());
        int maximumOffset = session.mEmulator.isAlternateBufferActive() ? 0 : session.mEmulator.getScreen().getActiveTranscriptRows();
        int topRow = session.mEmulator.isAlternateBufferActive() ? 0 : -Math.min(scrollOffset, maximumOffset);
        renderer.render(session.mEmulator, g2, topRow, -1, -1, -1, -1);
    }

    // -- TerminalSessionClient --

    @Override public void onTextChanged(TerminalSession s) {
        if (s.mEmulator != null && s.mEmulator.isAlternateBufferActive()) scrollOffset = 0;
        if (s.mEmulator != null && s.mEmulator.isSynchronizedOutput()) {
            armSynchronizedOutputRepaint();
            return;
        }
        synchronizedOutputRepaintPending = false;
        synchronizedOutputTimer.stop();
        repaint();
    }
    @Override public void onTitleChanged(TerminalSession s) {
        JFrame f = (JFrame) SwingUtilities.getWindowAncestor(this);
        if (f != null && s.mEmulator != null) f.setTitle(String.valueOf(s.mEmulator.getTitle()));
    }
    @Override public void onSessionFinished(TerminalSession s) {
        if (frame != null) frame.dispose();
    }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
        Thread clipboardWriter = new Thread(() -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            } catch (Exception ignored) {
            }
        }, "clipboard-writer");
        clipboardWriter.setDaemon(true);
        clipboardWriter.start();
    }
    @Override public void onPasteTextFromClipboard(TerminalSession s) {
        Thread clipboardReader = new Thread(() -> {
            try {
                String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                if (text != null && s.mEmulator != null) s.mEmulator.paste(text);
            } catch (Exception ignored) {
            }
        }, "clipboard-reader");
        clipboardReader.setDaemon(true);
        clipboardReader.start();
    }
    @Override public void onBell(TerminalSession s) { Toolkit.getDefaultToolkit().beep(); }
    @Override public void onColorsChanged(TerminalSession s) { requestRepaint(s); }
    @Override public void onTerminalCursorStateChange(boolean state) { requestRepaint(session); }
    @Override public void setTerminalShellPid(TerminalSession s, int pid) { }
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) { System.err.println(tag + ": " + message); }
    @Override public void logWarn(String tag, String message) { }
    @Override public void logInfo(String tag, String message) { }
    @Override public void logDebug(String tag, String message) { }
    @Override public void logVerbose(String tag, String message) { }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { e.printStackTrace(); }
    @Override public void logStackTrace(String tag, Exception e) { e.printStackTrace(); }
}
