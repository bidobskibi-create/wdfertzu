package com.archdroid.miuihome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * MIUI-style home screen drawn by one custom View: clock, paged 4-column app grid, page dots and a dock.
 *
 * DRAG AND DROP: press and hold an icon -> it lifts under your finger and the others make room.
 *   - move it anywhere in the grid or onto another page (hold it at the screen edge to flip pages)
 *   - drop it on the dock (up to 4 apps; dropping on a full dock swaps with that app)
 *   - drag it to the top bar: left = App info, right = Uninstall
 *   - lift your finger without moving = the app menu (App info / Uninstall)
 * Order and dock are saved. Long-press on empty space = Wallpaper / System settings.
 */
public class HomeView extends View {
    private static final int COLS = 4, DOCK_MAX = 4;
    private static final int T_NONE = 0, T_INFO = 1, T_UNINSTALL = 2;

    private final Activity act;
    private final PackageManager pm;
    private final float d;
    private final int iconS;
    private final ArrayList<App> grid = new ArrayList<App>();
    private final ArrayList<App> dock = new ArrayList<App>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Handler h = new Handler();
    private final Scroller scroller;
    private final SharedPreferences sp;
    private final int touchSlop, maxFling, minFling, longMs;
    private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // layout
    private int W, H, rows = 1, perPage = COLS;
    private float statusH, cellW, rowH, dockCellW, clockBottom, gridTop, gridBottom, topPad, dockTop, dotsY, topBarH, cellOff;
    private float scrollPx;
    private Shader topShader;
    private String timeStr = "", dayStr = "", dateStr = "";
    private int gen;
    private ArrayList<App> pending;      // reload finished while dragging: applied after the drop

    // touch
    private float downX, downY, lastX, lastY;
    private boolean scrolling, moved, longFired;
    private App down;
    private VelocityTracker vt;

    // drag state
    private App dragApp;
    private boolean fromDock;
    private int origIndex;
    private float dragX, dragY, pickX, pickY;
    private boolean dragMoved;
    private int insGrid = -1, insDock = -1, swapSlot = -1, hoverT = T_NONE;
    private float barA;
    private int edgeDir;

    private final Runnable longRun = new Runnable() {
        public void run() {
            longFired = true;
            if (down != null) beginDrag(down);
            else if (!scrolling && !moved) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                emptyMenu();
            }
        }
    };

    private final Runnable edgeRun = new Runnable() {
        public void run() {                      // finger held at the screen edge while dragging: flip a page
            if (dragApp == null || edgeDir == 0) return;
            int cur = Math.round(scrollPx / Math.max(1, W));
            int t = Math.max(0, Math.min(pages() - 1, cur + edgeDir));
            if (t != cur) snapTo(t);
            h.postDelayed(this, 750);
        }
    };

    public HomeView(Context c) {
        super(c);
        act = (Activity) c;
        pm = c.getPackageManager();
        d = c.getResources().getDisplayMetrics().density;
        iconS = (int) (52 * d);
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        scroller = new Scroller(c, new DecelerateInterpolator());
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        maxFling = vc.getScaledMaximumFlingVelocity();
        minFling = (int) (500 * d);
        longMs = ViewConfiguration.getLongPressTimeout();
        tp.setTextAlign(Paint.Align.CENTER);
        tickClock();
        reload();
    }

    // ---------------------------------------------------------------- loading

    public void reload() {
        final int g = ++gen;
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<App> all = query();
                post(new Runnable() { public void run() { if (g == gen) apply(all); } });
            }
        }).start();
    }

    private ArrayList<App> query() {
        ArrayList<App> out = new ArrayList<App>();
        try {
            Intent mi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(mi, 0);
            String self = getContext().getPackageName();
            for (ResolveInfo ri : l) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || self.equals(ai.packageName)) continue;
                out.add(new App(new ComponentName(ai.packageName, ai.name),
                        String.valueOf(ri.loadLabel(pm)), makeIcon(ri.loadIcon(pm))));
            }
            Collections.sort(out, new Comparator<App>() {
                public int compare(App a, App b) { return a.label.compareToIgnoreCase(b.label); }
            });
        } catch (Throwable t) { }
        return out;
    }

    private Bitmap makeIcon(Drawable dr) {
        Bitmap b = Bitmap.createBitmap(iconS, iconS, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        dr.setBounds(0, 0, iconS, iconS);
        dr.draw(c);
        return b;
    }

    private void apply(ArrayList<App> all) {
        if (dragApp != null) { pending = all; return; }
        HashMap<String, App> old = new HashMap<String, App>();
        for (App a : grid) old.put(a.key(), a);
        for (App a : dock) old.put(a.key(), a);
        HashMap<String, App> byKey = new HashMap<String, App>();
        for (App a : all) {
            App o = old.get(a.key());
            if (o != null) { a.x = o.x; a.y = o.y; a.placed = o.placed; }    // already on screen: don't jump
            byKey.put(a.key(), a);
        }
        ArrayList<App> nd = new ArrayList<App>(), ng = new ArrayList<App>();
        HashSet<String> used = new HashSet<String>();
        String sd = sp.getString("dock", null);
        if (sd != null) {
            for (String k : sd.split(",")) {
                App a = byKey.get(k);
                if (a != null && nd.size() < DOCK_MAX && used.add(k)) nd.add(a);
            }
        } else {
            Intent[] ins = {
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                    new Intent(Intent.ACTION_DIAL)};
            for (Intent in : ins) {
                ResolveInfo ri = pm.resolveActivity(in, 0);
                if (ri == null || ri.activityInfo == null) continue;
                String pk = ri.activityInfo.packageName;
                App m = byKey.get(new ComponentName(pk, ri.activityInfo.name).flattenToShortString());
                if (m == null) for (App a : all) if (a.pkg().equals(pk)) { m = a; break; }
                if (m != null && nd.size() < DOCK_MAX && used.add(m.key())) nd.add(m);
            }
        }
        for (String k : sp.getString("order", "").split(",")) {
            App a = byKey.get(k);
            if (a != null && used.add(k)) ng.add(a);
        }
        for (App a : all) if (used.add(a.key())) ng.add(a);      // new installs go to the end
        grid.clear(); grid.addAll(ng);
        dock.clear(); dock.addAll(nd);
        fitLabels();
        clampScroll();
        if (sd == null) save();
        invalidate();
    }

    private void save() {
        StringBuilder o = new StringBuilder(), k = new StringBuilder();
        for (App a : grid) { if (o.length() > 0) o.append(','); o.append(a.key()); }
        for (App a : dock) { if (k.length() > 0) k.append(','); k.append(a.key()); }
        sp.edit().putString("order", o.toString()).putString("dock", k.toString()).commit();
    }

    private void fitLabels() {
        if (W == 0) return;
        tp.setTypeface(tfMed);
        tp.setTextSize(11 * d);
        for (App a : grid) a.shortLabel = TextUtils.ellipsize(a.label, tp, cellW - 6 * d, TextUtils.TruncateAt.END).toString();
        for (App a : dock) a.shortLabel = a.label;
    }

    // ---------------------------------------------------------------- clock

    public void tickClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        Locale lc = Locale.getDefault();
        timeStr = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", lc).format(now);
        dayStr = new SimpleDateFormat("EEEE", lc).format(now);
        dateStr = new SimpleDateFormat("MMMM d", lc).format(now);
        invalidate();
    }

    // ---------------------------------------------------------------- layout

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        W = w; H = hh;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusH = id > 0 ? getResources().getDimensionPixelSize(id) : 24 * d;
        clockBottom = statusH + 108 * d;
        float dockH = 86 * d;
        dockTop = H - dockH;
        dotsY = dockTop - 14 * d;
        gridTop = clockBottom;
        gridBottom = dotsY - 14 * d;
        rowH = 88 * d;
        rows = Math.max(1, (int) ((gridBottom - gridTop) / rowH));
        perPage = COLS * rows;
        topPad = Math.max(0, (gridBottom - gridTop - rows * rowH) / 2f);
        cellW = W / (float) COLS;
        dockCellW = W / 4f;
        cellOff = (rowH - (iconS + 18 * d)) / 2f;
        topBarH = statusH + 56 * d;
        topShader = new LinearGradient(0, 0, 0, clockBottom + 30 * d, 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
        for (App a : grid) a.placed = false;
        for (App a : dock) a.placed = false;
        fitLabels();
        clampScroll();
    }

    private int pages() {
        int cnt = grid.size() + (dragApp != null ? 1 : 0);      // while dragging there is room for one more page
        return Math.max(1, (int) Math.ceil(cnt / (float) Math.max(1, perPage)));
    }

    private float maxScroll() { return Math.max(0, (pages() - 1) * W); }

    private void clampScroll() { scrollPx = Math.max(0, Math.min(maxScroll(), scrollPx)); }

    private float gridX(int di) { return (di / perPage) * W + (di % perPage % COLS) * cellW; }
    private float gridY(int di) { return gridTop + topPad + (di % perPage / COLS) * rowH; }
    private float dockX(int di, int n) { return (W - n * dockCellW) / 2f + di * dockCellW; }
    private float dockY() { return dockTop + 43 * d - iconS / 2f - cellOff; }

    // ---------------------------------------------------------------- drawing

    private boolean stepApp(App a, float tx, float ty, float ts) {
        if (!a.placed) { a.x = tx; a.y = ty; a.scale = ts; a.placed = true; return false; }
        float dx = tx - a.x, dy = ty - a.y, ds = ts - a.scale;
        if (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f && Math.abs(ds) < 0.005f) {
            a.x = tx; a.y = ty; a.scale = ts;
            return false;
        }
        a.x += dx * 0.3f; a.y += dy * 0.3f; a.scale += ds * 0.3f;
        return true;
    }

    private void drawApp(Canvas c, App a, float cellX, float cellY, float cw, boolean label) {
        float cx = cellX + cw / 2f, top = cellY + cellOff;
        c.save();
        c.scale(a.scale, a.scale, cx, top + iconS / 2f);
        c.drawBitmap(a.icon, cx - iconS / 2f, top, p);
        c.restore();
        if (label) {
            tp.setTypeface(tfMed);
            tp.setTextSize(11 * d);
            tp.setColor(Color.WHITE);
            tp.setShadowLayer(3 * d, 0, 1 * d, 0xAA000000);
            c.drawText(a.shortLabel, cx, top + iconS + 14 * d, tp);
            tp.setShadowLayer(0, 0, 0, 0);
        }
    }

    @Override protected void onDraw(Canvas c) {
        boolean busy = false;
        boolean dragging = dragApp != null;

        // ---- animate everything toward its slot
        for (int j = 0; j < grid.size(); j++) {
            int di = (dragging && insGrid >= 0 && j >= insGrid) ? j + 1 : j;      // leave a gap for the dragged app
            busy |= stepApp(grid.get(j), gridX(di), gridY(di), 1f);
        }
        int n = dock.size() + (dragging && insDock >= 0 ? 1 : 0);
        for (int j = 0; j < dock.size(); j++) {
            int di = (dragging && insDock >= 0 && j >= insDock) ? j + 1 : j;
            busy |= stepApp(dock.get(j), dockX(di, n), dockY(), swapSlot == j ? 0.82f : 1f);
        }
        float bt = dragging ? 1f : 0f;
        barA += (bt - barA) * 0.3f;
        if (Math.abs(bt - barA) > 0.01f) busy = true; else barA = bt;

        // ---- scrims
        p.setShader(topShader);
        c.drawRect(0, 0, W, clockBottom + 30 * d, p);
        p.setShader(null);
        p.setColor(0x55000000);
        c.drawRect(0, dockTop, W, H, p);
        p.setColor(0x22FFFFFF);
        c.drawRect(0, dockTop, W, dockTop + 1, p);

        // ---- clock
        tp.setTypeface(tfLight);
        tp.setTextSize(58 * d);
        tp.setColor(Color.WHITE);
        tp.setShadowLayer(5 * d, 0, 1 * d, 0x66000000);
        c.drawText(timeStr, W / 2f, statusH + 70 * d, tp);
        tp.setTypeface(tfMed);
        tp.setTextSize(14 * d);
        tp.setColor(0xF0FFFFFF);
        c.drawText(dayStr + ", " + dateStr, W / 2f, statusH + 94 * d, tp);
        tp.setShadowLayer(0, 0, 0, 0);

        // ---- grid (pages scroll sideways)
        for (int j = 0; j < grid.size(); j++) {
            App a = grid.get(j);
            float ax = a.x - scrollPx;
            if (ax + cellW < 0 || ax > W) continue;
            drawApp(c, a, ax, a.y, cellW, true);
        }

        // ---- page dots
        int pg = pages();
        if (pg > 1) {
            float cur = scrollPx / Math.max(1, W), start = W / 2f - (pg - 1) * 7 * d;
            for (int i = 0; i < pg; i++) {
                float near = Math.max(0f, 1f - Math.abs(cur - i));
                p.setColor(Color.argb((int) (110 + 145 * near), 255, 255, 255));
                c.drawCircle(start + i * 14 * d, dotsY, (3f + 1.2f * near) * d, p);
            }
        }

        // ---- dock
        for (int j = 0; j < dock.size(); j++) {
            App a = dock.get(j);
            drawApp(c, a, a.x, a.y, dockCellW, false);
        }

        // ---- top drop targets (App info / Uninstall), only while dragging
        if (barA > 0.01f) {
            int al = (int) (255 * barA);
            for (int s = 0; s < 2; s++) {
                boolean on = hoverT == (s == 0 ? T_INFO : T_UNINSTALL);
                int base = s == 0 ? 0xFF2F80ED : 0xFFE5484D;
                p.setColor(Color.argb((int) ((on ? 235 : 150) * barA), Color.red(base), Color.green(base), Color.blue(base)));
                c.drawRect(s * W / 2f, 0, (s + 1) * W / 2f, topBarH, p);
                tp.setTypeface(tfMed);
                tp.setTextSize(15 * d);
                tp.setColor(Color.argb(al, 255, 255, 255));
                c.drawText(s == 0 ? "App info" : "Uninstall", (s * 2 + 1) * W / 4f, statusH + 34 * d, tp);
            }
        }

        // ---- the dragged icon, on top of everything, lifted and under the finger
        if (dragging) {
            dragApp.scale += (1.22f - dragApp.scale) * 0.3f;
            if (Math.abs(1.22f - dragApp.scale) > 0.005f) busy = true;
            float cx = dragX, cy = dragY - 18 * d;
            c.save();
            c.scale(dragApp.scale, dragApp.scale, cx, cy);
            p.setColor(0x44000000);
            c.drawCircle(cx, cy + 5 * d, iconS * 0.52f, p);
            p.setAlpha(245);
            c.drawBitmap(dragApp.icon, cx - iconS / 2f, cy - iconS / 2f, p);
            p.setAlpha(255);
            c.restore();
        }

        if (busy || dragging || !scroller.isFinished()) postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- scrolling

    private void snapTo(int page) {
        page = Math.max(0, Math.min(pages() - 1, page));
        int from = (int) scrollPx, to = page * W;
        scroller.startScroll(from, 0, to - from, 0, 300);
        postInvalidateOnAnimation();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.getCurrX();
            updateWallpaper();
            if (dragApp != null) updateHover();
            postInvalidateOnAnimation();
        }
    }

    private void updateWallpaper() {
        try {
            if (getWindowToken() == null) return;
            WallpaperManager wm = WallpaperManager.getInstance(getContext());
            int pg = pages();
            wm.setWallpaperOffsetSteps(pg > 1 ? 1f / (pg - 1) : 1f, 0f);
            wm.setWallpaperOffsets(getWindowToken(), maxScroll() > 0 ? scrollPx / maxScroll() : 0f, 0f);
        } catch (Throwable t) { }
    }

    public void goHome() {
        if (dragApp != null) endDrag(false);
        h.removeCallbacks(longRun);
        snapTo(0);
    }

    // ---------------------------------------------------------------- touch

    private App hit(float x, float y) {
        if (y >= dockTop) {
            for (App a : dock) if (x >= a.x && x < a.x + dockCellW) return a;
            return null;
        }
        if (y >= gridTop && y < gridBottom + 8 * d) {
            float cx = x + scrollPx;
            for (App a : grid) if (cx >= a.x && cx < a.x + cellW && y >= a.y && y < a.y + rowH) return a;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x; downY = lastY = y;
                moved = scrolling = longFired = false;
                scroller.forceFinished(true);
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                down = hit(x, y);
                h.postDelayed(longRun, longMs);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (dragApp != null) {
                    dragX = x; dragY = y;
                    if (Math.abs(x - pickX) > touchSlop || Math.abs(y - pickY) > touchSlop) dragMoved = true;
                    updateHover();
                    invalidate();
                    return true;
                }
                if (!scrolling && (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop)) {
                    moved = true;
                    h.removeCallbacks(longRun);
                    if (Math.abs(x - downX) > Math.abs(y - downY)) scrolling = true;
                }
                if (scrolling) {
                    scrollPx += lastX - x;
                    clampScroll();
                    updateWallpaper();
                    invalidate();
                }
                lastX = x; lastY = y;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                h.removeCallbacks(longRun);
                if (dragApp != null) {
                    dragX = x; dragY = y;
                    updateHover();
                    endDrag(up);
                } else if (scrolling) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vx = vt.getXVelocity();
                    int base = (int) Math.floor(scrollPx / Math.max(1, W));
                    int target;
                    if (vx < -minFling) target = base + 1;
                    else if (vx > minFling) target = base;
                    else target = Math.round(scrollPx / Math.max(1, W));
                    snapTo(target);
                } else if (up && !moved && !longFired && down != null) {
                    launch(down);
                }
                if (vt != null) { vt.recycle(); vt = null; }
                down = null;
                return true;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- drag and drop

    private void beginDrag(App a) {
        fromDock = dock.contains(a);
        ArrayList<App> src = fromDock ? dock : grid;
        origIndex = src.indexOf(a);
        src.remove(a);
        dragApp = a;
        a.scale = 1f;
        dragX = pickX = lastX;
        dragY = pickY = lastY;
        dragMoved = false;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        updateHover();
        invalidate();
    }

    /** Works out where the dragged app would land if it were dropped now (gap in the grid / dock / a top target). */
    private void updateHover() {
        if (dragApp == null) return;
        float fx = dragX, fy = dragY;
        hoverT = T_NONE; insGrid = -1; insDock = -1; swapSlot = -1;
        int newEdge = 0;
        if (fy < topBarH) {
            hoverT = fx < W / 2f ? T_INFO : T_UNINSTALL;
        } else if (fy > dockTop - 8 * d) {
            int dn = dock.size();
            if (dn < DOCK_MAX) {
                float start = (W - (dn + 1) * dockCellW) / 2f;
                insDock = Math.max(0, Math.min(dn, (int) ((fx - start) / dockCellW)));
            } else {
                float start = (W - dn * dockCellW) / 2f;
                swapSlot = Math.max(0, Math.min(dn - 1, (int) ((fx - start) / dockCellW)));
            }
        } else {
            int page = Math.max(0, Math.min(pages() - 1, (int) Math.floor((scrollPx + fx) / Math.max(1, W))));
            float xin = scrollPx + fx - page * W;
            int col = Math.max(0, Math.min(COLS - 1, (int) (xin / cellW)));
            int row = Math.max(0, Math.min(rows - 1, (int) ((fy - gridTop - topPad) / rowH)));
            insGrid = Math.max(0, Math.min(grid.size(), page * perPage + row * COLS + col));
            if (fx < 22 * d) newEdge = -1; else if (fx > W - 22 * d) newEdge = 1;
        }
        if (newEdge != edgeDir) {
            edgeDir = newEdge;
            h.removeCallbacks(edgeRun);
            if (edgeDir != 0) h.postDelayed(edgeRun, 450);
        }
    }

    /** Puts the dragged icon's start position (where your finger let go) into the coordinates of its new home. */
    private void startFromFinger(App a, boolean toDock) {
        float cw = toDock ? dockCellW : cellW;
        float cx = dragX - cw / 2f, cy = (dragY - 18 * d) - iconS / 2f - cellOff;
        a.x = toDock ? cx : cx + scrollPx;
        a.y = cy;
        a.placed = true;                 // keeps scale 1.22 -> animates down to 1 while it settles
    }

    private void returnHome(App a) {
        ArrayList<App> list = fromDock ? dock : grid;
        startFromFinger(a, fromDock);
        list.add(Math.max(0, Math.min(origIndex, list.size())), a);
    }

    private void endDrag(boolean drop) {
        if (dragApp == null) return;
        App a = dragApp;
        h.removeCallbacks(edgeRun);
        edgeDir = 0;
        boolean noMove = !dragMoved;
        int t = hoverT;

        if (!drop) {
            returnHome(a);
        } else if (t != T_NONE) {
            returnHome(a);
            if (t == T_INFO) appInfo(a); else uninstall(a);
        } else if (insDock >= 0) {
            startFromFinger(a, true);
            dock.add(Math.min(insDock, dock.size()), a);
        } else if (swapSlot >= 0 && swapSlot < dock.size()) {
            App old = dock.get(swapSlot);
            startFromFinger(a, true);
            dock.set(swapSlot, a);
            old.x += scrollPx;                                   // screen -> grid content coordinates
            grid.add(Math.max(0, Math.min(fromDock ? grid.size() : origIndex, grid.size())), old);
        } else if (insGrid >= 0) {
            startFromFinger(a, false);
            grid.add(Math.min(insGrid, grid.size()), a);
        } else {
            returnHome(a);
        }

        dragApp = null;                       // the list now contains it, drawing/animation takes over
        hoverT = T_NONE; insGrid = -1; insDock = -1; swapSlot = -1;
        save();
        if (pending != null) { ArrayList<App> pa = pending; pending = null; apply(pa); }
        clampScroll();
        snapTo(Math.round(scrollPx / Math.max(1, W)));
        invalidate();
        if (drop && noMove && t == T_NONE) appMenu(a);       // lifted and released in place = open the app menu
    }

    // ---------------------------------------------------------------- actions

    private void launch(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            act.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(act, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void appInfo(App a) {
        try {
            act.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                    Uri.fromParts("package", a.pkg(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(act, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstall(App a) {
        try {
            act.startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.pkg(), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(act, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void appMenu(final App a) {
        new AlertDialog.Builder(act).setTitle(a.label)
                .setItems(new CharSequence[]{"App info", "Uninstall"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        if (w == 0) appInfo(a); else uninstall(a);
                    }
                }).show();
    }

    private void emptyMenu() {
        new AlertDialog.Builder(act)
                .setItems(new CharSequence[]{"Wallpaper", "System settings"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        try {
                            if (w == 0) act.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"));
                            else act.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) {
                            Toast.makeText(act, "Not available", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        if (vt != null) { vt.recycle(); vt = null; }
        super.onDetachedFromWindow();
    }
}
