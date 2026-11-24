package com.tvdual.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * APK Signature Scheme v2 签名（不依赖 apksig，纯 JDK/Android 通用 API）。
 * 适用于 Android 7.0+；v1（JAR 签名）只在 5.x/6.x 才需要，这里不做。
 */
public final class V2Signer {

    private static final int BLOCK_ID = 0x7109871a;
    private static final int ALG_RSA_PKCS1_SHA256 = 0x0103;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes();
    private static final int CHUNK = 1024 * 1024;

    /** 是否把长度前缀一起纳入签名（v2 规范要求包含）。测试时可切换。 */
    static boolean signWithPrefix = false;

    private V2Signer() {}

    public static byte[] sign(byte[] apk, PrivateKey key, X509Certificate cert) throws Exception {
        int eocd = findEocd(apk);
        if (eocd < 0) throw new IllegalArgumentException("找不到 EOCD");
        int cdSize = Bytes.i32(apk, eocd + 12);
        int cdOffset = Bytes.i32(apk, eocd + 16);
        if (cdOffset + cdSize > apk.length) throw new IllegalArgumentException("中央目录越界");

        byte[] entries = Bytes.slice(apk, 0, cdOffset);
        byte[] cd = Bytes.slice(apk, cdOffset, cdOffset + cdSize);
        byte[] eocdBytes = Bytes.slice(apk, eocd, apk.length);

        // 先用全 0 摘要算出签名块长度（长度与摘要内容无关）
        int blockLen = buildBlock(new byte[32], key, cert).length;
        int newCdOffset = cdOffset + blockLen;

        // 算摘要时：EOCD 的 CD 偏移按规范替换为「签名块起始位置」
        byte[] eocdForDigest = eocdBytes.clone();
        Bytes.w32(eocdForDigest, 16, cdOffset);
        byte[] digest = computeDigest(entries, cd, eocdForDigest);
        // 写文件时：EOCD 的 CD 偏移必须是真实的中央目录位置
        byte[] eocdPatched = eocdBytes.clone();
        Bytes.w32(eocdPatched, 16, newCdOffset);
        if (System.getProperty("dbg") != null) {
            StringBuilder hx = new StringBuilder();
            for (byte x : digest) hx.append(String.format("%02x", x));
            System.out.println("dbg newCdOffset=" + newCdOffset + " blockLen=" + blockLen
                    + " entries=" + entries.length + " cd=" + cd.length + " eocd=" + eocdPatched.length
                    + " digest=" + hx);
        }

        byte[] block = buildBlock(digest, key, cert);
        if (block.length != blockLen) throw new IllegalStateException("签名块长度不稳定");

        ByteArrayOutputStream out = new ByteArrayOutputStream(apk.length + blockLen + 16);
        out.write(entries, 0, entries.length);
        out.write(block, 0, block.length);
        out.write(cd, 0, cd.length);
        out.write(eocdPatched, 0, eocdPatched.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 流式签名
    /** 流式签名：适合大包（123MB 也不占堆）。 */
    public static void signFile(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(in, "r");
        try {
            long len = raf.length();
            int tailLen = (int) Math.min(len, 65557);
            byte[] tail = new byte[tailLen];
            raf.seek(len - tailLen);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if (Bytes.i32(tail, i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) throw new IllegalArgumentException("找不到 EOCD");

            long cdSize = Bytes.i32(tail, eocd + 12) & 0xFFFFFFFFL;
            long cdOffset = Bytes.i32(tail, eocd + 16) & 0xFFFFFFFFL;
            byte[] eocdBytes = new byte[tailLen - eocd];
            System.arraycopy(tail, eocd, eocdBytes, 0, eocdBytes.length);

            int blockLen = buildBlock(new byte[32], key, cert).length;
            long newCdOffset = cdOffset + blockLen;

            byte[] eocdForDigest = eocdBytes.clone();
            Bytes.w32(eocdForDigest, 16, (int) cdOffset);
            byte[] eocdForFile = eocdBytes.clone();
            Bytes.w32(eocdForFile, 16, (int) newCdOffset);

            byte[] digest = computeDigest(raf, cdOffset, cdSize, eocdForDigest);
            byte[] block = buildBlock(digest, key, cert);
            if (block.length != blockLen) throw new IllegalStateException("签名块长度不稳定");

            OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024);
            try {
                copyRange(raf, os, 0, cdOffset);
                os.write(block);
                copyRange(raf, os, cdOffset, cdSize);
                os.write(eocdForFile);
            } finally {
                os.flush();
                os.close();
            }
        } finally {
            raf.close();
        }
    }

    private static void copyRange(RandomAccessFile raf, OutputStream os, long off, long len)
            throws Exception {
        raf.seek(off);
        byte[] buf = new byte[256 * 1024];
        long left = len;
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            raf.readFully(buf, 0, n);
            os.write(buf, 0, n);
            left -= n;
        }
    }

    static byte[] computeDigest(RandomAccessFile raf, long cdOffset, long cdSize, byte[] eocd)
            throws Exception {
        List<byte[]> digests = new ArrayList<>();
        feedFile(digests, raf, 0, cdOffset);
        feedFile(digests, raf, cdOffset, cdSize);
        feedBytes(digests, eocd);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((byte) 0x5A);
        md.update(le32(digests.size()));
        for (byte[] d : digests) md.update(d);
        return md.digest();
    }

    private static void feedFile(List<byte[]> out, RandomAccessFile raf, long off, long len)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[CHUNK];
        long p = off;
        while (p < off + len) {
            int n = (int) Math.min(CHUNK, off + len - p);
            raf.seek(p);
            raf.readFully(buf, 0, n);
            md.reset();
            md.update((byte) 0xA5);
            md.update(le32(n));
            md.update(buf, 0, n);
            out.add(md.digest());
            p += n;
        }
    }

    private static void feedBytes(List<byte[]> out, byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int p = 0;
        while (p < data.length) {
            int n = Math.min(CHUNK, data.length - p);
            md.reset();
            md.update((byte) 0xA5);
            md.update(le32(n));
            md.update(data, p, n);
            out.add(md.digest());
            p += n;
        }
    }

    // ------------------------------------------------------------------ 摘要
    static byte[] computeDigest(byte[] a, byte[] b, byte[] c) throws Exception {
        List<byte[]> digests = new ArrayList<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (byte[] section : new byte[][]{a, b, c}) {
            for (int p = 0; p < section.length; p += CHUNK) {
                int n = Math.min(CHUNK, section.length - p);
                md.reset();
                md.update((byte) 0xA5);
                md.update(le32(n));
                md.update(section, p, n);
                digests.add(md.digest());
            }
        }
        md.reset();
        md.update((byte) 0x5A);
        md.update(le32(digests.size()));
        for (byte[] d : digests) md.update(d);
        return md.digest();
    }

    // ------------------------------------------------------------------ 签名块
    static byte[] buildBlock(byte[] digest, PrivateKey key, X509Certificate cert) throws Exception {
        byte[] signedData = encodeSignedData(digest, cert);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        if (signWithPrefix) {
            sig.update(signedData);
        } else {
            sig.update(signedData, 4, signedData.length - 4);
        }
        byte[] signature = sig.sign();

        byte[] signatures = lp(lp(concat(le32(ALG_RSA_PKCS1_SHA256), lp(signature))));
        byte[] publicKey = lp(cert.getPublicKey().getEncoded());
        byte[] signer = lp(signedData, signatures, publicKey);
        byte[] signers = lp(signer);

        byte[] pairValue = signers;
        byte[] pair = concat(le64(4 + pairValue.length), le32(BLOCK_ID), pairValue);

        byte[] trailingSize = le64(pair.length + 8 + MAGIC.length);
        byte[] content = concat(pair, trailingSize, MAGIC);
        return concat(le64(content.length), content);
    }

    static byte[] encodeSignedData(byte[] digest, X509Certificate cert) throws Exception {
        byte[] digestRecord = lp(concat(le32(ALG_RSA_PKCS1_SHA256), lp(digest)));
        byte[] digests = lp(digestRecord);
        byte[] certificates = lp(lp(cert.getEncoded()));
        byte[] attributes = lp(new byte[0]);
        return lp(digests, certificates, attributes);
    }

    // ------------------------------------------------------------------ 工具
    static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    /** 长度前缀 + 内容。 */
    static byte[] lp(byte[]... parts) {
        byte[] body = Bytes.concat(parts);
        return concat(le32(body.length), body);
    }

    static byte[] concat(byte[]... parts) {
        return Bytes.concat(parts);
    }

    static int findEocd(byte[] b) {
        int min = Math.max(0, b.length - 65557);
        for (int i = b.length - 22; i >= min; i--) {
            if (Bytes.i32(b, i) == 0x06054b50) return i;
        }
        return -1;
    }
}
