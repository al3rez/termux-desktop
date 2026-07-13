import com.termux.desktop.Java2DRenderer;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Pixel assertions for the mixed emoji line rendered by RenderHarness. */
public class EmojiRenderChecks {
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        int cw = Integer.parseInt(args[1]);
        int ch = Integer.parseInt(args[2]);

        // A 😀 B ❤️ C 🇹🇭 D, with emoji clusters normalized to two visual cells.
        assertInk(image, cw, ch, 2, 3, "grinning face");
        assertInk(image, cw, ch, 7, 8, "heart variation sequence");
        assertInk(image, cw, ch, 12, 13, "Thailand flag");

        // The heart's VS16 and the flag's two regional indicators must not
        // make the final D drift left; the preceding cell is a space.
        assertTrue(cellInk(image, cw, ch, 15) > 0, "following D is not at column 15");
        assertTrue(cellInk(image, cw, ch, 14) == 0, "following D drifted into column 14");

        int colored = coloredPixels(image, cw * 2, cw * 4, 0, ch);
        Font base = Font.createFont(Font.TRUETYPE_FONT, new File(args[3]));
        boolean colorPath = new Java2DRenderer(Integer.parseInt(args[4]), base).isColorEmojiEnabled();
        if (colorPath) assertTrue(colored > 0, "color emoji path enabled but grinning face has no colored pixels");

        System.out.println("emoji checks ok (colorPath=" + colorPath + ", grinningColoredPixels=" + colored + ")");
    }

    private static void assertInk(BufferedImage image, int cw, int ch, int firstColumn, int lastColumn, String name) {
        int ink = 0;
        for (int column = firstColumn; column <= lastColumn; column++) ink += cellInk(image, cw, ch, column);
        assertTrue(ink > 0, name + " rendered no ink");
    }

    private static int cellInk(BufferedImage image, int cw, int ch, int column) {
        int ink = 0;
        for (int y = 0; y < ch; y++) {
            for (int x = column * cw; x < (column + 1) * cw; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) ink++;
            }
        }
        return ink;
    }

    private static int coloredPixels(BufferedImage image, int left, int right, int top, int bottom) {
        int colored = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 12) colored++;
            }
        }
        return colored;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
    }
}
