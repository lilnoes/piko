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
    private static final AtomicInteger HOOK_CALLS = new AtomicInteger();
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

    private static volatile Object lastMedia;

    /** On-device empty-state line: distinguishes "hooks never fire" from filtered/skipped entries. */
    public static String captureStatus() {
        String skip = lastSkip;
        return "capture hooks fired " + HOOK_CALLS.get()
            + " times this session, " + PERSISTED.get() + " stored"
            + (skip == null || skip.isEmpty() ? "" : ". last skip: " + skip);
    }

    private static void skip(String reason) {
        lastSkip = reason;
        Logger.printDebug(() -> "WatchHistoryHook skip: " + reason);
    }

    /** Called from bytecode hooks whenever a post or Reel media object is bound/viewed. */
    public static void onMediaViewed(final Object mediaObject) {
        onMediaViewed(mediaObject, null);
    }

    public static void onMediaViewedReel(final Object mediaObject) {
        onMediaViewed(mediaObject, PikoWatchHistoryDb.TYPE_REEL);
    }

    public static void onMediaViewedPost(final Object mediaObject) {
        onMediaViewed(mediaObject, PikoWatchHistoryDb.TYPE_POST);
    }

    private static void onMediaViewed(final Object mediaObject, final String typeHint) {
        HOOK_CALLS.incrementAndGet();
        Logger.printDebug(() -> "WatchHistoryHook.onMediaViewed type=" + typeHint
            + " media=" + (mediaObject == null ? "null" : mediaObject.getClass().getName()));
        if (mediaObject == null) {
            skip("null media");
            return;
        }
        if (!Pref.watchHistory()) {
            skip("pref off");
            return;
        }
        if (mediaObject == lastMedia && typeHint == null) {
            skip("same object");
            return;
        }
        lastMedia = mediaObject;
        getWorker().post(new Runnable() {
            @Override
            public void run() {
                persist(mediaObject, typeHint);
            }
        });
    }

    private static void persist(Object mediaObject, String typeHint) {
        try {
            Context ctx = PikoUtils.getContext();
            if (ctx == null) {
                skip("no context");
                return;
            }

            MediaData mediaData = new MediaData(mediaObject);
            String mediaId = mediaData.getPostID();
            if (mediaId == null || mediaId.isEmpty() || "0".equals(mediaId)) {
                skip("no media id");
                return;
            }

            long now = System.currentTimeMillis();
            Long last = RECENT.put(mediaId, now);
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                skip("dedupe");
                return;
            }
            if (RECENT.size() > 400) RECENT.clear();

            try {
                if (mediaData.getPostType() == PostType.STORY) {
                    skip("story");
                    return;
                }
            } catch (Exception ignored) {}

            String type;
            if (PikoWatchHistoryDb.TYPE_REEL.equals(typeHint)) {
                type = PikoWatchHistoryDb.TYPE_REEL;
            } else if (PikoWatchHistoryDb.TYPE_POST.equals(typeHint)) {
                type = PikoWatchHistoryDb.TYPE_POST;
            } else {
                PostType postType;
                try {
                    postType = mediaData.getPostType();
                } catch (Exception e) {
                    postType = PostType.POST;
                }
                type = postType == PostType.REEL
                    ? PikoWatchHistoryDb.TYPE_REEL
                    : PikoWatchHistoryDb.TYPE_POST;
            }

            String username = "";
            try {
                UserData userData = mediaData.getUserData();
                if (userData != null) {
                    String name = userData.getUsername();
                    if (name != null) username = name;
                }
            } catch (Exception ignored) {}

            String caption = "";
            try {
                String text = mediaData.getDescriptionText();
                if (text != null) caption = text;
            } catch (Exception ignored) {}

            String title = audioTitle(mediaData);
            if (title.isEmpty()) title = firstLine(caption);

            String hashtags = extractHashtags(caption);

            String permalink = "";
            try {
                String link = Links.generatePostLink(mediaObject, 0);
                if (link != null) permalink = link;
            } catch (Exception ignored) {}

            PikoWatchHistoryDb.Entry entry = new PikoWatchHistoryDb.Entry();
            entry.mediaId = mediaId;
            entry.type = type;
            entry.username = username;
            entry.title = title;
            entry.caption = caption;
            entry.hashtags = hashtags;
            entry.permalink = permalink;
            entry.watchedAt = now;
            PikoWatchHistoryDb.getInstance(ctx).upsert(entry);
            PERSISTED.incrementAndGet();
            lastSkip = "";
            Logger.printDebug(() -> "WatchHistoryHook.persist id=" + mediaId + " type=" + type);
        } catch (Exception e) {
            skip("persist error: " + e.getMessage());
            Logger.printException(() -> "WatchHistoryHook.persist", e);
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
