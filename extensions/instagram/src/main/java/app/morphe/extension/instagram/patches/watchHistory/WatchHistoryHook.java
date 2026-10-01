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

import java.lang.reflect.Field;
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
        "imprDwell", "imprEnd", "clipsWatch",
    };
    private static final int[] SITE_CALLS = new int[SITES.length];
    private static final int[] SITE_NULLS = new int[SITES.length];
    private static final Object[] SITE_LAST = new Object[SITES.length];

    /**
     * Diagnostic probes. The autoplay anchors above never fired on 439, so these are spread
     * across Instagram's own impression tracker and its on-screen listener implementations, and
     * the debug log reports which ones fire and when. Probes never write history; they only
     * count and identify. The millisecond offset is what separates a real view from a prefetch
     * burst: prefetch delivers several distinct media inside the same instant.
     */
    static final int PROBE_COUNT = 24;
    private static final int PROBE_LOG_LIMIT = 6;
    private static final int[] PROBE_CALLS = new int[PROBE_COUNT];
    private static final int[] PROBE_LOGGED = new int[PROBE_COUNT];
    private static final Object[] PROBE_LAST = new Object[PROBE_COUNT];
    private static volatile long probeEpoch;

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

    /** Rewritten at patch time with the method each probe index landed on. */
    public static String probeReport() {
        return "probe-report";
    }

    public static void onProbe0(Object media) { probe(media, 0); }

    public static void onProbe1(Object media) { probe(media, 1); }

    public static void onProbe2(Object media) { probe(media, 2); }

    public static void onProbe3(Object media) { probe(media, 3); }

    public static void onProbe4(Object media) { probe(media, 4); }

    public static void onProbe5(Object media) { probe(media, 5); }

    public static void onProbe6(Object media) { probe(media, 6); }

    public static void onProbe7(Object media) { probe(media, 7); }

    public static void onProbe8(Object media) { probe(media, 8); }

    public static void onProbe9(Object media) { probe(media, 9); }

    public static void onProbe10(Object media) { probe(media, 10); }

    public static void onProbe11(Object media) { probe(media, 11); }

    public static void onProbe12(Object media) { probe(media, 12); }

    public static void onProbe13(Object media) { probe(media, 13); }

    public static void onProbe14(Object media) { probe(media, 14); }

    public static void onProbe15(Object media) { probe(media, 15); }

    public static void onProbe16(Object media) { probe(media, 16); }

    public static void onProbe17(Object media) { probe(media, 17); }

    public static void onProbe18(Object media) { probe(media, 18); }

    public static void onProbe19(Object media) { probe(media, 19); }

    public static void onProbe20(Object media) { probe(media, 20); }

    public static void onProbe21(Object media) { probe(media, 21); }

    public static void onProbe22(Object media) { probe(media, 22); }

    public static void onProbe23(Object media) { probe(media, 23); }

    /**
     * Counts every call, and describes the first few distinct media per probe so the log can be
     * correlated against what was actually on screen at that moment.
     */
    private static void probe(final Object media, final int index) {
        try {
            PROBE_CALLS[index]++;
            if (media == null || media == PROBE_LAST[index]) return;
            PROBE_LAST[index] = media;
            if (PROBE_LOGGED[index] >= PROBE_LOG_LIMIT) return;
            PROBE_LOGGED[index]++;
            long now = System.currentTimeMillis();
            if (probeEpoch == 0) probeEpoch = now;
            final long offset = now - probeEpoch;
            getWorker().post(() -> describeProbe(media, index, offset));
        } catch (Exception ignored) {}
    }

    private static void describeProbe(Object media, int index, long offset) {
        String description;
        try {
            MediaData mediaData = new MediaData(media);
            String username = "";
            try {
                UserData userData = mediaData.getUserData();
                if (userData != null && userData.getUsername() != null) username = userData.getUsername();
            } catch (Exception ignored) {}
            description = mediaData.getPostID() + " @" + username + " raw=" + mediaData.describePostType();
        } catch (Exception e) {
            description = "unreadable: " + e;
        }
        WatchHistoryDebug.log("probe" + index + " at " + offset + "ms saw " + description);
        WatchHistoryDebug.flush();
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

    /** Instagram's impression tracker closing out a view that lasted past its dwell threshold. */
    public static void onImpressionDwell(Object media) {
        capture(media, 9);
    }

    /** Impression end, which the tracker only reports once the minimum view duration is met. */
    public static void onImpressionEnd(Object media) {
        capture(media, 10);
    }

    /**
     * Milliseconds of playback after which Instagram's own clips watch-state listener records a
     * reel as watched. Matching its threshold keeps the history in step with what Instagram
     * itself considers viewed.
     */
    private static final int CLIPS_WATCH_MS = 3000;

    private static final String MEDIA_CLASS_NAME = "com.instagram.feed.media.Media";
    private static final ConcurrentHashMap<Class<?>, Field> MEDIA_FIELDS = new ConcurrentHashMap<>();
    private static Object lastClipsItem;

    /** Reel playback progress. Fires every few hundred milliseconds while a reel is on screen. */
    public static void onClipsProgress(Object item, int positionMs) {
        if (positionMs < CLIPS_WATCH_MS) return;
        clipsWatch(item);
    }

    /** A reel that looped has necessarily been watched through once. */
    public static void onClipsLoop(Object item, int positionMs, int loopCount) {
        if (loopCount < 1) return;
        clipsWatch(item);
    }

    /**
     * Progress keeps arriving for the rest of the reel once the threshold is passed, so the item
     * is promoted once and the reference comparison keeps the rest off the worker thread.
     */
    private static void clipsWatch(Object item) {
        try {
            if (item == null || item == lastClipsItem) return;
            lastClipsItem = item;
            Object media = clipsMedia(item);
            if (media != null) capture(media, 11);
        } catch (Exception e) {
            lastSkip = "clipsWatch error: " + e.getMessage();
        }
    }

    /**
     * The clips item carries its media in a single field, located by type because its name is
     * obfuscated and changes between Instagram versions.
     */
    private static Object clipsMedia(Object item) throws Exception {
        Class<?> itemClass = item.getClass();
        Field field = MEDIA_FIELDS.get(itemClass);
        if (field == null) {
            for (Class<?> c = itemClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field candidate : c.getDeclaredFields()) {
                    if (MEDIA_CLASS_NAME.equals(candidate.getType().getName())) {
                        candidate.setAccessible(true);
                        field = candidate;
                        break;
                    }
                }
                if (field != null) break;
            }
            if (field == null) {
                skipQuiet("no media field on " + itemClass.getName());
                return null;
            }
            MEDIA_FIELDS.put(itemClass, field);
        }
        return field.get(item);
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
        builder.append("\nwatched ").append(PERSISTED.get());
        String skip = lastSkip;
        if (skip != null && !skip.isEmpty()) builder.append(", last skip: ").append(skip);

        builder.append('\n').append(probeReport()).append('\n');
        boolean anyProbe = false;
        for (int i = 0; i < PROBE_COUNT; i++) {
            if (PROBE_CALLS[i] == 0) continue;
            if (anyProbe) builder.append(", ");
            anyProbe = true;
            builder.append("probe").append(i).append(' ').append(PROBE_CALLS[i]);
        }
        if (!anyProbe) builder.append("no probe has fired this session");
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

    /**
     * Only anchors that mean "this media played" may stamp a watch. Every binder and state
     * factory is prefetch-time: clipsState fires for the reel on screen and the next few queued
     * behind it in the same instant, so treating it as a watch fills the list with unseen media.
     */
    private static boolean isWatchSite(int site) {
        // autoplayState and autoplayHistory have never been observed firing on 439. The impression
        // and clips sites are Instagram's own "this was on screen long enough to count" events:
        // they are reached only after a view outlives a dwell threshold, which is the one thing
        // prefetch cannot satisfy.
        return site == 0 || site == 1 || site == 9 || site == 10 || site == 11;
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
            boolean reel = postType == PostType.REEL;
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
            boolean watched = isWatchSite(site);
            PikoWatchHistoryDb.getInstance(ctx).upsert(entry, watched);

            if (watched) PERSISTED.incrementAndGet();
            lastSkip = "";
            WatchHistoryDebug.log(siteName + (watched ? " watched " : " seen ") + type + " " + mediaId
                + " @" + username + " product=" + postType + " raw=" + mediaData.describePostType());
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
