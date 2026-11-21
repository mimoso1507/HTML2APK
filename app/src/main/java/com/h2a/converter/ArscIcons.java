package com.h2a.converter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lee resources.arsc y devuelve las rutas de archivo de los recursos llamados ic_launcher. */
public final class ArscIcons {
    private static List<String> pool(ByteBuffer b, int off) {
        int cnt = b.getInt(off + 8), flags = b.getInt(off + 16), start = b.getInt(off + 20);
        boolean utf8 = (flags & 0x100) != 0;
        List<String> r = new ArrayList<>(cnt);
        for (int i = 0; i < cnt; i++) {
            int o = off + start + b.getInt(off + 28 + 4 * i);
            if (utf8) {
                int l = b.get(o) & 0xff; o += (l & 0x80) != 0 ? 2 : 1;
                int bl = b.get(o) & 0xff; o++;
                if ((bl & 0x80) != 0) { bl = ((bl & 0x7f) << 8) | (b.get(o) & 0xff); o++; }
                byte[] d = new byte[bl];
                for (int k = 0; k < bl; k++) d[k] = b.get(o + k);
                r.add(new String(d, StandardCharsets.UTF_8));
            } else {
                int l = b.getShort(o) & 0xffff; o += 2;
                char[] c = new char[l];
                for (int k = 0; k < l; k++) c[k] = (char) (b.getShort(o + 2 * k) & 0xffff);
                r.add(new String(c));
            }
        }
        return r;
    }

    public static Set<String> iconPaths(byte[] arsc, String resName) {
        Set<String> out = new LinkedHashSet<>();
        try {
            ByteBuffer b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
            int gpSize = b.getInt(12 + 4);
            List<String> global = pool(b, 12);
            int pkg = 12 + gpSize;
            while (pkg < arsc.length) {
                int type = b.getShort(pkg) & 0xffff, hs = b.getShort(pkg + 2) & 0xffff, sz = b.getInt(pkg + 4);
                if (type == 0x0200) {
                    int keyOff = b.getInt(pkg + 8 + 4 + 256 + 8);
                    List<String> keys = pool(b, pkg + keyOff);
                    int p = pkg + hs, end = pkg + sz;
                    while (p < end) {
                        int t = b.getShort(p) & 0xffff, h = b.getShort(p + 2) & 0xffff, s = b.getInt(p + 4);
                        if (t == 0x0201) {
                            int ec = b.getInt(p + 12), es = b.getInt(p + 16);
                            for (int i = 0; i < ec; i++) {
                                int o = b.getInt(p + h + 4 * i);
                                if (o == -1) continue;
                                int eo = p + es + o;
                                int flags = b.getShort(eo + 2) & 0xffff, key = b.getInt(eo + 4);
                                if ((flags & 1) != 0 || key >= keys.size() || !resName.equals(keys.get(key))) continue;
                                int dt = b.get(eo + 8 + 3) & 0xff, data = b.getInt(eo + 8 + 4);
                                if (dt == 3 && data >= 0 && data < global.size()) out.add(global.get(data));
                            }
                        }
                        if (s <= 0) break;
                        p += s;
                    }
                }
                if (sz <= 0) break;
                pkg += sz;
            }
        } catch (RuntimeException ignored) { }
        return out;
    }
}
