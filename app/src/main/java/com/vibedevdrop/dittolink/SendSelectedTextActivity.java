package com.vibedevdrop.dittolink;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Handles Android's PROCESS_TEXT action for selected text. */
public final class SendSelectedTextActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 20);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText("Ditto Link · Send to PC");
        title.setTextSize(20);
        root.addView(title);
        TextView status = new TextView(this);
        status.setText("Sending selected text…");
        status.setPadding(0, 18, 0, 12);
        root.addView(status);
        setContentView(root);

        CharSequence selected = getIntent().getCharSequenceExtra(IntentText.EXTRA_PROCESS_TEXT);
        if (getIntent().getAction() == null || !IntentText.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                || selected == null || selected.length() == 0) { showError(status, "No selected text was provided."); return; }
        List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
        if (profiles.isEmpty()) {
            status.setText("Add a PC in Ditto Link settings first.");
            Button open = new Button(this); open.setText("Open Ditto Link"); root.addView(open);
            open.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); }); return;
        }
        String value = selected.toString();
        if (profiles.size() == 1) sendToPc(profiles.get(0), value, status, root);
        else {
            String[] labels = new String[profiles.size()];
            for (int i = 0; i < profiles.size(); i++) labels[i] = profiles.get(i).name + "  ·  " + profiles.get(i).routeSummary();
            new android.app.AlertDialog.Builder(this).setTitle("Send to PC").setItems(labels, (dialog, which) -> {
                PcProfileStore.Profile profile = profiles.get(which); PcProfileStore.select(this, profile.id); sendToPc(profile, value, status, root);
            }).setOnCancelListener(d -> finish()).show();
        }
    }

    private void sendToPc(PcProfileStore.Profile profile, String value, TextView status, LinearLayout root) {
        status.setText("Sending to " + profile.name + "…");
        new Thread(() -> {
            String result;
            try {
                String host = DittoProtocol.sendTextWithFallback(profile,
                        route -> MainActivity.phoneAddressFor(profile, route), value);
                TransferHistory.addText(this, "SENT", value);
                result = MainActivity.isLanRoute(profile, host)
                        ? "Sent over LAN; receipt is unconfirmed. Add phone IP " + MainActivity.phoneAddressFor(profile, host) + " to Ditto Friends."
                        : "Sent to Ditto on your PC over Tailscale.";
            } catch (Exception e) { result = "Could not send: " + e.getMessage(); }
            String message = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                status.setText(message); Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                if (message.startsWith("Sent")) new Handler(Looper.getMainLooper()).postDelayed(this::finish, 700);
                else { Button close = new Button(this); close.setText("Close"); root.addView(close); close.setOnClickListener(v -> finish()); }
            });
        }, "DittoLink-send-selection").start();
    }
    private void showError(TextView status, String message) {
        status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
    }

    /** Intent constants avoid coupling this tiny receiver to selection UI internals. */
    private static final class IntentText {
        static final String ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
        static final String EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT";
    }
}
