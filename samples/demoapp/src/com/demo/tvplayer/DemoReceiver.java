package com.demo.tvplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class DemoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        Log.i("Demo", "boot in " + ctx.getPackageName() + " const=" + Const.PKG);
    }
}
