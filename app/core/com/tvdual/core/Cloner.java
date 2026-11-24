package com.tvdual.core;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * 端上克隆：改清单包名/authorities/组件名 → 改 arsc 包名 → 流式重打包 → v2 签名。
 *
 * 不改 dex（类名保持原样），因此清单里的相对类名会被展开成全限定名。
 * 代价：Class.forName(getPackageName()+".X") 这类自省代码会失效。
 */
public final class Cloner {

    public static final class Spec {
        public String oldPackage;
        public String newPackage;
        public String label;                  // 桌面显示名（字面量，可空）
        public PrivateKey key;
        public X509Certificate cert;
        public int pageSize = 16384;          // 16KB 页对齐（Android 15+ 要求 .so 如此）
        public boolean rewritePermissions = true;
    }

    private Cloner() {}

    /** @return 签名后的克隆 APK（写在 workDir 里） */
    public static File clone(File src, File workDir, Spec spec) throws Exception {
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + workDir);
        }
        byte[] manifest = ApkZip.readEntry(src, "AndroidManifest.xml");
        if (manifest == null) throw new IOException("APK 里没有 AndroidManifest.xml");

        byte[] newManifest = Axml.rewrite(manifest, rule(spec), remover());
        byte[] arsc = ApkZip.readEntry(src, "resources.arsc");
        byte[] newArsc = arsc == null ? null : Arsc.renamePackage(arsc, spec.newPackage);

        Map<String, byte[]> replace = new HashMap<>();
        replace.put("AndroidManifest.xml", newManifest);
        if (newArsc != null) replace.put("resources.arsc", newArsc);

        File unsigned = new File(workDir, "u.apk");
        File signed = new File(workDir, "s.apk");
        ApkZip.repack(src, unsigned, replace, spec.pageSize);
        V2Signer.signFile(unsigned, signed, spec.key, spec.cert);
        if (!unsigned.delete()) unsigned.deleteOnExit();
        return signed;
    }

    // ------------------------------------------------------------------ 规则
    static Axml.Rule rule(final Spec s) {
        return new Axml.Rule() {
            @Override
            public String map(String element, String ns, String attr, String value) {
                boolean android = ns != null && ns.endsWith("/apk/res/android");

                if (!android) {
                    if ("manifest".equals(element) && "package".equals(attr)) {
                        return s.newPackage;
                    }
                    return null;
                }
                if ("label".equals(attr) && "application".equals(element) && s.label != null) {
                    return s.label;
                }
                if (value == null) return null;
                if ("authorities".equals(attr)) return rewriteAuthorities(value, s);
                if ("process".equals(attr) || "taskAffinity".equals(attr)) {
                    return value.startsWith(s.oldPackage)
                            ? s.newPackage + value.substring(s.oldPackage.length()) : null;
                }
                if ("name".equals(attr)) {
                    if (value.startsWith(".")) return s.oldPackage + value;
                    if (s.rewritePermissions && isPermissionElement(element)
                            && value.startsWith(s.oldPackage + ".")) {
                        return s.newPackage + value.substring(s.oldPackage.length());
                    }
                    return null;
                }
                if ("targetActivity".equals(attr) || "parentActivityName".equals(attr)
                        || "backupAgent".equals(attr) || "manageSpaceActivity".equals(attr)
                        || "appComponentFactory".equals(attr) || "zygotePreloadName".equals(attr)) {
                    return value.startsWith(".") ? s.oldPackage + value : null;
                }
                if (("permission".equals(attr) || "readPermission".equals(attr)
                        || "writePermission".equals(attr))
                        && value.startsWith(s.oldPackage + ".")) {
                    return s.newPackage + value.substring(s.oldPackage.length());
                }
                if ("value".equals(attr) && "meta-data".equals(element)
                        && (value.equals(s.oldPackage) || value.startsWith(s.oldPackage + "."))) {
                    return s.newPackage + value.substring(s.oldPackage.length());
                }
                return null;
            }
        };
    }

    static Axml.Remover remover() {
        return new Axml.Remover() {
            @Override
            public boolean remove(String element, String ns, String attr, String value) {
                boolean android = ns != null && ns.endsWith("/apk/res/android");
                return android && ("sharedUserId".equals(attr) || "sharedUserMaxSdkVersion".equals(attr));
            }
        };
    }

    private static boolean isPermissionElement(String element) {
        return "permission".equals(element) || "uses-permission".equals(element)
                || "permission-group".equals(element) || "permission-tree".equals(element)
                || "uses-permission-sdk-23".equals(element);
    }

    private static String rewriteAuthorities(String value, Spec s) {
        String[] parts = value.split(";");
        boolean any = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith(s.oldPackage)) {
                p = s.newPackage + p.substring(s.oldPackage.length());
                any = true;
            }
            if (i > 0) sb.append(';');
            sb.append(p);
        }
        if (!any) return s.newPackage + "." + value.replace(';', '_');
        return sb.toString();
    }
}
