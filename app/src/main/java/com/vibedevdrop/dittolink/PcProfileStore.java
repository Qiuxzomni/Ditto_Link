package com.vibedevdrop.dittolink;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores the user's trusted Ditto PCs and their local/Tailscale routes. */
final class PcProfileStore {
    private static final String PREFS = "peer";
    private static final String PROFILES = "pcProfiles";
    private static final String ACTIVE = "activePcId";

    static final class Profile {
        String id;
        String name;
        String lanHost;
        String tailnetHost;
        String password;

        Profile(String id, String name, String lanHost, String tailnetHost, String password) {
            this.id = id; this.name = name; this.lanHost = lanHost;
            this.tailnetHost = tailnetHost; this.password = password;
        }

        static Profile create() { return new Profile(UUID.randomUUID().toString(), "", "", "", ""); }

        List<String> hosts() {
            List<String> result = new ArrayList<>();
            addHost(result, lanHost);
            addHost(result, tailnetHost);
            return result;
        }

        String routeSummary() {
            if (!lanHost.trim().isEmpty() && !tailnetHost.trim().isEmpty()) return "LAN + Tailscale";
            if (!lanHost.trim().isEmpty()) return "LAN";
            if (!tailnetHost.trim().isEmpty()) return "Tailscale / host";
            return "No address";
        }

        private static void addHost(List<String> hosts, String value) {
            if (value == null) return;
            String host = value.trim();
            if (!host.isEmpty() && !hosts.contains(host)) hosts.add(host);
        }
    }

    static synchronized List<Profile> getAll(Context context) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(PROFILES, "");
        if (json.isEmpty()) {
            String legacyHost = prefs.getString("host", "");
            String legacyPassword = prefs.getString("password", "");
            List<Profile> migrated = migrateLegacy(legacyHost, PasswordVault.open(legacyPassword),
                    !legacyPassword.isEmpty() && PasswordVault.isSealed(legacyPassword));
            if (!migrated.isEmpty()) save(context, migrated, migrated.get(0).id);
            else prefs.edit().putString(PROFILES, "[]").apply();
            return migrated;
        }
        List<Profile> result = new ArrayList<>();
        boolean reseal = false;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String storedPassword = item.optString("password");
                if (!storedPassword.isEmpty() && !PasswordVault.isSealed(storedPassword)) reseal = true;
                Profile profile = new Profile(item.optString("id"), item.optString("name"),
                        item.optString("lanHost"), item.optString("tailnetHost"),
                        PasswordVault.open(storedPassword));
                if (profile.id.isEmpty()) profile.id = UUID.randomUUID().toString();
                // Keep a profile even when its password cannot be read (lost Keystore key, restored
                // backup) so the address survives and the user only has to retype the password.
                if (!profile.hosts().isEmpty()) result.add(profile);
            }
        } catch (Exception ignored) { }
        if (reseal) save(context, result, prefs.getString(ACTIVE, ""));
        return result;
    }

    static synchronized Profile active(Context context) {
        List<Profile> profiles = getAll(context);
        if (profiles.isEmpty()) return null;
        String activeId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "");
        for (Profile profile : profiles) if (profile.id.equals(activeId)) return profile;
        select(context, profiles.get(0).id);
        return profiles.get(0);
    }

    static synchronized void save(Context context, List<Profile> profiles, String activeId) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, String> previousPasswords = new HashMap<>();
        String previousJson = prefs.getString(PROFILES, "");
        String legacyPassword = prefs.getString("password", "");
        try {
            JSONArray previous = new JSONArray(previousJson);
            for (int i = 0; i < previous.length(); i++) {
                JSONObject item = previous.optJSONObject(i);
                if (item != null) previousPasswords.put(item.optString("id"), item.optString("password"));
            }
        } catch (Exception ignored) { }

        JSONArray array = new JSONArray();
        Map<String, String> sealedById = new HashMap<>();
        try {
            for (Profile profile : profiles) {
                if (profile.id == null || profile.id.isEmpty()) profile.id = UUID.randomUUID().toString();
                JSONObject item = new JSONObject();
                item.put("id", profile.id);
                item.put("name", profile.name == null ? "" : profile.name.trim());
                item.put("lanHost", profile.lanHost == null ? "" : profile.lanHost.trim());
                item.put("tailnetHost", profile.tailnetHost == null ? "" : profile.tailnetHost.trim());
                String plainPassword = profile.password == null ? "" : profile.password;
                String oldPassword = previousPasswords.get(profile.id);
                String sealedPassword = plainPassword.isEmpty() && oldPassword != null && !oldPassword.isEmpty()
                        ? oldPassword : null;
                if (sealedPassword == null && plainPassword.isEmpty() && profiles.size() == 1
                        && !legacyPassword.isEmpty()) sealedPassword = legacyPassword;
                if (sealedPassword == null) sealedPassword = PasswordVault.seal(plainPassword);
                // Encryption unavailable: leave the previously stored values untouched instead of
                // overwriting a recoverable secret with an empty one.
                if (sealedPassword == null) return;
                item.put("password", sealedPassword);
                array.put(item);
                sealedById.put(profile.id, sealedPassword);
            }
        } catch (Exception ignored) { return; }
        Profile active = null;
        for (Profile profile : profiles) if (profile.id.equals(activeId)) { active = profile; break; }
        String activePassword = "";
        if (active != null) {
            activePassword = sealedById.get(active.id);
            if (activePassword == null) return;
        }
        var editor = prefs.edit()
                .putString(PROFILES, array.toString()).putString(ACTIVE, activeId == null ? "" : activeId);
        if (active != null) {
            String host = !active.lanHost.trim().isEmpty() ? active.lanHost.trim() : active.tailnetHost.trim();
            editor.putString("host", host).putString("password", activePassword);
        } else editor.putString("host", "").putString("password", "");
        editor.apply();
    }

    static synchronized void select(Context context, String id) {
        List<Profile> profiles = getAll(context);
        save(context, profiles, id);
    }

    private static List<Profile> migrateLegacy(String hostValue, String password, boolean preserveUnreadable) {
        List<Profile> result = new ArrayList<>();
        String host = hostValue == null ? "" : hostValue.trim();
        if (host.isEmpty() || ((password == null || password.isEmpty()) && !preserveUnreadable)) return result;
        if (password == null) password = "";
        boolean local = isLocalAddress(host);
        Profile profile = new Profile(UUID.randomUUID().toString(), "My PC",
                local ? host : "", local ? "" : host, password);
        result.add(profile);
        return result;
    }

    private static boolean isLocalAddress(String host) {
        return host.startsWith("192.168.") || host.startsWith("10.")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9]{1,3}\\.[0-9]{1,3}")
                || host.endsWith(".local");
    }

    private PcProfileStore() { }
}
