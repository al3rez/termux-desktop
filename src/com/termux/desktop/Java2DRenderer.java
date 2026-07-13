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

                final float measuredCodePointWidth = measureCodePoint(line, currentCharIndex, charsForCodePoint, codePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;
                final Font fontForCodePoint = fontFor(codePoint);

                if (style != lastRunStyle || fontForCodePoint != lastRunFont || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
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

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFont);
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
}
