package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The memory sheet before a setup or a start, at any amount of free memory (not only when it is short): what is
 * free of the phone's RAM, what the start takes, the apps that hold the memory now, a one-tap am kill-all, and,
 * when more than {@link #FORCE_SHARE} of the RAM is in use, a force-stop of the apps that are started. After a
 * round it says how much went; Start goes on as before. The phone's own adb shell does the work (the Wireless
 * debugging pairing); Android 13 and older also have the app's killBackgroundProcesses.
 */
final class MemoryBeforeStart {
    private MemoryBeforeStart() {}

    /** The force-stop button appears when more than this share of MemTotal is in use (avail under 40 %). */
    static final double FORCE_SHARE = 0.60;

    static boolean forceButton(long total, long avail) {
        return total > 0 && avail < total * 2 / 5;
    }

    static String gb(long b) {
        return String.format(Locale.ROOT, "%.1f GB", b / 1e9);
    }

    static String statsText(String what, long total, long avail, long need) {
        String s = "Free " + gb(avail) + " of " + gb(total) + "  ·  " + what + " takes about " + gb(need) + ".";
        return s + (avail < need
                ? "\nMemory is short: Android may close the app, and work that was not saved is lost."
                : "\nEnough for the start.");
    }

    /** Shows the sheet; @go runs when Start is pressed (Battery.askOnce and the service's start follow it). */
    static void show(Activity a, String what, long need, Runnable go) {
        long total = Helper.phoneRam(a), avail = Helper.available(a);
        AppLog.note(a, String.format(Locale.ROOT, "memory before %s: %.1f GB free of %.1f GB, takes about %.1f GB",
                what, avail / 1e9, total / 1e9, need / 1e9));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 6), p, 0);
        TextView stats = Ui.text(a, box, statsText(what, total, avail, need), 15, Ui.TEXT);
        TextView top = Ui.text(a, box, "Reading what holds the memory…", 12.5f, Ui.DIM);
        top.setPadding(0, Ui.dp(a, 14), 0, 0);
        TextView result = Ui.text(a, box, "", 13, Ui.DIM);
        result.setPadding(0, Ui.dp(a, 10), 0, 0);
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(a, 14), 0, 0);
        TextView kill = Ui.capsule(a, "Kill background", true, null);
        TextView force = Ui.capsule(a, "Force-stop my apps", true, null);
        actions.addView(kill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(a, 10);
        actions.addView(force, lp);
        force.setVisibility(forceButton(total, avail) ? View.VISIBLE : View.GONE);
        box.addView(actions);
        TextView note = Ui.text(a, box, "Kill background ends the other apps' cached processes; their notifications stay. "
                + "Force-stop also ends their services and notifications (the launcher, keyboard, Settings and this app "
                + "stay). Both go through the Wireless debugging pairing; without it, Android 13 and older still let this "
                + "app end its own background processes.", 11.5f, Ui.FAINT);
        note.setPadding(0, Ui.dp(a, 10), 0, 0);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Memory before " + what)
                .setView(box)
                .setPositiveButton("Start", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .create();
        kill.setOnClickListener(v -> free(a, what, need, total, false, stats, top, result, kill, force));
        force.setOnClickListener(v -> free(a, what, need, total, true, stats, top, result, kill, force));
        dlg.show();
        readTop(a, top);
    }

    private static void readTop(Activity a, TextView top) {
        new Thread(() -> {
            String t = MemoryCleaner.topText(a, 6, 30_000);
            a.runOnUiThread(() -> {
                if (!a.isDestroyed()) {
                    top.setText(t.isEmpty() ? "No process list came back." : t);
                }
            });
        }, "memory-top").start();
    }

    private static void free(Activity a, String what, long need, long total, boolean force, TextView stats, TextView top,
                             TextView result, TextView kill, TextView forceBtn) {
        kill.setEnabled(false);
        forceBtn.setEnabled(false);
        result.setTextColor(Ui.DIM);
        result.setText(force ? "Force-stopping your apps…" : "Killing the other apps' background processes…");
        new Thread(() -> {
            try {
                long[] r = MemoryCleaner.freeNow(a, force, force ? 180_000 : 30_000);
                long now = Helper.available(a);
                String t = MemoryCleaner.topText(a, 6, 30_000);
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) {
                        return;
                    }
                    stats.setText(statsText(what, total, now, need));
                    result.setTextColor(r[1] > r[0] ? Ui.GREEN : Ui.ORANGE);
                    result.setText(String.format(Locale.ROOT, "%s: %d MB -> %d MB (freed %d MB)",
                            force ? "Force-stop" : "Kill background", r[0], r[1], r[1] - r[0]));
                    top.setText(t.isEmpty() ? "No process list came back." : t);
                    kill.setEnabled(true);
                    forceBtn.setEnabled(true);
                    forceBtn.setVisibility(forceButton(total, now) ? View.VISIBLE : View.GONE);
                });
            } catch (java.io.IOException e) {
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) {
                        return;
                    }
                    result.setTextColor(Ui.RED);
                    result.setText("Could not free memory: " + e.getMessage());
                    kill.setEnabled(true);
                    forceBtn.setEnabled(true);
                });
            }
        }, "memory-free").start();
    }
}
