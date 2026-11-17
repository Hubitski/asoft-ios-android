package com.asoft.ios;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Free memory while iOS runs": every 90 s, while iOS runs, and once before a setup's helper VM job on a phone
 * short of memory (beforeHelper), the background processes
 * of the phone's other apps are ended the way Android ends them when memory runs short: not a force
 * stop, so their alarms, jobs and notifications stay, but an app left in the background starts afresh
 * when opened. iOS needs 4 to 8 GB, and a save writes 3 GB more, which Android otherwise answers by
 * ending this app.
 *
 * Android 13 and older: ActivityManager.killBackgroundProcesses, for the apps a person starts. Android 14
 * and later let an app end only its own processes: there the app asks adb's shell on this phone for
 * "am kill-all" (every cached process; nothing in the foreground, so not this app, its QEMU or its
 * services), which works once the user has paired the app with Wireless debugging (WirelessAdb). With a
 * pairing the same is used on older Androids too.
 */
final class MemoryCleaner {
    private static final String PREFS = "asoft", KEY = "free_ram", KEY_AGGRESSIVE = "free_ram_aggressive";
    private static final long PERIOD_MS = 90_000;
    /** The aggressive round force-stops one app at a time: a hundred of them must not hit the adb timeout. */
    private static final int AGGRESSIVE_TIMEOUT_MS = 180_000;
    private static Thread loop;

    private MemoryCleaner() {}

    /** Without a pairing (WirelessAdb) only up to Android 13. */
    static boolean supported(Context c) {
        return Build.VERSION.SDK_INT < 34 || WirelessAdb.paired(c);
    }

    static boolean enabled(Context c) {
        return supported(c) && prefs(c).getBoolean(KEY, false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY, on).apply();
        if (on && Guest.running()) {
            start(c);
        }
    }

    /**
     * Force-stop the phone's other apps too (am force-stop through the pairing), not only their background
     * processes: alarms, jobs and notifications stop with them and each starts from its first screen next time.
     * Off by default; needs the pairing, since an app may not force-stop another.
     */
    static boolean aggressive(Context c) {
        return WirelessAdb.paired(c) && prefs(c).getBoolean(KEY_AGGRESSIVE, false);
    }

    static void setAggressive(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AGGRESSIVE, on).apply();
        if (on) {
            setEnabled(c, true);        // a force-stop round only runs inside the timer
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Runs the rounds in the background until iOS stops; a second call while it runs does nothing. */
    static synchronized void start(Context c) {
        if (!enabled(c) || loop != null && loop.isAlive()) {
            return;
        }
        Context app = c.getApplicationContext();
        loop = new Thread(() -> {
            // the first round once QEMU is up, then every PERIOD_MS while it runs
            for (int i = 0; i < 100 && !Guest.running(); i++) {
                sleep(200);
            }
            while (Guest.running() && enabled(app)) {
                if (busy.compareAndSet(false, true)) {
                    try {
                        round(app, "free memory", 30_000);
                    } finally {
                        busy.set(false);
                    }
                }
                sleep(PERIOD_MS);
            }
        }, "free-memory");
        loop.setDaemon(true);
        loop.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** The apps a person starts (the launcher's list), but this one, the launcher and the keyboard. */
    private static Set<String> others(Context c) {
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        ResolveInfo home = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null && home.activityInfo != null) {
            skip.add(home.activityInfo.packageName);
        }
        String ime = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) {
            skip.add(ime.substring(0, ime.indexOf('/')));
        }
        Set<String> out = new HashSet<>();
        List<ResolveInfo> apps = c.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo r : apps) {
            String p = r.activityInfo.packageName;
            if (!skip.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static long availableMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    /** The last round's failure through adb, said once in the log until it changes. */
    private static String adbSaid;

    /** A round is running (the timer's or beforeSave's): the other one skips. */
    private static final java.util.concurrent.atomic.AtomicBoolean busy =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One round now, when the user has this on: Save and close calls it with iOS paused, right before the
     * save writes its state, so what the other apps hold in the background goes first. Skipped while the
     * timer's round runs (that one frees the same).
     */
    static void beforeSave(Context c) {
        if (!enabled(c)) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            AppLog.note(c, "free memory before the save: the timer's round is running");
            return;
        }
        try {
            round(c, "free memory before the save", 10_000);
        } finally {
            busy.set(false);
        }
    }

    /**
     * One round before a helper VM job on a phone short of memory (Helper.beforeJob), when the user has this on:
     * the setup's build needs its memory as iOS does, and Android's memory killer ends the VM half way otherwise.
     * True when a round ran.
     */
    static boolean beforeHelper(Context c) {
        if (!enabled(c) || !busy.compareAndSet(false, true)) {
            return false;
        }
        try {
            round(c, "free memory before the helper VM", 30_000);
            return true;
        } finally {
            busy.set(false);
        }
    }

    /**
     * One round before a setup boot (PhoneSetup26.startCool): iOS's DataMigration boots need the room more than
     * any other step, and it is the step Android's memory killer has ended most often.
     */
    static void beforeBoot(Context c) {
        if (!enabled(c) || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            round(c, "free memory before the boot", 30_000);
        } finally {
            busy.set(false);
        }
        // and the 90 s rounds go on while this boot's QEMU runs
        start(c);
    }

    /** What the aggressive round force-stops: every app a person starts, but Settings too (the way back). */
    private static Set<String> aggressiveTargets(Context c) {
        Set<String> apps = new HashSet<>(others(c));
        apps.remove("com.android.settings");
        apps.remove("com.samsung.android.settings");
        apps.remove("com.google.android.settings");
        return apps;
    }

    /** One adb shell command: force-stop each package in turn (am takes one). */
    static String forceStopCommand(Set<String> apps) {
        StringBuilder b = new StringBuilder("for p in");
        for (String p : apps) {
            b.append(' ').append(p);
        }
        return b.append("; do am force-stop \"$p\"; done").toString();
    }

    /**
     * One round on the user's request (the memory sheet before a start): am kill-all, and with @force the
     * force-stop of every app a person starts. Returns {available before, after} in MB; through the pairing
     * when there is one, else the app's own killBackgroundProcesses (Android 13 and older, cached processes).
     */
    static long[] freeNow(Context c, boolean force, int timeoutMs) throws java.io.IOException {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        long before = availableMb(am);
        if (WirelessAdb.paired(c)) {
            if (force) {
                Set<String> apps = aggressiveTargets(c);
                if (!apps.isEmpty()) {
                    WirelessAdb.shell(c, forceStopCommand(apps), Math.max(timeoutMs, AGGRESSIVE_TIMEOUT_MS));
                }
            }
            WirelessAdb.shell(c, "am kill-all", timeoutMs);
        } else {
            for (String p : others(c)) {
                try {
                    am.killBackgroundProcesses(p);
                } catch (SecurityException ignored) {
                    // the platform refuses it on Android 14+: the pairing is the way there
                }
            }
        }
        sleep(1500);
        return new long[] {before, availableMb(am)};
    }

    /** A process line of "dumpsys meminfo": "  1,228,800K: com.foo (pid 12 / activities)". */
    private static final java.util.regex.Pattern TOP =
            java.util.regex.Pattern.compile("^\\s*([0-9,]+)K:\\s+(.+?)(?:\\s+\\(pid.*)?$");

    /** What the memory sheet shows: the top @n processes by PSS, or why there is no list (not paired). */
    static String topText(Context c, int n, int timeoutMs) {
        if (!WirelessAdb.paired(c)) {
            return "Wireless debugging is not paired: the apps cannot be listed (the kill still ends this app's "
                    + "background processes on Android 13 and older).";
        }
        try {
            return topLines(WirelessAdb.shell(c, "dumpsys meminfo", timeoutMs), n);
        } catch (java.io.IOException e) {
            return "Could not list the apps: " + e.getMessage();
        }
    }

    /** The first @n process lines of @dumpsys's "Total PSS by process" (a test without adb). */
    static String topLines(String dumpsys, int n) {
        if (dumpsys == null) {
            return "";
        }
        int at = dumpsys.indexOf("Total PSS by process:");
        if (at < 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int got = 0;
        for (String l : dumpsys.substring(at).split("\n")) {
            java.util.regex.Matcher m = TOP.matcher(l);
            if (m.matches()) {
                if (got >= n) {
                    break;
                }
                long kb;
                try {
                    kb = Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (got++ > 0) {
                    b.append('\n');
                }
                b.append(m.group(2).trim()).append(" — ").append(Math.round(kb / 1024.0)).append(" MB");
            } else if (got > 0) {
                break;      // the categories follow the processes
            }
        }
        return b.toString();
    }

    private static void round(Context c, String what, int adbTimeoutMs) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        long before = availableMb(am);
        if (WirelessAdb.paired(c)) {
            try {
                String forced = "";
                if (aggressive(c)) {
                    Set<String> apps = aggressiveTargets(c);
                    if (!apps.isEmpty()) {
                        WirelessAdb.shell(c, forceStopCommand(apps), Math.max(adbTimeoutMs, AGGRESSIVE_TIMEOUT_MS));
                        forced = " and force-stopped " + apps.size() + " apps";
                    }
                }
                WirelessAdb.shell(c, "am kill-all", adbTimeoutMs);
                sleep(1500);
                adbSaid = null;
                AppLog.note(c, what + ": am kill-all" + forced + " through wireless adb, available " + before + " -> "
                        + availableMb(am) + " MB");
                return;
            } catch (java.io.IOException e) {
                String why = String.valueOf(e.getMessage());
                if (!why.equals(adbSaid)) {
                    adbSaid = why;
                    AppLog.note(c, what + ": wireless adb: " + why);
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    return;     // nothing else an app may do here
                }
            }
        }
        Set<String> apps = others(c);
        for (String p : apps) {
            try {
                am.killBackgroundProcesses(p);
            } catch (SecurityException e) {
                AppLog.note(c, what + ": refused (" + e.getMessage() + ")");
                return;
            }
        }
        sleep(1500);
        AppLog.note(c, what + ": " + apps.size() + " apps' background processes ended, available "
                + before + " -> " + availableMb(am) + " MB");
    }
}
