package com.codearena.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A TCP forwarder that can be switched off, so a test can take a dependency away and give
 * it back.
 *
 * <h2>Why not simply stop the container</h2>
 * Testcontainers gives a restarted container a new mapped port, which the Spring context
 * under test has already been told about — so "stop it and start it again" leaves the
 * application pointing at nothing and tests the wrong thing. Putting a proxy in front means
 * the address the application knows never changes, while what is behind it can fail and
 * recover as often as a test likes.
 *
 * <p>It is also honest about what it simulates. Switching the proxy off drops every live
 * connection and refuses new ones, which is what the application sees when Redis dies: not
 * a polite error, but sockets that close and connections that will not open.
 */
public class FailableTcpProxy implements AutoCloseable {

    private final ServerSocket listener;
    private final String targetHost;
    private final int targetPort;
    private final ExecutorService pump = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tcp-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Socket> live = new CopyOnWriteArrayList<>();

    private volatile boolean up = true;

    public FailableTcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0);
        pump.submit(this::acceptLoop);
    }

    /** The port the application should be pointed at. */
    public int port() {
        return listener.getLocalPort();
    }

    /** Take the dependency away: drop what is open, refuse what arrives. */
    public void breakConnection() {
        up = false;
        for (Socket socket : live) {
            closeQuietly(socket);
        }
        live.clear();
    }

    /** Give it back. */
    public void restoreConnection() {
        up = true;
    }

    @Override
    public void close() {
        up = false;
        closeQuietly(listener);
        live.forEach(FailableTcpProxy::closeQuietly);
        pump.shutdownNow();
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket inbound = listener.accept();
                if (!up) {
                    // Refusing by closing immediately, rather than by not listening at all:
                    // the client sees a connection that dies, which is what a dead server
                    // behind a live host looks like.
                    closeQuietly(inbound);
                    continue;
                }
                Socket outbound = new Socket();
                outbound.connect(new InetSocketAddress(targetHost, targetPort), 2000);
                live.add(inbound);
                live.add(outbound);
                pump.submit(() -> copy(inbound, outbound));
                pump.submit(() -> copy(outbound, inbound));
            } catch (IOException e) {
                if (listener.isClosed()) {
                    return;
                }
                // A failed accept or connect is exactly the condition being simulated.
            }
        }
    }

    private void copy(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Either end going away ends this direction; the test is usually the cause.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not a failure.
        }
    }
}
