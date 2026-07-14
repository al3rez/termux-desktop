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
import java.awt.font.TextLayout;
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

    final float mFontWidth;
    final int mFontLineSpacing;
    private final int mFontAscent;
    final int mFontLineSpacingAndAscent;

    private final FontMetrics mMetrics;
    private final float[] asciiMeasures = new float[127];
    private final boolean[] asciiMeasureReady = new boolean[127];

    private static final String COLOR_EMOJI_FONT_PATH = "/usr/share/fonts/noto/NotoColorEmoji.ttf";
    private static final String EMOJI_PROBE = "\uD83D\uDE00";
    private static final int EMOJI_RENDER_DRAW_STRING = 1;
    private static final int EMOJI_RENDER_TEXT_LAYOUT = 2;
    private final Font mEmojiFont;
    private final int mEmojiRenderMode;

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

        EmojiSupport emojiSupport = findEmojiSupport();
        mEmojiFont = emojiSupport.font;
        mEmojiRenderMode = emojiSupport.renderMode;
    }

    /** The font to draw/measure a code point with: the main font, or the first fallback that has the glyph. */
    private Font fontFor(int codePoint) {
        if (codePoint >= 0x1F000 && codePoint <= 0x1FAFF && mEmojiFont != null) return mEmojiFont;
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
                // Mono-advance nerd font first: SymbolsNerdFont-Regular's icons
                // have wider advances that trigger run scaling ahead of a mono font.
                "/usr/share/fonts/TTF/JetBrainsMonoNerdFontMono-Regular.ttf",
                "/usr/share/fonts/TTF/SymbolsNerdFont-Regular.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
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

    private static final class EmojiSupport {
        final Font font;
        final int renderMode;

        EmojiSupport(Font font, int renderMode) {
            this.font = font;
            this.renderMode = renderMode;
        }
    }

    /**
     * Probe the installed color font instead of trusting Font.canDisplay().
     * Temurin 17 can load Noto Color Emoji's fixed-size CBDT font, but on this
     * Linux runtime it produces an empty glyph through both Java2D APIs.
     */
    private EmojiSupport findEmojiSupport() {
        Font colorFont = loadFont(COLOR_EMOJI_FONT_PATH);
        if (colorFont != null) {
            colorFont = colorFont.deriveFont((float) mTextSize);
            int colorRenderMode = probeColorEmoji(colorFont);
            if (colorRenderMode != 0) return new EmojiSupport(colorFont, colorRenderMode);
        }

        // DejaVu Sans contains useful monochrome emoji outlines even when the
        // terminal's selected mono face does not. Prefer the caller's face if
        // it really renders the probe; this avoids replacing an installed
        // emoji-capable terminal font with a different outline.
        if (canRenderGlyph(mFont, 0x1F600)) return new EmojiSupport(mFont, 0);
        Font monoEmoji = loadFont("/usr/share/fonts/TTF/DejaVuSans.ttf");
        if (monoEmoji != null) {
            monoEmoji = monoEmoji.deriveFont((float) mTextSize);
            if (canRenderGlyph(monoEmoji, 0x1F600)) return new EmojiSupport(monoEmoji, 0);
        }

        Font logicalSans = new Font(Font.SANS_SERIF, Font.PLAIN, mTextSize);
        if (canRenderGlyph(logicalSans, 0x1F600)) return new EmojiSupport(logicalSans, 0);
        return new EmojiSupport(mFont, 0);
    }

    private static Font loadFont(String path) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new java.io.File(path));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int probeColorEmoji(Font font) {
        for (int renderMode : new int[]{EMOJI_RENDER_DRAW_STRING, EMOJI_RENDER_TEXT_LAYOUT}) {
            BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            if (renderMode == EMOJI_RENDER_DRAW_STRING) {
                g.drawString(EMOJI_PROBE, 4, 72);
            } else {
                new TextLayout(EMOJI_PROBE, font, g.getFontRenderContext()).draw(g, 4, 72);
            }
            boolean colored = hasColoredPixels(image);
            g.dispose();
            if (colored) return renderMode;
        }
        return 0;
    }

    private static boolean canRenderGlyph(Font font, int codePoint) {
        if (font == null || !font.canDisplay(codePoint)) return false;
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(new String(Character.toChars(codePoint)), 4, Math.max(16, font.getSize()));
        boolean inked = hasInk(image);
        g.dispose();
        return inked;
    }

    private static boolean hasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) return true;
            }
        }
        return false;
    }

    private static boolean hasColoredPixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 12) return true;
            }
        }
        return false;
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

    private static final class EmojiCluster {
        final int charCount;
        final int terminalWidth;
        final int visualWidth;
        final int firstCodePoint;
        final boolean regionalPair;
        final boolean hasZwj;

        EmojiCluster(int charCount, int terminalWidth, int visualWidth, int firstCodePoint,
                     boolean regionalPair, boolean hasZwj) {
            this.charCount = charCount;
            this.terminalWidth = terminalWidth;
            this.visualWidth = visualWidth;
            this.firstCodePoint = firstCodePoint;
            this.regionalPair = regionalPair;
            this.hasZwj = hasZwj;
        }
    }

    /** Group the emoji sequences that the terminal row stores in one or more cells. */
    private static EmojiCluster emojiClusterAt(char[] chars, int start, int limit) {
        if (start >= limit) return null;
        int firstCodePoint = codePointAt(chars, start, limit);
        int firstChars = codePointCharCount(chars, start, limit);
        int next = start + firstChars;

        if (isRegionalIndicator(firstCodePoint)) {
            if (next < limit) {
                int second = codePointAt(chars, next, limit);
                if (isRegionalIndicator(second)) {
                    int secondChars = codePointCharCount(chars, next, limit);
                    return new EmojiCluster(firstChars + secondChars, 2, 2, firstCodePoint, true, false);
                }
            }
            return new EmojiCluster(firstChars, 1, 1, firstCodePoint, false, false);
        }

        boolean hasEmojiVariation = next < limit && codePointAt(chars, next, limit) == 0xFE0F;
        boolean emojiBase = isEmojiBaseCodePoint(firstCodePoint)
            && (firstCodePoint >= 0x1F000 || WcWidth.width(firstCodePoint) == 2);
        if (!emojiBase && !hasEmojiVariation) return null;

        int index = next;
        int terminalWidth = Math.max(1, WcWidth.width(firstCodePoint));
        boolean hasZwj = false;
        while (index < limit) {
            int codePoint = codePointAt(chars, index, limit);
            int codePointChars = codePointCharCount(chars, index, limit);
            if (isVariationSelector(codePoint) || isEmojiModifier(codePoint)
                || (WcWidth.width(codePoint) <= 0 && codePoint != 0x200D)) {
                index += codePointChars;
                continue;
            }
            if (codePoint != 0x200D) break;

            int componentStart = index + codePointChars;
            if (componentStart >= limit) {
                index = componentStart; // Do not leave a trailing ZWJ to become tofu.
                break;
            }
            int component = codePointAt(chars, componentStart, limit);
            if (!isEmojiBaseCodePoint(component)) break;
            hasZwj = true;
            int componentChars = codePointCharCount(chars, componentStart, limit);
            terminalWidth += Math.max(1, WcWidth.width(component));
            index = componentStart + componentChars;
        }
        return new EmojiCluster(index - start, terminalWidth, 2, firstCodePoint, false, hasZwj);
    }

    private static int codePointAt(char[] chars, int index, int limit) {
        char c = chars[index];
        if (Character.isHighSurrogate(c) && index + 1 < limit && Character.isLowSurrogate(chars[index + 1])) {
            return Character.toCodePoint(c, chars[index + 1]);
        }
        return c;
    }

    private static int codePointCharCount(char[] chars, int index, int limit) {
        return Character.isHighSurrogate(chars[index]) && index + 1 < limit
            && Character.isLowSurrogate(chars[index + 1]) ? 2 : 1;
    }

    private static boolean isVariationSelector(int codePoint) {
        return (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
            || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isEmojiBaseCodePoint(int codePoint) {
        if (codePoint >= 0x1F000 && codePoint <= 0x1FAFF) return true;
        if (codePoint >= 0x2600 && codePoint <= 0x27BF) return true;
        switch (codePoint) {
            case 0x00A9: case 0x00AE: case 0x203C: case 0x2049:
            case 0x2122: case 0x2139: case 0x3030: case 0x303D:
            case 0x3297: case 0x3299:
                return true;
            default:
                return false;
        }
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

            int column = 0; // Visual column; emoji sequences may normalize to two cells.
            int terminalColumn = 0;
            while (terminalColumn < columns && currentCharIndex < charsUsedInLine) {
                final int charsForCodePoint = codePointCharCount(line, currentCharIndex, charsUsedInLine);
                final int codePoint = codePointAt(line, currentCharIndex, charsUsedInLine);
                final int codePointWcWidth = WcWidth.width(codePoint);
                final EmojiCluster emoji = emojiClusterAt(line, currentCharIndex, charsUsedInLine);
                final int terminalWidth = emoji == null ? codePointWcWidth : emoji.terminalWidth;
                final int visualWidth = emoji == null ? codePointWcWidth : emoji.visualWidth;
                final boolean insideCursor = emoji != null
                    ? cursorX >= terminalColumn && cursorX < terminalColumn + terminalWidth
                    : (cursorX == terminalColumn || (codePointWcWidth == 2 && cursorX == terminalColumn + 1));
                final boolean insideSelection = terminalColumn <= selx2
                    && terminalColumn + Math.max(1, terminalWidth) - 1 >= selx1;
                final long style = lineObject.getStyle(terminalColumn);
                final boolean geometric = isGeometricCodePoint(codePoint);

                if (geometric) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = Math.max(0, column - lastRunStartColumn);
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
                    // Text runs draw shifted down by mFontLineSpacingAndAscent (the
                    // heightOffset loop starts there, matching Android). Geometry
                    // must use the same origin or it sits above the text backgrounds.
                    drawGeometricCell(canvas, palette, column * mFontWidth,
                        (row - topRow) * mFontLineSpacing + mFontLineSpacingAndAscent,
                        codePointWcWidth * mFontWidth, mFontLineSpacing, codePoint, style, cursorColor, cursorShape,
                        reverseVideo || invertCursorTextColor || insideSelection);

                    column += codePointWcWidth;
                    terminalColumn += codePointWcWidth;
                    currentCharIndex += charsForCodePoint;
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        currentCharIndex += codePointCharCount(line, currentCharIndex, charsUsedInLine);
                    }
                    continue;
                }

                if (emoji != null) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = Math.max(0, column - lastRunStartColumn);
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
                    drawEmojiRun(canvas, palette, heightOffset, column, visualWidth,
                        new String(line, currentCharIndex, emoji.charCount), emoji, style,
                        cursorColor, cursorShape, reverseVideo || invertCursorTextColor || insideSelection);
                    column += visualWidth;
                    terminalColumn += terminalWidth;
                    currentCharIndex += emoji.charCount;
                    continue;
                }

                final float measuredCodePointWidth = measureCodePoint(line, currentCharIndex, charsForCodePoint, codePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;
                final Font fontForCodePoint = fontFor(codePoint);

                if (lastRunStartColumn < 0 || style != lastRunStyle || fontForCodePoint != lastRunFont || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = Math.max(0, column - lastRunStartColumn);
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
                terminalColumn += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += codePointCharCount(line, currentCharIndex, charsUsedInLine);
                }
            }

            if (lastRunStartColumn >= 0) {
                final int columnWidthSinceLastRun = Math.max(0, columns - lastRunStartColumn);
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
        // Solid powerline triangles only: their font glyphs sit ~1px short of
        // the cell top, leaving a background sliver between prompt segments.
        // Chevrons/semicircles (E0B1, E0B3-E0B7) stay on the font path — they
        // don't need seamless joins and the font's shapes look better.
        if (codePoint == 0xE0B0 || codePoint == 0xE0B2) return true;
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
        // Triangles need antialiased diagonals; axis-aligned box/block fills
        // must stay aliased so neighboring cells join without seams.
        geometryCanvas.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
            (codePoint == 0xE0B0 || codePoint == 0xE0B2) ? java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                                                         : java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        geometryCanvas.setColor(new Color(foreColor, true));
        if (codePoint == 0xE0B0 || codePoint == 0xE0B2) {
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            if (codePoint == 0xE0B0) { // solid right-pointing triangle
                p.moveTo(x, y); p.lineTo(x + width, y + height / 2f); p.lineTo(x, y + height);
            } else { // solid left-pointing triangle
                p.moveTo(x + width, y); p.lineTo(x, y + height / 2f); p.lineTo(x + width, y + height);
            }
            p.closePath();
            geometryCanvas.fill(p);
        } else if (codePoint >= 0x2580) {
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

    /** Draw an emoji cluster in its visual cell span without normal-run scaling. */
    private void drawEmojiRun(Graphics2D canvas, int[] palette, float y, int startColumn, int widthColumns,
                              String clusterText, EmojiCluster cluster, long textStyle,
                              int cursor, int cursorStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;

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

        float left = startColumn * mFontWidth;
        float width = widthColumns * mFontWidth;
        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            canvas.setColor(new Color(backColor, true));
            canvas.fill(new Rectangle2D.Float(left, y - mFontLineSpacingAndAscent + mFontAscent,
                width, mFontLineSpacingAndAscent - mFontAscent));
        }

        if (cursor != 0) {
            canvas.setColor(new Color(cursor, true));
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            float cRight = left + width;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) cRight = left + width / 4.f;
            canvas.fill(new Rectangle2D.Float(left, y - cursorHeight, cRight - left, cursorHeight));
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) foreColor = dimColor(foreColor);

        Font drawFont = mEmojiFont == null ? mFont : mEmojiFont;
        String drawText = clusterText;
        boolean customFlag = false;
        if (mEmojiRenderMode == 0) {
            // Java2D's monochrome fallback must not ask a font to shape VS16 or
            // a ZWJ sequence it cannot support: draw the first emoji component.
            if (cluster.regionalPair) {
                if (fontCanDisplayAll(drawFont, clusterText)) {
                    drawText = clusterText;
                } else {
                    drawText = regionalIndicatorLetters(clusterText);
                    drawFont = mFont;
                    customFlag = true;
                }
            } else {
                drawText = new String(Character.toChars(cluster.firstCodePoint));
                if (!drawFont.canDisplay(cluster.firstCodePoint)) {
                    // A missing ZWJ component must not turn into Java2D's
                    // replacement-box glyph. A plain question mark is a
                    // legible, single-cell-safe last resort.
                    drawText = "?";
                    drawFont = mFont;
                }
            }
        }

        Font styledFont = bold ? drawFont.deriveFont(Font.BOLD) : drawFont;
        if (underline || strikeThrough) {
            Map<TextAttribute, Object> attrs = new java.util.HashMap<>();
            if (underline) attrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            if (strikeThrough) attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            styledFont = styledFont.deriveFont(attrs);
        }
        canvas.setFont(styledFont);
        canvas.setColor(new Color(foreColor, true));

        float advance;
        TextLayout layout = null;
        if (mEmojiRenderMode == EMOJI_RENDER_DRAW_STRING && !customFlag) {
            advance = (float) styledFont.getStringBounds(drawText, canvas.getFontRenderContext()).getWidth();
            if (advance <= 0.f) advance = width;
        } else {
            layout = new TextLayout(drawText, styledFont, canvas.getFontRenderContext());
            advance = layout.getAdvance();
        }
        float drawX = left + (width - advance) / 2.f;
        float baseline = y - mFontLineSpacingAndAscent;
        if (mEmojiRenderMode == EMOJI_RENDER_DRAW_STRING && !customFlag) {
            canvas.drawString(drawText, drawX, baseline);
        } else {
            layout.draw(canvas, drawX, baseline);
        }
    }

    private static boolean fontCanDisplayAll(Font font, String text) {
        for (int codePoint : text.codePoints().toArray()) {
            if (!font.canDisplay(codePoint)) return false;
        }
        return true;
    }

    private static String regionalIndicatorLetters(String text) {
        int first = text.codePointAt(0);
        int second = text.codePointAt(Character.charCount(first));
        char firstLetter = (char) ('A' + first - 0x1F1E6);
        char secondLetter = (char) ('A' + second - 0x1F1E6);
        return new String(new char[]{firstLetter, secondLetter});
    }

    private void drawTextRun(Graphics2D canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, Font runFont) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
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

    /** Exposed for headless render regressions and diagnostics. */
    public boolean isColorEmojiEnabled() {
        return mEmojiRenderMode != 0;
    }
}
