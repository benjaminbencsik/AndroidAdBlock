package app.androidadblock;

/** Minimal IPv4/IPv6 + UDP parsing and building for packets read from the tun device. */
final class Packets {
    private Packets() {}

    static final class Udp {
        boolean v6;
        byte[] src;
        byte[] dst;
        int srcPort;
        int dstPort;
        byte[] payload;
    }

    /** Returns the UDP datagram carried by an IP packet, or null if it is anything else. */
    static Udp parse(byte[] b, int len) {
        if (len < 1) return null;
        int version = (b[0] & 0xff) >> 4;
        int udpAt;
        Udp u = new Udp();
        if (version == 4) {
            if (len < 20) return null;
            int ihl = (b[0] & 0x0f) * 4;
            if (ihl < 20 || len < ihl + 8) return null;
            if ((b[9] & 0xff) != 17) return null;
            // Fragments other than the first carry no UDP header.
            if ((u16(b, 6) & 0x1fff) != 0) return null;
            u.src = slice(b, 12, 4);
            u.dst = slice(b, 16, 4);
            udpAt = ihl;
        } else if (version == 6) {
            if (len < 48) return null;
            if ((b[6] & 0xff) != 17) return null;
            u.v6 = true;
            u.src = slice(b, 8, 16);
            u.dst = slice(b, 24, 16);
            udpAt = 40;
        } else {
            return null;
        }
        u.srcPort = u16(b, udpAt);
        u.dstPort = u16(b, udpAt + 2);
        int udpLen = u16(b, udpAt + 4);
        if (udpLen < 8 || udpAt + udpLen > len) return null;
        u.payload = slice(b, udpAt + 8, udpLen - 8);
        return u;
    }

    /** Builds the IP packet that answers {@code req} with the given UDP payload. */
    static byte[] reply(Udp req, byte[] payload) {
        int udpLen = 8 + payload.length;
        int ipLen = req.v6 ? 40 : 20;
        byte[] p = new byte[ipLen + udpLen];
        if (req.v6) {
            p[0] = 0x60;
            put16(p, 4, udpLen);
            p[6] = 17;
            p[7] = 64;
            System.arraycopy(req.dst, 0, p, 8, 16);
            System.arraycopy(req.src, 0, p, 24, 16);
        } else {
            p[0] = 0x45;
            put16(p, 2, p.length);
            put16(p, 6, 0x4000);
            p[8] = 64;
            p[9] = 17;
            System.arraycopy(req.dst, 0, p, 12, 4);
            System.arraycopy(req.src, 0, p, 16, 4);
            put16(p, 10, fold(sum(p, 0, 20, 0)));
        }
        put16(p, ipLen, req.dstPort);
        put16(p, ipLen + 2, req.srcPort);
        put16(p, ipLen + 4, udpLen);
        System.arraycopy(payload, 0, p, ipLen + 8, payload.length);

        // UDP checksum over the pseudo-header (addresses, protocol, length) plus the datagram.
        long s = req.v6 ? sum(p, 8, 32, 0) : sum(p, 12, 8, 0);
        s += 17 + udpLen;
        int c = fold(sum(p, ipLen, udpLen, s));
        put16(p, ipLen + 6, c == 0 ? 0xffff : c);
        return p;
    }

    static long sum(byte[] b, int off, int len, long start) {
        long s = start;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            s += u16(b, i);
            i += 2;
        }
        if (i < end) s += (b[i] & 0xff) << 8;
        return s;
    }

    static int fold(long s) {
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(b, off, r, 0, len);
        return r;
    }
}
