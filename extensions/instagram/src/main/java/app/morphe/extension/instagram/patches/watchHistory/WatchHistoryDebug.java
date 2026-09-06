/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.watchHistory;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.Locale;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Logger;

/**
 * Ring buffer of capture events, readable from the watch history screen.
 *
 * Logcat is not reachable on an unrooted device, so every decision the capture path makes has
 * to be inspectable in the app itself. Survives a process restart, which matters because
 * Instagram is frequently killed between watching and checking.
 */
final class WatchHistoryDebug {

    private static final int MAX_LINES = 300;
    private static final String FILE_NAME = "piko_watch_history_debug.log";

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static boolean loaded;
    private static boolean dirty;
    private static long startedAt;

    private WatchHistoryDebug() {}

    static synchronized void log(String line) {
        load();
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        long seconds = (System.currentTimeMillis() - startedAt) / 1000L;
        LINES.addLast(String.format(Locale.US, "+%d:%02d %s", seconds / 60, seconds % 60, line));
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
        dirty = true;
        Logger.printDebug(() -> "WatchHistory: " + line);
    }

    static synchronized String snapshot() {
        load();
        if (LINES.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String line : LINES) builder.append(line).append('\n');
        return builder.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        loaded = true;
        dirty = false;
        File file = file();
        if (file != null && file.exists() && !file.delete()) {
            Logger.printDebug(() -> "WatchHistoryDebug could not delete " + FILE_NAME);
        }
    }

    /** Must not run on the main thread. */
    static synchronized void flush() {
        if (!dirty) return;
        File file = file();
        if (file == null) return;
        dirty = false;
        try (FileWriter writer = new FileWriter(file, false)) {
            for (String line : LINES) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (Exception e) {
            Logger.printException(() -> "WatchHistoryDebug.flush", e);
        }
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File file = file();
        if (file == null || !file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) LINES.removeFirst();
            }
            LINES.addLast("--- restart ---");
        } catch (Exception e) {
            Logger.printException(() -> "WatchHistoryDebug.load", e);
        }
    }

    private static File file() {
        Context ctx = PikoUtils.getContext();
        return ctx == null ? null : new File(ctx.getFilesDir(), FILE_NAME);
    }
}
