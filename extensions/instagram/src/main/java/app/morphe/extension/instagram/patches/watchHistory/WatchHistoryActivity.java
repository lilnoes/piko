/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.watchHistory;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.db.PikoWatchHistoryDb;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.ui.Dim;

public class WatchHistoryActivity extends Activity {

    private static final int TAB_ALL = 0;
    private static final int TAB_POSTS = 1;
    private static final int TAB_REELS = 2;

    private final List<PikoWatchHistoryDb.Entry> entries = new ArrayList<>();
    private HistoryAdapter adapter;
    private LinearLayout root;
    private GridView gridView;
    private TextView emptyView;
    private EditText searchBox;
    private TextView allTab;
    private TextView postsTab;
    private TextView reelsTab;
    private int currentTab = TAB_ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        InstagramPreferenceStyle.applySystemBarStyle(this);

        root.addView(buildToolbar());
        root.addView(buildSearch());
        root.addView(buildTabs());

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Dim.dp8 * 2, Dim.dp8 * 4, Dim.dp8 * 2, Dim.dp8 * 4);
        emptyView.setTextColor(InstagramPreferenceStyle.secondaryTextColor());

        gridView = new GridView(this);
        adapter = new HistoryAdapter();
        gridView.setAdapter(adapter);
        gridView.setNumColumns(3);
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setHorizontalSpacing(2);
        gridView.setVerticalSpacing(2);
        gridView.setPadding(0, 0, 0, 0);
        gridView.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        gridView.setSelector(android.R.color.transparent);
        gridView.setOnItemClickListener((parent, view, pos, id) -> {
            PikoWatchHistoryDb.Entry entry = entries.get(pos);
            if (entry.permalink != null && !entry.permalink.isEmpty()) {
                PikoUtils.openUrl(entry.permalink, true);
            }
        });

        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1
        );
        root.addView(gridView, fill);
        root.addView(emptyView, fill);
        emptyView.setVisibility(View.GONE);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });

        setContentView(root);
        reload();
    }

    private LinearLayout buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        toolbar.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);

        ImageView back = new ImageView(this);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
        backParams.gravity = Gravity.CENTER_VERTICAL;
        back.setLayoutParams(backParams);
        UI.setThemedIcon(back, "material_ic_keyboard_arrow_left_black_24dp");
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText(str("piko_watch_history_title"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, PikoUtils.spToPixels(20));
        title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        );
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        titleParams.leftMargin = Dim.dp8 / 2;
        title.setLayoutParams(titleParams);

        TextView debug = toolbarButton(str("piko_watch_history_debug"));
        debug.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
        debug.setOnClickListener(v -> showDebugLog());

        TextView clear = toolbarButton(str("piko_clear"));
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(this))
            .setMessage(str("piko_watch_history_clear_confirm"))
            .setPositiveButton(str("piko_clear"), (d, w) -> {
                PikoWatchHistoryDb.getInstance(this).clearAll();
                reload();
            })
            .setNegativeButton(str("piko_cancel"), null)
            .show());

        toolbar.addView(back);
        toolbar.addView(title);
        toolbar.addView(debug);
        toolbar.addView(clear);
        return toolbar;
    }

    private TextView toolbarButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, PikoUtils.spToPixels(16));
        button.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        button.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_VERTICAL;
        button.setLayoutParams(params);
        return button;
    }

    private void showDebugLog() {
        String log = WatchHistoryHook.debugLog();

        TextView text = new TextView(this);
        text.setText(log);
        text.setTextIsSelectable(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        text.setPadding(Dim.dp8 * 2, Dim.dp8 * 2, Dim.dp8 * 2, Dim.dp8 * 2);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        new android.app.AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(this))
            .setTitle(str("piko_watch_history_debug_title"))
            .setView(scroll)
            .setPositiveButton(str("piko_copy"), (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("piko watch history", log));
                    Toast.makeText(this, str("piko_copied"), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(str("piko_clear"), (d, w) -> WatchHistoryHook.clearDebugLog())
            .setNeutralButton(str("piko_cancel"), null)
            .show();
    }

    private EditText buildSearch() {
        searchBox = new EditText(this);
        searchBox.setHint(str("piko_watch_history_search"));
        searchBox.setSingleLine(true);
        searchBox.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        searchBox.setHintTextColor(InstagramPreferenceStyle.secondaryTextColor());
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchBox.setPadding(Dim.dp8 * 2, Dim.dp8, Dim.dp8 * 2, Dim.dp8);
        searchBox.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                reload();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return searchBox;
    }

    private LinearLayout buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(Dim.dp8, 0, Dim.dp8, Dim.dp8);

        allTab = tabButton(str("piko_watch_history_all"), TAB_ALL);
        postsTab = tabButton(str("piko_watch_history_posts"), TAB_POSTS);
        reelsTab = tabButton(str("piko_watch_history_reels"), TAB_REELS);
        tabs.addView(allTab, tabParams());
        tabs.addView(postsTab, tabParams());
        tabs.addView(reelsTab, tabParams());
        paintTabs();
        return tabs;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        );
        params.setMargins(Dim.dp8 / 2, 0, Dim.dp8 / 2, 0);
        return params;
    }

    private TextView tabButton(String label, int tab) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setOnClickListener(v -> {
            currentTab = tab;
            paintTabs();
            reload();
        });
        return button;
    }

    private void paintTabs() {
        styleTab(allTab, currentTab == TAB_ALL);
        styleTab(postsTab, currentTab == TAB_POSTS);
        styleTab(reelsTab, currentTab == TAB_REELS);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected
            ? InstagramPreferenceStyle.primaryTextColor()
            : InstagramPreferenceStyle.secondaryTextColor());
        tab.getPaint().setFakeBoldText(selected);
    }

    private void reload() {
        String type = null;
        if (currentTab == TAB_POSTS) type = PikoWatchHistoryDb.TYPE_POST;
        else if (currentTab == TAB_REELS) type = PikoWatchHistoryDb.TYPE_REEL;
        String search = searchBox != null && searchBox.getText() != null
            ? searchBox.getText().toString()
            : "";
        entries.clear();
        entries.addAll(PikoWatchHistoryDb.getInstance(this).query(type, search));
        adapter.notifyDataSetChanged();

        boolean empty = entries.isEmpty();
        gridView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean hasQuery = search != null && !search.trim().isEmpty();
            if (hasQuery) {
                emptyView.setText(str("piko_watch_history_no_results"));
            } else {
                emptyView.setText(str("piko_watch_history_empty") + "\n\n" + WatchHistoryHook.captureStatus());
            }
        }
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int pos) { return entries.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            Cell holder;

            if (convertView == null) {
                cell = new SquareCell(WatchHistoryActivity.this);
                cell.setBackgroundColor(0xFF1A1A1A);
                holder = new Cell();

                holder.cover = new ImageView(WatchHistoryActivity.this);
                holder.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(holder.cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ));

                holder.play = new TextView(WatchHistoryActivity.this);
                holder.play.setText("▶");
                holder.play.setTextColor(Color.WHITE);
                holder.play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                holder.play.setShadowLayer(4, 0, 0, Color.BLACK);
                FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                );
                playParams.gravity = Gravity.TOP | Gravity.END;
                playParams.setMargins(0, Dim.dp8, Dim.dp8, 0);
                cell.addView(holder.play, playParams);

                View fade = new View(WatchHistoryActivity.this);
                GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.TRANSPARENT, 0xCC000000}
                );
                fade.setBackground(gradient);
                FrameLayout.LayoutParams fadeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Dim.dp8 * 5
                );
                fadeParams.gravity = Gravity.BOTTOM;
                cell.addView(fade, fadeParams);

                holder.user = new TextView(WatchHistoryActivity.this);
                holder.user.setTextColor(Color.WHITE);
                holder.user.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                holder.user.setMaxLines(1);
                FrameLayout.LayoutParams userParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                );
                userParams.gravity = Gravity.BOTTOM;
                userParams.setMargins(Dim.dp8, 0, Dim.dp8, Dim.dp8 / 2);
                cell.addView(holder.user, userParams);

                cell.setTag(holder);
            } else {
                cell = (FrameLayout) convertView;
                holder = (Cell) cell.getTag();
            }

            PikoWatchHistoryDb.Entry entry = entries.get(position);
            boolean reel = PikoWatchHistoryDb.TYPE_REEL.equals(entry.type);
            holder.play.setVisibility(reel ? View.VISIBLE : View.GONE);
            String user = entry.username != null && !entry.username.isEmpty()
                ? entry.username
                : str("piko_unknown");
            holder.user.setText(user);
            WatchHistoryThumbs.bind(holder, entry.coverUrl);
            return cell;
        }
    }

    /**
     * Child references for a recycled grid cell. The thumbnail loader tracks its pending URL
     * here rather than on the ImageView's tag, which is a single slot the adapter also needs.
     */
    static final class Cell {
        ImageView cover;
        TextView play;
        TextView user;
        String pendingUrl;
    }

    private static final class SquareCell extends FrameLayout {
        SquareCell(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
