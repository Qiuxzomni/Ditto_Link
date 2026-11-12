package com.vibedevdrop.dittolink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class StartListenerOnBoot extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!context.getSharedPreferences("peer", Context.MODE_PRIVATE).getBoolean("backgroundReceive", false)
                || PcProfileStore.getAll(context).isEmpty()) return;
        Intent service = new Intent(context, ClipboardListenerService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
