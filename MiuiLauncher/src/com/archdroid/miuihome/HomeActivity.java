package com.archdroid.miuihome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.WindowManager;

/** The home screen host: window setup + receivers for installs/removals and the clock. */
public class HomeActivity extends Activity {
    private HomeView view;

    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (view != null) view.reload(); }
    };
    private final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (view != null) view.tickClock(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        view = new HomeView(this);
        setContentView(view);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);

        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, tf);
    }

    @Override protected void onNewIntent(Intent i) {      // Home pressed while we are already home
        super.onNewIntent(i);
        view.goHome();
    }

    @Override public void onBackPressed() { view.goHome(); }   // a launcher never "exits"

    @Override protected void onResume() {
        super.onResume();
        view.tickClock();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgRx); } catch (Throwable t) { }
        try { unregisterReceiver(timeRx); } catch (Throwable t) { }
        super.onDestroy();
    }
}
