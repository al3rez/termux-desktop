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

        // Powerline: " A " red segment, then E0B0 in column 3 drawn red-on-blue.
        // The separator cell's left edge must be red at top AND bottom (full height).
        int x = 3 * cw + 1;
        // Sample at quarter heights where the triangle has real width (its top
        // and bottom corners are 1px slivers that antialiasing blends away).
        int upper = pl.getRGB(x, ch / 4);
        int lower = pl.getRGB(x, ch - 1 - ch / 4);
        assertTrue(red(upper) > 100 && red(upper) > 2 * blue(upper), "E0B0 upper-left should be red, got " + hex(upper));
        assertTrue(red(lower) > 100 && red(lower) > 2 * blue(lower), "E0B0 lower-left should be red, got " + hex(lower));
        // Apex reaches the right side of the cell at mid height.
        int apex = pl.getRGB(3 * cw + cw - 2, ch / 2);
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
