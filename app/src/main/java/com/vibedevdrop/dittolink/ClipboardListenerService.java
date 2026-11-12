package com.vibedevdrop.dittolink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Keeps Ditto's inbound listener available while the user switches to other apps. */
public final class ClipboardListenerService extends Service {
    private static final String CHANNEL = "dittolink_receive";
    private static final String LARGE_RECEIVE_CHANNEL = "ditto_large_receive";
    private static final String INCOMING_CHANNEL = "ditto_incoming";
    private static final String ACTION_ACCEPT_LARGE_RECEIVE = "com.vibedevdrop.dittolink.ACCEPT_LARGE_RECEIVE";
    private static final String ACTION_DECLINE_LARGE_RECEIVE = "com.vibedevdrop.dittolink.DECLINE_LARGE_RECEIVE";
    static final String ACTION_RELOAD_PROFILES = "com.vibedevdrop.dittolink.RELOAD_PROFILES";
    private static final int SERVICE_NOTIFICATION = 20;
    private static final int LARGE_RECEIVE_NOTIFICATION = 22;
    private static final int INCOMING_FILE_NOTIFICATION = 23;
    private static final Object RECEIVE_DECISION_LOCK = new Object();
    private static PendingReceiveDecision pendingReceiveDecision;
    private static volatile boolean listenerReady;
    private volatile Thread listenerThread;
    private long lastFileProgressNotificationAt;

    private static final class PendingReceiveDecision {
        final CountDownLatch answered = new CountDownLatch(1);
        volatile boolean accepted;
    }

    static boolean isReady() { return listenerReady; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(SERVICE_NOTIFICATION, notification("Ready to receive from your PC"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ACCEPT_LARGE_RECEIVE.equals(intent.getAction())) {
            resolveLargeReceive(true);
            if (listenerThread == null || !listenerThread.isAlive()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_DECLINE_LARGE_RECEIVE.equals(intent.getAction())) {
            resolveLargeReceive(false);
            if (listenerThread == null || !listenerThread.isAlive()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && "stop".equals(intent.getAction())) {
            resolveLargeReceive(false);
            getSharedPreferences("peer", MODE_PRIVATE).edit().putBoolean("backgroundReceive", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD_PROFILES.equals(intent.getAction())) {
            Thread current = listenerThread;
            if (current != null) current.interrupt();
            listenerThread = null;
            listenerReady = false;
            DittoProtocol.closeListener();
        }
        List<PcProfileStore.Profile> profiles = PcProfileStore.getAll(this);
        if (profiles.isEmpty()) {
            getSharedPreferences("peer", MODE_PRIVATE).edit().putBoolean("backgroundReceive", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        Thread current = listenerThread;
        if (current == null || !current.isAlive()) {
            listenerThread = new Thread(() -> listenUntilStopped(profiles), "DittoLink-listener");
            listenerThread.start();
        }
        return START_STICKY;
    }

    private void listenUntilStopped(List<PcProfileStore.Profile> profiles) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DittoProtocol.listen(profiles, incoming -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ditto", incoming));
                    TransferHistory.addText(this, "RECEIVED", incoming);
                    getSystemService(NotificationManager.class).notify(SERVICE_NOTIFICATION + 1,
                            notification("Text copied from PC — paste it in any app"));
                }, this::filesRequested,
                        new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Ditto"),
                        result -> handleReceivedFiles(result), this::showFileProgress,
                        () -> { if (!Thread.currentThread().isInterrupted()) listenerReady = true; },
                        this::confirmLargeReceive);
            } catch (Exception e) {
                listenerReady = false;
                clearIncomingFileNotification();
                if (!Thread.currentThread().isInterrupted()) {
                    getSystemService(NotificationManager.class).notify(SERVICE_NOTIFICATION,
                            notification("Reconnecting: " + (e.getMessage() == null ? "transfer issue" : e.getMessage())));
                    try { Thread.sleep(2000); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private boolean confirmLargeReceive(long bytesAfterNextFile, long nextFileSize, int fileIndex, int totalFiles) {
        PendingReceiveDecision decision = new PendingReceiveDecision();
        synchronized (RECEIVE_DECISION_LOCK) { pendingReceiveDecision = decision; }

        String message;
        if (totalFiles > 0 && fileIndex >= totalFiles) {
            message = "This transfer is " + formatSize(bytesAfterNextFile) + " across " + totalFiles
                    + " file(s). Receive it?";
        } else {
            String count = totalFiles > 0 ? fileIndex + " of " + totalFiles : fileIndex + " file(s) so far";
            message = "At least " + formatSize(bytesAfterNextFile) + " total (" + count + "; next file "
                    + formatSize(nextFileSize) + "). The total may be larger. Receive this file and the rest?";
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(LARGE_RECEIVE_NOTIFICATION, largeReceiveNotification(message));
        boolean answered = false;
        try { answered = decision.answered.await(2, TimeUnit.MINUTES); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        finally {
            synchronized (RECEIVE_DECISION_LOCK) {
                if (pendingReceiveDecision == decision) pendingReceiveDecision = null;
            }
            manager.cancel(LARGE_RECEIVE_NOTIFICATION);
        }
        return answered && decision.accepted;
    }

    private void handleReceivedFiles(DittoProtocol.FileReceiveResult result) {
        List<File> files = result.files;
        clearIncomingFileNotification();
        if (result.declined && files.isEmpty()) {
            getSystemService(NotificationManager.class).notify(SERVICE_NOTIFICATION + 1,
                    notification("Large transfer declined — no files received (next file " + formatSize(result.pendingFileSize) + ")"));
            return;
        }
        if (files.isEmpty()) return;

        var prefs = getSharedPreferences("peer", MODE_PRIVATE);
        int visibleCount = 0;
        Uri latestPublicUri = null;
        List<String> historyFiles = new ArrayList<>();
        for (File file : files) {
            try {
                Uri publicUri = saveToPublicDownloads(file);
                if (publicUri != null) {
                    if (!file.delete()) {
                        getContentResolver().delete(publicUri, null, null);
                        throw new IOException("Could not clean up the temporary received file");
                    }
                    latestPublicUri = publicUri;
                }
                visibleCount++;
                if (publicUri != null) historyFiles.add(publicUri.toString());
                else if (Build.VERSION.SDK_INT < 29)
                    historyFiles.add(ReceivedFileProvider.uriForDownloadFileName(file.getName()).toString());
            } catch (Exception ignored) { }
        }
        if (!historyFiles.isEmpty())
            TransferHistory.addFiles(this, "RECEIVED", TransferHistory.fileSummary(historyFiles.size()), historyFiles);
        var update = prefs.edit();
        if (latestPublicUri != null) {
            update.putString("lastReceivedUri", latestPublicUri.toString()).remove("lastReceivedPath");
        } else {
            update.putString("lastReceivedPath", files.get(files.size() - 1).getAbsolutePath()).remove("lastReceivedUri");
        }
        update.apply();

        String message;
        if (result.declined) {
            String total = result.expectedFileCount > 0 ? " of " + result.expectedFileCount : "";
            message = "Received " + files.size() + total + " file(s); declined the next "
                    + formatSize(result.pendingFileSize) + " file";
        } else {
            message = visibleCount == files.size()
                    ? "Received " + files.size() + " file(s) — saved to Download/Ditto"
                    : "Received " + files.size() + " file(s) in Ditto Link";
        }
        getSystemService(NotificationManager.class).notify(SERVICE_NOTIFICATION + 1, notification(message));
    }

    private void showFileProgress(DittoProtocol.FileTransferProgress progress) {
        int percent = progress.fileSize <= 0 ? 100
                : (int)Math.min(100, progress.bytesRead * 100 / progress.fileSize);
        long now = System.currentTimeMillis();
        boolean fileStarted = progress.bytesRead == 0;
        boolean fileFinished = progress.bytesRead >= progress.fileSize;
        if (!fileStarted && !fileFinished && now - lastFileProgressNotificationAt < 250) return;
        lastFileProgressNotificationAt = now;

        // A file is on its way: announce it (the first post of the transfer alerts, the rest just
        // update the same notification) so the user knows an incoming transfer has started.
        String title = fileStarted
                ? "Receiving file — " + formatSize(progress.fileSize)
                : "Receiving file — " + percent + "%";
        String content = progress.totalFiles > 0
                ? progress.fileName + " (" + progress.fileIndex + " of " + progress.totalFiles + ")"
                : progress.fileName;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, INCOMING_CHANNEL) : new Notification.Builder(this);
        Notification progressNotification = builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setProgress(100, percent, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        getSystemService(NotificationManager.class).notify(INCOMING_FILE_NOTIFICATION, progressNotification);
    }

    private void clearIncomingFileNotification() {
        getSystemService(NotificationManager.class).cancel(INCOMING_FILE_NOTIFICATION);
    }

    private Notification largeReceiveNotification(String message) {
        PendingIntent accept = receiveDecisionIntent(ACTION_ACCEPT_LARGE_RECEIVE, 31);
        PendingIntent decline = receiveDecisionIntent(ACTION_DECLINE_LARGE_RECEIVE, 32);
        return new Notification.Builder(this, LARGE_RECEIVE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Large file transfer")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_save, "RECEIVE", accept)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DECLINE", decline)
                .build();
    }

    private PendingIntent receiveDecisionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, ClipboardListenerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void resolveLargeReceive(boolean accepted) {
        synchronized (RECEIVE_DECISION_LOCK) {
            if (pendingReceiveDecision != null) {
                pendingReceiveDecision.accepted = accepted;
                pendingReceiveDecision.answered.countDown();
            }
        }
    }

    private String formatSize(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private List<DittoProtocol.FilePart> filesRequested(List<String> paths) {
        var prefs = getSharedPreferences("peer", MODE_PRIVATE);
        List<DittoProtocol.FilePart> result = new ArrayList<>();
        for (String path : paths) {
            String name = path.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            String stored = prefs.getString("sharedFile:" + name, null);
            if (stored != null) {
                File file = new File(stored);
                if (file.isFile()) result.add(new DittoProtocol.FilePart(path, file));
            }
        }
        return result;
    }

    private Uri saveToPublicDownloads(File source) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ditto");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create Download/Ditto");
            File target = new File(folder, source.getName());
            try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return null;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(source.getName());
        String mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
        values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ditto");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create file in Download/Ditto");
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Could not write file in Download/Ditto");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            return uri;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            if (error instanceof IOException) throw (IOException)error;
            throw new IOException("Could not save file to Download/Ditto", error);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Ditto connection", NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(LARGE_RECEIVE_CHANNEL,
                    "Large transfer approvals", NotificationManager.IMPORTANCE_HIGH));
            NotificationChannel incoming = new NotificationChannel(INCOMING_CHANNEL,
                    "Incoming files", NotificationManager.IMPORTANCE_DEFAULT);
            incoming.setDescription("Announces a file that is being received from your PC");
            manager.createNotificationChannel(incoming);
        }
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, ClipboardListenerService.class).setAction("stop");
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Ditto Link")
                .setContentText(message)
                .setOngoing(message.startsWith("Ready") || message.startsWith("Reconnecting"))
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
                .build();
    }

    @Override public void onDestroy() {
        resolveLargeReceive(false);
        listenerReady = false;
        clearIncomingFileNotification();
        Thread thread = listenerThread;
        if (thread != null) thread.interrupt();
        DittoProtocol.closeListener();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
