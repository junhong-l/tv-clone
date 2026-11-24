package com.tvdual.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 已安装应用枚举、克隆序号、启动/卸载/安装。 */
final class Apps {

    static final Pattern CLONE = Pattern.compile("^(.*)\\.clone(\\d+)$");

    static final class Entry {
        String pkg;
        String label;
        String base;
        String versionName = "";
        String sourceDir;
        int index;              // 0 = 原版
        boolean clone;
        int splitCount;
        Drawable icon;
    }

    private Apps() {}

    static List<Entry> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Entry> out = new ArrayList<>();
        List<PackageInfo> packs;
        try {
            packs = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            return out;
        }
        for (PackageInfo pi : packs) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || ai.sourceDir == null) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (system) continue;
            if (ctx.getPackageName().equals(pi.packageName)) continue;

            Entry e = new Entry();
            e.pkg = pi.packageName;
            e.versionName = pi.versionName == null ? "" : pi.versionName;
            e.sourceDir = ai.sourceDir;
            e.splitCount = ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length;
            try {
                e.label = pm.getApplicationLabel(ai).toString();
            } catch (Throwable t) {
                e.label = pi.packageName;
            }
            Matcher m = CLONE.matcher(e.pkg);
            if (m.matches()) {
                e.base = m.group(1);
                e.index = parseInt(m.group(2));
                e.clone = true;
            } else {
                e.base = e.pkg;
                e.index = 0;
            }
            try {
                e.icon = pm.getApplicationIcon(pi.packageName);
            } catch (Throwable ignored) {
            }
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int c = a.base.compareToIgnoreCase(b.base);
                return c != 0 ? c : Integer.compare(a.index, b.index);
            }
        });
        return out;
    }

    /** 下一个可用的克隆序号：1..9。 */
    static int nextCloneIndex(PackageManager pm, String base) {
        for (int i = 1; i <= 9; i++) {
            try {
                pm.getPackageInfo(base + ".clone" + i, 0);
            } catch (Throwable t) {
                return i;
            }
        }
        return 0;
    }

    static Intent launch(PackageManager pm, String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        String[] cats = {"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg);
            List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
            if (ris != null && !ris.isEmpty()) {
                ResolveInfo ri = ris.get(0);
                return new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        }
        return null;
    }

    static void uninstall(Context ctx, String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /**
     * 优先用标准安装 Intent（所有电视/盒子都实现），失败才退回 PackageInstaller 会话。
     * @return "intent" 或 "session"
     */
    static String install(Context ctx, File apk) throws Exception {
        android.net.Uri uri = CloneProvider.uriFor(apk);
        String[] actions = {Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE};
        for (String action : actions) {
            try {
                Intent i = new Intent(action)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (action.equals(Intent.ACTION_INSTALL_PACKAGE)) {
                    i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                    i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
                }
                if (pm(ctx).resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) == null) continue;
                ctx.startActivity(i);
                android.util.Log.i("TVDual", "install via " + action + " -> " + uri);
                return "intent";
            } catch (Throwable t) {
                android.util.Log.w("TVDual", "install via " + action + " failed", t);
            }
        }

        PackageInstaller pi = pm(ctx).getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("tvdual", 0, apk.length())) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(MainActivity.ACTION_INSTALL_RESULT).setPackage(ctx.getPackageName());
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000;   // FLAG_MUTABLE
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(ctx, id, cb, flags);
            session.commit(pending.getIntentSender());
        } finally {
            session.close();
        }
        return "session";
    }

    private static PackageManager pm(Context ctx) {
        return ctx.getPackageManager();
    }

    static String size(long bytes) {
        if (bytes >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
