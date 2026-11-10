package app.androidadblock;

/** Just enough DNS to read the question of a query and to answer it with "nothing here". */
final class DnsMessage {
    private DnsMessage() {}

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;

    static final class Question {
        String name;
        int type;
        /** Offset just past the question section. */
        int end;
    }

    /** Returns the first question of a standard query, or null if the message is not one. */
    static Question parse(byte[] m) {
        if (m.length < 17) return null;
        if ((m[2] & 0x80) != 0) return null;            // a response, not a query
        if (Packets.u16(m, 4) < 1) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (true) {
            if (i >= m.length) return null;
            int n = m[i] & 0xff;
            i++;
            if (n == 0) break;
            if (n > 63 || i + n > m.length) return null; // compression never appears in a question
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < n; k++) {
                char c = (char) (m[i + k] & 0xff);
                if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
                sb.append(c);
            }
            i += n;
        }
        if (i + 4 > m.length) return null;
        Question q = new Question();
        q.name = sb.toString();
        q.type = Packets.u16(m, i);
        q.end = i + 4;
        return q;
    }

    /**
     * Answer for a blocked name: 0.0.0.0 for A, :: for AAAA, and an empty
     * answer for every other type, so apps fail fast instead of retrying.
     */
    static byte[] blocked(byte[] query, Question q) {
        boolean addr = q.type == TYPE_A || q.type == TYPE_AAAA;
        int rdLen = q.type == TYPE_A ? 4 : 16;
        byte[] r = new byte[q.end + (addr ? 12 + rdLen : 0)];
        System.arraycopy(query, 0, r, 0, q.end);
        r[2] = (byte) (0x80 | (query[2] & 0x79));        // response; keep opcode and RD
        r[3] = (byte) 0x80;                              // recursion available, NOERROR
        Packets.put16(r, 4, 1);
        Packets.put16(r, 6, addr ? 1 : 0);
        Packets.put16(r, 8, 0);
        Packets.put16(r, 10, 0);
        if (addr) {
            int i = q.end;
            Packets.put16(r, i, 0xc00c);                 // pointer to the question name
            Packets.put16(r, i + 2, q.type);
            Packets.put16(r, i + 4, 1);                  // class IN
            Packets.put16(r, i + 8, 60);                 // TTL (low 16 bits)
            Packets.put16(r, i + 10, rdLen);
        }
        return r;
    }
}
