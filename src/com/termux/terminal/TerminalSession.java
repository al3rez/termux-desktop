package com.termux.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Desktop replacement for Termux's Android TerminalSession: same role (owns the
 * shell process + pty and feeds the TerminalEmulator), but backed by pty4j
 * instead of Android JNI.
 */
public final class TerminalSession extends TerminalOutput {

    public TerminalEmulator mEmulator;
    private final TerminalSessionClient mClient;
    private PtyProcess mProcess;
    private OutputStream mPtyIn;

    public TerminalSession(TerminalSessionClient client) {
        mClient = client;
    }

    public void initializeEmulator(int columns, int rows, int cellW, int cellH, String[] command) throws IOException {
        mEmulator = new TerminalEmulator(this, columns, rows, cellW, cellH, 2000, mClient);

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");

        mProcess = new PtyProcessBuilder(command)
            .setEnvironment(env)
            .setInitialColumns(columns)
            .setInitialRows(rows)
            .start();
        mPtyIn = mProcess.getOutputStream();

        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = mProcess.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    final byte[] chunk = buf;
                    final int len = n;
                    javax.swing.SwingUtilities.invokeAndWait(() -> {
                        mEmulator.append(chunk, len);
                        mClient.onTextChanged(this);
                    });
                }
            } catch (Exception ignored) {
            }
            mClient.onSessionFinished(this);
        }, "pty-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void updateSize(int columns, int rows, int cellW, int cellH) {
        if (mEmulator == null) return;
        mEmulator.resize(columns, rows, cellW, cellH);
        if (mProcess != null) mProcess.setWinSize(new WinSize(columns, rows));
    }

    public boolean isRunning() {
        return mProcess != null && mProcess.isAlive();
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        try {
            if (mPtyIn != null) {
                mPtyIn.write(data, offset, count);
                mPtyIn.flush();
            }
        } catch (IOException ignored) {
        }
    }

    public void writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        write(b, 0, b.length);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }
}
