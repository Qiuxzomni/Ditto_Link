package com.vibedevdrop.dittolink;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;

import java.io.File;
import java.io.FileOutputStream;
import java.net.NetworkInterface;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.media.MediaMetadataRetriever;
import android.webkit.MimeTypeMap;
import android.view.animation.AccelerateDecelerateInterpolator;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 7301;
    private static final int BG = Color.rgb(8, 13, 18);
    private static final int PANEL = Color.rgb(16, 25, 34);
    private static final int PANEL_ALT = Color.rgb(19, 31, 42);
    private static final int LINE = Color.rgb(48, 69, 80);
    private static final int CYAN = Color.rgb(64, 223, 239);
    private static final int AMBER = Color.rgb(255, 184, 77);
    private static final int GREEN = Color.rgb(91, 242, 162);
    private static final int WHITE = Color.rgb(233, 242, 247);
    private static final int MUTED = Color.rgb(130, 150, 163);

    private LinearLayout root;
    private FrameLayout pages;
    private ScrollView homePage, historyPage;
    private ScrollView historyTimelineScroll;
    private LinearLayout homeContent;
    private TextView homeStatus, listenerValue, pcAddressValue, historySentValue, historyReceivedValue;
    private LinearLayout homeRecentRows, historyRows;
    private FrameLayout bottomNav;
    private Dialog settingsDialog;
    private Button receiveButton;
    private EditText textInput;
    private ArtFrame homeLinkFrame, homeActionsFrame, homeActivityFrame;
    private Bitmap historyIconsSource, themedTextIcon, themedFileIcon, themedImageIcon;
    private Bitmap settingsDialogArt, settingsInputArt, settingsButtonArt;
    private int currentPage;
    private boolean discoverTailscaleOnReturn;
    private boolean tailscaleReturnCheckScheduled;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnailLoader = Executors.newFixedThreadPool(2);
    private final BroadcastReceiver historyChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateHome();
            if (currentPage == 1) refreshHistory();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settingsDialogArt = cropTransparent(BitmapFactory.decodeResource(getResources(), R.drawable.settings_dialog_frame));
        BitmapFactory.Options controlArtOptions = new BitmapFactory.Options(); controlArtOptions.inSampleSize = 2;
        settingsInputArt = cropTransparent(BitmapFactory.decodeResource(getResources(), R.drawable.settings_input_frame, controlArtOptions));
        settingsButtonArt = cropTransparent(BitmapFactory.decodeResource(getResources(), R.drawable.settings_button_frame, controlArtOptions));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        var prefs = getSharedPreferences("peer", MODE_PRIVATE);
        if (prefs.getBoolean("backgroundReceive", false)) startReceiverService();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        FrameLayout swipeArea = new SwipeFrame(this);
        pages = new FrameLayout(this);
        homePage = buildHomePage();
        historyPage = buildHistoryPage();
        pages.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(historyPage, new FrameLayout.LayoutParams(-1, -1));
        historyPage.setVisibility(View.GONE);
        swipeArea.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        root.addView(swipeArea, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomNav = buildBottomNav();
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        updateHome();
        updateBottomNav();
        if (!prefs.getBoolean("tailscaleWelcomeShown", false)) {
            prefs.edit().putBoolean("tailscaleWelcomeShown", true).apply();
            root.postDelayed(this::showTailscaleWelcome, 350);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (homeContent != null) updateHome();
        if (currentPage == 1) refreshHistory();
        if (discoverTailscaleOnReturn && !tailscaleReturnCheckScheduled) {
            tailscaleReturnCheckScheduled = true;
            checkTailscaleAfterReturn(0);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TransferHistory.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(historyChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(historyChanges, filter);
    }

    @Override protected void onStop() {
        unregisterReceiver(historyChanges);
        super.onStop();
    }

    private ScrollView buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setPadding(0, 0, 0, dp(8));
        scroll.addView(homeContent, new ScrollView.LayoutParams(-1, -2));
        ArtFrame header = artFrame(R.drawable.home_header, 52, 116);
        Button settings = transparentTap();
        settings.setOnClickListener(v -> showSettings());
        header.place(settings, 829, 68, 93, 91);
        addArt(homeContent, header, 0);

        homeLinkFrame = artFrame(R.drawable.home_link, 180, 315);
        listenerValue = label("PC ONLINE", 13, GREEN, true);
        listenerValue.setTextSize(10);
        listenerValue.setSingleLine(true);
        listenerValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        listenerValue.setGravity(Gravity.CENTER);
        listenerValue.setBackground(round(0xFF091B20, 0x8840D9E8, 12));
        homeLinkFrame.place(listenerValue, 118, 312, 205, 46);
        pcAddressValue = label(configuredHost().contains("not configured") ? "ADD PC IN SETTINGS" : "Tailscale", 12, WHITE, true);
        pcAddressValue.setTextSize(10);
        pcAddressValue.setGravity(Gravity.CENTER);
        pcAddressValue.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pcAddressValue.setSingleLine(true);
        pcAddressValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pcAddressValue.setBackground(round(0xFF0A1A20, 0x6640D9E8, 10));
        pcAddressValue.setOnClickListener(v -> chooseProfileForTransfers());
        homeLinkFrame.place(pcAddressValue, 133, 378, 226, 43);
        addArt(homeContent, homeLinkFrame, 10);

        ArtFrame composer = artFrame(R.drawable.home_composer, 507, 389);
        textInput = new EditText(this);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setTextSize(16);
        textInput.setTextColor(WHITE);
        textInput.setHintTextColor(MUTED);
        textInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        textInput.setBackgroundColor(Color.TRANSPARENT);
        textInput.setSingleLine(false);
        textInput.setMinLines(3);
        textInput.setMaxLines(5);
        composer.place(textInput, 54, 588, 833, 164);
        Button send = transparentTap();
        send.setOnClickListener(v -> sendTextToPc());
        composer.place(send, 178, 785, 585, 81);
        TextView paste = label("PASTE", 11, CYAN, true);
        paste.setGravity(Gravity.CENTER);
        paste.setOnClickListener(v -> pasteClipboard());
        composer.place(paste, 785, 521, 97, 51);
        addArt(homeContent, composer, 10);

        homeActionsFrame = artFrame(R.drawable.home_actions, 910, 210);
        Button choose = transparentTap();
        choose.setOnClickListener(v -> chooseFiles());
        homeActionsFrame.place(choose, 55, 991, 372, 99);
        receiveButton = actionButton("", Color.TRANSPARENT, WHITE, Color.TRANSPARENT);
        receiveButton.setBackgroundColor(Color.TRANSPARENT);
        receiveButton.setOnClickListener(v -> toggleReceiving());
        homeActionsFrame.place(receiveButton, 773, 993, 128, 82);
        addArt(homeContent, homeActionsFrame, 10);

        homeActivityFrame = artFrame(R.drawable.home_activity, 1141, 361);
        homeRecentRows = new LinearLayout(this);
        homeRecentRows.setOrientation(LinearLayout.VERTICAL);
        homeRecentRows.setTag("home-recent-rows");
        ScrollView recentScroll = new ScrollView(this);
        recentScroll.setFillViewport(false);
        recentScroll.setVerticalScrollBarEnabled(false);
        recentScroll.setNestedScrollingEnabled(true);
        recentScroll.setClipToPadding(false);
        recentScroll.setBackground(round(0xFF09151B, 0x6650D9E8, 9));
        recentScroll.setPadding(dp(7), dp(4), dp(7), dp(4));
        recentScroll.addView(homeRecentRows, new ScrollView.LayoutParams(-1, -2));
        homeActivityFrame.place(recentScroll, 35, 1207, 870, 250);
        addArt(homeContent, homeActivityFrame, 10);
        return scroll;
    }

    private View buildConnectionCard() {
        LinearLayout card = panel(PANEL, LINE, 18);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout titleRow = row();
        TextView title = label("DITTO LINK", 12, AMBER, true);
        title.setLetterSpacing(0.12f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        listenerValue = label("RECEIVE OFF", 10, AMBER, true);
        listenerValue.setLetterSpacing(0.06f);
        titleRow.addView(listenerValue);
        card.addView(titleRow);
        space(card, 14);

        LinearLayout route = row();
        route.setGravity(Gravity.CENTER_VERTICAL);
        TextView phone = chip("ANDROID", CYAN);
        route.addView(phone, new LinearLayout.LayoutParams(-2, dp(34)));
        TextView arrows = label("  ◀━━━━  ⇄  ━━━━▶  ", 13, CYAN, true);
        arrows.setGravity(Gravity.CENTER);
        route.addView(arrows, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView pc = chip("PC", WHITE);
        route.addView(pc, new LinearLayout.LayoutParams(-2, dp(34)));
        card.addView(route);
        space(card, 14);

        TextView pcAddress = label("PC  ·  " + configuredHost(), 13, WHITE, true);
        card.addView(pcAddress);
        space(card, 4);
        String phoneIp = tailscaleAddress();
        TextView addresses = label("PHONE  ·  " + (phoneIp.isEmpty() ? "Tailscale not detected" : phoneIp), 11, MUTED, false);
        addresses.setTypeface(Typeface.MONOSPACE);
        card.addView(addresses);
        return card;
    }

    private View buildComposerCard() {
        LinearLayout card = panel(PANEL, LINE, 18);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout heading = row();
        TextView title = label("CLIPBOARD TEXT", 12, AMBER, true);
        title.setLetterSpacing(0.1f);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView paste = label("PASTE", 10, CYAN, true);
        paste.setPadding(dp(10), dp(6), dp(10), dp(6));
        paste.setBackground(round(PANEL_ALT, CYAN, 12));
        heading.addView(paste);
        paste.setOnClickListener(v -> pasteClipboard());
        card.addView(heading);
        space(card, 10);

        textInput = new EditText(this);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setMinLines(3);
        textInput.setMaxLines(5);
        textInput.setTextSize(15);
        textInput.setTextColor(WHITE);
        textInput.setHintTextColor(MUTED);
        textInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        textInput.setBackground(round(Color.rgb(10, 17, 24), LINE, 12));
        card.addView(textInput, matchHeight(dp(108)));
        space(card, 10);

        Button send = actionButton("➤   SEND TO PC", CYAN, BG, CYAN);
        send.setTextSize(14);
        send.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(send, matchHeight(dp(48)));
        send.setOnClickListener(v -> sendTextToPc());
        return card;
    }

    private View buildActionRow() {
        LinearLayout actions = row();
        LinearLayout files = panel(PANEL_ALT, LINE, 16);
        files.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView fileIcon = label("▤", 22, AMBER, true);
        files.addView(fileIcon);
        space(files, 7);
        TextView fileTitle = label("FILES", 11, MUTED, true);
        fileTitle.setLetterSpacing(0.1f);
        files.addView(fileTitle);
        TextView fileAction = label("Choose files  ›", 14, WHITE, true);
        files.addView(fileAction);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(112), 1);
        actionLp.rightMargin = dp(7);
        actions.addView(files, actionLp);
        files.setOnClickListener(v -> chooseFiles());

        LinearLayout receive = panel(PANEL_ALT, LINE, 16);
        receive.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView receiveIcon = label("⇩", 22, CYAN, true);
        receive.addView(receiveIcon);
        space(receive, 7);
        TextView receiveTitle = label("BACKGROUND RX", 10, MUTED, true);
        receiveTitle.setLetterSpacing(0.07f);
        receive.addView(receiveTitle);
        receiveButton = actionButton("", PANEL, WHITE, LINE);
        receiveButton.setTextSize(13);
        LinearLayout.LayoutParams toggleLp = matchHeight(dp(34));
        toggleLp.topMargin = dp(5);
        receive.addView(receiveButton, toggleLp);
        LinearLayout.LayoutParams receiveLp = new LinearLayout.LayoutParams(0, dp(112), 1);
        receiveLp.leftMargin = dp(7);
        actions.addView(receive, receiveLp);
        receiveButton.setOnClickListener(v -> toggleReceiving());
        receive.setOnClickListener(v -> toggleReceiving());
        return actions;
    }

    private ScrollView buildHistoryPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        ArtFrame header = artFrame(R.drawable.home_header, 52, 116);
        Button settings = transparentTap(); settings.setOnClickListener(v -> showSettings());
        header.place(settings, 829, 68, 93, 91);
        addArt(content, header, 0);
        ArtFrame metrics = artFrame(R.drawable.history_metrics, 188, 522);
        historySentValue = label("000", 28, CYAN, true); historySentValue.setGravity(Gravity.CENTER);
        historySentValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        historyReceivedValue = label("000", 28, GREEN, true); historyReceivedValue.setGravity(Gravity.CENTER);
        historyReceivedValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        historySentValue.setBackground(round(0xFF08171E, 0x7740DCE8, 9));
        historyReceivedValue.setBackground(round(0xFF081A16, 0x775BEA91, 9));
        metrics.place(historySentValue, 327, 564, 104, 70);
        metrics.place(historyReceivedValue, 774, 564, 104, 70);
        addArt(content, metrics, 10);
        ArtFrame timeline = artFrame(R.drawable.history_timeline, 729, 742);
        historyRows = new LinearLayout(this);
        historyRows.setOrientation(LinearLayout.VERTICAL);
        historyTimelineScroll = new ScrollView(this);
        historyTimelineScroll.setFillViewport(false);
        historyTimelineScroll.setVerticalScrollBarEnabled(false);
        historyTimelineScroll.setNestedScrollingEnabled(true);
        historyTimelineScroll.setClipToPadding(false);
        historyTimelineScroll.setBackground(round(0xFF09151B, 0x7750D9E8, 9));
        historyTimelineScroll.setPadding(dp(7), dp(5), dp(7), dp(5));
        historyTimelineScroll.addView(historyRows, new ScrollView.LayoutParams(-1, -2));
        timeline.place(historyTimelineScroll, 32, 824, 877, 620);
        addArt(content, timeline, 10);
        scroll.setTag(content);
        return scroll;
    }

    private ArtFrame buildBottomNav() {
        ArtFrame nav = artFrame(R.drawable.home_nav, 1502, 145);
        nav.setTag(new int[] { R.drawable.home_nav, R.drawable.history_nav });
        Button home = transparentTap(), history = transparentTap();
        home.setOnClickListener(v -> showPage(0)); history.setOnClickListener(v -> showPage(1));
        nav.place(home, 85, 1508, 390, 138);
        nav.place(history, 475, 1508, 390, 138);
        return nav;
    }

    private void updateBottomNav() {
        if (root == null) return;
        Object tag = bottomNav.getTag();
        if (tag instanceof int[]) ((ArtFrame)bottomNav).setArt(currentPage == 0 ? ((int[])tag)[0] : ((int[])tag)[1]);
    }

    private void showPage(int page) {
        int targetPage = page == 0 ? 0 : 1;
        if (targetPage == currentPage) return;
        int oldPage = currentPage;
        currentPage = targetPage;
        View outgoing = oldPage == 0 ? homePage : historyPage;
        View incoming = targetPage == 0 ? homePage : historyPage;
        homePage.animate().cancel();
        historyPage.animate().cancel();
        homePage.setTranslationX(0); homePage.setAlpha(1f);
        historyPage.setTranslationX(0); historyPage.setAlpha(1f);
        int direction = targetPage > oldPage ? 1 : -1;
        int width = pages.getWidth() > 0 ? pages.getWidth() : getResources().getDisplayMetrics().widthPixels;
        incoming.setVisibility(View.VISIBLE);
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        incoming.setTranslationX(direction * width);
        incoming.setAlpha(1f);
        long duration = 420;
        AccelerateDecelerateInterpolator easing = new AccelerateDecelerateInterpolator();
        outgoing.animate().translationX(-direction * width)
                .setDuration(duration).setInterpolator(easing)
                .withEndAction(() -> {
                    outgoing.setVisibility(View.GONE);
                    outgoing.setTranslationX(0);
                    outgoing.setAlpha(1f);
                    outgoing.setLayerType(View.LAYER_TYPE_NONE, null);
                }).start();
        incoming.animate().translationX(0).setDuration(duration)
                .setInterpolator(easing).withEndAction(() -> incoming.setLayerType(View.LAYER_TYPE_NONE, null)).start();
        if (targetPage == 0) {
            updateHome();
        } else {
            refreshHistory();
        }
        updateBottomNav();
    }

    private void updateHome() {
        if (homeContent == null) return;
        if (listenerValue != null) {
            boolean enabled = getSharedPreferences("peer", MODE_PRIVATE).getBoolean("backgroundReceive", false);
            boolean ready = ClipboardListenerService.isReady();
            listenerValue.setText(configuredHost().contains("not configured") ? "PC NOT SET" : ready ? "LINK READY" : enabled ? "CONNECTING" : "PC LINK SET");
            listenerValue.setTextColor(ready ? GREEN : AMBER);
        }
        if (receiveButton != null) {
            boolean enabled = getSharedPreferences("peer", MODE_PRIVATE).getBoolean("backgroundReceive", false);
            receiveButton.setText(enabled ? "" : "OFF");
            receiveButton.setTextColor(enabled ? GREEN : MUTED);
            receiveButton.setBackground(enabled ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    : round(Color.rgb(13, 29, 37), CYAN, 16));
        }
        if (pcAddressValue != null) pcAddressValue.setText(configuredHost().contains("not configured") ? "ADD PC IN SETTINGS" : "Tailscale");
        refreshHomeRecent();
        if (homeStatus != null) {
            String message = getSharedPreferences("peer", MODE_PRIVATE).getString("lastStatus", "Ready to transfer.");
            homeStatus.setText(message);
        }
    }

    private void addHomeRecent() {
        LinearLayout card = panel(PANEL, LINE, 16);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout header = row();
        TextView title = label("RECENT ACTIVITY", 11, AMBER, true);
        title.setLetterSpacing(0.1f);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView viewAll = label("VIEW ALL  ›", 10, CYAN, true);
        header.addView(viewAll);
        viewAll.setOnClickListener(v -> showPage(1));
        card.addView(header);
        LinearLayout recentRows = new LinearLayout(this);
        recentRows.setOrientation(LinearLayout.VERTICAL);
        recentRows.setTag("home-recent-rows");
        space(card, 6);
        card.addView(recentRows);
        homeContent.addView(card);
        refreshHomeRecent();
    }

    private void refreshHomeRecent() {
        LinearLayout target = homeRecentRows;
        if (target == null) return;
        target.removeAllViews();
        List<TransferHistory.Entry> entries = TransferHistory.read(this);
        if (entries.isEmpty()) {
            TextView empty = label("NO RECENT TRANSFERS", 12, MUTED, true);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            target.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            TransferHistory.Entry entry = entries.get(i);
            if (i > 0) { View divider = new View(this); divider.setBackgroundColor(0x663D6673); target.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); }
            target.addView(transferRow(entry, true), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void refreshHistory() {
        if (historyRows == null) return;
        List<TransferHistory.Entry> entries = TransferHistory.read(this);
        long sent = TransferHistory.count(this, "SENT");
        long received = TransferHistory.count(this, "RECEIVED");
        if (historySentValue != null) historySentValue.setText(formatCounter(sent));
        if (historyReceivedValue != null) historyReceivedValue.setText(formatCounter(received));
        historyRows.removeAllViews();
        if (entries.isEmpty()) {
            TextView none = label("NO TRANSFERS YET", 17, WHITE, true);
            none.setGravity(Gravity.CENTER);
            historyRows.addView(none, new LinearLayout.LayoutParams(-1, -2));
            TextView hint = label("Send text or files to begin your local timeline", 12, MUTED, false);
            hint.setGravity(Gravity.CENTER);
            historyRows.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int count = entries.size();
        for (int i = 0; i < count; i++) {
            TransferHistory.Entry entry = entries.get(i);
            if (i > 0) { View divider = new View(this); divider.setBackgroundColor(0x773B5E69); historyRows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); }
            historyRows.addView(transferRow(entry, false), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View transferRow(TransferHistory.Entry entry, boolean compact) {
        LinearLayout item = row();
        item.setPadding(dp(compact ? 5 : 8), dp(compact ? 5 : 7), dp(compact ? 4 : 8), dp(compact ? 5 : 7));
        item.setBackground(round(Color.TRANSPARENT, 0x334A7580, 9));
        int accent = "SENT".equals(entry.direction) ? CYAN : GREEN;
        Uri fileUri = entry.files.isEmpty() ? null : Uri.parse(entry.files.get(0));
        String mime = fileUri == null ? "" : mimeType(fileUri);
        boolean thumbnail = entry.files.size() == 1 && (mime.startsWith("image/") || mime.startsWith("video/"));
        int iconSize = dp(compact ? 42 : 50);
        FrameLayout icon = new FrameLayout(this);
        icon.setBackgroundColor(Color.TRANSPARENT);
        ImageView typeIcon = new ImageView(this);
        typeIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        typeIcon.setImageBitmap(themedTypeIcon(entry, mime));
        icon.addView(typeIcon, new FrameLayout.LayoutParams(-1, -1));
        if (thumbnail && fileUri != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setPadding(dp(1), dp(1), dp(1), dp(1));
            image.setBackground(round(0xFF0A1820, accent, 10));
            image.setClipToOutline(true);
            icon.addView(image, new FrameLayout.LayoutParams(-1, -1));
            loadThumbnail(image, fileUri, mime);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(9);
        item.addView(icon, iconLp);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        String titleText = entry.kind.equals("TEXT")
                ? TransferHistory.preview(entry.label, compact ? 48 : 96)
                : entry.files.size() == 1 ? displayName(fileUri) : entry.label;
        if (titleText == null || titleText.isEmpty()) titleText = entry.label;
        TextView title = label(titleText, compact ? 12 : 14, WHITE, true);
        title.setMaxLines(compact ? 1 : 2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = label(transferDetails(entry), compact ? 9 : 10, accent, false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton share = new ImageButton(this);
        share.setImageResource(android.R.drawable.ic_menu_share);
        share.setImageTintList(android.content.res.ColorStateList.valueOf(CYAN));
        share.setBackgroundColor(Color.TRANSPARENT);
        share.setContentDescription("Share transfer");
        share.setPadding(dp(7), dp(7), dp(7), dp(7));
        share.setMinimumWidth(0); share.setMinimumHeight(0);
        share.setOnClickListener(v -> shareEntry(entry));
        item.addView(share, new LinearLayout.LayoutParams(dp(compact ? 36 : 42), dp(compact ? 40 : 46)));
        if ("FILE".equals(entry.kind)) {
            ImageButton delete = new ImageButton(this);
            delete.setImageResource(android.R.drawable.ic_menu_delete);
            delete.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFF7777));
            delete.setBackgroundColor(Color.TRANSPARENT);
            delete.setContentDescription("Delete files");
            delete.setPadding(dp(7), dp(7), dp(7), dp(7));
            delete.setMinimumWidth(0); delete.setMinimumHeight(0);
            delete.setOnClickListener(v -> confirmDeleteFiles(entry));
            item.addView(delete, new LinearLayout.LayoutParams(dp(compact ? 36 : 42), dp(compact ? 40 : 46)));
        }
        item.setOnClickListener(v -> {
            if ("TEXT".equals(entry.kind)) copyHistoryText(entry.label);
            else openEntry(entry);
        });
        return item;
    }

    private void confirmDeleteFiles(TransferHistory.Entry entry) {
        int count = entry.files.size();
        String noun = count == 1 ? "file" : "files";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete " + noun + "?")
                .setMessage("Delete " + count + " " + noun + " from this device?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> deleteFiles(entry))
                .show();
    }

    private void deleteFiles(TransferHistory.Entry entry) {
        new Thread(() -> {
            int deleted = 0;
            for (String file : entry.files) {
                try {
                    Uri uri = Uri.parse(file);
                    if (getContentResolver().delete(uri, null, null) > 0) deleted++;
                } catch (Exception ignored) { }
            }
            TransferHistory.read(this);
            int deletedCount = deleted;
            mainHandler.post(() -> {
                updateHome();
                if (currentPage == 1) refreshHistory();
                String message = deletedCount == entry.files.size()
                        ? "Files deleted and removed from history."
                        : deletedCount > 0
                        ? "Deleted " + deletedCount + " of " + entry.files.size() + " files."
                        : "Could not delete the file(s).";
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "DittoLink-delete-files").start();
    }

    private Bitmap themedTypeIcon(TransferHistory.Entry entry, String mime) {
        if (historyIconsSource == null)
            historyIconsSource = BitmapFactory.decodeResource(getResources(), R.drawable.history_timeline);
        if (historyIconsSource == null) return null;
        if (entry.kind.equals("TEXT")) {
            if (themedTextIcon == null) themedTextIcon = Bitmap.createBitmap(historyIconsSource, 137, 134, 96, 96);
            return themedTextIcon;
        }
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            if (themedImageIcon == null) themedImageIcon = Bitmap.createBitmap(historyIconsSource, 137, 427, 96, 96);
            return themedImageIcon;
        }
        if (themedFileIcon == null) themedFileIcon = Bitmap.createBitmap(historyIconsSource, 137, 277, 96, 96);
        return themedFileIcon;
    }

    private String transferDetails(TransferHistory.Entry entry) {
        String direction = "SENT".equals(entry.direction) ? "TO PC" : "FROM PC";
        StringBuilder details = new StringBuilder(direction);
        if (entry.kind.equals("FILE") && !entry.files.isEmpty()) {
            long totalSize = 0;
            boolean hasSize = false;
            java.util.LinkedHashSet<String> extensions = new java.util.LinkedHashSet<>();
            for (String file : entry.files) {
                Uri uri = Uri.parse(file);
                long size = fileSize(uri);
                if (size >= 0) { totalSize += size; hasSize = true; }
                String name = displayName(uri);
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1)
                    extensions.add(name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT));
            }
            if (hasSize) details.append("  ·  ").append(formatSize(totalSize));
            if (!extensions.isEmpty()) {
                details.append("  ·  ");
                int shown = 0;
                for (String extension : extensions) {
                    if (shown == 3) { details.append("+").append(extensions.size() - shown); break; }
                    if (shown > 0) details.append(", ");
                    details.append(extension);
                    shown++;
                }
            }
        }
        details.append("  ·  ").append(shortTime(entry.time));
        return details.toString();
    }

    private long fileSize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) { }
        try (android.content.res.AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? -1 : descriptor.getLength();
        } catch (Exception ignored) { return -1; }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        String[] units = { "K", "M", "G", "T" };
        double value = bytes;
        int unit = -1;
        do { value /= 1024.0; unit++; } while (value >= 1024 && unit < units.length - 1);
        String number = String.format(java.util.Locale.ROOT, value < 10 ? "%.1f" : "%.0f", value);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + units[unit];
    }

    private View statCard(String title, String value, int accent) {
        LinearLayout card = panel(PANEL, LINE, 14);
        card.setPadding(dp(13), dp(10), dp(13), dp(8));
        TextView label = label(title, 9, MUTED, true);
        label.setLetterSpacing(0.11f);
        card.addView(label);
        TextView number = label(value, 25, accent, true);
        number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(number);
        return card;
    }

    private View historyRow(TransferHistory.Entry entry) {
        LinearLayout card = panel(PANEL, LINE, 14);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout row = row();
        TextView direction = label(entry.direction.equals("SENT") ? "↑" : "↓", 23,
                entry.direction.equals("SENT") ? CYAN : GREEN, true);
        direction.setGravity(Gravity.CENTER);
        direction.setBackground(round(PANEL_ALT, LINE, 12));
        row.addView(direction, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        String titleText = entry.kind.equals("TEXT") ? TransferHistory.preview(entry.label, 96) : entry.label;
        TextView name = label(titleText, 14, WHITE, true);
        name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.setOnClickListener(v -> { if ("TEXT".equals(entry.kind)) copyHistoryText(entry.label); else openEntry(entry); });
        details.addView(name);
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(entry.time));
        TextView meta = label(entry.direction + "  ·  " + entry.kind + "  ·  " + when, 10, MUTED, false);
        details.addView(meta);
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private void showSettings() { showPcManager(); }

    private void showTailscaleWelcome() {
        Dialog dialog = new Dialog(this);
        LinearLayout box = panel(PANEL, CYAN, 18);
        box.setBackground(settingsDialogArt == null
                ? new TechPanelDrawable(getResources().getDisplayMetrics().density, CYAN, true)
                : bitmapSurface(settingsDialogArt));
        box.setPadding(dp(25), dp(43), dp(25), dp(25));

        TextView eyebrow = label("FIRST-TIME SETUP", 12, AMBER, true);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setGravity(Gravity.CENTER);
        box.addView(eyebrow);
        View rule = new View(this);
        rule.setBackgroundColor(0xAA40DFEF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, dp(1));
        ruleLp.topMargin = dp(9); ruleLp.bottomMargin = dp(12); box.addView(rule, ruleLp);

        TextView title = label("BEST EXPERIENCE", 19, WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.04f);
        box.addView(title);
        TextView explanation = label("Install Tailscale on your phone and PC for a private connection from anywhere, without router setup.", 12, WHITE, false);
        explanation.setGravity(Gravity.CENTER);
        explanation.setLineSpacing(dp(3), 1.0f);
        explanation.setPadding(dp(5), dp(8), dp(5), dp(1));
        box.addView(explanation);

        TailscaleIllustration illustration = new TailscaleIllustration(this);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(-1, dp(112));
        artLp.topMargin = dp(3); artLp.bottomMargin = dp(6); box.addView(illustration, artLp);

        Button install = themedButton("SET UP TAILSCALE", CYAN, true);
        LinearLayout.LayoutParams installLp = matchHeight(dp(46)); box.addView(install, installLp);
        install.setOnClickListener(v -> handleTailscaleSetup(dialog));
        Button continueButton = themedButton("CONTINUE WITHOUT IT", 0xFF9FB4BC, false);
        LinearLayout.LayoutParams continueLp = matchHeight(dp(44)); continueLp.topMargin = dp(9); box.addView(continueButton, continueLp);
        continueButton.setOnClickListener(v -> dialog.dismiss());

        showTechDialog(dialog, box);
    }

    private void openTailscalePlayStore(Dialog dismissDialog) {
        if (dismissDialog != null) dismissDialog.dismiss();
        Uri marketUri = Uri.parse("market://details?id=com.tailscale.ipn");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, marketUri));
        } catch (android.content.ActivityNotFoundException noPlayStore) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn")));
            } catch (android.content.ActivityNotFoundException noBrowser) {
                setStatus("Could not open the Tailscale download page.");
            }
        }
    }

    private void handleTailscaleSetup(Dialog dismissDialog) {
        if (dismissDialog != null) dismissDialog.dismiss();
        if (!isTailscaleInstalled()) {
            openTailscalePlayStore(null);
            return;
        }

        String phoneIp = tailscaleAddress();
        if (phoneIp.isEmpty()) {
            showTailscaleNotConnected();
            return;
        }
        showTailscaleAddress(phoneIp);
    }

    private boolean isTailscaleInstalled() {
        try {
            getPackageManager().getPackageInfo("com.tailscale.ipn", 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException notInstalled) {
            return false;
        }
    }

    private void showTailscaleNotConnected() {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel("TAILSCALE IS OFF");
        TextView message = label("Tailscale is installed, but this phone has no active Tailscale connection. Start Tailscale and turn it on, then tap SET UP TAILSCALE again to find this phone's IP.", 11, MUTED, false);
        message.setLineSpacing(dp(3), 1.0f);
        message.setPadding(0, dp(4), 0, dp(13));
        box.addView(message);
        LinearLayout buttons = row();
        Button open = themedButton("OPEN TAILSCALE", CYAN, true);
        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        buttons.addView(open, new LinearLayout.LayoutParams(0, dp(43), 1));
        View gap = new View(this); buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(43), 1));
        box.addView(buttons);
        open.setOnClickListener(v -> {
            dialog.dismiss();
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (launch == null) setStatus("Open Tailscale and turn on the connection, then tap SET UP TAILSCALE again.");
            else {
                try {
                    discoverTailscaleOnReturn = true;
                    startActivity(launch);
                }
                catch (android.content.ActivityNotFoundException unavailable) {
                    discoverTailscaleOnReturn = false;
                    setStatus("Open Tailscale and turn on the connection, then tap SET UP TAILSCALE again.");
                }
            }
        });
        close.setOnClickListener(v -> dialog.dismiss());
        showTechDialog(dialog, box);
    }

    private void checkTailscaleAfterReturn(int attempt) {
        mainHandler.postDelayed(() -> {
            if (!discoverTailscaleOnReturn) {
                tailscaleReturnCheckScheduled = false;
                return;
            }
            String phoneIp = tailscaleAddress();
            if (!phoneIp.isEmpty()) {
                discoverTailscaleOnReturn = false;
                tailscaleReturnCheckScheduled = false;
                showTailscaleAddress(phoneIp);
            } else if (attempt < 20) {
                checkTailscaleAfterReturn(attempt + 1);
            } else {
                discoverTailscaleOnReturn = false;
                tailscaleReturnCheckScheduled = false;
                setStatus("Tailscale is still not connected. Turn it on, then tap SET UP TAILSCALE to check again.");
            }
        }, 500);
    }

    private void showTailscaleAddress(String phoneIp) {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel("TAILSCALE CONNECTED");
        TextView explanation = label("PHONE TAILSCALE IP", 9, CYAN, true);
        explanation.setLetterSpacing(0.1f);
        box.addView(explanation);
        TextView address = label(phoneIp, 17, WHITE, true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        address.setPadding(0, dp(6), 0, dp(8));
        box.addView(address);
        TextView note = label("Use this phone IP in Ditto on your PC under Friends so the PC can send clips back to this phone.", 10, MUTED, false);
        note.setLineSpacing(dp(3), 1.0f);
        note.setPadding(0, 0, 0, dp(12));
        box.addView(note);
        LinearLayout buttons = row();
        Button copy = themedButton("COPY IP", CYAN, true);
        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        buttons.addView(copy, new LinearLayout.LayoutParams(0, dp(43), 1));
        View gap = new View(this); buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(43), 1));
        box.addView(buttons);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Phone Tailscale IP", phoneIp));
            dialog.dismiss();
            setStatus("Phone Tailscale IP copied: " + phoneIp);
        });
        close.setOnClickListener(v -> dialog.dismiss());
        showTechDialog(dialog, box);
    }

    private void showTermsAndConditions() {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel("TERMS & CONDITIONS");
        TextView intro = label("DITTO LINK  ·  VERSION " + appVersion(), 10, AMBER, true);
        intro.setPadding(0, dp(2), 0, dp(7)); box.addView(intro);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        addTermsSection(content, "1. THE APP", "Ditto Link is an independent companion app for transferring clipboard text and files between your Android device and PCs running Ditto Clipboard Manager. It is not an official Ditto or Tailscale product.");
        addTermsSection(content, "2. YOUR DEVICE AND TRANSFERS", "Connection details, including saved PC addresses and the Ditto password, are stored on this device. Local transfer history can include copied text (up to 4,000 characters per text entry), file names or file references, transfer direction, and time. Received files are saved to Internal storage/Download/Ditto until you delete them. The app does not provide a Ditto Link cloud history service. Content you send is transmitted to the PC you select; Tailscale, when used, is a separate network service.");
        addTermsSection(content, "3. BACKGROUND RECEIVING", "If you enable Background Receive, the app keeps its listener running so configured, authorized PCs can send clips to your phone. Received text may be placed on your phone's clipboard, and received files are saved on the device. You can turn Background Receive off in the app at any time.");
        addTermsSection(content, "4. YOUR RESPONSIBILITIES", "Only connect to PCs and networks you trust. Keep your Ditto password private, check the destination before sending, and only transfer content you have permission to use. You are responsible for your device, network, firewall, and any router settings you choose to change.");
        addTermsSection(content, "5. THIRD-PARTY SERVICES", "Ditto Clipboard Manager, Tailscale, Google Play, and Ko-fi are provided by their respective owners and may have separate terms and privacy practices. Ditto Link does not control those services.");
        addTermsSection(content, "6. DONATIONS", "Donations are optional and are handled by Ko-fi under its terms. A donation is support for the project and does not purchase additional app features or a service subscription.");
        addTermsSection(content, "7. AVAILABILITY AND LIABILITY", "Ditto Link is provided as available. Device, Android, firewall, or network differences can interrupt or prevent transfers; keep separate copies of important files. To the extent permitted by applicable law, the developer is not responsible for indirect or consequential loss. These terms do not limit rights that cannot legally be limited.");
        addTermsSection(content, "8. CONTACT", "Questions or support: VibeCodeDrop@pm.me");
        scroll.addView(content);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - dp(300);
        int termsHeight = Math.max(dp(250), Math.min(dp(430), availableHeight));
        box.addView(scroll, new LinearLayout.LayoutParams(-1, termsHeight));

        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        LinearLayout.LayoutParams closeLp = matchHeight(dp(42)); closeLp.topMargin = dp(9); box.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());
        showTechDialog(dialog, box);
    }

    private void addTermsSection(LinearLayout parent, String heading, String body) {
        TextView title = label(heading, 10, CYAN, true);
        title.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(8); parent.addView(title, titleLp);
        TextView paragraph = label(body, 10, WHITE, false);
        paragraph.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.topMargin = dp(3); bodyLp.bottomMargin = dp(3); parent.addView(paragraph, bodyLp);
    }

    private void openExternalLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException unavailable) { setStatus("No browser is available to open that link."); }
    }

    private void showPcManager() {
        if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
        List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
        String activeId = getSharedPreferences("peer", MODE_PRIVATE).getString("activePcId", "");
        Map<String, TextView> statusLabels = new java.util.LinkedHashMap<>();
        Map<String, Boolean> onlineStates = new java.util.HashMap<>();
        Map<String, LinearLayout> friendCards = new java.util.HashMap<>();

        Dialog dialog = new Dialog(this);
        LinearLayout box = panel(PANEL, CYAN, 18);
        box.setBackground(settingsDialogArt == null ? new TechPanelDrawable(getResources().getDisplayMetrics().density, CYAN, true) : bitmapSurface(settingsDialogArt));
        box.setPadding(dp(25), dp(43), dp(25), dp(25));
        LinearLayout heading = row();
        TextView gear = label("⚙", 22, CYAN, true); gear.setGravity(Gravity.CENTER);
        heading.addView(gear, new LinearLayout.LayoutParams(dp(34), dp(36)));
        TextView title = label("SETTINGS", 18, WHITE, true); title.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1); titleLp.leftMargin = dp(8);
        heading.addView(title, titleLp); box.addView(heading);
        View rule = new View(this); rule.setBackgroundColor(0xAA40DFEF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, dp(1)); ruleLp.topMargin = dp(9); ruleLp.bottomMargin = dp(12); box.addView(rule, ruleLp);
        TextView friendsTitle = label("DITTO FRIENDS", 12, AMBER, true); friendsTitle.setLetterSpacing(0.1f); box.addView(friendsTitle);

        ScrollView friendScroll = new ScrollView(this); friendScroll.setFillViewport(false);
        LinearLayout friendList = new LinearLayout(this); friendList.setOrientation(LinearLayout.VERTICAL);
        if (profiles.isEmpty()) {
            TextView empty = label("No friends added yet.", 12, MUTED, false);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20)); friendList.addView(empty);
        }
        for (PcProfileStore.Profile profile : profiles) {
            boolean selected = profile.id.equals(activeId);
            LinearLayout card = row();
            card.setPadding(dp(10), dp(9), dp(8), dp(9));
            card.setBackground(settingsInputArt == null ? new TechPanelDrawable(getResources().getDisplayMetrics().density, selected ? CYAN : 0xFF41616B, false) : bitmapSurface(settingsInputArt));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2); cardLp.bottomMargin = dp(8);
            TextView pcIcon = label("▣", 22, selected ? CYAN : GREEN, true); pcIcon.setGravity(Gravity.CENTER);
            card.addView(pcIcon, new LinearLayout.LayoutParams(dp(38), dp(46)));
            LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(9), 0, dp(3), 0); details.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(profile.name, 13, WHITE, true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(name);
            TextView state = label((selected ? "CHECKING  ·  DEFAULT FOR SENDS" : "CHECKING"), 9, selected ? CYAN : MUTED, true);
            state.setPadding(0, dp(2), 0, dp(2)); details.addView(state);
            statusLabels.put(profile.id, state); onlineStates.put(profile.id, false); friendCards.put(profile.id, card);
            TextView route = label(profileAddressSummary(profile), 9, MUTED, false); route.setMaxLines(2); route.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(route);
            card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton edit = new ImageButton(this);
            edit.setImageResource(android.R.drawable.ic_menu_edit);
            edit.setImageTintList(android.content.res.ColorStateList.valueOf(CYAN));
            edit.setBackground(new TechPanelDrawable(getResources().getDisplayMetrics().density, CYAN, false));
            edit.setContentDescription("Edit " + profile.name); edit.setPadding(dp(9), dp(9), dp(9), dp(9));
            card.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));
            edit.setOnClickListener(v -> { dialog.dismiss(); editProfile(profile); });
            card.setOnClickListener(v -> {
                PcProfileStore.select(this, profile.id);
                activeIdForCards(profiles, profile.id, statusLabels, onlineStates, friendCards);
                updateHome(); setStatus("Default send destination: " + profile.name);
            });
            card.setOnLongClickListener(v -> { dialog.dismiss(); showRemoveFriendDialog(profile); return true; });
            friendList.addView(card, cardLp);
        }
        friendScroll.addView(friendList);
        int listHeight = profiles.isEmpty() ? dp(64) : Math.min(dp(330), profiles.size() * dp(82));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, listHeight);
        scrollLp.topMargin = dp(10); box.addView(friendScroll, scrollLp);

        Button add = themedButton("＋   ADD FRIEND", CYAN, true);
        LinearLayout.LayoutParams addLp = matchHeight(dp(46)); addLp.topMargin = dp(4); box.addView(add, addLp);
        add.setOnClickListener(v -> { dialog.dismiss(); editProfile(PcProfileStore.Profile.create()); });
        Button setupTailscale = themedButton("SET UP TAILSCALE", CYAN, false);
        LinearLayout.LayoutParams setupLp = matchHeight(dp(42)); setupLp.topMargin = dp(8); box.addView(setupTailscale, setupLp);
        setupTailscale.setOnClickListener(v -> handleTailscaleSetup(null));
        LinearLayout infoActions = row();
        Button terms = themedButton("TERMS & CONDITIONS", 0xFF9FB4BC, false);
        Button donate = themedButton("DONATE", AMBER, true);
        terms.setTextSize(10); donate.setTextSize(11);
        infoActions.addView(terms, new LinearLayout.LayoutParams(0, dp(42), 1));
        infoActions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        infoActions.addView(donate, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams infoActionsLp = new LinearLayout.LayoutParams(-1, dp(42));
        infoActionsLp.topMargin = dp(10); box.addView(infoActions, infoActionsLp);
        terms.setOnClickListener(v -> showTermsAndConditions());
        donate.setOnClickListener(v -> openExternalLink("https://ko-fi.com/vibedevdrop"));
        space(box, 10);
        TextView version = label("VERSION  " + appVersion(), 10, MUTED, true); box.addView(version);
        TextView support = label("SUPPORT  ·  VibeCodeDrop@pm.me", 11, CYAN, true);
        support.setPadding(0, dp(6), 0, dp(1));
        support.setOnClickListener(v -> {
            Intent email = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:VibeCodeDrop@pm.me"));
            email.putExtra(Intent.EXTRA_SUBJECT, "Ditto Link Support");
            try { startActivity(email); } catch (android.content.ActivityNotFoundException unavailable) { setStatus("No email app is available."); }
        });
        box.addView(support);
        TextView privacy = label("Privacy Policy", 11, CYAN, false);
        privacy.setPaintFlags(privacy.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        privacy.setPadding(0, dp(2), 0, 0);
        privacy.setOnClickListener(v -> openExternalLink("https://github.com/Qiuxzomni/Ditto_Link/blob/main/PRIVACY_POLICY.md"));
        box.addView(privacy);
        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        LinearLayout.LayoutParams closeLp = matchHeight(dp(42)); closeLp.topMargin = dp(10); box.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(box); dialog.setCancelable(true); settingsDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(30), -2);
        }
        refreshFriendPresence(profiles, statusLabels, onlineStates, friendCards);
    }

    private void activeIdForCards(List<PcProfileStore.Profile> profiles, String activeId,
                                  Map<String, TextView> labels, Map<String, Boolean> online,
                                  Map<String, LinearLayout> cards) {
        for (PcProfileStore.Profile profile : profiles) {
            boolean selected = profile.id.equals(activeId);
            boolean isOnline = Boolean.TRUE.equals(online.get(profile.id));
            TextView status = labels.get(profile.id);
            if (status != null) {
                status.setText((isOnline ? "PORT OPEN" : "OFFLINE") + (selected ? "  ·  DEFAULT" : ""));
                status.setTextColor(isOnline ? GREEN : MUTED);
            }
            LinearLayout card = cards.get(profile.id);
            if (card != null) card.setBackground(settingsInputArt == null
                    ? new TechPanelDrawable(getResources().getDisplayMetrics().density, selected ? CYAN : 0xFF41616B, false)
                    : bitmapSurface(settingsInputArt));
        }
    }

    private void refreshFriendPresence(List<PcProfileStore.Profile> profiles, Map<String, TextView> labels,
                                       Map<String, Boolean> online, Map<String, LinearLayout> cards) {
        new Thread(() -> {
            Map<String, Boolean> found = new java.util.HashMap<>();
            for (PcProfileStore.Profile profile : profiles) found.put(profile.id, LanDiscovery.reachableRoute(profile) != null);
            mainHandler.post(() -> {
                online.putAll(found);
                String active = getSharedPreferences("peer", MODE_PRIVATE).getString("activePcId", "");
                activeIdForCards(profiles, active, labels, online, cards);
            });
        }, "DittoLink-friend-status").start();
    }

    private String profileAddressSummary(PcProfileStore.Profile profile) {
        StringBuilder value = new StringBuilder();
        if (!profile.lanHost.trim().isEmpty()) value.append("LAN  ").append(profile.lanHost.trim());
        if (!profile.tailnetHost.trim().isEmpty()) {
            if (value.length() > 0) value.append("   ·   ");
            value.append("TAILSCALE  ").append(profile.tailnetHost.trim());
        }
        return value.toString();
    }

    private void showRemoveFriendDialog(PcProfileStore.Profile profile) {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel("REMOVE FRIEND");
        TextView message = label("Remove " + profile.name + " from Ditto Friends?", 12, MUTED, false);
        message.setPadding(0, dp(10), 0, dp(14)); box.addView(message);
        LinearLayout buttons = row();
        Button cancel = themedButton("CLOSE", 0xFF9FB4BC, false);
        Button remove = themedButton("REMOVE", 0xFFFF8B83, false);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1));
        View gap = new View(this); buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(remove, new LinearLayout.LayoutParams(0, dp(44), 1)); box.addView(buttons);
        cancel.setOnClickListener(v -> { dialog.dismiss(); showPcManager(); });
        remove.setOnClickListener(v -> {
            List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
            profiles.removeIf(p -> p.id.equals(profile.id));
            String active = getSharedPreferences("peer", MODE_PRIVATE).getString("activePcId", "");
            if (profile.id.equals(active)) active = profiles.isEmpty() ? "" : profiles.get(0).id;
            PcProfileStore.save(this, profiles, active);
            refreshListenerIfEnabled(); updateHome(); dialog.dismiss(); showPcManager();
        });
        showTechDialog(dialog, box);
    }

    private void editProfile(PcProfileStore.Profile original) {
        boolean adding = PcProfileStore.getAll(this).stream().noneMatch(p -> p.id.equals(original.id));
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel(adding ? "ADD FRIEND" : "EDIT FRIEND  ·  " + original.name);
        EditText name = addProfileField(box, "NAME", "Friend name", original.name, false);
        EditText tail = addProfileField(box, "TAILSCALE IP", "100.x.x.x or hostname", original.tailnetHost, false);
        EditText local = addProfileField(box, "LOCAL IP", "192.168.x.x", original.lanHost, false);
        EditText password = addProfileField(box, "DITTO PASSWORD", "Ditto shared password", original.password, true);
        android.graphics.drawable.Drawable passwordBackground = password.getBackground();
        Runnable showPasswordError = () -> {
            GradientDrawable errorBackground = new GradientDrawable();
            errorBackground.setColor(0xFF0A171D);
            errorBackground.setCornerRadius(dp(10));
            errorBackground.setStroke(dp(2), 0xFFFF5964);
            password.setBackground(errorBackground);
            password.requestFocus();
            setStatus("Enter the Ditto password before saving or closing this friend.");
        };
        Runnable updateDismissalRule = () -> {
            boolean hasRoute = !tail.getText().toString().trim().isEmpty()
                    || !local.getText().toString().trim().isEmpty();
            boolean passwordMissing = password.getText().toString().isEmpty();
            boolean mayDismiss = !hasRoute || !passwordMissing;
            dialog.setCancelable(mayDismiss);
            dialog.setCanceledOnTouchOutside(mayDismiss);
        };
        TextWatcher dismissalWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateDismissalRule.run(); }
        };
        tail.addTextChangedListener(dismissalWatcher);
        local.addTextChangedListener(dismissalWatcher);
        password.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateDismissalRule.run();
                if (s.length() > 0) password.setBackground(passwordBackground);
            }
        });
        updateDismissalRule.run();

        LinearLayout controls = row();
        Button ok = themedButton("OK", CYAN, true);
        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        controls.addView(ok, new LinearLayout.LayoutParams(0, dp(44), 1));
        View gap = new View(this); controls.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        controls.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(-1, -2); controlsLp.topMargin = dp(10); box.addView(controls, controlsLp);
        Button scan = themedButton("SCAN LAN", CYAN, true);
        scan.setTextSize(11);
        android.graphics.drawable.Drawable scanGlyph = getDrawable(android.R.drawable.ic_menu_search);
        if (scanGlyph != null) {
            scanGlyph = scanGlyph.mutate();
            scanGlyph.setTint(CYAN);
            scanGlyph.setBounds(0, 0, dp(13), dp(13));
            scan.setCompoundDrawablesRelative(scanGlyph, null, null, null);
            scan.setCompoundDrawablePadding(dp(4));
        }
        LinearLayout.LayoutParams scanLp = matchHeight(dp(44)); scanLp.topMargin = dp(9); box.addView(scan, scanLp);
        scan.setOnClickListener(v -> scanLanInto(local));
        close.setOnClickListener(v -> {
            boolean hasRoute = !tail.getText().toString().trim().isEmpty()
                    || !local.getText().toString().trim().isEmpty();
            if (hasRoute && password.getText().toString().isEmpty()) {
                showPasswordError.run();
                return;
            }
            dialog.dismiss(); showPcManager();
        });
        ok.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String l = local.getText().toString().trim();
            String t = tail.getText().toString().trim();
            String pw = password.getText().toString();
            if (pw.isEmpty()) { showPasswordError.run(); return; }
            if (n.isEmpty()) { showScanNotice("NAME REQUIRED", "Enter a name for this friend."); return; }
            if (l.isEmpty() && t.isEmpty()) {
                showScanNotice("ADDRESS REQUIRED", "Enter a Tailscale IP, a local IP, or both."); return;
            }
            original.name = n; original.lanHost = l; original.tailnetHost = t; original.password = pw;
            List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
            int found = -1; for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(original.id)) found = i;
            if (found >= 0) profiles.set(found, original); else profiles.add(original);
            String active = getSharedPreferences("peer", MODE_PRIVATE).getString("activePcId", "");
            if (active.isEmpty() || adding) active = original.id;
            PcProfileStore.save(this, profiles, active);
            refreshListenerIfEnabled(); updateHome(); setStatus("Friend saved."); dialog.dismiss(); showPcManager();
        });
        showTechDialog(dialog, box);
        updateDismissalRule.run();
    }

    private LinearLayout themedDialogPanel(String title) {
        LinearLayout box = panel(PANEL, CYAN, 18);
        box.setBackground(settingsDialogArt == null ? new TechPanelDrawable(getResources().getDisplayMetrics().density, CYAN, true) : bitmapSurface(settingsDialogArt));
        box.setPadding(dp(25), dp(43), dp(25), dp(25));
        TextView heading = label(title, 15, WHITE, true); heading.setLetterSpacing(0.06f);
        box.addView(heading);
        View rule = new View(this); rule.setBackgroundColor(0xAA40DFEF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, dp(1)); ruleLp.topMargin = dp(9); ruleLp.bottomMargin = dp(11); box.addView(rule, ruleLp);
        return box;
    }

    private EditText addProfileField(LinearLayout parent, String title, String hint, String value, boolean secret) {
        TextView label = label(title, 9, CYAN, true); label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2); labelLp.topMargin = dp(5); parent.addView(label, labelLp);
        EditText field = editField(hint, value);
        field.setBackground(settingsInputArt == null ? new TechPanelDrawable(getResources().getDisplayMetrics().density, 0xFF3AAAB8, false) : bitmapSurface(settingsInputArt));
        field.setPadding(dp(19), dp(4), dp(19), dp(4));
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams fieldLp = matchHeight(dp(43)); fieldLp.topMargin = dp(4); parent.addView(field, fieldLp);
        return field;
    }

    private Button themedButton(String text, int accent, boolean primary) {
        Button button = actionButton(text, primary ? 0xFF0A2026 : 0xFF131E24, primary ? WHITE : 0xFFB8C7CC, accent);
        button.setBackground(primary && settingsButtonArt != null ? bitmapSurface(settingsButtonArt)
                : new TechPanelDrawable(getResources().getDisplayMetrics().density, accent, false));
        button.setTextColor(primary ? WHITE : 0xFFD2DDE1);
        return button;
    }

    private BitmapDrawable bitmapSurface(Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setGravity(Gravity.FILL);
        drawable.setFilterBitmap(true);
        return drawable;
    }

    private Bitmap cropTransparent(Bitmap source) {
        if (source == null) return null;
        int left = source.getWidth(), top = source.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < source.getHeight(); y += 2) {
            for (int x = 0; x < source.getWidth(); x += 2) {
                if (Color.alpha(source.getPixel(x, y)) > 10) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return source;
        int margin = 3;
        left = Math.max(0, left - margin); top = Math.max(0, top - margin);
        right = Math.min(source.getWidth() - 1, right + margin); bottom = Math.min(source.getHeight() - 1, bottom + margin);
        Bitmap cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
        if (cropped != source) source.recycle();
        return cropped;
    }

    private final class TailscaleIllustration extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TailscaleIllustration(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 320f, getHeight() / 112f);
            canvas.save();
            canvas.translate((getWidth() - 320f * scale) / 2f, (getHeight() - 112f * scale) / 2f);
            canvas.scale(scale, scale);

            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f); paint.setColor(CYAN);
            paint.setPathEffect(new DashPathEffect(new float[] {2f, 7f}, 0));
            canvas.drawLine(78, 57, 135, 57, paint); canvas.drawLine(185, 57, 242, 57, paint);
            paint.setPathEffect(null);
            paint.setStrokeWidth(1.5f); paint.setColor(AMBER);
            canvas.drawArc(new RectF(127, 13, 193, 101), 205, 130, false, paint);
            paint.setColor(CYAN); canvas.drawArc(new RectF(132, 22, 188, 92), 25, 130, false, paint);

            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF091A20);
            paint.setShadowLayer(9, 0, 0, 0xAA20DFFF);
            canvas.drawRoundRect(new RectF(19, 20, 77, 96), 11, 11, paint);
            canvas.drawRoundRect(new RectF(241, 27, 309, 72), 6, 6, paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.3f); paint.setColor(CYAN);
            canvas.drawRoundRect(new RectF(19, 20, 77, 96), 11, 11, paint);
            canvas.drawRoundRect(new RectF(241, 27, 309, 72), 6, 6, paint);
            canvas.drawLine(259, 73, 259, 84, paint); canvas.drawLine(291, 73, 291, 84, paint);
            canvas.drawRoundRect(new RectF(252, 83, 298, 87), 2, 2, paint);
            canvas.drawLine(38, 27, 58, 27, paint); canvas.drawLine(38, 90, 58, 90, paint);

            paint.setStyle(Paint.Style.FILL); paint.setColor(CYAN);
            canvas.drawCircle(48, 48, 7, paint);
            canvas.drawRoundRect(new RectF(40, 53, 56, 67), 5, 5, paint);
            canvas.drawRoundRect(new RectF(35, 55, 39, 66), 2, 2, paint);
            canvas.drawRoundRect(new RectF(57, 55, 61, 66), 2, 2, paint);
            canvas.drawLine(44, 67, 42, 72, paint); canvas.drawLine(52, 67, 54, 72, paint);
            paint.setColor(0xFF4FE8F5);
            canvas.drawRect(250, 35, 264, 47, paint); canvas.drawRect(267, 35, 281, 47, paint);
            canvas.drawRect(250, 50, 264, 63, paint); canvas.drawRect(267, 50, 281, 63, paint);

            Path shield = new Path(); shield.moveTo(160, 29); shield.lineTo(183, 39);
            shield.lineTo(179, 68); shield.lineTo(160, 84); shield.lineTo(141, 68);
            shield.lineTo(137, 39); shield.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xDD0A2830); paint.setShadowLayer(10, 0, 0, 0xCC20DFFF);
            canvas.drawPath(shield, paint); paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f); paint.setColor(CYAN);
            canvas.drawPath(shield, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f); paint.setColor(WHITE);
            canvas.drawRoundRect(new RectF(153, 49, 167, 63), 2, 2, paint);
            canvas.drawArc(new RectF(155, 43, 165, 55), 180, 180, false, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(WHITE); canvas.drawCircle(160, 55, 1.5f, paint);
            canvas.restore();
        }
    }

    private void showTechDialog(Dialog dialog, View content) {
        dialog.setContentView(content); dialog.setCancelable(true); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(34), -2);
        }
    }

    private void scanLanInto(EditText localField) {
        Dialog progress = new Dialog(this);
        LinearLayout box = themedDialogPanel("SCANNING...");
        TextView message = label("Checking this local network for Ditto PCs.", 11, MUTED, false);
        message.setPadding(0, 0, 0, dp(12)); box.addView(message);
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminate(true);
        box.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        showTechDialog(progress, box); progress.setCancelable(false);
        new Thread(() -> {
            List<LanDiscovery.Device> devices = null; String error = null;
            try { devices = LanDiscovery.scan(); } catch (Exception e) { error = e.getMessage(); }
            List<LanDiscovery.Device> results = devices; String problem = error;
            mainHandler.post(() -> {
                progress.dismiss();
                if (problem != null) { showScanNotice("SCAN UNAVAILABLE", problem); return; }
                if (results.isEmpty()) { showScanNotice("NO DITTO PC FOUND", "No Ditto listener answered on this local network."); return; }
                if (results.size() == 1) {
                    fillLocalRoute(localField, results.get(0)); return;
                }
                showLanChoices(results, localField);
            });
        }, "DittoLink-edit-lan-scan").start();
    }

    private void showLanChoices(List<LanDiscovery.Device> devices, EditText localField) {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel("DITTO PCs FOUND");
        TextView text = label("Choose the PC whose local route belongs in this friend.", 11, MUTED, false);
        text.setPadding(0, 0, 0, dp(8)); box.addView(text);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (LanDiscovery.Device device : devices) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(11), dp(8), dp(11), dp(8));
            card.setBackground(settingsInputArt == null ? new TechPanelDrawable(getResources().getDisplayMetrics().density, CYAN, false) : bitmapSurface(settingsInputArt));
            card.addView(label(device.name, 12, WHITE, true));
            card.addView(label(device.type + "  ·  " + device.ip, 10, CYAN, false));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2); cardLp.bottomMargin = dp(7); rows.addView(card, cardLp);
            card.setOnClickListener(v -> { dialog.dismiss(); fillLocalRoute(localField, device); });
        }
        scroll.addView(rows); box.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(330), devices.size() * dp(68))));
        Button close = themedButton("CLOSE", 0xFF9FB4BC, false);
        LinearLayout.LayoutParams closeLp = matchHeight(dp(42)); closeLp.topMargin = dp(8); box.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());
        showTechDialog(dialog, box);
    }

    private void fillLocalRoute(EditText localField, LanDiscovery.Device device) {
        localField.setText(device.ip); localField.setSelection(localField.length());
        showScanNotice("LAN ROUTE READY", device.name + "\n" + device.type + "\n" + device.ip +
                "\n\nLocal IP filled. Ditto Link will try this route first, then Tailscale."
                + "\n\nFor this PC to accept clips, add this phone's LAN IP (" + localNetworkAddress()
                + ") to Ditto Options → Friends on the PC.");
    }

    private void showScanNotice(String title, String message) {
        Dialog dialog = new Dialog(this);
        LinearLayout box = themedDialogPanel(title);
        TextView details = label(message == null ? "Please try again." : message, 11, MUTED, false);
        details.setPadding(0, dp(3), 0, dp(13)); box.addView(details);
        Button ok = themedButton("OK", CYAN, true); box.addView(ok, matchHeight(dp(42)));
        ok.setOnClickListener(v -> dialog.dismiss());
        showTechDialog(dialog, box);
    }
    private void chooseProfileForTransfers() {
        chooseDestinationProfile(profile -> { PcProfileStore.select(this, profile.id); updateHome(); });
    }

    private void chooseDestinationProfile(Consumer<PcProfileStore.Profile> callback) {
        List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
        if (profiles.isEmpty()) { showSettings(); setStatus("Add a PC address and Ditto password first."); return; }
        if (profiles.size() == 1) { callback.accept(profiles.get(0)); return; }
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) labels[i] = profiles.get(i).name + "  ·  " + profiles.get(i).routeSummary();
        new android.app.AlertDialog.Builder(this).setTitle("Send to PC").setItems(labels, (dialog, which) -> {
            PcProfileStore.Profile profile = profiles.get(which); PcProfileStore.select(this, profile.id); callback.accept(profile);
        }).setNegativeButton("CANCEL", null).show();
    }

    private void refreshListenerIfEnabled() {
        if (getSharedPreferences("peer", MODE_PRIVATE).getBoolean("backgroundReceive", false)) {
            Intent service = new Intent(this, ClipboardListenerService.class)
                    .setAction(ClipboardListenerService.ACTION_RELOAD_PROFILES);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        }
    }

    private void sendTextToPc() {
        String value = textInput.getText().toString();
        if (value.trim().isEmpty()) { setStatus("Enter some text to send."); return; }
        chooseDestinationProfile(profile -> {
            setStatus("Sending text to " + profile.name + "…");
            new Thread(() -> {
                String result;
                try {
                    String host = DittoProtocol.sendTextWithFallback(profile,
                            route -> phoneAddressFor(profile, route), value);
                    TransferHistory.addText(this, "SENT", value);
                    result = isLanRoute(profile, host)
                            ? "Sent over LAN; PC receipt is unconfirmed. If missing, check that PC Ditto Friends contains phone IP " + phoneAddressFor(profile, host) + "."
                            : "Sent over Tailscale; PC receipt is unconfirmed.";
                } catch (Exception e) { result = "Could not send: " + e.getMessage(); }
                String message = result;
                mainHandler.post(() -> { setStatus(message); updateHome(); });
            }).start();
        });
    }
    private void toggleReceiving() {
        var prefs = getSharedPreferences("peer", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("backgroundReceive", false);
        if (enabled) {
            prefs.edit().putBoolean("backgroundReceive", false).apply();
            stopService(new Intent(this, ClipboardListenerService.class));
            updateHome(); setStatus("Background receive stopped.");
            return;
        }
        if (PcProfileStore.getAll(this).isEmpty()) {
            showSettings(); setStatus("Set your PC address and Ditto password to receive."); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        prefs.edit().putBoolean("backgroundReceive", true).apply();
        startReceiverService();
        updateHome(); setStatus("Background receive enabled.");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("peer", MODE_PRIVATE).edit().putBoolean("backgroundReceive", true).apply();
            startReceiverService(); updateHome(); setStatus("Background receive enabled.");
        } else setStatus("Allow notifications to show incoming transfer alerts while receiving.");
    }

    private void startReceiverService() {
        Intent service = new Intent(this, ClipboardListenerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            textInput.setText(value);
            textInput.setSelection(textInput.length());
            setStatus("Clipboard text loaded into the composer.");
        } else setStatus("Phone clipboard is empty.");
    }

    private void chooseFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, 42);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 42 || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        chooseDestinationProfile(profile -> sendFilesToPc(selected, profile));
    }

    private void sendFilesToPc(ArrayList<Uri> selected, PcProfileStore.Profile profile) {
        setStatus("Preparing " + selected.size() + " file(s)…");
        new Thread(() -> {
            String result;
            try {
                var prefs = getSharedPreferences("peer", MODE_PRIVATE);
                List<String> names = new ArrayList<>();
                for (Uri uri : selected) {
                    String name = displayName(uri);
                    if (name == null || name.isEmpty()) name = "shared-file";
                    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                    File cached = new File(getCacheDir(), "shared_" + name);
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(cached)) {
                        if (input == null) throw new java.io.IOException("Cannot open " + name);
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    prefs.edit().putString("sharedFile:" + name, cached.getAbsolutePath()).apply();
                    names.add(name);
                }
                prefs.edit().putBoolean("backgroundReceive", true).apply();
                startReceiverService();
                long deadline = System.currentTimeMillis() + 8000;
                while (!ClipboardListenerService.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                if (!ClipboardListenerService.isReady()) throw new java.io.IOException("Could not start the Ditto receiver.");
                String host = DittoProtocol.sendFileClipWithFallback(profile,
                        route -> phoneAddressFor(profile, route), names);
                List<String> historyFiles = new ArrayList<>();
                for (String name : names) historyFiles.add(ReceivedFileProvider.uriForSharedFileName(name).toString());
                TransferHistory.addFiles(this, "SENT", TransferHistory.fileSummary(names.size()), historyFiles);
                result = isLanRoute(profile, host)
                        ? "File clip sent over LAN; PC receipt is unconfirmed. If missing, check that PC Ditto Friends contains phone IP " + phoneAddressFor(profile, host) + "."
                        : "File clip sent over Tailscale; PC receipt is unconfirmed.";
            } catch (Exception e) { result = "Could not send files: " + e.getMessage(); }
            String message = result;
            mainHandler.post(() -> { setStatus(message); updateHome(); });
        }).start();
    }
    private String displayName(Uri uri) {
        if (uri == null) return "";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private String mimeType(Uri uri) {
        if (uri == null) return "application/octet-stream";
        String type = getContentResolver().getType(uri);
        if (type != null && !type.isEmpty() && !"application/octet-stream".equals(type)) return type;
        String name = displayName(uri);
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        String extension = name == null ? "" : MimeTypeMap.getFileExtensionFromUrl(name);
        if (extension.isEmpty() && name != null && name.lastIndexOf('.') >= 0)
            extension = name.substring(name.lastIndexOf('.') + 1);
        String inferred = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
        return inferred == null ? (type == null ? "application/octet-stream" : type) : inferred;
    }

    private void loadThumbnail(ImageView target, Uri uri, String mime) {
        String key = uri.toString();
        target.setTag(key);
        thumbnailLoader.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    bitmap = getContentResolver().loadThumbnail(uri, new android.util.Size(dp(96), dp(96)), null);
            } catch (Exception ignored) { }
            if (bitmap == null && mime.startsWith("video/")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this, uri);
                    bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Exception ignored) { }
                finally { try { retriever.release(); } catch (Exception ignored) { } }
            } else if (bitmap == null && mime.startsWith("image/")) {
                try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 4;
                        bitmap = BitmapFactory.decodeStream(stream, null, options);
                    }
                } catch (Exception ignored) { }
            }
            Bitmap result = bitmap;
            if (result != null) mainHandler.post(() -> {
                if (key.equals(target.getTag())) target.setImageBitmap(result);
            });
        });
    }

    private void openEntry(TransferHistory.Entry entry) {
        if (entry.kind.equals("TEXT")) {
            copyHistoryText(entry.label);
            return;
        }
        if (entry.files.isEmpty()) {
            setStatus("This older file entry has no saved file reference.");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (String file : entry.files) {
            Uri uri = Uri.parse(file);
            uris.add(uri);
            names.add(displayName(uri));
        }
        if (uris.size() == 1) {
            openFile(uris.get(0));
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("Choose a file")
                .setItems(names.toArray(new String[0]), (dialog, which) -> openFile(uris.get(which)))
                .setNegativeButton("CANCEL", null).show();
    }

    private void openFile(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mimeType(uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(view, "Open file")); }
        catch (android.content.ActivityNotFoundException unavailable) { setStatus("No app can open this file."); }
    }

    private void shareEntry(TransferHistory.Entry entry) {
        if (entry.kind.equals("TEXT")) {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, entry.label);
            startActivity(Intent.createChooser(send, "Share text"));
            return;
        }
        if (entry.files.isEmpty()) {
            setStatus("This older file entry has no saved file reference.");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (String file : entry.files) uris.add(Uri.parse(file));
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        String type = uris.size() == 1 ? mimeType(uris.get(0)) : "*/*";
        send.setType(type);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newUri(getContentResolver(), "Ditto Link", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(send, "Share files"));
    }

    private void shareLatestReceived() {
        var prefs = getSharedPreferences("peer", MODE_PRIVATE);
        String publicUri = prefs.getString("lastReceivedUri", "");
        Uri uri;
        if (!publicUri.isEmpty()) {
            uri = Uri.parse(publicUri);
        } else {
            String path = prefs.getString("lastReceivedPath", "");
            if (path.isEmpty() || !new File(path).isFile()) { setStatus("No received file is available yet."); return; }
            uri = Uri.parse("content://com.vibedevdrop.dittolink.files/" + Uri.encode(new File(path).getName()));
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        String type = getContentResolver().getType(uri);
        share.setType(type == null ? "application/octet-stream" : type);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share received file"));
    }

    private void setStatus(String message) {
        getSharedPreferences("peer", MODE_PRIVATE).edit().putString("lastStatus", message).apply();
        if (homeStatus != null) homeStatus.setText(message);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showHistoryText(String text) {
        TextView content = label(text, 14, WHITE, false);
        content.setTextIsSelectable(false);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clipboard text")
                .setView(scroll)
                .setPositiveButton("COPY", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ditto Link", text));
                    android.widget.Toast.makeText(this, "Text copied", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void copyHistoryText(String text) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Ditto Link", text));
        android.widget.Toast.makeText(this, "Text copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    private String shortTime(long timestamp) {
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date(timestamp));
        String date = new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH)
                .format(new Date(timestamp)).toUpperCase(java.util.Locale.ROOT);
        return time + " · " + date;
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }

    private String formatCounter(long count) {
        if (count < 1000) return String.format(java.util.Locale.ROOT, "%03d", count);
        String[] units = { "k", "m", "b", "t" };
        double value = count;
        int unit = -1;
        do { value /= 1000.0; unit++; } while (value >= 1000.0 && unit < units.length - 1);
        if (value >= 999.5 && unit < units.length - 1) { value /= 1000.0; unit++; }
        String number = String.format(java.util.Locale.ROOT, value < 10 ? "%.1f" : "%.0f", value);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + units[unit];
    }

    private Button transparentTap() {
        Button button = new Button(this);
        button.setText("");
        button.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(0); button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        return button;
    }

    private ArtFrame artFrame(int drawable, int sourceY, int sourceHeight) {
        return new ArtFrame(this, drawable, sourceY, sourceHeight);
    }

    private void addArt(LinearLayout parent, ArtFrame frame, int gapAfter) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        if (gapAfter > 0) params.bottomMargin = dp(gapAfter);
        parent.addView(frame, params);
    }

    private final class ArtFrame extends FrameLayout {
        private static final int SOURCE_WIDTH = 941;
        private static final float VERTICAL_STRETCH = 1.18f;
        private final int sourceTop, sourceHeight;
        private final android.widget.ImageView image;
        private final ArrayList<Placement> placements = new ArrayList<>();

        ArtFrame(Context context, int drawable, int top, int height) {
            super(context);
            sourceTop = top; sourceHeight = height;
            image = new android.widget.ImageView(context);
            image.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            image.setImageResource(drawable);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            setClipChildren(true);
        }

        void setArt(int drawable) { image.setImageResource(drawable); }

        void place(View view, int x, int y, int width, int height) {
            placements.add(new Placement(view, x, y, width, height));
            addView(view);
            requestLayout();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width == 0) width = getResources().getDisplayMetrics().widthPixels;
            float scale = width / (float)SOURCE_WIDTH;
            int height = Math.round(sourceHeight * scale * VERTICAL_STRETCH);
            setMeasuredDimension(width, height);
            image.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            for (Placement p : placements) {
                int w = Math.max(1, Math.round(p.width * scale));
                int h = Math.max(1, Math.round(p.height * scale * VERTICAL_STRETCH));
                p.view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            float scale = width / (float)SOURCE_WIDTH;
            image.layout(0, 0, width, b - t);
            for (Placement p : placements) {
                int left = Math.round(p.x * scale);
                int top = Math.round((p.y - sourceTop) * scale * VERTICAL_STRETCH);
                p.view.layout(left, top, left + p.view.getMeasuredWidth(), top + p.view.getMeasuredHeight());
            }
        }
    }

    private static final class Placement {
        final View view; final int x, y, width, height;
        Placement(View view, int x, int y, int width, int height) {
            this.view = view; this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    private String configuredHost() {
        PcProfileStore.Profile profile = PcProfileStore.active(this);
        return profile == null ? "PC address not configured" : profile.name + "  ·  " + profile.routeSummary();
    }

    private LinearLayout panel(int color, int stroke, int radius) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(round(color, stroke, radius));
        return view;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView chip(String text, int color) {
        TextView view = label(text, 10, color, true);
        view.setLetterSpacing(0.08f);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(PANEL_ALT, color, 10));
        return view;
    }

    private Button actionButton(String text, int fill, int foreground, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(foreground);
        button.setTextSize(12);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setAllCaps(false);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(round(fill, stroke, 12));
        button.setStateListAnimator(null);
        return button;
    }

    private EditText editField(String hint, String text) {
        EditText field = new EditText(this);
        field.setText(text);
        field.setHint(hint);
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setSingleLine(true);
        field.setPadding(dp(12), dp(5), dp(12), dp(5));
        field.setBackground(round(Color.rgb(10, 17, 24), LINE, 10));
        return field;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != fill) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchHeight(int height) { return new LinearLayout.LayoutParams(-1, height); }
    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    static String tailscaleAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(nic.getInetAddresses())) {
                    String ip = address.getHostAddress();
                    if (!address.isLoopbackAddress() && ip != null && ip.matches("100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.[0-9]{1,3}\\.[0-9]{1,3}")) return ip;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    static String localNetworkAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            interfaces.sort((a, b) -> Boolean.compare(!a.getName().toLowerCase(java.util.Locale.ROOT).startsWith("wlan"),
                    !b.getName().toLowerCase(java.util.Locale.ROOT).startsWith("wlan")));
            for (NetworkInterface nic : interfaces) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    static String phoneAddressFor(PcProfileStore.Profile profile, String destinationHost) {
        String lan = profile.lanHost == null ? "" : profile.lanHost.trim();
        return !lan.isEmpty() && lan.equalsIgnoreCase(destinationHost) ? localNetworkAddress() : tailscaleAddress();
    }

    static boolean isLanRoute(PcProfileStore.Profile profile, String destinationHost) {
        return profile.lanHost != null && !profile.lanHost.trim().isEmpty()
                && profile.lanHost.trim().equalsIgnoreCase(destinationHost);
    }

    private final class SwipeFrame extends FrameLayout {
        private float downX, downY;
        private boolean horizontalSwipe;
        private boolean transitionStarted;
        SwipeFrame(Context context) { super(context); setBackgroundColor(BG); }
        @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            // Keep horizontal tab gestures available while ScrollView still handles vertical drags.
            super.requestDisallowInterceptTouchEvent(false);
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); horizontalSwipe = false; transitionStarted = false;
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.abs(dx) > dp(34) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    horizontalSwipe = true;
                    transitionStarted = true;
                    showPage(dx < 0 ? 1 : 0);
                    return true;
                }
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if ((event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                    && horizontalSwipe) {
                horizontalSwipe = false;
                transitionStarted = false;
                return true;
            }
            return horizontalSwipe || transitionStarted;
        }
    }
}
