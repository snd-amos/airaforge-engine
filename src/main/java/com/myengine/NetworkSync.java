package com.myengine;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

/**
 * Phase 3 — IPC foundation for the multi-process editor. Fast, lossy UDP
 * transport between the Editor (server: broadcasts entity transforms) and a
 * standalone Runtime instance (client: listens + applies them).
 *
 * <p>Both ends use a non-blocking {@link DatagramChannel} so polling never
 * stalls the render loop — {@code receive()} returns null immediately when no
 * packet is waiting. This turn only establishes the sockets; the actual
 * transform serialization / per-frame pump lands in Phase 3B.
 */
final class NetworkSync {

    private DatagramChannel channel;
    private boolean server;
    /** Loopback address the editor blasts packets at (same machine, child proc). */
    private InetSocketAddress peer;

    private NetworkSync() {}

    /** Editor side: bind nothing, just open a non-blocking channel and aim it
     *  at the loopback runtime port so {@code send()} can broadcast. */
    static NetworkSync initServer(int port) {
        NetworkSync n = new NetworkSync();
        n.server = true;
        try {
            n.channel = DatagramChannel.open();
            n.channel.configureBlocking(false);
            n.peer = new InetSocketAddress("127.0.0.1", port);
            Logger.info("[NET] IPC server ready — broadcasting to 127.0.0.1:" + port);
        } catch (Exception e) {
            Logger.error("[NET] initServer failed", e);
            n.channel = null;
        }
        return n;
    }

    /** Runtime side: bind the loopback port and listen non-blocking for the
     *  editor's transform packets. */
    static NetworkSync initClient(int port) {
        NetworkSync n = new NetworkSync();
        n.server = false;
        try {
            n.channel = DatagramChannel.open();
            n.channel.configureBlocking(false);
            n.channel.socket().setReuseAddress(true);
            n.channel.bind(new InetSocketAddress("127.0.0.1", port));
            Logger.info("[NET] IPC client listening on 127.0.0.1:" + port);
        } catch (Exception e) {
            Logger.error("[NET] initClient failed", e);
            n.channel = null;
        }
        return n;
    }

    boolean isOpen() { return channel != null && channel.isOpen(); }
    boolean isServer() { return server; }

    /** Editor → broadcast a packet to the runtime (no-op until wired in 3B). */
    void send(java.nio.ByteBuffer data) {
        if (channel == null || peer == null) return;
        try {
            channel.send(data, peer);
        } catch (Exception e) {
            // UDP is lossy by design — swallow transient send errors.
        }
    }

    /** Runtime → poll for one waiting packet, or null if none. Non-blocking. */
    java.net.SocketAddress receive(java.nio.ByteBuffer dst) {
        if (channel == null) return null;
        try {
            return channel.receive(dst);
        } catch (Exception e) {
            return null;
        }
    }

    void close() {
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
        }
    }
}
