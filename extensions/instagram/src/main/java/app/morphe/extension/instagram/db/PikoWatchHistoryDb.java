/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class PikoWatchHistoryDb extends SQLiteOpenHelper {

    public static final String TYPE_POST = "post";
    public static final String TYPE_REEL = "reel";

    private static final String DB_NAME = "piko_watch_history.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "watch_history";
    private static final int MAX_ROWS = 1000;

    private static volatile PikoWatchHistoryDb instance;

    public static PikoWatchHistoryDb getInstance(Context context) {
        if (instance == null) {
            synchronized (PikoWatchHistoryDb.class) {
                if (instance == null) {
                    instance = new PikoWatchHistoryDb(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PikoWatchHistoryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "media_id TEXT UNIQUE NOT NULL," +
            "type TEXT NOT NULL," +
            "username TEXT," +
            "title TEXT," +
            "caption TEXT," +
            "hashtags TEXT," +
            "permalink TEXT," +
            "cover_url TEXT," +
            "watched_at INTEGER NOT NULL" +
            ")"
        );
        db.execSQL("CREATE INDEX idx_watch_watched_at ON " + TABLE + "(watched_at)");
        db.execSQL("CREATE INDEX idx_watch_type ON " + TABLE + "(type)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN cover_url TEXT");
        }
    }

    /**
     * Inserts a new watch, or refreshes metadata on a duplicate.
     * bumpWatchedAt is only true for a real watch (playback / Reels viewer). Bind callbacks
     * must not rewrite watched_at or a later prefetch steals the top of the list.
     */
    public void upsert(Entry entry, boolean bumpWatchedAt) {
        if (entry == null || entry.mediaId == null || entry.mediaId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        Cursor existing = db.rawQuery(
            "SELECT type, username, title, caption, hashtags, permalink, watched_at, cover_url FROM "
                + TABLE + " WHERE media_id = ?",
            new String[]{entry.mediaId}
        );
        try {
            if (existing.moveToFirst()) {
                ContentValues values = new ContentValues();
                boolean existingReel = TYPE_REEL.equals(existing.getString(0));
                values.put("type", existingReel || TYPE_REEL.equals(entry.type) ? TYPE_REEL : entry.type);
                values.put("username", firstNonEmpty(entry.username, existing.getString(1)));
                values.put("title", firstNonEmpty(entry.title, existing.getString(2)));
                values.put("caption", firstNonEmpty(entry.caption, existing.getString(3)));
                values.put("hashtags", firstNonEmpty(entry.hashtags, existing.getString(4)));
                values.put("permalink", firstNonEmpty(entry.permalink, existing.getString(5)));
                values.put("cover_url", firstNonEmpty(entry.coverUrl, existing.getString(7)));
                values.put("watched_at", bumpWatchedAt ? entry.watchedAt : existing.getLong(6));
                db.update(TABLE, values, "media_id = ?", new String[]{entry.mediaId});
            } else {
                ContentValues values = new ContentValues();
                values.put("media_id", entry.mediaId);
                values.put("type", entry.type);
                values.put("username", emptyToNull(entry.username));
                values.put("title", emptyToNull(entry.title));
                values.put("caption", emptyToNull(entry.caption));
                values.put("hashtags", emptyToNull(entry.hashtags));
                values.put("permalink", emptyToNull(entry.permalink));
                values.put("cover_url", emptyToNull(entry.coverUrl));
                values.put("watched_at", entry.watchedAt);
                db.insert(TABLE, null, values);
                trimToCap(db);
            }
        } finally {
            existing.close();
        }
    }

    public void upsert(Entry entry) {
        upsert(entry, true);
    }

    private void trimToCap(SQLiteDatabase db) {
        db.execSQL(
            "DELETE FROM " + TABLE + " WHERE rowid NOT IN (" +
            "SELECT rowid FROM " + TABLE + " ORDER BY watched_at DESC LIMIT " + MAX_ROWS + ")"
        );
    }

    public List<Entry> query(String type, String search) {
        ArrayList<Entry> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT media_id, type, username, title, caption, hashtags, permalink, watched_at, cover_url FROM "
            + TABLE + " WHERE 1=1"
        );
        ArrayList<String> args = new ArrayList<>();
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (search != null && !search.trim().isEmpty()) {
            String like = "%" + search.trim() + "%";
            sql.append(" AND (")
                .append("IFNULL(username,'') LIKE ? COLLATE NOCASE OR ")
                .append("IFNULL(title,'') LIKE ? COLLATE NOCASE OR ")
                .append("IFNULL(caption,'') LIKE ? COLLATE NOCASE OR ")
                .append("IFNULL(hashtags,'') LIKE ? COLLATE NOCASE")
                .append(")");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY watched_at DESC");
        Cursor cursor = getReadableDatabase().rawQuery(
            sql.toString(),
            args.toArray(new String[0])
        );
        try {
            while (cursor.moveToNext()) {
                Entry entry = new Entry();
                entry.mediaId = cursor.getString(0);
                entry.type = cursor.getString(1);
                entry.username = cursor.getString(2);
                entry.title = cursor.getString(3);
                entry.caption = cursor.getString(4);
                entry.hashtags = cursor.getString(5);
                entry.permalink = cursor.getString(6);
                entry.watchedAt = cursor.getLong(7);
                entry.coverUrl = cursor.getString(8);
                results.add(entry);
            }
        } finally {
            cursor.close();
        }
        return results;
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String firstNonEmpty(String preferred, String fallback) {
        return preferred != null && !preferred.isEmpty() ? preferred : emptyToNull(fallback);
    }

    public static class Entry {
        public String mediaId;
        public String type;
        public String username;
        public String title;
        public String caption;
        public String hashtags;
        public String permalink;
        public String coverUrl;
        public long watchedAt;
    }
}
