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
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
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
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private boolean selectionMouseDown;
    private boolean selectionDragged;
    private boolean selectionStartedByMultiClick;
    private int selectionAnchorX = -1;
    private int selectionAnchorY = -1;
    private int selectionX1 = -1;
    private int selectionY1 = -1;
    private int selectionX2 = -1;
    private int selectionY2 = -1;
    private static final ExecutorService CLIPBOARD_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clipboard-io");
        thread.setDaemon(true);
        return thread;
    });

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
                if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    if (hasSelection()) copySelection(emulator());
                    e.consume();
                    return;
                }
                clearSelection();
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

                boolean mouseTracking = emulator.isMouseTrackingActive();
                if (e.getButton() == MouseEvent.BUTTON1 && (!mouseTracking || e.isShiftDown())) {
                    beginSelection(e, emulator);
                } else if (e.getButton() == MouseEvent.BUTTON2 && (!mouseTracking || e.isShiftDown())) {
                    clearSelection();
                    pasteFromClipboards(session, true);
                } else if (mouseTracking) {
                    clearSelection();
                    mouseButtonDown = button;
                    emulator.sendMouseEvent(button, mouseColumn(e), mouseRow(e), true);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                TerminalEmulator emulator = emulator();
                if (selectionMouseDown) {
                    // A double/triple click already installed its complete word/line
                    // rectangle. Preserve it unless the pointer was actually dragged.
                    if (!selectionStartedByMultiClick || selectionDragged) updateSelection(e, emulator);
                    if (selectionDragged || selectionStartedByMultiClick) {
                        copySelection(emulator);
                    } else {
                        clearSelection();
                    }
                    selectionMouseDown = false;
                    selectionDragged = false;
                    selectionStartedByMultiClick = false;
                } else if (emulator != null && mouseButtonDown >= 0 && emulator.isMouseTrackingActive()) {
                    emulator.sendMouseEvent(mouseButtonDown, mouseColumn(e), mouseRow(e), false);
                }
                mouseButtonDown = -1;
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                TerminalEmulator emulator = emulator();
                if (selectionMouseDown) {
                    updateSelection(e, emulator);
                    return;
                }
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
        List<Image> icons = loadAppIcons();
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
        // The X11 peer is created by pack(). Applying the icon now, and again
        // after mapping, makes the _NET_WM_ICON update reliable under XWayland.
        if (!icons.isEmpty()) frame.setIconImages(icons);
        term.startShell();
        frame.setVisible(true);
        if (!icons.isEmpty()) frame.setIconImages(icons);
        term.requestFocusInWindow();
        return frame;
    }

    private static java.awt.Image appIcon;
    private static List<Image> appIcons;

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

    /** Supply the X11 peer with several concrete sizes instead of one scaled source image. */
    private static List<Image> loadAppIcons() {
        if (appIcons != null) return appIcons;
        Image source = loadAppIcon();
        if (source == null) return Collections.emptyList();

        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 48, 64, 96, 128, 192}) {
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
            graphics.dispose();
            icons.add(scaled);
        }
        return appIcons = Collections.unmodifiableList(icons);
    }

    private void finishSession() {
        if (session != null) session.finish();
    }

    void startShell() throws Exception {
        TerminalSession newSession = new TerminalSession(this);
        session = newSession;
        String shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        try {
            newSession.initializeEmulator(cols(), rows(), (int) renderer.getFontWidth(), renderer.getFontLineSpacing(),
                new String[]{shell, "-l"});
        } catch (Exception e) {
            newSession.finish();
            throw e;
        }
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
        return (int) Math.floor((e.getY() - renderer.getFontLineSpacingAndAscent()) / (double) renderer.getFontLineSpacing()) + 1;
    }

    private int selectionColumn(MouseEvent e, TerminalEmulator emulator) {
        int column = (int) Math.floor(e.getX() / (double) renderer.getFontWidth());
        return Math.max(0, Math.min(emulator.mColumns - 1, column));
    }

    private int selectionRow(MouseEvent e, TerminalEmulator emulator) {
        int row = (int) Math.floor((e.getY() - renderer.getFontLineSpacingAndAscent())
            / (double) renderer.getFontLineSpacing()) + terminalTopRow(emulator);
        int minimum = -emulator.getScreen().getActiveTranscriptRows();
        return Math.max(minimum, Math.min(emulator.mRows - 1, row));
    }

    private int terminalTopRow(TerminalEmulator emulator) {
        return emulator.isAlternateBufferActive() ? 0
            : -Math.min(scrollOffset, emulator.getScreen().getActiveTranscriptRows());
    }

    private void beginSelection(MouseEvent e, TerminalEmulator emulator) {
        clearSelection();
        selectionMouseDown = true;
        selectionDragged = false;
        selectionStartedByMultiClick = false;
        selectionAnchorX = selectionColumn(e, emulator);
        selectionAnchorY = selectionRow(e, emulator);

        if (e.getClickCount() >= 3) {
            selectLine(selectionAnchorY, emulator);
            selectionStartedByMultiClick = hasSelection();
        } else if (e.getClickCount() == 2) {
            selectWord(selectionAnchorX, selectionAnchorY, emulator);
            selectionStartedByMultiClick = hasSelection();
        } else {
            setSelection(selectionAnchorX, selectionAnchorY, selectionAnchorX, selectionAnchorY);
        }
        repaint();
    }

    private void updateSelection(MouseEvent e, TerminalEmulator emulator) {
        if (!selectionMouseDown || emulator == null) return;
        int x = selectionColumn(e, emulator);
        int y = selectionRow(e, emulator);
        if (x != selectionAnchorX || y != selectionAnchorY) selectionDragged = true;
        setSelection(selectionAnchorX, selectionAnchorY, x, y);
        repaint();
    }

    /** Set inclusive endpoints in the same coordinate system as TerminalBuffer and TerminalRenderer. */
    private void setSelection(int x1, int y1, int x2, int y2) {
        if (y1 < y2 || (y1 == y2 && x1 <= x2)) {
            selectionX1 = x1;
            selectionY1 = y1;
            selectionX2 = x2;
            selectionY2 = y2;
        } else {
            selectionX1 = x2;
            selectionY1 = y2;
            selectionX2 = x1;
            selectionY2 = y1;
        }
    }

    private void selectLine(int row, TerminalEmulator emulator) {
        setSelection(0, row, emulator.mColumns - 1, row);
    }

    private void selectWord(int column, int row, TerminalEmulator emulator) {
        String clicked = cellText(emulator, column, row);
        if (clicked.isEmpty() || isWhitespace(clicked)) {
            clearSelection();
            return;
        }

        boolean clickedDelimiter = isWordDelimiter(clicked);
        int left = column;
        int right = column;
        if (!clickedDelimiter) {
            while (left > 0 && !isWordDelimiter(cellText(emulator, left - 1, row))) left--;
            while (right < emulator.mColumns - 1 && !isWordDelimiter(cellText(emulator, right + 1, row))) right++;
        }
        setSelection(left, row, right, row);
    }

    private static String cellText(TerminalEmulator emulator, int column, int row) {
        return emulator.getScreen().getSelectedText(column, row, column, row);
    }

    private static boolean isWhitespace(String text) {
        return text.isEmpty() || Character.isWhitespace(text.codePointAt(0));
    }

    /** Word selection treats whitespace and punctuation as boundaries while retaining underscores in words. */
    private static boolean isWordDelimiter(String text) {
        if (text.isEmpty()) return true;
        int codePoint = text.codePointAt(0);
        if (Character.isWhitespace(codePoint)) return true;
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') return false;
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION
            || ",.;:!?()[]{}<>\"'`~!@#$%^&*+-=/\\|".indexOf(codePoint) >= 0;
    }

    private boolean hasSelection() {
        return selectionX1 >= 0 && selectionY1 >= 0 - (emulator() == null ? 0 : emulator().getScreen().getActiveTranscriptRows())
            && selectionX2 >= selectionX1 && selectionY2 >= selectionY1;
    }

    private void clearSelection() {
        if (!hasSelection() && !selectionMouseDown) return;
        selectionAnchorX = selectionAnchorY = -1;
        selectionX1 = selectionY1 = selectionX2 = selectionY2 = -1;
        selectionMouseDown = false;
        selectionDragged = false;
        selectionStartedByMultiClick = false;
        repaint();
    }

    private void copySelection(TerminalEmulator emulator) {
        if (emulator == null || !hasSelection()) {
            clearSelection();
            return;
        }
        String text = emulator.getScreen().getSelectedText(selectionX1, selectionY1, selectionX2, selectionY2);
        if (text == null || text.isEmpty()) {
            clearSelection();
            return;
        }
        writeTextToClipboards(text);
    }

    private static void writeTextToClipboards(String text) {
        queueClipboardWrite(text, true);
    }

    private static void writeTextToClipboard(String text) {
        queueClipboardWrite(text, false);
    }

    private static void queueClipboardWrite(String text, boolean includePrimary) {
        if (text == null) return;
        CLIPBOARD_IO.execute(() -> {
            final Toolkit toolkit;
            try {
                toolkit = Toolkit.getDefaultToolkit();
            } catch (Exception e) {
                reportClipboardFailure("initialize", e);
                return;
            }

            try {
                setClipboardText(toolkit.getSystemClipboard(), text);
            } catch (Exception e) {
                reportClipboardFailure("system clipboard write", e);
            }
            if (includePrimary) {
                try {
                    Clipboard primary = toolkit.getSystemSelection();
                    if (primary != null) setClipboardText(primary, text);
                } catch (Exception e) {
                    reportClipboardFailure("PRIMARY write", e);
                }
            }
        });
    }

    private static void setClipboardText(Clipboard clipboard, String text) {
        if (clipboard == null) return;
        StringSelection selection = new StringSelection(text);
        clipboard.setContents(selection, selection);
    }

    private static void reportClipboardFailure(String operation, Exception e) {
        System.err.println("clipboard " + operation + " failed: " + e);
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
        renderer.render(session.mEmulator, g2, topRow, selectionY1, selectionY2, selectionX1, selectionX2);
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
        if (s != null) s.finish();
        if (frame != null) frame.dispose();
    }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
        writeTextToClipboard(text);
    }
    @Override public void onPasteTextFromClipboard(TerminalSession s) {
        pasteFromClipboards(s, false);
    }

    private static void pasteFromClipboards(TerminalSession s, boolean primaryFirst) {
        if (s == null) return;
        CLIPBOARD_IO.execute(() -> {
            String text = null;
            try {
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                Clipboard primary = null;
                Clipboard clipboard = null;
                try {
                    primary = toolkit.getSystemSelection();
                } catch (Exception ignored) {
                }
                try {
                    clipboard = toolkit.getSystemClipboard();
                } catch (Exception ignored) {
                }
                text = primaryFirst ? readClipboardText(primary) : readClipboardText(clipboard);
                if (text == null) text = primaryFirst ? readClipboardText(clipboard) : readClipboardText(primary);
            } catch (Exception e) {
                reportClipboardFailure("read", e);
            }
            if (text != null) {
                final String pasteText = text;
                SwingUtilities.invokeLater(() -> {
                    if (s.mEmulator != null) s.mEmulator.paste(pasteText);
                });
            }
        });
    }

    private static String readClipboardText(Clipboard clipboard) {
        if (clipboard == null) return null;
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (Exception ignored) {
            return null;
        }
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
