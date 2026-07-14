package com.termux.desktop;

import javax.swing.SwingUtilities;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Persistent Swing process which opens terminal windows on request. */
public final class TermuxDesktopDaemon {

    private final Map<String, Font> fonts = new HashMap<>();

    public static void main(String[] args) {
        installSupervisionHandler();
        try {
            new TermuxDesktopDaemon().run();
        } catch (Throwable t) {
            logFailure("daemon", t);
            System.exit(1);
        }
    }

    private static void installSupervisionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
            logFailure("uncaught thread " + thread.getName(), failure));
    }

    private static void logFailure(String operation, Throwable failure) {
        System.err.println("termux-desktop " + operation + " failed: " + failure);
        failure.printStackTrace(System.err);
    }

    private void run() throws Exception {
        long startupNanos = System.nanoTime();
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (runtimeDir == null || runtimeDir.isEmpty()) {
            throw new IllegalStateException("XDG_RUNTIME_DIR is not set");
        }

        Path socketPath = Path.of(runtimeDir, "termux-desktop.sock");
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        warmUp();
        ServerSocketChannel server = bind(address, socketPath);
        if (server == null) return;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException ignored) {
            }
        }, "socket-cleanup"));

        long startupMs = (System.nanoTime() - startupNanos) / 1_000_000L;
        System.err.println("daemon-startup-ms: " + startupMs);

        try (server) {
            while (true) {
                try (SocketChannel client = server.accept()) {
                    try {
                        handle(client);
                    } catch (Throwable t) {
                        // A request is a supervision boundary.  A bad client
                        // must not unwind the accept loop or close other windows.
                        logFailure("socket request", t);
                    }
                } catch (Throwable t) {
                    logFailure("socket accept", t);
                }
            }
        }
    }

    private void warmUp() throws Exception {
        Font font = font(TermuxDesktop.DEFAULT_FONT_PATH, TermuxDesktop.DEFAULT_FONT_SIZE);
        SwingUtilities.invokeAndWait(() -> new Java2DRenderer(TermuxDesktop.DEFAULT_FONT_SIZE, font));
    }

    private ServerSocketChannel bind(UnixDomainSocketAddress address, Path socketPath) throws IOException {
        try {
            return openServer(address);
        } catch (IOException bindFailure) {
            if (canConnect(address)) {
                System.err.println("termux-desktop daemon is already running");
                return null;
            }
            Files.deleteIfExists(socketPath);
            return openServer(address);
        }
    }

    private static ServerSocketChannel openServer(UnixDomainSocketAddress address) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(address);
            return server;
        } catch (IOException e) {
            server.close();
            throw e;
        }
    }

    private static boolean canConnect(UnixDomainSocketAddress address) {
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(address);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void handle(SocketChannel client) {
        final BufferedReader reader;
        final BufferedWriter writer;
        try {
            reader = new BufferedReader(new InputStreamReader(
                Channels.newInputStream(client), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(
                Channels.newOutputStream(client), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            logFailure("socket setup", t);
            return;
        }

        String request;
        try {
            request = reader.readLine();
        } catch (Throwable t) {
            logFailure("socket read", t);
            return;
        }

        long requestNanos = System.nanoTime();
        String response;
        try {
            OpenRequest open = parse(request);
            Font font = font(open.fontPath, open.size);
            AtomicReference<Throwable> error = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TermuxDesktop.openWindow(font, open.size);
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            if (error.get() != null) throw new RequestFailure(error.get());
            long openMs = (System.nanoTime() - requestNanos) / 1_000_000L;
            response = "ok " + openMs;
            System.err.println("window-open-ms: " + openMs);
        } catch (Throwable t) {
            logFailure("request", t);
            response = "error " + singleLine(t);
        }

        try {
            writer.write(response);
            writer.newLine();
            writer.flush();
        } catch (Throwable t) {
            logFailure("socket response", t);
        }
    }

    private Font font(String path, int size) throws Exception {
        Font cached = fonts.get(path);
        if (cached == null) {
            cached = TermuxDesktop.loadFont(path, size);
            fonts.put(path, cached);
        }
        return cached;
    }

    private static OpenRequest parse(String request) {
        if (request == null || !(request.equals("open") || request.startsWith("open "))) {
            throw new IllegalArgumentException("expected: open [fontPath] [size]");
        }

        String arguments = request.length() == 4 ? "" : request.substring(5).trim();
        String fontPath = TermuxDesktop.DEFAULT_FONT_PATH;
        int size = TermuxDesktop.DEFAULT_FONT_SIZE;
        if (!arguments.isEmpty()) {
            int lastSpace = arguments.lastIndexOf(' ');
            if (lastSpace < 0) {
                fontPath = arguments;
            } else {
                fontPath = arguments.substring(0, lastSpace).trim();
                size = Integer.parseInt(arguments.substring(lastSpace + 1).trim());
            }
        }
        if (fontPath.isEmpty()) throw new IllegalArgumentException("fontPath is empty");
        if (size < 6 || size > 200) throw new IllegalArgumentException("size must be between 6 and 200");
        return new OpenRequest(fontPath, size);
    }

    private static String singleLine(String message) {
        if (message == null || message.isEmpty()) return "request failed";
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String singleLine(Throwable failure) {
        if (failure instanceof RequestFailure && failure.getCause() != null)
            return singleLine(failure.getCause().getMessage());
        return singleLine(failure == null ? null : failure.getMessage());
    }

    private static final class RequestFailure extends RuntimeException {
        RequestFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class OpenRequest {
        final String fontPath;
        final int size;

        OpenRequest(String fontPath, int size) {
            this.fontPath = fontPath;
            this.size = size;
        }
    }
}
