package com.tvdual.core;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** 桌面测试台：把端上那套 Cloner 跑在电脑上，产物可直接用 aapt2/apksigner 验。 */
public final class RunClone {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法: RunClone <src.apk> <oldPkg> <newPkg> <label> <out.apk> [clone.key] [clone.crt]");
            System.exit(2);
        }
        File src = new File(args[0]);
        File out = new File(args[4]);
        String p12 = args.length > 5 ? args[5] : "assets/clone.key";
        String pass = args.length > 6 ? args[6] : "assets/clone.crt";

        File keyFile = new File(p12);
        File certFile = new File(pass);
        byte[] keyBytes = readAll(keyFile);
        byte[] certBytes = readAll(certFile);
        PrivateKey key = java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
        X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certBytes));

        Cloner.Spec spec = new Cloner.Spec();
        spec.oldPackage = args[1];
        spec.newPackage = args[2];
        spec.label = args[3];
        spec.key = key;
        spec.cert = cert;

        File work = new File(out.getParentFile(), "work_" + out.getName());
        long t0 = System.currentTimeMillis();
        long mem0 = usedMemory();
        File signed = Cloner.clone(src, work, spec);

        if (out.exists()) out.delete();
        if (!signed.renameTo(out)) throw new IllegalStateException("重命名失败: " + signed);
        deleteTree(work);
        System.out.println("ok bytes=" + out.length()
                + " ms=" + (System.currentTimeMillis() - t0)
                + " heapDeltaMB=" + ((usedMemory() - mem0) / 1048576));
    }

    private static long usedMemory() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
