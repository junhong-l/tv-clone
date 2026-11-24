package com.tvdual.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * APK（ZIP）流式重打包：边读边写，不在内存里装整个包（123MB 的包在电视上也不会 OOM）。
 * 保证 resources.arsc 为 STORED、.so 页对齐、条目 4 字节对齐。
 */
public final class ApkZip {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LFH_SIG = 0x04034b50;
    private static final int BUF = 128 * 1024;

    public static final class Entry {
        public String name;
        int method;
        int flags;
        int time;
        int date;
        long crc;
        long csize;
        long usize;
        long localOffset;
        long dataOffset;
        byte[] replaced;          // 非空表示用这份未压缩数据替换
        long outOffset;           // 新包里的本地头偏移
    }

    public static final class Meta {
        public List<Entry> entries = new ArrayList<>();
        public long cdOffset;
        public long cdSize;
        public byte[] eocd;
    }

    private ApkZip() {}

    public static Meta read(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            return read(raf);
        } finally {
            raf.close();
        }
    }

    static Meta read(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        int tailLen = (int) Math.min(len, 65557);
        byte[] tail = new byte[tailLen];
        raf.seek(len - tailLen);
        raf.readFully(tail);

        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (Bytes.i32(tail, i) == EOCD_SIG) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IOException("不是合法 APK/ZIP（找不到 EOCD）");

        Meta m = new Meta();
        int count = Bytes.u16(tail, eocd + 10);
        m.cdSize = Bytes.i32(tail, eocd + 12) & 0xFFFFFFFFL;
        m.cdOffset = Bytes.i32(tail, eocd + 16) & 0xFFFFFFFFL;
        m.eocd = new byte[tailLen - eocd];
        System.arraycopy(tail, eocd, m.eocd, 0, m.eocd.length);
        if (m.cdOffset + m.cdSize > len) throw new IOException("中央目录越界");
        if (m.cdSize > 16 * 1024 * 1024L) throw new IOException("中央目录异常大");

        byte[] cd = new byte[(int) m.cdSize];
        raf.seek(m.cdOffset);
        raf.readFully(cd);

        int p = 0;
        for (int i = 0; i < count; i++) {
            if (Bytes.i32(cd, p) != CD_SIG) throw new IOException("中央目录损坏");
            Entry e = new Entry();
            e.flags = Bytes.u16(cd, p + 8);
            e.method = Bytes.u16(cd, p + 10);
            e.time = Bytes.u16(cd, p + 12);
            e.date = Bytes.u16(cd, p + 14);
            e.crc = Bytes.i32(cd, p + 16) & 0xFFFFFFFFL;
            e.csize = Bytes.i32(cd, p + 20) & 0xFFFFFFFFL;
            e.usize = Bytes.i32(cd, p + 24) & 0xFFFFFFFFL;
            int nameLen = Bytes.u16(cd, p + 28);
            int extraLen = Bytes.u16(cd, p + 30);
            int commentLen = Bytes.u16(cd, p + 32);
            e.localOffset = Bytes.i32(cd, p + 42) & 0xFFFFFFFFL;
            e.name = new String(cd, p + 46, nameLen, "UTF-8");
            p += 46 + nameLen + extraLen + commentLen;
            if (isOldSignatureFile(e.name)) continue;

            byte[] lfh = new byte[30];
            raf.seek(e.localOffset);
            raf.readFully(lfh);
            if (Bytes.i32(lfh, 0) != LFH_SIG) throw new IOException("本地头损坏: " + e.name);
            e.dataOffset = e.localOffset + 30 + Bytes.u16(lfh, 26) + Bytes.u16(lfh, 28);
            m.entries.add(e);
        }
        return m;
    }

    /** 读出某个条目的解压后内容（只用于 manifest / arsc 这类小文件）。 */
    public static byte[] readEntry(File file, String name) throws IOException {
        Meta m = read(file);
        Entry target = null;
        for (Entry e : m.entries) if (name.equals(e.name)) target = e;
        if (target == null) return null;

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(target.dataOffset);
            byte[] raw = new byte[(int) target.csize];
            raf.readFully(raw);
            if (target.method == 0) return raw;
            java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
            try {
                inf.setInput(raw);
                byte[] out = new byte[(int) target.usize];
                int n;
                try {
                    n = inf.inflate(out);
                } catch (java.util.zip.DataFormatException dfe) {
                    throw new IOException("解压失败: " + target.name, dfe);
                }
                if (n != out.length) {
                    byte[] trimmed = new byte[n];
                    System.arraycopy(out, 0, trimmed, 0, n);
                    return trimmed;
                }
                return out;
            } finally {
                inf.end();
            }
        } finally {
            raf.close();
        }
    }

    public static void repack(File src, File dst, Map<String, byte[]> replace, int pageSize) throws IOException {
        Meta m = read(src);
        RandomAccessFile raf = new RandomAccessFile(src, "r");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUF);
        try {
            byte[] buf = new byte[BUF];
            long pos = 0;

            for (Entry e : m.entries) {
                if (replace != null && replace.containsKey(e.name)) {
                    e.replaced = replace.get(e.name);
                }
                byte[] name = e.name.getBytes("UTF-8");
                byte[] data;
                int method;
                long crc;
                long usize;

                if (e.replaced != null) {
                    data = e.replaced;
                    method = 0;
                    CRC32 c = new CRC32();
                    c.update(data);
                    crc = c.getValue();
                    usize = data.length;
                } else {
                    data = null;
                    method = e.method;
                    crc = e.crc;
                    usize = e.usize;
                }
                long compressedSize = data != null ? data.length : e.csize;

                int align = 4;
                if (e.name.endsWith(".so") && method == 0) align = pageSize;
                int headerFixed = 30 + name.length;
                int pad = (int) ((align - ((pos + headerFixed) % align)) % align);
                if (pad > 0 && pad < 4) pad += align;

                e.outOffset = pos;
                // 本地头
                writeInt(out, LFH_SIG);
                writeShort(out, 20);
                writeShort(out, e.flags & ~0x08);
                writeShort(out, method);
                writeShort(out, e.time);
                writeShort(out, e.date);
                writeInt(out, (int) crc);
                writeInt(out, (int) compressedSize);
                writeInt(out, (int) usize);
                writeShort(out, name.length);
                writeShort(out, pad);
                out.write(name);
                if (pad >= 4) {
                    writeShort(out, 0x0000);
                    writeShort(out, pad - 4);
                    for (int k = 0; k < pad - 4; k++) out.write(0);
                }
                pos += headerFixed + pad;

                if (data != null) {
                    out.write(data);
                    pos += data.length;
                } else {
                    raf.seek(e.dataOffset);
                    long left = e.csize;
                    while (left > 0) {
                        int n = (int) Math.min(buf.length, left);
                        raf.readFully(buf, 0, n);
                        out.write(buf, 0, n);
                        left -= n;
                        pos += n;
                    }
                }
            }

            long cdOffset = pos;
            for (Entry e : m.entries) {
                byte[] name = e.name.getBytes("UTF-8");
                byte[] data = e.replaced;
                long crc = e.crc;
                long usize = e.usize;
                long csize = e.csize;
                int method = e.method;
                if (data != null) {
                    CRC32 c = new CRC32();
                    c.update(data);
                    crc = c.getValue();
                    usize = data.length;
                    csize = data.length;
                    method = 0;
                }
                writeInt(out, CD_SIG);
                writeShort(out, 20);
                writeShort(out, 20);
                writeShort(out, e.flags & ~0x08);
                writeShort(out, method);
                writeShort(out, e.time);
                writeShort(out, e.date);
                writeInt(out, (int) crc);
                writeInt(out, (int) csize);
                writeInt(out, (int) usize);
                writeShort(out, name.length);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeInt(out, 0);
                writeInt(out, (int) e.outOffset);
                out.write(name);
                pos += 46 + name.length;
            }
            long cdSize = pos - cdOffset;

            writeInt(out, EOCD_SIG);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, m.entries.size());
            writeShort(out, m.entries.size());
            writeInt(out, (int) cdSize);
            writeInt(out, (int) cdOffset);
            writeShort(out, 0);
        } finally {
            out.flush();
            out.close();
            raf.close();
        }
    }

    private static boolean isOldSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) return true;
        String upper = name.toUpperCase();
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static void writeShort(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void writeInt(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }
}
