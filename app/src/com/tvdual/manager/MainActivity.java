package com.tvdual.manager;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tvdual.core.Cloner;

import java.io.File;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 双开管家 · TV 端
 *
 * 主界面是「已安装应用」，对着某个 App 点「双开」，本机现场重打包 + 签名 + 安装。
 */
public class MainActivity extends Activity {

    static final String ACTION_INSTALL_RESULT = "com.tvdual.manager.INSTALL_RESULT";
    private static final String KEY_PASS = "tvdual-clone-2024";
    private static final int MODE_APP = 0;
    private static final int MODE_HELP = 1;
    private static final String[] TAB_TITLES = {"应用", "说明"};
    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨";

    private int mode = MODE_APP;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView status;
    private final TextView[] tabs = new TextView[2];
    private BroadcastReceiver installReceiver;
    private List<Apps.Entry> appList = new ArrayList<>();

    private PrivateKey privateKey;
    private X509Certificate certificate;
    private boolean keyError;
    private boolean busy;
    private String pendingClonePkg;      // 正在安装的克隆包名，用于兜底判定

    private FrameLayout container;
    private View busyView;
    private TextView busyText;

    // ------------------------------------------------------------------ 生命周期
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
        buildUi();
        registerInstallReceiver();
        loadKey();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy && list != null) refresh();
    }

    @Override
    protected void onDestroy() {
        if (installReceiver != null) {
            try {
                unregisterReceiver(installReceiver);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mode != MODE_APP) {
            switchMode(MODE_APP);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面骨架
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int px = Ui.dp(this, 40);
        root.setPadding(px, Ui.dp(this, 26), px, Ui.dp(this, 16));

        root.addView(buildHeader());
        root.addView(buildStatusBar());

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildFooter());

        container = new FrameLayout(this);
        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(buildBusyOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(container);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(Ui.text(this, "双开管家", 28, Ui.TEXT, true));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView t = Ui.text(this, TAB_TITLES[i], 18, Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12));
            t.setBackground(Ui.focusable(0x00000000, Ui.CARD, this, 10, 2));
            t.setTextColor(Ui.tabColors());
            t.setFocusable(true);
            t.setClickable(true);
            onClick(t, () -> switchMode(index));
            tabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(this, 8);
            header.addView(t, lp);
        }
        return header;
    }

    private View buildStatusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Ui.dp(this, 14), 0, 0);
        status = Ui.text(this, "", 15, Ui.SUB, false);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refreshBtn = Ui.button(this, "刷新", false);
        onClick(refreshBtn, () -> {
            refresh();
            toast("已刷新");
        });
        bar.addView(refreshBtn);
        return bar;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, Ui.dp(this, 10), 0, 0);
        footer.addView(Ui.text(this, "方向键移动 · OK 确认 · 返回上一层", 13, 0xFF63788E, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(Ui.text(this, "v2.0 · 端上重打包", 13, 0xFF63788E, false));
        return footer;
    }

    private View buildBusyOverlay() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xCC05080C);
        overlay.setFocusable(true);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> { });          // 吞掉点击

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.round(Ui.PANEL, this, 18));
        card.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        busyText = Ui.text(this, "", 22, Ui.TEXT, true);
        busyText.setGravity(Gravity.CENTER);
        card.addView(busyText);
        TextView hint = Ui.text(this, "首次克隆大应用需要十几秒，请不要按返回键", 14, Ui.SUB, false);
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(hint);

        overlay.addView(card);
        busyView = overlay;
        overlay.setVisibility(View.GONE);
        return overlay;
    }

    // ------------------------------------------------------------------ 刷新
    private void refresh() {
        if (list == null) return;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(mode == i);
        list.removeAllViews();
        if (mode == MODE_APP) buildAppList();
        else buildHelp();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void switchMode(int m) {
        if (mode == m) return;
        mode = m;
        refresh();
    }

    private void buildAppList() {
        appList = Apps.installed(this);
        int groups = 0;
        for (int i = 0; i < appList.size(); i++) {
            if (appList.get(i).clone) groups++;
        }
        status.setText("已装 " + (appList.size() - groups) + " 个应用 · 已双开 " + groups + " 份"
                + (keyError ? " · ⚠ 签名密钥加载失败" : ""));

        if (appList.isEmpty()) {
            list.addView(empty("没有第三方应用", "系统内置应用不在列表里。"));
            return;
        }
        String lastBase = null;
        for (Apps.Entry e : appList) {
            if (!e.base.equals(lastBase)) {
                lastBase = e.base;
                int count = 0;
                for (Apps.Entry x : appList) if (x.base.equals(e.base)) count++;
                list.addView(section(e.label, count));
            }
            list.addView(appRow(e));
        }
    }

    private void buildHelp() {
        status.setText("使用说明");
        list.addView(helpCard("怎么双开",
                "1. 在列表里选一个应用，焦点移到它右边的「双开」按钮，按 OK；\n"
                        + "2. 应用会在本机现场重打包、重新签名，然后弹出系统安装确认；\n"
                        + "3. 装完回到桌面，会出现「腾讯视频」和「腾讯视频 ①」两个图标，各自登录不同账号。\n"
                        + "整个过程不联网、不上传 APK。"));
        list.addView(helpCard("硬性条件",
                "• 需要 Android 7.0 及以上（克隆包只带 v2 签名）；\n"
                        + "• 应用必须是单 APK 安装，从应用商店分卷安装的（App Bundle）暂不支持；\n"
                        + "• 系统需要允许本应用「安装未知应用」，首次点双开会自动跳转授权。"));
        list.addView(helpCard("为什么有的应用克隆后闪退",
                "克隆包换了签名，所以：加固壳（360/腾讯乐固/梆梆）、支付与微信登录、"
                        + "DRM 版权片源（Netflix/迪士尼）、以及用签名做校验的应用会失效或闪退。"
                        + "这是重打包路线的固有限制，不是安装步骤的问题。"));
        list.addView(helpCard("卸载",
                "「应用」列表里克隆条目右侧有「卸载」按钮；卸载克隆不影响原版。"));
    }

    // ------------------------------------------------------------------ 行
    private View appRow(Apps.Entry e) {
        LinearLayout row = rowShell();
        row.addView(iconOf(e.icon));

        String sub = e.pkg + (e.versionName.isEmpty() ? "" : "  ·  v" + e.versionName);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, e.label, 21, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = Ui.text(this, sub, 13, Ui.SUB, false);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(t);
        box.addView(s);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        boxLp.leftMargin = Ui.dp(this, 16);
        boxLp.rightMargin = Ui.dp(this, 16);
        row.addView(box, boxLp);

        row.addView(chip(e.clone ? "克隆 " + circled(e.index) : "原版",
                e.clone ? Ui.ACCENT : 0xFF5C6B7C));

        if (!e.clone) {
            TextView cloneBtn = Ui.button(this, "双开", true);
            onClick(cloneBtn, () -> startClone(e));
            row.addView(cloneBtn);
        }
        TextView launchBtn = Ui.button(this, "启动", !e.clone);
        onClick(launchBtn, () -> launchApp(e));
        row.addView(launchBtn);
        if (e.clone) {
            TextView del = Ui.button(this, "卸载", false);
            onClick(del, () -> Apps.uninstall(this, e.pkg));
            row.addView(del);
        }
        onClick(row, () -> launchApp(e));
        return row;
    }

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rowBg(this));
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private View iconOf(Drawable d) {
        ImageView iv = new ImageView(this);
        int s = Ui.dp(this, 54);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        if (d != null) iv.setImageDrawable(d);
        else iv.setImageResource(R.mipmap.ic_launcher);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private View chip(String text, int color) {
        TextView c = Ui.text(this, text, 12, color, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        c.setBackground(Ui.roundStroke(0x00000000, (color & 0x00FFFFFF) | 0x66000000, this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 12);
        c.setLayoutParams(lp);
        return c;
    }

    private View section(String label, int count) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 8));
        box.addView(Ui.text(this, label, 15, 0xFF7E93A8, true));
        if (count > 1) box.addView(Ui.text(this, "  已双开 " + (count - 1) + " 份", 13, Ui.ACCENT, true));
        return box;
    }

    private View empty(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(0xFF121A22, this, 16));
        box.setPadding(Ui.dp(this, 28), Ui.dp(this, 26), Ui.dp(this, 28), Ui.dp(this, 26));
        box.addView(Ui.text(this, title, 20, Ui.TEXT, true));
        TextView b = Ui.text(this, body, 14, Ui.SUB, false);
        b.setPadding(0, Ui.dp(this, 10), 0, 0);
        b.setLineSpacing(Ui.dp(this, 6), 1f);
        box.addView(b);
        return box;
    }

    private View helpCard(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.PANEL, this, 16));
        box.setPadding(Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22));
        box.addView(Ui.text(this, title, 19, Ui.TEXT, true));
        TextView b = Ui.text(this, body, 14, Ui.SUB, false);
        b.setPadding(0, Ui.dp(this, 10), 0, 0);
        b.setLineSpacing(Ui.dp(this, 7), 1f);
        box.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        box.setLayoutParams(lp);
        return box;
    }

    // ------------------------------------------------------------------ 克隆
    private void startClone(final Apps.Entry e) {
        if (busy) return;
        if (keyError || privateKey == null) {
            toast("签名密钥加载失败，无法生成克隆");
            return;
        }
        if (e.splitCount > 0) {
            toast("该应用是分卷安装（App Bundle），暂不支持端上双开");
            return;
        }
        final int idx = Apps.nextCloneIndex(getPackageManager(), e.base);
        if (idx == 0) {
            toast("最多只能双开 9 份");
            return;
        }
        File srcFile = new File(e.sourceDir);
        if (!srcFile.canRead()) {
            toast("读不到应用安装包，系统限制了该路径");
            return;
        }
        long need = srcFile.length() * 2 + 16 * 1024 * 1024L;
        try {
            StatFs fs = new StatFs(getCacheDir().getAbsolutePath());
            long free = fs.getAvailableBytes();
            if (free < need) {
                toast("存储空间不足：还需要约 " + Apps.size(need - free));
                return;
            }
        } catch (Throwable ignored) {
        }

        final String clonePkg = e.pkg + ".clone" + idx;
        final String cloneLabel = e.label + " " + circled(idx);
        pendingClonePkg = clonePkg;
        busy = true;
        showBusy("正在生成 " + cloneLabel + " …");

        new Thread(() -> {
            String err = null;
            try {
                Cloner.Spec spec = new Cloner.Spec();
                spec.oldPackage = e.pkg;
                spec.newPackage = clonePkg;
                spec.label = cloneLabel;
                spec.key = privateKey;
                spec.cert = certificate;

                File work = new File(getCacheDir(), "tvdual_work");
                deleteTree(work);
                work.mkdirs();
                File raw = Cloner.clone(srcFile, work, spec);

                // 关键：挪到 cacheDir 根目录，CloneProvider 只从根目录取文件，
                // 否则安装器拿到 URI 也打不开，会报「解析软件包时出现问题」。
                File apk = new File(getCacheDir(), clonePkg + ".apk");
                if (apk.exists() && !apk.delete()) throw new java.io.IOException("旧克隆包删除失败");
                if (!raw.renameTo(apk)) {
                    copyFile(raw, apk);
                }
                deleteTree(work);
                if (!apk.exists() || apk.length() == 0) throw new java.io.IOException("克隆包写入失败");

                runOnUiThread(() -> showBusy("正在交给系统安装器 …"));
                String how = Apps.install(MainActivity.this, apk);
                if ("session".equals(how)) {
                    apk.delete();              // 已写进安装会话，本地副本可删
                } else {
                    runOnUiThread(() -> toast("已打开系统安装界面，请按遥控器确认"));
                }
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> {
                busy = false;
                hideBusy();
                if (ferr != null) toast("双开失败：" + ferr);
                refresh();
            });
        }, "tvdual-clone").start();
    }

    private void showBusy(String text) {
        if (busyText != null) busyText.setText(text);
        if (busyView != null) busyView.setVisibility(View.VISIBLE);
    }

    private void hideBusy() {
        if (busyView != null) busyView.setVisibility(View.GONE);
    }

    /** 用 PKCS#8 DER + X.509 DER 直接加载，避开 Android 各版本对 PKCS12 加密算法支持不一的问题。 */
    private void loadKey() {
        try {
            byte[] keyBytes = readAsset("clone.key");
            byte[] certBytes = readAsset("clone.crt");
            privateKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
            certificate = (X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(certBytes));
            if (privateKey == null || certificate == null) keyError = true;
        } catch (Throwable t) {
            keyError = true;
        }
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void launchApp(Apps.Entry e) {
        Intent i = Apps.launch(getPackageManager(), e.pkg);
        if (i == null) {
            toast("找不到 " + e.label + " 的启动入口");
            return;
        }
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
        }
    }

    private void registerInstallReceiver() {
        installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.util.Log.i("TVDual", "install result status=" + st
                        + " msg=" + msg + " pkg=" + pendingClonePkg);
                if (st == PackageInstaller.STATUS_SUCCESS) {
                    toast("安装成功，回桌面看看");
                    refresh();
                    return;
                }
                if (st < 0) {
                    // 个别机型不回填 extras，直接查包在不在
                    final String pkg = pendingClonePkg;
                    new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            toast(isInstalled(pkg) ? "安装成功，回桌面看看"
                                    : "安装未完成：系统没返回状态，也没查到新包");
                            refresh();
                        }
                    }, 1500);
                    return;
                }
                toast("安装未完成：" + (msg == null ? codeName(st) : msg));
                refresh();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_INSTALL_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installReceiver, f);
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String codeName(int st) {
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "被系统拦截（是否允许「安装未知应用」？）";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "已取消";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "与已装应用冲突（签名或版本）";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "存储空间不足";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "与设备不兼容";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "安装包无效（解析失败）";
            default:
                return "code=" + st;
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(from);
        java.io.OutputStream out = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }

    private void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String circled(int n) {
        int i = n - 1;
        return (i >= 0 && i < CIRCLED.length()) ? String.valueOf(CIRCLED.charAt(i)) : String.valueOf(n);
    }

    private interface Act {
        void run();
    }

    private static void onClick(View v, final Act a) {
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                a.run();
            }
        });
    }
}
