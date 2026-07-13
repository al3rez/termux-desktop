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

    public static void main(String[] args) throws Exception {
        new TermuxDesktopDaemon().run();
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
                    handle(client);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
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

    private void handle(SocketChannel client) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            Channels.newInputStream(client), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
            Channels.newOutputStream(client), StandardCharsets.UTF_8));

        String request = reader.readLine();
        long requestNanos = System.nanoTime();
        String response;
        try {
            OpenRequest open = parse(request);
            Font font = font(open.fontPath, open.size);
            AtomicReference<Exception> error = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TermuxDesktop.openWindow(font, open.size);
                } catch (Exception e) {
                    error.set(e);
                }
            });
            if (error.get() != null) throw error.get();
            long openMs = (System.nanoTime() - requestNanos) / 1_000_000L;
            response = "ok " + openMs;
            System.err.println("window-open-ms: " + openMs);
        } catch (Exception e) {
            response = "error " + singleLine(e.getMessage());
        }

        writer.write(response);
        writer.newLine();
        writer.flush();
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

    private static final class OpenRequest {
        final String fontPath;
        final int size;

        OpenRequest(String fontPath, int size) {
            this.fontPath = fontPath;
            this.size = size;
        }
    }
}
