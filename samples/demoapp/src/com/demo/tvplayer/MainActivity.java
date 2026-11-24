package com.demo.tvplayer;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101820"));

        StringBuilder sb = new StringBuilder();
        sb.append("package = ").append(getPackageName()).append("\n");
        sb.append("const   = ").append(Const.PKG).append("\n");
        sb.append("authority = ").append(Const.AUTHORITY).append("\n");
        try {
            Class<?> c = Class.forName("com.demo.tvplayer.MainActivity");
            sb.append("reflect = ").append(c.getName()).append("\n");
        } catch (Throwable e) {
            sb.append("reflect = FAIL ").append(e).append("\n");
        }
        try {
            Cursor cur = getContentResolver().query(
                    Uri.parse("content://" + Const.AUTHORITY + "/who"), null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                sb.append("provider = ").append(cur.getString(0));
            }
            if (cur != null) cur.close();
        } catch (Throwable e) {
            sb.append("provider = FAIL ").append(e.getMessage());
        }

        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setText(sb.toString());
        root.addView(t, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        Log.i("Demo", sb.toString());
    }
}
