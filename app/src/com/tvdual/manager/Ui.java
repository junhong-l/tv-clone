package com.tvdual.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯代码构建 UI 的小工具：配色、圆角、焦点态。 */
final class Ui {
    static final int BG = 0xFF0B0F14;
    static final int PANEL = 0xFF121A22;
    static final int CARD = 0xFF182430;
    static final int CARD_FOCUS = 0xFF22364C;
    static final int ACCENT = 0xFF4C9AFF;
    static final int TEXT = 0xFFE8EEF6;
    static final int SUB = 0xFF8FA3B8;
    static final int GREEN = 0xFF38D39F;
    static final int WARN = 0xFFFFB020;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(int color, int strokeColor, Context c, float radiusDp) {
        GradientDrawable d = round(color, c, radiusDp);
        d.setStroke(dp(c, 1.5f), strokeColor);
        return d;
    }

    /** 有焦点时换成高亮底 + 描边。 */
    static Drawable focusable(int normal, int focused, Context c, float radiusDp, int strokeDp) {
        GradientDrawable n = round(normal, c, radiusDp);
        GradientDrawable f = round(focused, c, radiusDp);
        f.setStroke(dp(c, strokeDp), ACCENT);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, n);
        return s;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    static ColorStateList tabColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_selected},
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{ACCENT, Color.WHITE, SUB});
    }

    static ColorStateList btnColors() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.WHITE, 0xFFDCEEFF});
    }

    /**
     * TV 按钮：未选中=深灰底浅字，选中=纯白底 + 蓝色粗描边 + 深色字。
     * 电视上 3 米外也要一眼看出焦点在哪，不能只靠一圈细描边区分。
     */
    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 16, 0, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 11), dp(c, 24), dp(c, 11));
        b.setMinWidth(dp(c, 96));

        GradientDrawable focused = round(0xFFFFFFFF, c, 22);
        focused.setStroke(dp(c, 3), primary ? ACCENT : 0xFF9CC8FF);

        GradientDrawable normal = round(primary ? 0xFF1D4E8F : 0xFF22303F, c, 22);
        normal.setStroke(dp(c, 1), primary ? 0xFF2F6DB8 : 0xFF3A4F66);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);

        b.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{0xFF0A1220, primary ? 0xFFEAF2FF : 0xFFC7D6E5}));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    /** 列表行：选中时整行提亮 + 3dp 蓝边，避免和按钮抢焦点时看不出来。 */
    static Drawable rowBg(Context c) {
        GradientDrawable focused = round(0xFF2F4E70, c, 14);
        focused.setStroke(dp(c, 3), ACCENT);
        GradientDrawable normal = round(CARD, c, 14);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }
}
