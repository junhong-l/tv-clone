package com.demo.tvplayer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class DemoService extends Service {
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Log.i("Demo", "service in " + getPackageName());
        return START_NOT_STICKY;
    }
}
