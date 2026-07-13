package com.termux.desktop;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Line-for-line port of com.termux.view.TerminalRenderer from android.graphics
 * (Canvas/Paint) to Java2D (Graphics2D/Font). The run-batching, wcwidth
 * mismatch detection and horizontal run scaling are Termux's algorithm
 * unchanged; only the drawing API differs.
 */
public final class Java2DRenderer {

    final int mTextSize;
    final Font mFont;
    final Font mFontBold;
    final Font mFontItalic;

    final float mFontWidth;
    final int mFontLineSpacing;
    private final int mFontAscent;
    final int mFontLineSpacingAndAscent;

    private final FontMetrics mMetrics;
    private final float[] asciiMeasures = new float[127];
    private final boolean[] asciiMeasureReady = new boolean[127];

    /**
     * Fallback fonts for glyphs the main font lacks (box drawing, nerd font
     * symbols, emoji). Android's renderer gets this for free from the system;
     * Java2D fonts do not fall back, so pick per code point via canDisplay().
     */
    private Font[] mFallbackFonts;

    public Java2DRenderer(int textSize, Font font) {
        mTextSize = textSize;
        mFont = font.deriveFont((float) textSize);
        mFontBold = mFont.deriveFont(Font.BOLD);
        mFontItalic = mFont.deriveFont(AffineTransform.getShearInstance(-0.35, 0));

        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(mFont);
        mMetrics = g.getFontMetrics();
        g.dispose();

        // android: fontSpacing = descent - ascent (ascent negative); port keeps signs
        mFontLineSpacing = (int) Math.ceil(mMetrics.getAscent() + mMetrics.getDescent() + mMetrics.getLeading());
        mFontAscent = -(int) Math.ceil(mMetrics.getAscent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = (float) mFont.getStringBounds("X", mMetrics.getFontRenderContext()).getWidth();
    }

    /** The font to draw/measure a code point with: the main font, or the first fallback that has the glyph. */
    private Font fontFor(int codePoint) {
        if (mFont.canDisplay(codePoint)) return mFont;
        for (Font f : fallbackFonts()) {
            if (f.canDisplay(codePoint)) return f;
        }
        return mFont;
    }

    private Font[] fallbackFonts() {
        if (mFallbackFonts == null) {
            java.util.List<Font> fallbacks = new java.util.ArrayList<>();
            for (String path : new String[]{
                // Symbols-only Nerd Font first: covers all NF private-use icons
                // without text glyphs that could shadow later fallbacks.
                "/usr/share/fonts/TTF/SymbolsNerdFont-Regular.ttf",
                "/usr/share/fonts/TTF/JetBrainsMonoNerdFontMono-Regular.ttf",
                System.getProperty("user.home") + "/.local/share/fonts/ArialMonospacedForSAPNerdFont-Regular.ttf",
            }) {
                try {
                    fallbacks.add(Font.createFont(Font.TRUETYPE_FONT, new java.io.File(path)).deriveFont((float) mTextSize));
                } catch (Exception ignored) {
                }
            }
            // Logical font last: the JRE composes it from fontconfig, so it covers
            // nearly everything (CJK, emoji, symbols) that the explicit ones miss.
            fallbacks.add(new Font(Font.MONOSPACED, Font.PLAIN, mTextSize));
            mFallbackFonts = fallbacks.toArray(new Font[0]);
        }
        return mFallbackFonts;
    }

    private float measure(char[] chars, int start, int count) {
        int codePoint = Character.codePointAt(chars, start);
        return (float) fontFor(codePoint).getStringBounds(chars, start, start + count, mMetrics.getFontRenderContext()).getWidth();
    }

    private float measureCodePoint(char[] chars, int start, int count, int codePoint) {
        // Geometry is drawn in cell coordinates below. Never let a font's
        // metrics make one of these cells participate in run scaling.
        if (isGeometricCodePoint(codePoint)) return WcWidth.width(codePoint) * mFontWidth;
        if (codePoint < asciiMeasures.length && asciiMeasureReady[codePoint]) return asciiMeasures[codePoint];
        float width = measure(chars, start, count);
        if (codePoint < asciiMeasures.length) {
            asciiMeasures[codePoint] = width;
            asciiMeasureReady[codePoint] = true;
        }
        return width;
    }

    public final void render(TerminalEmulator mEmulator, Graphics2D canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo) {
            canvas.setColor(new Color(palette[TextStyle.COLOR_INDEX_FOREGROUND], true));
            canvas.fillRect(0, 0, 1 << 15, 1 << 15);
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            Font lastRunFont = mFont;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);
                final boolean geometric = isGeometricCodePoint(codePoint);

                if (geometric) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFont);
                        lastRunStartColumn = -1;
                    }
                    int cursorColor = insideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                    boolean invertCursorTextColor = insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
                    drawGeometricCell(canvas, palette, column * mFontWidth, (row - topRow) * mFontLineSpacing,
                        codePointWcWidth * mFontWidth, mFontLineSpacing, codePoint, style, cursorColor, cursorShape,
                        reverseVideo || invertCursorTextColor || insideSelection);

                    column += codePointWcWidth;
                    currentCharIndex += charsForCodePoint;
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                    }
                    continue;
                }

                final float measuredCodePointWidth = measureCodePoint(line, currentCharIndex, charsForCodePoint, codePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;
                final Font fontForCodePoint = fontFor(codePoint);

                if (lastRunStartColumn < 0 || style != lastRunStyle || fontForCodePoint != lastRunFont || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFont);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunFont = fontForCodePoint;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            if (lastRunStartColumn >= 0) {
                final int columnWidthSinceLastRun = columns - lastRunStartColumn;
                final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                boolean invertCursorTextColor = lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
                drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                    measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFont);
            }
        }
    }

    private static final int BOX_NONE = 0;
    private static final int BOX_LIGHT = 1;
    private static final int BOX_HEAVY = 2;

    private static int boxShape(int up, int right, int down, int left) {
        return up | (right << 2) | (down << 4) | (left << 6);
    }

    /**
     * Box and block glyphs are cell geometry, rather than text. Double-line
     * characters (2550-256C) intentionally remain on the font path because
     * their two parallel strokes need font-specific spacing.
     */
    private static boolean isGeometricCodePoint(int codePoint) {
        if (codePoint >= 0x2580 && codePoint <= 0x259F) return true;
        if (codePoint >= 0x256D && codePoint <= 0x257F) return true;
        if (codePoint >= 0x2500 && codePoint <= 0x254B) return true;
        return false;
    }

    private void drawGeometricCell(Graphics2D canvas, int[] palette, float x, float y, float width, float height,
                                   int codePoint, long textStyle, int cursor, int cursorStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) backColor = palette[backColor];

        final boolean reverseVideoHere = reverseVideo ^ (effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            canvas.setColor(new Color(backColor, true));
            canvas.fill(new Rectangle2D.Float(x, y, width, height));
        }

        if (cursor != 0) {
            canvas.setColor(new Color(cursor, true));
            float cursorHeight = height;
            float cursorY = y;
            float cursorWidth = width;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cursorHeight /= 4.f;
                cursorY += height - cursorHeight;
            } else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cursorWidth /= 4.f;
            }
            canvas.fill(new Rectangle2D.Float(x, cursorY, cursorWidth, cursorHeight));
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) foreColor = dimColor(foreColor);

        // Pixel-aligned filled shapes keep neighboring cells joined even when
        // the text antialiasing hint is enabled for the rest of the terminal.
        Graphics2D geometryCanvas = (Graphics2D) canvas.create();
        geometryCanvas.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        geometryCanvas.setColor(new Color(foreColor, true));
        if (codePoint >= 0x2580) {
            drawBlockElement(geometryCanvas, x, y, width, height, codePoint, foreColor);
        } else {
            drawBoxElement(geometryCanvas, x, y, width, height, codePoint, bold);
        }
        geometryCanvas.dispose();
    }

    private static int dimColor(int color) {
        int red = (0xFF & (color >> 16)) * 2 / 3;
        int green = (0xFF & (color >> 8)) * 2 / 3;
        int blue = (0xFF & color) * 2 / 3;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static float lineThickness(float cellWidth, int weight, boolean bold) {
        float light = Math.max(1.f, Math.round(cellWidth / 10.f));
        if (bold) light = Math.max(light, 2.f);
        return weight == BOX_HEAVY ? Math.max(light + 1.f, Math.round(cellWidth / 5.f)) : light;
    }

    private static void fillHorizontal(Graphics2D canvas, float x1, float x2, float center, float thickness) {
        canvas.fill(new Rectangle2D.Float(Math.min(x1, x2), center - thickness / 2.f,
            Math.abs(x2 - x1), thickness));
    }

    private static void fillVertical(Graphics2D canvas, float y1, float y2, float center, float thickness) {
        canvas.fill(new Rectangle2D.Float(center - thickness / 2.f, Math.min(y1, y2),
            thickness, Math.abs(y2 - y1)));
    }

    private static void drawBoxElement(Graphics2D canvas, float x, float y, float width, float height,
                                       int codePoint, boolean bold) {
        if (codePoint >= 0x2504 && codePoint <= 0x250B) {
            boolean horizontal = codePoint == 0x2504 || codePoint == 0x2505 || codePoint == 0x2508 || codePoint == 0x2509;
            int dashCount = (codePoint == 0x2504 || codePoint == 0x2505 || codePoint == 0x2506 || codePoint == 0x2507) ? 3 : 4;
            int weight = (codePoint == 0x2505 || codePoint == 0x2507 || codePoint == 0x2509 || codePoint == 0x250B)
                ? BOX_HEAVY : BOX_LIGHT;
            float thickness = lineThickness(width, weight, bold);
            if (horizontal) drawDashesHorizontal(canvas, x, y, width, height, dashCount, thickness);
            else drawDashesVertical(canvas, x, y, width, height, dashCount, thickness);
            return;
        }

        if (codePoint == 0x2571 || codePoint == 0x2572 || codePoint == 0x2573) {
            float thickness = lineThickness(width, BOX_LIGHT, bold);
            if (codePoint != 0x2572) drawDiagonal(canvas, x, y, width, height, false, thickness);
            if (codePoint != 0x2571) drawDiagonal(canvas, x, y, width, height, true, thickness);
            return;
        }

        int shape = boxShapeForCodePoint(codePoint);
        if (shape == 0) return;
        float centerX = x + width / 2.f;
        float centerY = y + height / 2.f;
        int up = shape & 3;
        int right = (shape >> 2) & 3;
        int down = (shape >> 4) & 3;
        int left = (shape >> 6) & 3;
        float upThickness = lineThickness(width, up, bold);
        float rightThickness = lineThickness(width, right, bold);
        float downThickness = lineThickness(width, down, bold);
        float leftThickness = lineThickness(width, left, bold);

        if (up != BOX_NONE) fillVertical(canvas, y, centerY, centerX, upThickness);
        if (right != BOX_NONE) fillHorizontal(canvas, centerX, x + width, centerY, rightThickness);
        if (down != BOX_NONE) fillVertical(canvas, centerY, y + height, centerX, downThickness);
        if (left != BOX_NONE) fillHorizontal(canvas, x, centerX, centerY, leftThickness);

        float centerThickness = Math.max(Math.max(upThickness, downThickness), Math.max(leftThickness, rightThickness));
        canvas.fill(new Rectangle2D.Float(centerX - centerThickness / 2.f, centerY - centerThickness / 2.f,
            centerThickness, centerThickness));
    }

    private static void drawDashesHorizontal(Graphics2D canvas, float x, float y, float width, float height,
                                              int count, float thickness) {
        float dashWidth = width / (count * 2.f - 1.f);
        float center = y + height / 2.f;
        for (int i = 0; i < count; i++) {
            float start = x + i * dashWidth * 2.f;
            fillHorizontal(canvas, start, Math.min(x + width, start + dashWidth), center, thickness);
        }
    }

    private static void drawDashesVertical(Graphics2D canvas, float x, float y, float width, float height,
                                            int count, float thickness) {
        float dashHeight = height / (count * 2.f - 1.f);
        float center = x + width / 2.f;
        for (int i = 0; i < count; i++) {
            float start = y + i * dashHeight * 2.f;
            fillVertical(canvas, start, Math.min(y + height, start + dashHeight), center, thickness);
        }
    }

    private static void drawDiagonal(Graphics2D canvas, float x, float y, float width, float height,
                                     boolean downRight, float thickness) {
        double length = Math.sqrt(width * width + height * height);
        float nx = (float) (-height * thickness / (2.0 * length));
        float ny = (float) (width * thickness / (2.0 * length));
        float x1 = downRight ? x : x;
        float y1 = downRight ? y : y + height;
        float x2 = x + width;
        float y2 = downRight ? y + height : y;
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        path.moveTo(x1 + nx, y1 + ny);
        path.lineTo(x2 + nx, y2 + ny);
        path.lineTo(x2 - nx, y2 - ny);
        path.lineTo(x1 - nx, y1 - ny);
        path.closePath();
        canvas.fill(path);
    }

    private static int boxShapeForCodePoint(int codePoint) {
        final int l = BOX_LIGHT;
        final int h = BOX_HEAVY;
        switch (codePoint) {
            case 0x2500: return boxShape(0, l, 0, l);
            case 0x2501: return boxShape(0, h, 0, h);
            case 0x2502: return boxShape(l, 0, l, 0);
            case 0x2503: return boxShape(h, 0, h, 0);
            case 0x250C: return boxShape(0, l, l, 0);
            case 0x250D: return boxShape(0, h, l, 0);
            case 0x250E: return boxShape(0, l, h, 0);
            case 0x250F: return boxShape(0, h, h, 0);
            case 0x2510: return boxShape(0, 0, l, l);
            case 0x2511: return boxShape(0, 0, l, h);
            case 0x2512: return boxShape(0, 0, h, l);
            case 0x2513: return boxShape(0, 0, h, h);
            case 0x2514: return boxShape(l, l, 0, 0);
            case 0x2515: return boxShape(l, h, 0, 0);
            case 0x2516: return boxShape(h, l, 0, 0);
            case 0x2517: return boxShape(h, h, 0, 0);
            case 0x2518: return boxShape(l, 0, 0, l);
            case 0x2519: return boxShape(l, 0, 0, h);
            case 0x251A: return boxShape(h, 0, 0, l);
            case 0x251B: return boxShape(h, 0, 0, h);
            case 0x251C: return boxShape(l, l, l, 0);
            case 0x251D: return boxShape(l, h, l, 0);
            case 0x251E: return boxShape(h, l, l, 0);
            case 0x251F: return boxShape(l, l, h, 0);
            case 0x2520: return boxShape(h, l, h, 0);
            case 0x2521: return boxShape(h, l, l, 0);
            case 0x2522: return boxShape(l, l, h, 0);
            case 0x2523: return boxShape(h, h, h, 0);
            case 0x2524: return boxShape(l, 0, l, l);
            case 0x2525: return boxShape(l, 0, l, h);
            case 0x2526: return boxShape(h, 0, l, l);
            case 0x2527: return boxShape(l, 0, h, l);
            case 0x2528: return boxShape(h, 0, h, l);
            case 0x2529: return boxShape(h, 0, l, l);
            case 0x252A: return boxShape(l, 0, h, l);
            case 0x252B: return boxShape(h, 0, h, h);
            case 0x252C: return boxShape(0, l, l, l);
            case 0x252D: return boxShape(0, l, l, h);
            case 0x252E: return boxShape(0, h, l, l);
            case 0x252F: return boxShape(0, h, l, h);
            case 0x2530: return boxShape(0, l, h, l);
            case 0x2531: return boxShape(0, l, h, h);
            case 0x2532: return boxShape(0, h, h, l);
            case 0x2533: return boxShape(0, h, h, h);
            case 0x2534: return boxShape(l, l, 0, l);
            case 0x2535: return boxShape(l, l, 0, h);
            case 0x2536: return boxShape(l, h, 0, l);
            case 0x2537: return boxShape(l, h, 0, h);
            case 0x2538: return boxShape(h, l, 0, l);
            case 0x2539: return boxShape(h, l, 0, h);
            case 0x253A: return boxShape(h, h, 0, l);
            case 0x253B: return boxShape(h, h, 0, h);
            case 0x253C: return boxShape(l, l, l, l);
            case 0x253D: return boxShape(l, l, l, h);
            case 0x253E: return boxShape(l, h, l, l);
            case 0x253F: return boxShape(l, h, l, h);
            case 0x2540: return boxShape(h, l, l, l);
            case 0x2541: return boxShape(l, l, h, l);
            case 0x2542: return boxShape(h, l, h, l);
            case 0x2543: return boxShape(h, l, l, h);
            case 0x2544: return boxShape(h, h, l, l);
            case 0x2545: return boxShape(l, h, h, l);
            case 0x2546: return boxShape(l, l, h, h);
            case 0x2547: return boxShape(l, h, l, h);
            case 0x2548: return boxShape(l, h, h, h);
            case 0x2549: return boxShape(h, l, h, l);
            case 0x254A: return boxShape(h, h, h, l);
            case 0x254B: return boxShape(h, h, h, h);
            case 0x256D: return boxShape(0, l, l, 0); // Rounded corners use square joins at cell boundaries.
            case 0x256E: return boxShape(0, 0, l, l);
            case 0x256F: return boxShape(l, 0, 0, l);
            case 0x2570: return boxShape(l, l, 0, 0);
            case 0x2574: return boxShape(0, 0, 0, l);
            case 0x2575: return boxShape(l, 0, 0, 0);
            case 0x2576: return boxShape(0, l, 0, 0);
            case 0x2577: return boxShape(0, 0, l, 0);
            case 0x2578: return boxShape(0, 0, 0, h);
            case 0x2579: return boxShape(h, 0, 0, 0);
            case 0x257A: return boxShape(0, h, 0, 0);
            case 0x257B: return boxShape(0, 0, h, 0);
            case 0x257C: return boxShape(0, h, 0, l);
            case 0x257D: return boxShape(l, 0, h, 0);
            case 0x257E: return boxShape(0, l, 0, h);
            case 0x257F: return boxShape(h, 0, l, 0);
            default: return 0;
        }
    }

    private static void drawBlockElement(Graphics2D canvas, float x, float y, float width, float height,
                                         int codePoint, int foreColor) {
        switch (codePoint) {
            case 0x2580: canvas.fill(new Rectangle2D.Float(x, y, width, height / 2.f)); return;
            case 0x2581: canvas.fill(new Rectangle2D.Float(x, y + height * 7.f / 8.f, width, height / 8.f)); return;
            case 0x2582: canvas.fill(new Rectangle2D.Float(x, y + height * 3.f / 4.f, width, height / 4.f)); return;
            case 0x2583: canvas.fill(new Rectangle2D.Float(x, y + height * 5.f / 8.f, width, height * 3.f / 8.f)); return;
            case 0x2584: canvas.fill(new Rectangle2D.Float(x, y + height / 2.f, width, height / 2.f)); return;
            case 0x2585: canvas.fill(new Rectangle2D.Float(x, y + height * 3.f / 8.f, width, height * 5.f / 8.f)); return;
            case 0x2586: canvas.fill(new Rectangle2D.Float(x, y + height / 4.f, width, height * 3.f / 4.f)); return;
            case 0x2587: canvas.fill(new Rectangle2D.Float(x, y + height / 8.f, width, height * 7.f / 8.f)); return;
            case 0x2588: canvas.fill(new Rectangle2D.Float(x, y, width, height)); return;
            case 0x2589: canvas.fill(new Rectangle2D.Float(x, y, width * 7.f / 8.f, height)); return;
            case 0x258A: canvas.fill(new Rectangle2D.Float(x, y, width * 3.f / 4.f, height)); return;
            case 0x258B: canvas.fill(new Rectangle2D.Float(x, y, width * 5.f / 8.f, height)); return;
            case 0x258C: canvas.fill(new Rectangle2D.Float(x, y, width / 2.f, height)); return;
            case 0x258D: canvas.fill(new Rectangle2D.Float(x, y, width * 3.f / 8.f, height)); return;
            case 0x258E: canvas.fill(new Rectangle2D.Float(x, y, width / 4.f, height)); return;
            case 0x258F: canvas.fill(new Rectangle2D.Float(x, y, width / 8.f, height)); return;
            case 0x2590: canvas.fill(new Rectangle2D.Float(x + width / 2.f, y, width / 2.f, height)); return;
            case 0x2591: canvas.setColor(new Color((0x45 << 24) | (foreColor & 0x00FFFFFF), true)); canvas.fill(new Rectangle2D.Float(x, y, width, height)); return;
            case 0x2592: canvas.setColor(new Color((0x88 << 24) | (foreColor & 0x00FFFFFF), true)); canvas.fill(new Rectangle2D.Float(x, y, width, height)); return;
            case 0x2593: canvas.setColor(new Color((0xCC << 24) | (foreColor & 0x00FFFFFF), true)); canvas.fill(new Rectangle2D.Float(x, y, width, height)); return;
            case 0x2594: canvas.fill(new Rectangle2D.Float(x, y, width, height / 8.f)); return;
            case 0x2595: canvas.fill(new Rectangle2D.Float(x + width * 7.f / 8.f, y, width / 8.f, height)); return;
            case 0x2596: canvas.fill(new Rectangle2D.Float(x, y + height / 2.f, width / 2.f, height / 2.f)); return;
            case 0x2597: canvas.fill(new Rectangle2D.Float(x + width / 2.f, y + height / 2.f, width / 2.f, height / 2.f)); return;
            case 0x2598: canvas.fill(new Rectangle2D.Float(x, y, width / 2.f, height / 2.f)); return;
            case 0x2599:
                canvas.fill(new Rectangle2D.Float(x, y, width / 2.f, height));
                canvas.fill(new Rectangle2D.Float(x + width / 2.f, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            case 0x259A:
                canvas.fill(new Rectangle2D.Float(x, y, width / 2.f, height / 2.f));
                canvas.fill(new Rectangle2D.Float(x + width / 2.f, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            case 0x259B:
                canvas.fill(new Rectangle2D.Float(x, y, width, height / 2.f));
                canvas.fill(new Rectangle2D.Float(x, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            case 0x259C:
                canvas.fill(new Rectangle2D.Float(x, y, width, height / 2.f));
                canvas.fill(new Rectangle2D.Float(x + width / 2.f, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            case 0x259D: canvas.fill(new Rectangle2D.Float(x + width / 2.f, y, width / 2.f, height / 2.f)); return;
            case 0x259E:
                canvas.fill(new Rectangle2D.Float(x + width / 2.f, y, width / 2.f, height / 2.f));
                canvas.fill(new Rectangle2D.Float(x, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            case 0x259F:
                canvas.fill(new Rectangle2D.Float(x + width / 2.f, y, width / 2.f, height));
                canvas.fill(new Rectangle2D.Float(x, y + height / 2.f, width / 2.f, height / 2.f));
                return;
            default:
                return;
        }
    }

    private void drawTextRun(Graphics2D canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, Font runFont) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        AffineTransform savedMatrix = null;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            savedMatrix = canvas.getTransform();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            canvas.setColor(new Color(backColor, true));
            canvas.fill(new Rectangle2D.Float(left, y - mFontLineSpacingAndAscent + mFontAscent, right - left, mFontLineSpacingAndAscent - mFontAscent));
        }

        if (cursor != 0) {
            canvas.setColor(new Color(cursor, true));
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            float cRight = right;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) cRight -= ((right - left) * 3) / 4.;
            canvas.fill(new Rectangle2D.Float(left, y - cursorHeight, cRight - left, cursorHeight));
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            Font f = bold ? runFont.deriveFont(Font.BOLD) : runFont;
            if (italic) f = f.deriveFont(AffineTransform.getShearInstance(-0.35, 0));
            if (underline || strikeThrough) {
                Map<TextAttribute, Object> attrs = new java.util.HashMap<>();
                if (underline) attrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
                if (strikeThrough) attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                f = f.deriveFont(attrs);
            }
            canvas.setFont(f);
            canvas.setColor(new Color(foreColor, true));

            canvas.drawString(new String(text, startCharIndex, runWidthChars), left, y - mFontLineSpacingAndAscent);
        }

        if (savedMatrix != null) canvas.setTransform(savedMatrix);
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }

    public int getFontLineSpacingAndAscent() {
        return mFontLineSpacingAndAscent;
    }
}
