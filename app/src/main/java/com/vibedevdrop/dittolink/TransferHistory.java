package com.vibedevdrop.dittolink;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class TransferHistory {
    static final String ACTION_CHANGED = "com.vibedevdrop.dittolink.HISTORY_CHANGED";
    private static final int MAX_STORED_TEXT_CHARS = 4000;
    private static final String LIFETIME_PREFS = "lifetime_counters";

    static final class Entry {
        final long time;
        final String direction, kind, label;
        final List<String> files;
        Entry(long time, String direction, String kind, String label, List<String> files) {
            this.time = time; this.direction = direction; this.kind = kind; this.label = label;
            this.files = files;
        }
    }

    static void addText(Context context, String direction, String text) {
        String value = text == null || text.isEmpty() ? "(empty text)" : text;
        if (value.length() > MAX_STORED_TEXT_CHARS)
            value = value.substring(0, MAX_STORED_TEXT_CHARS) + "…";
        add(context, direction, "TEXT", value);
    }

    static String preview(String value, int maxChars) {
        if (value == null) return "";
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxChars ? oneLine : oneLine.substring(0, maxChars - 1) + "…";
    }

    static String fileSummary(int count) {
        return count + (count == 1 ? " file" : " files");
    }

    static synchronized void add(Context context, String direction, String kind, String label) {
        add(context, direction, kind, label, null);
    }

    static synchronized void addFiles(Context context, String direction, String label, List<String> files) {
        add(context, direction, "FILE", label, files);
    }

    private static synchronized void add(Context context, String direction, String kind, String label,
                                         List<String> files) {
        JSONArray previous;
        try { previous = new JSONArray(context.getSharedPreferences("history", Context.MODE_PRIVATE).getString("entries", "[]")); }
        catch (Exception e) { previous = new JSONArray(); }
        JSONArray next = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("time", System.currentTimeMillis());
            item.put("direction", direction);
            item.put("kind", kind);
            item.put("label", label);
            if (files != null) item.put("files", new JSONArray(files));
            next.put(item);
            for (int i = 0; i < previous.length() && i < 99; i++) next.put(previous.getJSONObject(i));
        } catch (Exception ignored) { }
        var preferences = context.getSharedPreferences("history", Context.MODE_PRIVATE);
        String countKey = "SENT".equals(direction) ? "sentCount" : "receivedCount";
        long count = count(context, direction);
        preferences.edit().putString("entries", next.toString()).putLong(countKey, count + 1).apply();
        context.getSharedPreferences(LIFETIME_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(countKey, count + 1).apply();
        context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
    }

    static synchronized long count(Context context, String direction) {
        var lifetime = context.getSharedPreferences(LIFETIME_PREFS, Context.MODE_PRIVATE);
        String key = "SENT".equals(direction) ? "sentCount" : "receivedCount";
        if (lifetime.contains(key)) return lifetime.getLong(key, 0L);
        long count = 0;
        var preferences = context.getSharedPreferences("history", Context.MODE_PRIVATE);
        try {
            count = Math.max(0, preferences.getLong(key, 0L));
            JSONArray entries = new JSONArray(preferences.getString("entries", "[]"));
            long storedEntries = 0;
            for (int i = 0; i < entries.length(); i++)
                if (direction.equals(entries.getJSONObject(i).optString("direction"))) storedEntries++;
            count = Math.max(count, storedEntries);
        } catch (Exception ignored) { }
        lifetime.edit().putLong(key, count).apply();
        return count;
    }

    static synchronized List<Entry> read(Context context) {
        List<Entry> result = new ArrayList<>();
        JSONArray persisted = new JSONArray();
        boolean changed = false;
        try {
            JSONArray entries = new JSONArray(context.getSharedPreferences("history", Context.MODE_PRIVATE).getString("entries", "[]"));
            // Capture legacy totals before pruning file rows that no longer have usable references.
            count(context, "SENT");
            count(context, "RECEIVED");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                List<String> files = new ArrayList<>();
                JSONArray fileArray = item.optJSONArray("files");
                if ("FILE".equals(item.optString("kind")) && fileArray == null) {
                    changed = true;
                    continue;
                }
                if (fileArray != null) {
                    boolean fileRefsChanged = false;
                    for (int f = 0; f < fileArray.length(); f++) {
                        String file = fileArray.optString(f, "");
                        if (file.isEmpty()) continue;
                        String existing = resolveExistingReference(context, item.optString("direction"), file);
                        if (existing != null) {
                            files.add(existing);
                            if (!existing.equals(file)) { changed = true; fileRefsChanged = true; }
                        }
                    }
                    if (files.size() != fileArray.length()) { changed = true; fileRefsChanged = true; }
                    if (files.isEmpty()) { changed = true; continue; }
                    if (fileRefsChanged) {
                        item.put("files", new JSONArray(files));
                        if ("FILE".equals(item.optString("kind")) && files.size() != fileArray.length())
                            item.put("label", fileSummary(files.size()));
                    }
                }
                persisted.put(item);
                result.add(new Entry(item.optLong("time"), item.optString("direction"), item.optString("kind"),
                        item.optString("label"), files));
            }
        } catch (Exception ignored) { }
        if (changed) {
            context.getSharedPreferences("history", Context.MODE_PRIVATE).edit()
                    .putString("entries", persisted.toString()).apply();
            context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
        }
        return result;
    }

    static synchronized boolean remove(Context context, Entry target) {
        JSONArray entries;
        try { entries = new JSONArray(context.getSharedPreferences("history", Context.MODE_PRIVATE).getString("entries", "[]")); }
        catch (Exception ignored) { return false; }
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (!removed && item != null && item.optLong("time") == target.time
                    && item.optString("direction").equals(target.direction)
                    && item.optString("kind").equals(target.kind)
                    && item.optString("label").equals(target.label)
                    && sameFiles(item.optJSONArray("files"), target.files)) {
                removed = true;
                continue;
            }
            if (item != null) kept.put(item);
        }
        if (removed) {
            context.getSharedPreferences("history", Context.MODE_PRIVATE).edit()
                    .putString("entries", kept.toString()).apply();
            context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
        }
        return removed;
    }

    private static boolean sameFiles(JSONArray stored, List<String> target) {
        if (stored == null || stored.length() != target.size()) return false;
        for (int i = 0; i < stored.length(); i++) if (!target.get(i).equals(stored.optString(i))) return false;
        return true;
    }

    private static boolean fileExists(Context context, android.net.Uri uri) {
        if ("media".equals(uri.getAuthority()) && android.os.Build.VERSION.SDK_INT >= 29) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                    new String[] { android.provider.MediaStore.MediaColumns.RELATIVE_PATH }, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return false;
                int column = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH);
                if (column >= 0) {
                    String relativePath = cursor.getString(column);
                    if (relativePath == null || !relativePath.equalsIgnoreCase(
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/Ditto/")) return false;
                }
            } catch (Exception ignored) { return false; }
        }
        try (android.os.ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (Exception ignored) { return false; }
    }

    private static String resolveExistingReference(Context context, String direction, String value) {
        android.net.Uri original = android.net.Uri.parse(value);
        if ("RECEIVED".equals(direction) && "com.vibedevdrop.dittolink.files".equals(original.getAuthority())
                && original.getPathSegments().size() > 1 && !"download".equals(original.getPathSegments().get(0)))
            return null;
        if ("com.vibedevdrop.dittolink.files".equals(original.getAuthority()) && original.getPathSegments().size() == 1) {
            String name = original.getLastPathSegment();
            if ("RECEIVED".equals(direction)) {
                android.net.Uri download = ReceivedFileProvider.uriForDownloadFileName(name);
                if (fileExists(context, download)) return download.toString();
                return null;
            }
            android.net.Uri shared = ReceivedFileProvider.uriForSharedFileName(name);
            return fileExists(context, shared) ? shared.toString() : null;
        }
        return fileExists(context, original) ? value : null;
    }

    private TransferHistory() { }
}
