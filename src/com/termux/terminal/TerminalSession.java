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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

/**
 * Desktop replacement for Termux's Android TerminalSession: same role (owns the
 * shell process + pty and feeds the TerminalEmulator), but backed by pty4j
 * instead of Android JNI.
 */
public final class TerminalSession extends TerminalOutput {

    /*
     * The reader owns the pty.  The emulator is owned by the EDT; this bounded
     * byte buffer is the only intentional hand-off between the two.
     *
     * A byte buffer, rather than a queue of individual reads, lets the small
     * reads returned by Unix pty4j coalesce while retaining a real 256 KiB
     * backpressure budget.  PTYInputStream.available() cannot be used for this:
     * pty4j 0.13.4 reports zero on Unix even when more data can be read.
    */
    private static final int PTY_READ_BYTES = 4 * 1024;
    private static final int PTY_BUFFER_BYTES = 256 * 1024;
    private static final int EDT_APPEND_BYTES = 1024;
    private static final int EDT_DRAIN_BYTES = 64 * 1024;
    private static final long EDT_DRAIN_NANOS = 4_000_000L;

    /** Emulator state is read and mutated on the Swing EDT only. */
    public TerminalEmulator mEmulator;
    private final TerminalSessionClient mClient;
    private final Object mOutputLock = new Object();
    private final byte[] mOutputBuffer = new byte[PTY_BUFFER_BYTES];
    /** A small append slice makes the EDT time limit effective for costly escapes. */
    private final byte[] mEdtBatch = new byte[EDT_APPEND_BYTES];
    private final AtomicBoolean mDrainScheduled = new AtomicBoolean();
    private final AtomicBoolean mPtyEndQueued = new AtomicBoolean();
    private final AtomicLong mAppendedBytes = new AtomicLong();
    private int mOutputReadPosition;
    private int mOutputWritePosition;
    private int mOutputSize;
    private PtyProcess mProcess;
    private OutputStream mPtyIn;
    private volatile boolean mFinished;

    public TerminalSession(TerminalSessionClient client) {
        mClient = client;
    }

    public synchronized void initializeEmulator(int columns, int rows, int cellW, int cellH, String[] command) throws IOException {
        if (mProcess != null) throw new IllegalStateException("TerminalSession is already initialized");

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

        PtyProcess process = mProcess;
        Thread reader = new Thread(() -> readPty(process),
            "pty-reader-" + Integer.toHexString(System.identityHashCode(this)));
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Read pty output without waiting for the EDT.  The bounded hand-off buffer
     * blocks this thread when the EDT falls behind.  EOF, I/O errors, and
     * unexpected stream failures all end only this terminal session.
     */
    private void readPty(PtyProcess process) {
        InputStream in = null;
        byte[] readBuffer = new byte[PTY_READ_BYTES];
        try {
            while (!mFinished) {
                try {
                    if (in == null) in = process.getInputStream();
                    int n = readFromPty(in, readBuffer, readBuffer.length);
                    if (n == -1) {
                        queuePtyEnd();
                        return;
                    }
                    if (n > 0 && !enqueueData(readBuffer, n)) return;
                } catch (IOException e) {
                    if (!mFinished) {
                        logFailure("pty read", e);
                        queuePtyEnd();
                    }
                    return;
                } catch (Throwable t) {
                    if (!mFinished) {
                        logFailure("pty reader", t);
                        queuePtyEnd();
                    }
                    return;
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable t) {
                    if (!mFinished) logFailure("pty input close", t);
                }
            }
        }
    }

    private int readFromPty(InputStream in, byte[] buffer, int length) throws IOException {
        if (length <= 0) return 0;
        return in.read(buffer, 0, length);
    }

    /** Copies into the circular buffer, blocking here to provide pty backpressure. */
    private boolean enqueueData(byte[] data, int length) {
        int offset = 0;
        while (offset < length && !mFinished) {
            synchronized (mOutputLock) {
                while (mOutputSize == mOutputBuffer.length && !mFinished) {
                    try {
                        mOutputLock.wait();
                    } catch (InterruptedException e) {
                        if (mFinished) return false;
                        logFailure("pty buffer interrupted", e);
                    }
                }
                if (mFinished) return false;

                int copied = Math.min(length - offset, mOutputBuffer.length - mOutputSize);
                copied = Math.min(copied, mOutputBuffer.length - mOutputWritePosition);
                System.arraycopy(data, offset, mOutputBuffer, mOutputWritePosition, copied);
                mOutputWritePosition = (mOutputWritePosition + copied) % mOutputBuffer.length;
                mOutputSize += copied;
                offset += copied;
            }
            scheduleDrain();
        }
        return offset == length;
    }

    private void queuePtyEnd() {
        if (mFinished || !mPtyEndQueued.compareAndSet(false, true)) return;
        scheduleDrain();
    }

    /** Post at most one drain runnable, so the AWT event queue stays bounded too. */
    private void scheduleDrain() {
        if (mFinished || !mDrainScheduled.compareAndSet(false, true)) return;
        try {
            SwingUtilities.invokeLater(this::drainOutputOnEdt);
        } catch (Throwable t) {
            mDrainScheduled.set(false);
            logFailure("EDT output scheduling", t);
        }
    }

    /** All emulator mutation and client notifications happen here, on the EDT. */
    private void drainOutputOnEdt() {
        long startedAt = System.nanoTime();
        int drainedBytes = 0;
        boolean changed = false;
        try {
            while (!mFinished
                && drainedBytes < EDT_DRAIN_BYTES
                && (drainedBytes == 0 || System.nanoTime() - startedAt < EDT_DRAIN_NANOS)) {
                int length;
                boolean end;
                synchronized (mOutputLock) {
                    length = Math.min(mOutputSize, mEdtBatch.length);
                    end = length == 0 && mPtyEndQueued.get();
                    if (length > 0) {
                        int first = Math.min(length, mOutputBuffer.length - mOutputReadPosition);
                        System.arraycopy(mOutputBuffer, mOutputReadPosition, mEdtBatch, 0, first);
                        if (first < length) {
                            System.arraycopy(mOutputBuffer, 0, mEdtBatch, first, length - first);
                        }
                        mOutputReadPosition = (mOutputReadPosition + length) % mOutputBuffer.length;
                        mOutputSize -= length;
                        mOutputLock.notifyAll();
                    }
                }
                if (end) {
                    notifyTextChanged(changed);
                    notifySessionFinished();
                    return;
                }
                if (length == 0) break;
                drainedBytes += length;

                TerminalEmulator emulator = mEmulator;
                if (emulator == null) continue;
                try {
                    emulator.append(mEdtBatch, length);
                    mAppendedBytes.addAndGet(length);
                    changed = true;
                } catch (Throwable t) {
                    // A malformed escape sequence or emulator regression must
                    // lose one batch, not the EDT or every other window.
                    logFailure("emulator append", t);
                }
            }
            notifyTextChanged(changed);
        } catch (Throwable t) {
            logFailure("EDT output drain", t);
        } finally {
            mDrainScheduled.set(false);
            if (!mFinished && hasPendingOutput()) scheduleDrain();
        }
    }

    private boolean hasPendingOutput() {
        synchronized (mOutputLock) {
            return mOutputSize > 0 || mPtyEndQueued.get();
        }
    }

    private void notifyTextChanged(boolean changed) {
        if (!changed || mFinished || mClient == null) return;
        try {
            mClient.onTextChanged(this);
        } catch (Throwable t) {
            logFailure("text-change callback", t);
        }
    }

    private void notifySessionFinished() {
        try {
            if (mClient != null) mClient.onSessionFinished(this);
        } catch (Throwable t) {
            logFailure("session-finished callback", t);
        } finally {
            finish();
        }
    }

    private void logFailure(String operation, Throwable t) {
        System.err.println("terminal-session " + operation + " failed: " + t);
        t.printStackTrace(System.err);
    }

    /** Bytes successfully appended by the EDT since the previous stats sample. */
    public long consumeAppendedBytes() {
        return mAppendedBytes.getAndSet(0);
    }

    public synchronized void updateSize(int columns, int rows, int cellW, int cellH) {
        if (mEmulator == null || mFinished) return;
        try {
            mEmulator.resize(columns, rows, cellW, cellH);
        } catch (Throwable t) {
            logFailure("emulator resize", t);
            return;
        }
        int widthPixels = columns * cellW;
        int heightPixels = rows * cellH;
        if (mProcess != null && !mFinished) {
            try {
                mProcess.setWinSize(new WinSize(columns, rows, widthPixels, heightPixels));
            } catch (Throwable t) {
                logFailure("pty resize", t);
            }
        }
    }

    public synchronized void finish() {
        if (mFinished) return;
        mFinished = true;
        synchronized (mOutputLock) {
            mOutputSize = 0;
            mOutputReadPosition = 0;
            mOutputWritePosition = 0;
            mOutputLock.notifyAll();
        }
        if (mProcess != null) {
            try {
                mProcess.destroy();
            } catch (Throwable t) {
                logFailure("pty destroy", t);
            }
        }
        if (mPtyIn != null) {
            try {
                mPtyIn.close();
            } catch (Throwable t) {
                logFailure("pty input close", t);
            }
        }
    }

    public synchronized boolean isRunning() {
        try {
            return mProcess != null && mProcess.isAlive();
        } catch (Throwable t) {
            logFailure("pty status", t);
            return false;
        }
    }

    @Override
    public synchronized void write(byte[] data, int offset, int count) {
        if (mFinished) return;
        try {
            if (mPtyIn != null) {
                mPtyIn.write(data, offset, count);
                mPtyIn.flush();
            }
        } catch (Throwable t) {
            logFailure("pty write", t);
        }
    }

    public void writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        write(b, 0, b.length);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        notifyClient("title change", () -> mClient.onTitleChanged(this));
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        notifyClient("copy callback", () -> mClient.onCopyTextToClipboard(this, text));
    }

    @Override
    public void onPasteTextFromClipboard() {
        notifyClient("paste callback", () -> mClient.onPasteTextFromClipboard(this));
    }

    @Override
    public void onBell() {
        notifyClient("bell callback", () -> mClient.onBell(this));
    }

    @Override
    public void onColorsChanged() {
        notifyClient("color callback", () -> mClient.onColorsChanged(this));
    }

    private void notifyClient(String operation, Runnable callback) {
        if (mClient == null) return;
        try {
            callback.run();
        } catch (Throwable t) {
            logFailure(operation, t);
        }
    }

}
