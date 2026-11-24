package com.tvdual.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二进制 AndroidManifest（AXML）改写器。
 *
 * 只做三件事：改属性值、删属性、把字符串池扩容——不重排元素，所以不会破坏未知结构。
 * 关键技巧：新字符串<b>追加</b>到池尾，保留原有下标不变，只把引用处的索引改掉；
 * chunk 是顺序排列的，池变大只会让后面的 chunk 整体后移，没有需要修正的偏移。
 */
public final class Axml {

    private static final int RES_XML = 0x0003;
    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int RES_XML_END_ELEMENT = 0x0103;

    private static final int TYPE_STRING = 0x03;

    /** 属性改写规则：返回 null 表示保持原值，返回新值则替换。 */
    public interface Rule {
        String map(String element, String ns, String attr, String value);
    }

    /** 需要整条删除的属性（例如 sharedUserId）。 */
    public interface Remover {
        boolean remove(String element, String ns, String attr, String value);
    }

    private Axml() {}

    public static byte[] rewrite(byte[] in, Rule rule, Remover remover) {
        if (Bytes.u16(in, 0) != RES_XML) {
            throw new IllegalArgumentException("不是二进制 XML（type=0x"
                    + Integer.toHexString(Bytes.u16(in, 0)) + "）");
        }
        int outerHeader = Bytes.u16(in, 2);

        // 1) 找出字符串池与各 chunk 区间
        int poolStart = -1;
        int poolEnd = -1;
        List<int[]> chunks = new ArrayList<>();      // {start, end, type}
        int pos = outerHeader;
        while (pos + 8 <= in.length) {
            int type = Bytes.u16(in, pos);
            int size = Bytes.i32(in, pos + 4);
            if (size <= 0 || pos + size > in.length) break;
            chunks.add(new int[]{pos, pos + size, type});
            if (type == RES_STRING_POOL && poolStart < 0) {
                poolStart = pos;
                poolEnd = pos + size;
            }
            pos += size;
        }
        if (poolStart < 0) throw new IllegalArgumentException("没有字符串池");

        Pool pool = Pool.parse(in, poolStart, poolEnd);

        // 2) 逐 chunk 改写（元素 chunk 需要重建，其余原样拷贝）
        List<byte[]> out = new ArrayList<>();
        out.add(Bytes.slice(in, 0, outerHeader));

        String[] stack = new String[64];
        int depth = 0;

        for (int[] c : chunks) {
            int start = c[0], end = c[1], type = c[2];
            if (type == RES_STRING_POOL) {
                continue;                            // 最后统一重建
            }
            if (type == RES_XML_START_ELEMENT) {
                String element = pool.get(Bytes.i32(in, start + 20));
                out.add(rewriteElement(in, start, end, pool, element, rule, remover));
                if (depth < stack.length) stack[depth] = element;
                depth++;
            } else if (type == RES_XML_END_ELEMENT) {
                depth = Math.max(0, depth - 1);
                out.add(Bytes.slice(in, start, end));
            } else {
                out.add(Bytes.slice(in, start, end));
            }
        }

        // 3) 重建字符串池，填回原位置
        byte[] newPool = pool.serialize();
        List<byte[]> head = new ArrayList<>();
        head.add(Bytes.slice(in, 0, poolStart));
        head.add(newPool);
        head.addAll(out.subList(1, out.size()));

        byte[] merged = Bytes.concat(head.toArray(new byte[0][]));
        Bytes.w32(merged, 4, merged.length);          // 外层 size
        return merged;
    }

    private static byte[] rewriteElement(byte[] in, int start, int end, Pool pool,
                                         String element, Rule rule, Remover remover) {
        int chunkHeader = Bytes.u16(in, start + 2);
        int attrStart = Bytes.u16(in, start + 16 + 8);   // node(16) + ns(4)+name(4) 后是 attributeStart
        int attrSize = Bytes.u16(in, start + 16 + 10);
        int attrCount = Bytes.u16(in, start + 16 + 12);
        if (attrSize != 20) return Bytes.slice(in, start, end);   // 未知布局，不碰

        int attrsOff = start + 16 + attrStart;
        List<byte[]> kept = new ArrayList<>();
        int keptCount = 0;

        for (int i = 0; i < attrCount; i++) {
            int a = attrsOff + i * 20;
            if (a + 20 > end) break;
            int nsIdx = Bytes.i32(in, a);
            int nameIdx = Bytes.i32(in, a + 4);
            int rawIdx = Bytes.i32(in, a + 8);
            int type = Bytes.u8(in, a + 15);
            int data = Bytes.i32(in, a + 16);

            String ns = nsIdx >= 0 ? pool.get(nsIdx) : null;
            String name = pool.get(nameIdx);
            String value = type == TYPE_STRING ? pool.get(data) : null;

            if (remover != null && remover.remove(element, ns, name, value)) {
                continue;
            }
            if (rule != null) {
                String mapped = rule.map(element, ns, name, value);
                if (mapped != null && !mapped.equals(value)) {
                    int idx = pool.add(mapped);
                    byte[] attr = Bytes.slice(in, a, a + 20);
                    Bytes.w32(attr, 8, idx);              // rawValue
                    attr[15] = (byte) TYPE_STRING;        // typedValue.type
                    Bytes.w32(attr, 16, idx);             // typedValue.data
                    kept.add(attr);
                    keptCount++;
                    continue;
                }
            }
            kept.add(Bytes.slice(in, a, a + 20));
            keptCount++;
        }

        byte[] head = Bytes.slice(in, start, attrsOff);
        Bytes.w16(head, 16 + 12, keptCount);              // attributeCount
        byte[] body = Bytes.concat(kept.toArray(new byte[0][]));
        byte[] chunk = Bytes.concat(head, body);
        Bytes.w32(chunk, 4, chunk.length);                // chunk size
        if (chunkHeader != 16) {
            // headerSize 不是 16 的少见情况直接放弃改写
            return Bytes.slice(in, start, end);
        }
        return chunk;
    }

    // ------------------------------------------------------------------ 字符串池
    static final class Pool {
        private final List<String> strings = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();
        private boolean utf8;
        private byte[] tail = new byte[0];
        private int styleCount;

        static Pool parse(byte[] b, int start, int end) {
            Pool p = new Pool();
            int headerSize = Bytes.u16(b, start + 2);
            int count = Bytes.i32(b, start + 8);
            p.styleCount = Bytes.i32(b, start + 12);
            int flags = Bytes.i32(b, start + 16);
            p.utf8 = (flags & 0x100) != 0;
            int stringsStart = Bytes.i32(b, start + 20);
            int stylesStart = Bytes.i32(b, start + 24);
            int offsetsBase = start + headerSize;
            for (int i = 0; i < count; i++) {
                int off = Bytes.i32(b, offsetsBase + i * 4);
                String s = p.utf8
                        ? readUtf8(b, start + stringsStart + off)
                        : readUtf16(b, start + stringsStart + off);
                p.strings.add(s);
                if (!p.index.containsKey(s)) p.index.put(s, i);
            }
            if (p.styleCount > 0 && stylesStart > 0) {
                p.tail = Bytes.slice(b, start + stylesStart, end);
            }
            return p;
        }

        String get(int i) {
            return (i >= 0 && i < strings.size()) ? strings.get(i) : "";
        }

        int add(String s) {
            Integer old = index.get(s);
            if (old != null) return old;
            strings.add(s);
            int i = strings.size() - 1;
            index.put(s, i);
            return i;
        }

        byte[] serialize() {
            int count = strings.size();
            int stylesStart = 0;

            // 字符串数据
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            int[] offs = new int[count];
            for (int i = 0; i < count; i++) {
                offs[i] = data.size();
                byte[] enc = utf8 ? encodeUtf8(strings.get(i)) : encodeUtf16(strings.get(i));
                data.write(enc, 0, enc.length);
            }
            while (data.size() % 4 != 0) data.write(0);

            int offsetsBytes = count * 4;
            int stylesBytes = styleCount * 4;
            int headerSize = 28;
            int stringsStart = headerSize + offsetsBytes + stylesBytes;
            int total = stringsStart + data.size() + tail.length;

            byte[] out = new byte[total];
            Bytes.w16(out, 0, RES_STRING_POOL);
            Bytes.w16(out, 2, headerSize);
            Bytes.w32(out, 4, total);
            Bytes.w32(out, 8, count);
            Bytes.w32(out, 12, styleCount);
            Bytes.w32(out, 16, utf8 ? 0x100 : 0);          // 追加过后不再是 SORTED，清掉
            Bytes.w32(out, 20, stringsStart);
            Bytes.w32(out, 24, styleCount > 0 ? stringsStart + data.size() : 0);
            for (int i = 0; i < count; i++) {
                Bytes.w32(out, headerSize + i * 4, offs[i]);
            }
            for (int i = 0; i < styleCount; i++) {
                Bytes.w32(out, headerSize + offsetsBytes + i * 4, i * 4);
            }
            byte[] body = data.toByteArray();
            System.arraycopy(body, 0, out, stringsStart, body.length);
            if (tail.length > 0) {
                System.arraycopy(tail, 0, out, stringsStart + body.length, tail.length);
            }
            return out;
        }

        private static String readUtf8(byte[] b, int off) {
            int len = b[off] & 0xFF;
            if ((len & 0x80) != 0) {
                len = ((len & 0x7F) << 8) | (b[off + 1] & 0xFF);
                off += 2;
            } else {
                off += 1;
            }
            int byteLen = b[off] & 0xFF;
            if ((byteLen & 0x80) != 0) {
                byteLen = ((byteLen & 0x7F) << 8) | (b[off + 1] & 0xFF);
                off += 2;
            } else {
                off += 1;
            }
            try {
                return new String(b, off, byteLen, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        private static String readUtf16(byte[] b, int off) {
            int len = Bytes.u16(b, off);
            off += 2;
            if ((len & 0x8000) != 0) {
                len = ((len & 0x7FFF) << 16) | Bytes.u16(b, off);
                off += 2;
            }
            char[] cs = new char[len];
            for (int i = 0; i < len; i++) cs[i] = (char) Bytes.u16(b, off + i * 2);
            return new String(cs);
        }

        private static byte[] encodeUtf8(String s) {
            byte[] raw;
            try {
                raw = s.getBytes("UTF-8");
            } catch (Exception e) {
                raw = s.getBytes();
            }
            int chars = s.length();
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            if (chars > 0x7F) {
                o.write(0x80 | (chars >>> 8));
                o.write(chars & 0xFF);
            } else {
                o.write(chars);
            }
            if (raw.length > 0x7F) {
                o.write(0x80 | (raw.length >>> 8));
                o.write(raw.length & 0xFF);
            } else {
                o.write(raw.length);
            }
            o.write(raw, 0, raw.length);
            o.write(0);
            return o.toByteArray();
        }

        private static byte[] encodeUtf16(String s) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            int len = s.length();
            if (len > 0x7FFF) {
                o.write(0x80 | (len >>> 16));
                o.write((len >>> 8) & 0xFF);
                o.write(len & 0xFF);
            } else {
                o.write(len & 0xFF);
                o.write((len >>> 8) & 0xFF);
            }
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                o.write(c & 0xFF);
                o.write((c >>> 8) & 0xFF);
            }
            o.write(0);
            o.write(0);
            return o.toByteArray();
        }
    }
}
