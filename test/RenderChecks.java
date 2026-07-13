import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Pixel assertions for the images produced by RenderHarness (cell size 13x24
 * with the default 20pt logical Monospaced font used by run-tests.sh).
 */
public class RenderChecks {
    public static void main(String[] args) throws Exception {
        BufferedImage pl = ImageIO.read(new File(args[0]));
        BufferedImage icons = ImageIO.read(new File(args[1]));
        int cw = Integer.parseInt(args[2]), ch = Integer.parseInt(args[3]);

        // Powerline: " A " red-bg segment, then E0B0 drawn red-on-blue. The
        // triangle's left-edge inked extent must match the segment background's
        // vertical extent (offset-agnostic: both are located by scanning).
        int segX = 1 * cw + 1, sepX = 3 * cw;  // sepX: triangle base column, its only full-height extent
        int segTop = -1, segBot = -1, triTop = -1, triBot = -1;
        for (int y = 0; y < pl.getHeight(); y++) {
            if (red(pl.getRGB(segX, y)) > 120 && blue(pl.getRGB(segX, y)) < 100) { if (segTop < 0) segTop = y; segBot = y; }
            if (red(pl.getRGB(sepX, y)) > 120 && blue(pl.getRGB(sepX, y)) < 100) { if (triTop < 0) triTop = y; triBot = y; }
        }
        assertTrue(segTop >= 0, "segment background not found");
        assertTrue(triTop >= 0, "E0B0 separator rendered nothing");
        assertTrue(Math.abs(segTop - triTop) <= 1 && Math.abs(segBot - triBot) <= 1,
            "E0B0 misaligned with segment bg: seg " + segTop + ".." + segBot + " vs tri " + triTop + ".." + triBot);
        int apex = pl.getRGB(3 * cw + cw - 2, (triTop + triBot) / 2);
        assertTrue(red(apex) > 100, "E0B0 apex should reach cell right edge, got " + hex(apex));

        // Icon aspect: branch icon (E0A0) in cell 0. Find inked horizontal extent.
        int minX = Integer.MAX_VALUE, maxX = -1;
        for (int px = 0; px < cw; px++)
            for (int py = 0; py < ch; py++)
                if ((icons.getRGB(px, py) & 0xFFFFFF) != 0) {
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                }
        assertTrue(maxX >= 0, "branch icon rendered nothing");
        int inked = maxX - minX + 1;
        assertTrue(inked >= cw / 2, "branch icon squeezed: inked width " + inked + " of cell " + cw);

        System.out.println("checks ok (icon inked width " + inked + "/" + cw + ")");
    }

    static int red(int rgb) { return (rgb >> 16) & 0xFF; }
    static int blue(int rgb) { return rgb & 0xFF; }
    static String hex(int rgb) { return Integer.toHexString(rgb & 0xFFFFFF); }

    static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            System.err.println("FAIL: " + msg);
            System.exit(1);
        }
    }
}
