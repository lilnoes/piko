/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.watchHistory;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
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
    private ListView listView;
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

        listView = new ListView(this);
        adapter = new HistoryAdapter();
        listView.setAdapter(adapter);
        listView.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        listView.setDivider(new android.graphics.drawable.ColorDrawable(
            UI.getThemedColour("igds_color_separator")));
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((parent, view, pos, id) -> {
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
        root.addView(listView, fill);
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

        TextView clear = new TextView(this);
        clear.setText(str("piko_clear"));
        clear.setTextSize(TypedValue.COMPLEX_UNIT_PX, PikoUtils.spToPixels(16));
        clear.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        clear.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        clearParams.gravity = Gravity.CENTER_VERTICAL;
        clear.setLayoutParams(clearParams);
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
        toolbar.addView(clear);
        return toolbar;
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
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
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
            LinearLayout row;
            TextView userView, titleView, captionView, metaView;

            if (convertView == null) {
                row = new LinearLayout(WatchHistoryActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = Dim.dp8;
                row.setPadding(pad * 2, pad, pad * 2, pad);

                userView = new TextView(WatchHistoryActivity.this);
                userView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                userView.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                userView.setTag("u");

                titleView = new TextView(WatchHistoryActivity.this);
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                titleView.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                titleView.setTag("t");

                captionView = new TextView(WatchHistoryActivity.this);
                captionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                captionView.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                captionView.setMaxLines(3);
                captionView.setTag("c");

                metaView = new TextView(WatchHistoryActivity.this);
                metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                metaView.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                metaView.setTag("m");

                row.addView(userView);
                row.addView(titleView);
                row.addView(captionView);
                row.addView(metaView);
            } else {
                row = (LinearLayout) convertView;
                userView = row.findViewWithTag("u");
                titleView = row.findViewWithTag("t");
                captionView = row.findViewWithTag("c");
                metaView = row.findViewWithTag("m");
            }

            PikoWatchHistoryDb.Entry entry = entries.get(position);
            String user = entry.username != null && !entry.username.isEmpty()
                ? "@" + entry.username
                : str("piko_unknown");
            String kind = PikoWatchHistoryDb.TYPE_REEL.equals(entry.type)
                ? str("piko_watch_history_reel")
                : str("piko_watch_history_post");
            userView.setText(user + "  ·  " + kind);

            boolean hasTitle = entry.title != null && !entry.title.isEmpty();
            titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
            if (hasTitle) titleView.setText(entry.title);

            boolean hasCaption = entry.caption != null && !entry.caption.isEmpty();
            captionView.setVisibility(hasCaption ? View.VISIBLE : View.GONE);
            if (hasCaption) captionView.setText(entry.caption);

            StringBuilder meta = new StringBuilder();
            if (entry.hashtags != null && !entry.hashtags.isEmpty()) {
                meta.append(entry.hashtags);
                meta.append("  ·  ");
            }
            meta.append(DateFormat.format("MMM dd, yyyy  HH:mm", new Date(entry.watchedAt)));
            metaView.setText(meta.toString());

            return row;
        }
    }
}
