import com.termux.desktop.Java2DRenderer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Renders terminal content to a PNG for visual regression checks.
 * Usage: RenderHarness <out.png> [fontPath] [size]
 * Reads the byte stream to feed the emulator from stdin.
 */
public class RenderHarness {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        Font font = args.length > 1
            ? Font.createFont(Font.TRUETYPE_FONT, new File(args[1]))
            : new Font(Font.MONOSPACED, Font.PLAIN, 20);
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 20;

        TerminalSessionClient nc = new NullClient();
        Java2DRenderer r = new Java2DRenderer(size, font);
        int cw = (int) r.getFontWidth(), ch = r.getFontLineSpacing();
        int cols = 60, rows = 8;
        TerminalEmulator em = new TerminalEmulator(new TerminalSession(nc), cols, rows, cw, ch, 500, nc);

        byte[] data = System.in.readAllBytes();
        em.append(data, data.length);

        BufferedImage img = new BufferedImage(cw * cols, ch * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        r.render(em, g, 0, -1, -1, -1, -1);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out + " cell=" + cw + "x" + ch);
    }

    static class NullClient implements TerminalSessionClient {
        public void onTextChanged(TerminalSession s) {}
        public void onTitleChanged(TerminalSession s) {}
        public void onSessionFinished(TerminalSession s) {}
        public void onCopyTextToClipboard(TerminalSession s, String t) {}
        public void onPasteTextFromClipboard(TerminalSession s) {}
        public void onBell(TerminalSession s) {}
        public void onColorsChanged(TerminalSession s) {}
        public void onTerminalCursorStateChange(boolean b) {}
        public void setTerminalShellPid(TerminalSession s, int p) {}
        public Integer getTerminalCursorStyle() { return null; }
        public void logError(String t, String m) {}
        public void logWarn(String t, String m) {}
        public void logInfo(String t, String m) {}
        public void logDebug(String t, String m) {}
        public void logVerbose(String t, String m) {}
        public void logStackTraceWithMessage(String t, String m, Exception e) {}
        public void logStackTrace(String t, Exception e) {}
    }
}
