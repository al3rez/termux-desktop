package com.termux.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
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
     * The reader owns the pty and these batches.  The emulator is owned by the
     * EDT; the queue is the only intentional hand-off between the two.
     *
     * Keep one batch in the reader in addition to the queue slots, so the
     * maximum retained pty output is approximately 256 KiB.  ArrayBlockingQueue
     * also gives us backpressure instead of allowing invokeLater/event-queue
     * work to grow without bound.
     */
    private static final int PTY_READ_BYTES = 4 * 1024;
    private static final int PTY_BATCH_BYTES = 32 * 1024;
    private static final int PTY_BUFFER_BYTES = 256 * 1024;
    private static final int OUTPUT_QUEUE_CAPACITY = PTY_BUFFER_BYTES / PTY_BATCH_BYTES - 1;
    private static final int EDT_DRAIN_BYTES = 64 * 1024;

    /** Emulator state is read and mutated on the Swing EDT only. */
    public TerminalEmulator mEmulator;
    private final TerminalSessionClient mClient;
    private final ArrayBlockingQueue<OutputBatch> mOutputQueue =
        new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
    private final AtomicBoolean mDrainScheduled = new AtomicBoolean();
    private final AtomicBoolean mPtyEndQueued = new AtomicBoolean();
    private final AtomicLong mAppendedBytes = new AtomicLong();
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
     * Read and batch pty output without waiting for the EDT.  Only EOF or an
     * IOException from the pty ends the session; unexpected runtime failures
     * are logged and the reader keeps trying.
     */
    private void readPty(PtyProcess process) {
        InputStream in = null;
        byte[] readBuffer = new byte[PTY_READ_BYTES];
        BatchBuffer batch = new BatchBuffer();
        try {
            while (!mFinished) {
                try {
                    if (in == null) in = process.getInputStream();
                    int n = readFromPty(in, readBuffer, readBuffer.length);
                    if (n == -1) {
                        if (!flushBatch(batch)) return;
                        queuePtyEnd();
                        return;
                    }
                    if (n > 0) batch.length = appendToBatch(readBuffer, n, batch.data, batch.length);

                    boolean eof = false;
                    while (!mFinished) {
                        if (batch.length == batch.data.length && !flushBatch(batch)) return;
                        if (availableOnPty(in) <= 0) break;

                        int readLimit = Math.min(readBuffer.length, batch.data.length - batch.length);
                        n = readFromPty(in, readBuffer, readLimit);
                        if (n == -1) {
                            eof = true;
                            break;
                        }
                        if (n == 0) break;
                        batch.length = appendToBatch(readBuffer, n, batch.data, batch.length);
                    }

                    if (!flushBatch(batch)) return;
                    if (eof) {
                        queuePtyEnd();
                        return;
                    }
                } catch (IOException e) {
                    if (!mFinished) {
                        logFailure("pty read", e);
                        queuePtyEnd();
                    }
                    return;
                } catch (Throwable t) {
                    logFailure("pty reader", t);
                    // Keep this supervisor loop alive for an unexpected client
                    // or stream implementation failure.  A pty IOException
                    // takes the explicit terminal path above.
                    Thread.yield();
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

    private int appendToBatch(byte[] source, int length, byte[] batch, int batchLength) {
        int copied = Math.min(length, batch.length - batchLength);
        System.arraycopy(source, 0, batch, batchLength, copied);
        if (copied != length) throw new IllegalStateException("pty read exceeded batch capacity");
        return batchLength + copied;
    }

    private int readFromPty(InputStream in, byte[] buffer, int length) throws IOException {
        if (length <= 0) return 0;
        while (!mFinished) {
            try {
                return in.read(buffer, 0, length);
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                logFailure("pty read attempt", t);
                Thread.yield();
            }
        }
        return -1;
    }

    private int availableOnPty(InputStream in) throws IOException {
        try {
            return Math.max(0, in.available());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            logFailure("pty availability check", t);
            return 0;
        }
    }

    /** Blocks when the bounded output queue is full, providing pty backpressure. */
    private boolean enqueueData(byte[] data, int length) {
        byte[] queuedData = length == data.length ? data : Arrays.copyOf(data, length);
        OutputBatch batch = new OutputBatch(queuedData, length, false);
        while (!mFinished) {
            try {
                mOutputQueue.put(batch);
            } catch (InterruptedException e) {
                if (mFinished) return false;
                logFailure("pty queue interrupted", e);
                continue;
            } catch (Throwable t) {
                logFailure("pty queue", t);
                Thread.yield();
                continue;
            }
            try {
                scheduleDrain();
            } catch (Throwable t) {
                // The batch is already in the bounded queue; leave it there
                // and let a later enqueue or end marker retry scheduling.
                logFailure("EDT output scheduling", t);
            }
            return true;
        }
        return false;
    }

    private boolean flushBatch(BatchBuffer batch) {
        if (batch.length == 0) return true;
        boolean full = batch.length == batch.data.length;
        if (!enqueueData(batch.data, batch.length)) return false;
        if (full) batch.data = new byte[PTY_BATCH_BYTES];
        batch.length = 0;
        return true;
    }

    private void queuePtyEnd() {
        if (mFinished || !mPtyEndQueued.compareAndSet(false, true)) return;
        OutputBatch end = new OutputBatch(null, 0, true);
        while (!mFinished) {
            try {
                mOutputQueue.put(end);
            } catch (InterruptedException e) {
                if (mFinished) return;
                logFailure("pty end queue interrupted", e);
                continue;
            } catch (Throwable t) {
                logFailure("pty end queue", t);
                Thread.yield();
                continue;
            }
            try {
                scheduleDrain();
            } catch (Throwable t) {
                logFailure("EDT output scheduling", t);
            }
            return;
        }
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
        int drainedBytes = 0;
        boolean changed = false;
        try {
            while (!mFinished && drainedBytes < EDT_DRAIN_BYTES) {
                OutputBatch batch = mOutputQueue.poll();
                if (batch == null) break;
                if (batch.end) {
                    notifyTextChanged(changed);
                    notifySessionFinished();
                    return;
                }

                TerminalEmulator emulator = mEmulator;
                if (emulator == null) continue;
                try {
                    emulator.append(batch.data, batch.length);
                    mAppendedBytes.addAndGet(batch.length);
                    changed = true;
                } catch (Throwable t) {
                    // A malformed escape sequence or emulator regression must
                    // lose one batch, not the EDT or every other window.
                    logFailure("emulator append", t);
                }
                drainedBytes += batch.length;
            }
            notifyTextChanged(changed);
        } catch (Throwable t) {
            logFailure("EDT output drain", t);
        } finally {
            mDrainScheduled.set(false);
            if (!mFinished && !mOutputQueue.isEmpty()) scheduleDrain();
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
        mOutputQueue.clear();
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

    private static final class OutputBatch {
        final byte[] data;
        final int length;
        final boolean end;

        OutputBatch(byte[] data, int length, boolean end) {
            this.data = data;
            this.length = length;
            this.end = end;
        }
    }

    private static final class BatchBuffer {
        byte[] data = new byte[PTY_BATCH_BYTES];
        int length;
    }
}
