package com.archdroid.miuihome;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** One launchable app. x/y are the animated top-left of its cell (grid: page content coords, dock: screen coords). */
final class App {
    final ComponentName cn;
    final String label;
    final Bitmap icon;
    String shortLabel;
    float x, y, scale = 1f;
    boolean placed;                  // false until it has a position, so new apps appear in place instead of flying in

    App(ComponentName cn, String label, Bitmap icon) {
        this.cn = cn;
        this.label = label;
        this.icon = icon;
        this.shortLabel = label;
    }

    String key() { return cn.flattenToShortString(); }
    String pkg() { return cn.getPackageName(); }
}
