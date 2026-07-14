import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** JUnit regression and performance tests for the bounded PTY-to-EDT pipeline. */
@Execution(ExecutionMode.SAME_THREAD)
public final class TerminalPipelineTest {

    private static final int KIB = 1024;
    private static final long TIMEOUT_SECONDS = 15;
    private static final Method ENQUEUE_DATA = method("enqueueData", byte[].class, int.class);
    private static final Method READ_PTY = method("readPty", PtyProcess.class);
    private static final Field OUTPUT_SIZE = field("mOutputSize");

    /**
     * Use input smaller than the 64 KiB byte cap, but expensive enough that the
     * 4 ms elapsed-time cap must yield after an append slice.  The marker runs
     * in the next EDT turn and observes bytes still buffered.
     */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    @DisplayName("P1: EDT drain yields on elapsed time under costly output")
    void edtDrainYieldsOnElapsedTime() throws Exception {
        TestClient client = new TestClient();
        TerminalSession session = new TerminalSession(client);
        byte[] output = repeatedClearScreen(32 * KIB);
        CountDownLatch markerRan = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(-1);
        AtomicLong drainQueuedAt = new AtomicLong();
        AtomicReference<Throwable> edtFailure = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    // A large real-world terminal makes repeated CSI 2 J
                    // sequences exceed the drain's time budget reliably.
                    session.mEmulator = new TerminalEmulator(session, 120, 40, 8, 16, 200, client);
                    drainQueuedAt.set(System.nanoTime());
                    assertTrue(invokeEnqueue(session, output), "failed to enqueue EDT test data");
                    SwingUtilities.invokeLater(() -> {
                        try {
                            remaining.set(intField(OUTPUT_SIZE, session));
                        } catch (Throwable t) {
                            edtFailure.set(t);
                        } finally {
                            session.finish();
                            markerRan.countDown();
                        }
                    });
                } catch (Throwable t) {
                    edtFailure.set(t);
                    session.finish();
                    markerRan.countDown();
                }
            });

            assertTrue(markerRan.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "EDT marker timed out");
            rethrow(edtFailure.get());
            int processed = output.length - remaining.get();
            long firstTurnMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - drainQueuedAt.get());

            assertTrue(remaining.get() > 0,
                "first EDT drain consumed all " + output.length + " bytes despite elapsed-time budget");
            assertTrue(processed > 0 && processed < output.length,
                "unexpected first-drain byte count: " + processed);
            System.out.println("P1 metric: first drain processed " + processed + "/" + output.length
                + " bytes, yielded with " + remaining.get() + " buffered (marker " + firstTurnMs + " ms)");
        } finally {
            session.finish();
        }
    }

    /**
     * Keep the EDT occupied while 224 one-KiB PTY reads arrive.  A seven-slot
     * queue of per-read arrays would stall around 28 KiB; the circular buffer
     * must retain all 224 KiB without consulting InputStream.available().
     */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    @DisplayName("P2: Unix reads coalesce within the intended backpressure budget")
    void unixReadsCoalesceWithinBackpressureBudget() throws Exception {
        final int payloadBytes = 224 * KIB;
        TestClient client = new TestClient();
        TerminalSession session = new TerminalSession(client);
        ChunkedInputStream input = new ChunkedInputStream(payloadBytes, KIB, false);
        FakePtyProcess pty = new FakePtyProcess(input);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicInteger bufferedBeforeDrain = new AtomicInteger(-1);
        AtomicReference<Thread> readerReference = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                session.mEmulator = new TerminalEmulator(session, 80, 24, 8, 16, 200, client);
                Thread reader = readerThread(session, pty, readerFailure);
                readerReference.set(reader);
                reader.start();
                try {
                    reader.join(5_000);
                    bufferedBeforeDrain.set(intField(OUTPUT_SIZE, session));
                } catch (Throwable t) {
                    readerFailure.set(t);
                }
            });

            Thread reader = readerReference.get();
            assertTrue(reader != null && !reader.isAlive(),
                "reader stalled before buffering 224 KiB (backpressure budget is too small)");
            rethrow(readerFailure.get());
            assertTrue(input.availableCalls.get() == 0,
                "InputStream.available() was called " + input.availableCalls.get() + " time(s)");
            assertTrue(bufferedBeforeDrain.get() == payloadBytes,
                "buffer retained " + bufferedBeforeDrain.get() + " bytes, expected " + payloadBytes);
            assertTrue(client.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "buffered PTY output did not drain to EOF");

            long appended = session.consumeAppendedBytes();
            assertTrue(appended == payloadBytes,
                "emulator appended " + appended + " bytes, expected " + payloadBytes);
            assertTrue(client.textChanges.get() < input.readCalls.get(),
                "output was not coalesced: " + client.textChanges.get() + " callbacks for "
                    + input.readCalls.get() + " reads");
            System.out.println("P2 batching metric: " + input.readCalls.get()
                + " small reads, available() calls=" + input.availableCalls.get()
                + ", buffered=" + bufferedBeforeDrain.get() + " bytes, text callbacks="
                + client.textChanges.get());
        } finally {
            session.finish();
        }
    }

    /** A persistent unchecked read failure must be attempted once, then end the session. */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    @DisplayName("P2: Persistent unchecked PTY failure terminates the session")
    void persistentPtyFailureTerminatesSession() throws Exception {
        TestClient client = new TestClient();
        TerminalSession session = new TerminalSession(client);
        ChunkedInputStream input = new ChunkedInputStream(0, KIB, true);
        FakePtyProcess pty = new FakePtyProcess(input);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = readerThread(session, pty, readerFailure);
        PrintStream originalError = System.err;
        ByteArrayOutputStream capturedError = new ByteArrayOutputStream();

        try (PrintStream capture = new PrintStream(capturedError, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            reader.start();
            reader.join(2_000);
            assertTrue(client.finished.await(2, TimeUnit.SECONDS),
                "session did not finish after persistent PTY failure");
        } finally {
            System.setErr(originalError);
            session.finish();
        }

        rethrow(readerFailure.get());
        assertTrue(!reader.isAlive(), "PTY reader remained alive after unchecked failure");
        assertTrue(input.readCalls.get() == 1,
            "persistent failure was retried " + input.readCalls.get() + " times");
        String log = capturedError.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("terminal-session pty reader failed"),
            "persistent failure was not logged");
        System.out.println("P2 failure metric: read attempts=" + input.readCalls.get()
            + ", reader terminated and session finished");
    }

    /** Start the real daemon main method without XDG_RUNTIME_DIR and inspect its process status. */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    @DisplayName("P2: Fatal daemon startup failure returns a nonzero status")
    void fatalDaemonStartupReturnsNonzeroStatus() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
            java,
            "-Djava.awt.headless=true",
            "-cp",
            System.getProperty("java.class.path"),
            "com.termux.desktop.TermuxDesktopDaemon"
        );
        builder.environment().remove("XDG_RUNTIME_DIR");
        builder.redirectErrorStream(true);
        Process daemon = builder.start();
        boolean exited = daemon.waitFor(10, TimeUnit.SECONDS);
        if (!exited) daemon.destroyForcibly();
        String output = new String(daemon.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "daemon failure subprocess did not exit");
        assertTrue(daemon.exitValue() == 1,
            "fatal daemon startup returned exit status " + daemon.exitValue() + ", expected 1");
        assertTrue(output.contains("XDG_RUNTIME_DIR is not set"),
            "daemon did not report the startup cause");
        System.out.println("P2 daemon metric: missing XDG_RUNTIME_DIR produced exit status "
            + daemon.exitValue());
    }

    private static Thread readerThread(
        TerminalSession session,
        PtyProcess pty,
        AtomicReference<Throwable> failure
    ) {
        return new Thread(() -> {
            try {
                READ_PTY.invoke(session, pty);
            } catch (InvocationTargetException e) {
                failure.set(e.getCause());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "pipeline-test-reader");
    }

    private static boolean invokeEnqueue(TerminalSession session, byte[] data) throws Exception {
        try {
            return (Boolean) ENQUEUE_DATA.invoke(session, data, data.length);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            return false;
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = TerminalSession.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = TerminalSession.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int intField(Field field, Object instance) throws IllegalAccessException {
        return field.getInt(instance);
    }

    private static byte[] repeatedClearScreen(int length) {
        byte[] sequence = "\u001B[2J".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[length];
        for (int offset = 0; offset < result.length; offset += sequence.length) {
            System.arraycopy(sequence, 0, result, offset, Math.min(sequence.length, result.length - offset));
        }
        return result;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new RuntimeException(failure);
    }

    private static final class ChunkedInputStream extends InputStream {
        private final int totalBytes;
        private final int chunkBytes;
        private final boolean failReads;
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger availableCalls = new AtomicInteger();
        private int position;

        ChunkedInputStream(int totalBytes, int chunkBytes, boolean failReads) {
            this.totalBytes = totalBytes;
            this.chunkBytes = chunkBytes;
            this.failReads = failReads;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            readCalls.incrementAndGet();
            if (failReads) throw new AssertionError("persistent native PTY failure");
            if (position == totalBytes) return -1;
            int count = Math.min(Math.min(length, chunkBytes), totalBytes - position);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            position += count;
            return count;
        }

        @Override
        public int available() {
            availableCalls.incrementAndGet();
            throw new AssertionError("available() must not gate Unix PTY batching");
        }
    }

    private static final class FakePtyProcess extends PtyProcess {
        private final InputStream input;

        FakePtyProcess(InputStream input) {
            this.input = input;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() {}
        @Override public void setWinSize(WinSize size) {}
        @Override public WinSize getWinSize() throws IOException { return new WinSize(80, 24); }
    }

    private static final class TestClient implements TerminalSessionClient {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger textChanges = new AtomicInteger();

        @Override public void onTextChanged(TerminalSession session) { textChanges.incrementAndGet(); }
        @Override public void onSessionFinished(TerminalSession session) { finished.countDown(); }
        @Override public void onTitleChanged(TerminalSession session) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
