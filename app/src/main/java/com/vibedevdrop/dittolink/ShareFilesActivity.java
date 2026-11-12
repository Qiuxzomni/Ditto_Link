package com.vibedevdrop.dittolink;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Receives files from Android's Share sheet and publishes a Ditto file clip. */
public final class ShareFilesActivity extends Activity {
    private LinearLayout root;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 20);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText("Ditto Link · Send files to PC");
        title.setTextSize(20);
        root.addView(title);
        status = new TextView(this);
        status.setText("Preparing shared files…");
        status.setPadding(0, 18, 0, 12);
        root.addView(status);
        setContentView(root);

        List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
        if (profiles.isEmpty()) {
            status.setText("Add a PC in Ditto Link settings first.");
            Button open = new Button(this); open.setText("Open Ditto Link"); root.addView(open);
            open.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); }); return;
        }
        List<Uri> uris = sharedUris(getIntent());
        if (uris.isEmpty()) { showError("No shareable files were received."); return; }
        if (profiles.size() == 1) startSending(profiles.get(0), uris);
        else {
            String[] labels = new String[profiles.size()];
            for (int i = 0; i < profiles.size(); i++) labels[i] = profiles.get(i).name + "  ·  " + profiles.get(i).routeSummary();
            new android.app.AlertDialog.Builder(this).setTitle("Send to PC").setItems(labels, (dialog, which) -> {
                PcProfileStore.Profile profile = profiles.get(which); PcProfileStore.select(this, profile.id); startSending(profile, uris);
            }).setOnCancelListener(d -> finish()).show();
        }
    }

    private void startSending(PcProfileStore.Profile profile, List<Uri> uris) {
        status.setText("Sending to " + profile.name + "…");
        new Thread(() -> sendFiles(profile, uris), "DittoLink-share-files").start();
    }
    private void sendFiles(PcProfileStore.Profile profile, List<Uri> uris) {
        try {
            var prefs = getSharedPreferences("peer", MODE_PRIVATE);
            File cache = getCacheDir();
            List<String> names = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            for (Uri uri : uris) {
                String displayName = displayName(uri);
                String name = uniqueName(safeName(displayName), usedNames);
                File target = new File(cache, "shared_" + name);
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new java.io.IOException("Could not open " + displayName);
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                prefs.edit().putString("sharedFile:" + name, target.getAbsolutePath()).apply();
                names.add(name);
            }

            prefs.edit().putBoolean("backgroundReceive", true).apply();
            Intent service = new Intent(this, ClipboardListenerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            long deadline = System.currentTimeMillis() + 8000;
            while (!ClipboardListenerService.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(100);
            if (!ClipboardListenerService.isReady()) throw new java.io.IOException("Ditto Link could not start its file listener.");

            String host = DittoProtocol.sendFileClipWithFallback(profile,
                    route -> MainActivity.phoneAddressFor(profile, route), names);
            List<String> historyFiles = new ArrayList<>();
            for (String name : names) historyFiles.add(ReceivedFileProvider.uriForSharedFileName(name).toString());
            TransferHistory.addFiles(this, "SENT", TransferHistory.fileSummary(names.size()), historyFiles);
            runOnUiThread(() -> {
                String routeNote = MainActivity.isLanRoute(profile, host)
                        ? " over LAN. PC receipt is unconfirmed; check PC Ditto Friends contains phone IP "
                                + MainActivity.phoneAddressFor(profile, host) + " if the clip doesn't appear."
                        : " over Tailscale. PC receipt is unconfirmed.";
                status.setText("File clip sent to " + profile.name + routeNote);
                Toast.makeText(this, "File clip sent; PC receipt is unconfirmed." + routeNote,
                        Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 900);
            });
        } catch (Exception e) {
            String message = "Could not send files: " + e.getMessage();
            runOnUiThread(() -> showError(message));
        }
    }

    private List<Uri> sharedUris(Intent intent) {
        LinkedHashSet<Uri> result = new LinkedHashSet<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) result.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> items = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (items != null) result.addAll(items);
        }
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) result.add(uri);
            }
        }
        return new ArrayList<>(result);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "shared-file" : segment;
    }

    private String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[.]+", "");
        return safe.isEmpty() ? "shared-file" : safe;
    }

    private String uniqueName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        while (!used.add(stem + "_" + suffix + extension)) suffix++;
        return stem + "_" + suffix + extension;
    }

    private void showError(String message) {
        status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Button close = new Button(this);
        close.setText("Close");
        root.addView(close);
        close.setOnClickListener(v -> finish());
    }
}
