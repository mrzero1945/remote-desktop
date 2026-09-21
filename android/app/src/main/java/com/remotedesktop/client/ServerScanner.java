package com.remotedesktop.client;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LAN scanner for Remote Desktop servers.
 *
 * A matching server is identified by sending it a protocol HANDSHAKE; the
 * server answers with HANDSHAKE_ACK only if it is a real Remote Desktop
 * server. One UDP socket fires probes at many candidate ip:port pairs and
 * collects the ACKs, which are reported back as Match entries.
 */
public final class ServerScanner {

    public interface Callback {
        void onScanStarted(int probeCount);

        void onResult(List<Match> matches);
    }

    /** A single responding host:port pair. */
    public static final class Match {
        public final String ip;
        public final int port;

        public Match(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String display() {
            return ip + ":" + port;
        }
    }

    private static final String TAG = "ServerScanner";
    private static final int RECV_TIMEOUT_MS = 15;
    private static final int WAIT_DEADLINE_MS = 800;

    private ServerScanner() {
    }

    /** Build the exact HANDSHAKE datagram the real client would send. */
    public static byte[] buildHandshakeBytes() {
        ByteBuffer payload = ByteBuffer.allocate(Proto.HANDSHAKE_PAYLOAD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(0); // screen width
        payload.putInt(0); // screen height
        payload.putInt(0); // fps
        payload.putInt(0); // encoder id
        payload.put(new byte[32]); // session key
        payload.put((byte) Proto.FEC_DEFAULT_LEVEL);
        ByteBuffer pkt = Proto.buildPacket(Proto.TYPE_HANDSHAKE,
                Proto.HANDSHAKE_PAYLOAD_SIZE);
        pkt.put(payload.array(), 0, Proto.HANDSHAKE_PAYLOAD_SIZE);
        Proto.fillChecksum(pkt);
        byte[] out = new byte[Proto.HEADER_SIZE + Proto.HANDSHAKE_PAYLOAD_SIZE];
        System.arraycopy(pkt.array(), 0, out, 0, out.length);
        return out;
    }

    private static byte[] buildDisconnectBytes() {
        ByteBuffer pkt = Proto.buildPacket(Proto.TYPE_DISCONNECT, 0);
        byte[] out = new byte[Proto.HEADER_SIZE];
        System.arraycopy(pkt.array(), 0, out, 0, Proto.HEADER_SIZE);
        return out;
    }

    /**
     * Enumerate the /24 candidate hosts on every IPv4 interface this device
     * has (wifi, ethernet, cellular tethering, ...). Own address is skipped.
     */
    public static List<String> enumerateLocalIpCandidates() {
        Set<String> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nis =
                    NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp()) continue;
                if (ni.getName().toLowerCase().contains("loopback")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress()) continue;
                    if (!a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    if (b.length != 4) continue;
                    int myLast = b[3] & 0xFF;
                    UiLog.i(TAG, "enumerate: subnet " + (b[0] & 0xFF) + "."
                            + (b[1] & 0xFF) + "." + (b[2] & 0xFF)
                            + ".x from iface " + ni.getName()
                            + " (own ." + myLast + ")");
                    for (int host = 1; host <= 254; host++) {
                        if (host == myLast) continue;
                        out.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + "."
                                + (b[2] & 0xFF) + "." + host);
                    }
                }
            }
        } catch (IOException e) {
            UiLog.w(TAG, "enumerateLocalIpCandidates failed", e);
        }
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * Probe every ip:port pair with a HANDSHAKE and call back with the matches.
     * Runs on the calling thread; must not be called from the UI thread.
     */
    public static void scan(List<String> ips, List<Integer> ports,
                            Callback cb) throws IOException {
        final byte[] probe = buildHandshakeBytes();
        final byte[] bye = buildDisconnectBytes();
        final Set<String> probed = new HashSet<>();
        List<Match> matches = new ArrayList<>();

        DatagramSocket socket = new DatagramSocket();
        socket.setReuseAddress(true);
        socket.setSoTimeout(RECV_TIMEOUT_MS);
        try {
            if (cb != null) cb.onScanStarted(ips.size() * ports.size());

            for (String ip : ips) {
                InetAddress addr = InetAddress.getByName(ip);
                for (int port : ports) {
                    probed.add(ip + ":" + port);
                    DatagramPacket p = new DatagramPacket(
                            probe, probe.length, addr, port);
                    socket.send(p);
                }
            }

            long deadline = System.nanoTime()
                    + WAIT_DEADLINE_MS * 1_000_000L;
            byte[] recv = new byte[Proto.MAX_PACKET_SIZE];
            Set<String> found = new HashSet<>();
            while (System.nanoTime() < deadline) {
                DatagramPacket dp = new DatagramPacket(recv, recv.length);
                try {
                    socket.receive(dp);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String src = dp.getAddress().getHostAddress() + ":"
                        + dp.getPort();
                if (isAck(dp.getData(), dp.getLength())
                        && probed.contains(src) && found.add(src)) {
                    int colon = src.indexOf(':');
                    String ip = src.substring(0, colon);
                    int port = Integer.parseInt(src.substring(colon + 1));
                    matches.add(new Match(ip, port));

                    // Politely leave the server before it starts streaming to
                    // the probe socket. A real connect() re-registers anyway.
                    DatagramPacket byePkt = new DatagramPacket(
                            bye, bye.length,
                            dp.getAddress(), dp.getPort());
                    socket.send(byePkt);
                }
            }
        } finally {
            socket.close();
        }
        if (cb != null) cb.onResult(matches);
    }

    /** True if this datagram looks like a protocol HANDSHAKE_ACK. */
    private static boolean isAck(byte[] data, int len) {
        if (data == null || len < Proto.HEADER_SIZE) return false;
        ByteBuffer hb = ByteBuffer.wrap(data, 0, Proto.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        return (hb.getShort() & 0xFFFF) == Proto.MAGIC
                && hb.get() == Proto.TYPE_HANDSHAKE_ACK;
    }
}