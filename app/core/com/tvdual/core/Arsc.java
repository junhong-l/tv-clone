package com.tvdual.core;

/**
 * resources.arsc 包名替换。
 *
 * ResTable_package 里 name 是固定 128 个 UTF-16 字符的字段（256 字节），
 * 所以改名不需要动任何偏移，原地覆盖即可。
 */
public final class Arsc {

    private static final int RES_TABLE = 0x0002;
    private static final int RES_TABLE_PACKAGE = 0x0200;

    private Arsc() {}

    public static byte[] renamePackage(byte[] arsc, String newPackage) {
        if (Bytes.u16(arsc, 0) != RES_TABLE) {
            throw new IllegalArgumentException("不是 resources.arsc");
        }
        int headerSize = Bytes.u16(arsc, 2);
        int pos = headerSize;
        while (pos + 8 <= arsc.length) {
            int type = Bytes.u16(arsc, pos);
            int size = Bytes.i32(arsc, pos + 4);
            if (size <= 0 || pos + size > arsc.length) break;
            if (type == RES_TABLE_PACKAGE) {
                // id(u32) 在 +8，name 从 +12 开始，最多 128 个 char
                for (int i = 0; i < 128; i++) {
                    int c = i < newPackage.length() ? newPackage.charAt(i) : 0;
                    Bytes.w16(arsc, pos + 12 + i * 2, c);
                }
            }
            pos += size;
        }
        return arsc;
    }
}
