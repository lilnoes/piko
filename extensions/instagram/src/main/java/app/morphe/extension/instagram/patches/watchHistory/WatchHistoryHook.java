/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.watchHistory;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.PostType;
import app.morphe.extension.instagram.db.PikoWatchHistoryDb;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.OriginalSoundDataIntf;
import app.morphe.extension.instagram.entity.TrackDataIntf;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.Links;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class WatchHistoryHook {

    private static final long DEDUPE_WINDOW_MS = 2000L;
    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();

    /**
     * One entry point per bytecode anchor, so the debug log attributes every capture to the
     * anchor that produced it. A tag cannot be passed as an argument because injecting a
     * const-string would need a free register in methods we do not control.
     */
    private static final String[] SITES = {
        "autoplayState", "autoplayHistory", "screenItem", "feedBind",
        "frameBind", "reelBind", "aslSession", "mediaExt", "clipsState",
    };
    private static final int[] SITE_CALLS = new int[SITES.length];
    private static final int[] SITE_NULLS = new int[SITES.length];
    private static final Object[] SITE_LAST = new Object[SITES.length];

    /** Cross-site identity gate: the same media object reaches several anchors per scroll. */
    private static final Object[] SEEN = new Object[16];
    private static int seenIndex;

    private static final AtomicInteger PERSISTED = new AtomicInteger();
    private static volatile String lastSkip = "";

    private static HandlerThread sWorkerThread;
    private static Handler sWorker;

    private static synchronized Handler getWorker() {
        if (sWorker == null) {
            sWorkerThread = new HandlerThread("piko-watch-history");
            sWorkerThread.start();
            sWorker = new Handler(sWorkerThread.getLooper());
        }
        return sWorker;
    }

    public static void openWatchHistory(Context ctx) {
        try {
            if (ctx == null) ctx = PikoUtils.getContext();
            if (ctx == null) return;
            Intent intent = new Intent(ctx, WatchHistoryActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "WatchHistoryHook.openWatchHistory", e);
        }
    }

    /** Rewritten at patch time with the anchors that injected. */
    public static String injectionReport() {
        return "injection-report";
    }

    public static void onAutoplayState(Object media) {
        capture(media, 0);
    }

    public static void onAutoplayHistory(Object media) {
        capture(media, 1);
    }

    public static void onScreenItem(Object media) {
        capture(media, 2);
    }

    public static void onFeedBind(Object media) {
        capture(media, 3);
    }

    public static void onFrameBind(Object media) {
        capture(media, 4);
    }

    public static void onReelBind(Object media) {
        capture(media, 5);
    }

    public static void onAslSession(Object media) {
        capture(media, 6);
    }

    public static void onMediaExt(Object media) {
        capture(media, 7);
    }

    public static void onClipsState(Object media) {
        capture(media, 8);
    }

    /**
     * Runs on Instagram's own threads inside hot binder and playback paths, so everything
     * before the worker hand-off is reference comparison and integer counting.
     */
    private static void capture(final Object media, final int site) {
        try {
            SITE_CALLS[site]++;
            if (media == null) {
                SITE_NULLS[site]++;
                return;
            }
            if (media == SITE_LAST[site]) return;
            SITE_LAST[site] = media;

            if (!Pref.watchHistory()) {
                lastSkip = "pref off";
                return;
            }
            if (seenRecently(media)) return;

            getWorker().post(() -> persist(media, site));
        } catch (Exception e) {
            lastSkip = "capture error: " + e.getMessage();
        }
    }

    private static boolean seenRecently(Object media) {
        synchronized (SEEN) {
            for (Object seen : SEEN) {
                if (seen == media) return true;
            }
            SEEN[seenIndex] = media;
            seenIndex = (seenIndex + 1) & (SEEN.length - 1);
            return false;
        }
    }

    /** On-device diagnostics: separates "no hook fired" from "fired but filtered out". */
    public static String captureStatus() {
        StringBuilder builder = new StringBuilder();
        builder.append(injectionReport()).append('\n');
        boolean any = false;
        for (int i = 0; i < SITES.length; i++) {
            if (SITE_CALLS[i] == 0) continue;
            if (any) builder.append(", ");
            any = true;
            builder.append(SITES[i]).append(' ').append(SITE_CALLS[i]);
            if (SITE_NULLS[i] > 0) builder.append(" (").append(SITE_NULLS[i]).append(" null)");
        }
        if (!any) builder.append("no hook has fired this session");
        builder.append("\nstored ").append(PERSISTED.get());
        String skip = lastSkip;
        if (skip != null && !skip.isEmpty()) builder.append(", last skip: ").append(skip);
        return builder.toString();
    }

    static String debugLog() {
        String log = WatchHistoryDebug.snapshot();
        return captureStatus() + "\n\n" + (log.isEmpty() ? "no events recorded" : log);
    }

    static void clearDebugLog() {
        WatchHistoryDebug.clear();
    }

    private static void skip(String site, String mediaId, String reason) {
        lastSkip = reason;
        WatchHistoryDebug.log(site + " skip " + reason + (mediaId == null ? "" : " id=" + mediaId));
    }

    private static void skipQuiet(String reason) {
        lastSkip = reason;
    }

    private static boolean isWatchSite(int site) {
        // autoplayState, autoplayHistory, reelBind, clipsState — real playback / Reels viewer
        return site == 0 || site == 1 || site == 5 || site == 8;
    }

    private static boolean isReelSite(int site) {
        return site == 5 || site == 8;
    }

    private static void persist(Object mediaObject, int site) {
        String siteName = SITES[site];
        try {
            Context ctx = PikoUtils.getContext();
            if (ctx == null) {
                skip(siteName, null, "no context");
                return;
            }

            MediaData mediaData = new MediaData(mediaObject);
            String mediaId;
            try {
                mediaId = mediaData.getPostID();
            } catch (Exception e) {
                skip(siteName, null, "id failed on " + mediaObject.getClass().getName() + ": " + e);
                return;
            }
            if (mediaId == null || mediaId.isEmpty() || "0".equals(mediaId)) {
                skip(siteName, null, "no id on " + mediaObject.getClass().getName());
                return;
            }

            long now = System.currentTimeMillis();
            Long last = RECENT.put(mediaId, now);
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                skipQuiet("dedupe");
                return;
            }
            if (RECENT.size() > 400) RECENT.clear();

            PostType postType;
            try {
                postType = mediaData.getPostType();
            } catch (Exception e) {
                postType = PostType.POST;
            }
            if (postType == PostType.STORY) {
                skipQuiet("story");
                return;
            }
            boolean reel = postType == PostType.REEL || isReelSite(site);
            String type = reel ? PikoWatchHistoryDb.TYPE_REEL : PikoWatchHistoryDb.TYPE_POST;

            String username = "";
            try {
                UserData userData = mediaData.getUserData();
                if (userData != null) {
                    String name = userData.getUsername();
                    if (name != null) username = name;
                }
            } catch (Exception ignored) {}
            if (username.isEmpty()) {
                skip(siteName, mediaId, "no username type=" + postType);
                return;
            }

            String caption = "";
            try {
                String text = mediaData.getDescriptionText();
                if (text != null) caption = text;
            } catch (Exception ignored) {}

            String title = audioTitle(mediaData);
            if (title.isEmpty()) title = firstLine(caption);

            String permalink = "";
            try {
                String link = Links.generatePostLink(mediaObject, 0);
                if (link != null) permalink = link;
            } catch (Exception ignored) {}
            if (reel && permalink.contains("/p/")) {
                permalink = permalink.replace("/p/", "/reel/");
            }

            String coverUrl = "";
            try {
                String cover = mediaData.getCoverUrl();
                if (cover != null) coverUrl = cover;
            } catch (Exception ignored) {}

            PikoWatchHistoryDb.Entry entry = new PikoWatchHistoryDb.Entry();
            entry.mediaId = mediaId;
            entry.type = type;
            entry.username = username;
            entry.title = title;
            entry.caption = caption;
            entry.hashtags = extractHashtags(caption);
            entry.permalink = permalink;
            entry.coverUrl = coverUrl;
            entry.watchedAt = now;
            PikoWatchHistoryDb.getInstance(ctx).upsert(entry, isWatchSite(site));

            PERSISTED.incrementAndGet();
            lastSkip = "";
            WatchHistoryDebug.log(siteName + " stored " + type + " " + mediaId + " @" + username
                + " product=" + postType + " raw=" + mediaData.describePostType());
        } catch (Exception e) {
            skip(siteName, null, "error: " + e);
            Logger.printException(() -> "WatchHistoryHook.persist", e);
        } finally {
            WatchHistoryDebug.flush();
        }
    }

    private static String audioTitle(MediaData mediaData) {
        try {
            Object audio = mediaData.getAudioMedia();
            if (audio instanceof TrackDataIntf) {
                String name = ((TrackDataIntf) audio).getSongName();
                if (name != null && !name.isEmpty()) return name;
            } else if (audio instanceof OriginalSoundDataIntf) {
                String name = ((OriginalSoundDataIntf) audio).getAudioName();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String firstLine(String caption) {
        if (caption == null || caption.isEmpty()) return "";
        int newline = caption.indexOf('\n');
        String line = newline < 0 ? caption : caption.substring(0, newline);
        return line.trim();
    }

    private static String extractHashtags(String caption) {
        if (caption == null || caption.isEmpty()) return "";
        Matcher matcher = HASHTAG.matcher(caption);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(matcher.group());
        }
        return builder.toString();
    }
}
