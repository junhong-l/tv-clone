package com.demo.tvplayer;

import android.app.Application;
import android.util.Log;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("Demo", "app start, pkg=" + getPackageName() + ", const=" + Const.PKG);
    }
}
