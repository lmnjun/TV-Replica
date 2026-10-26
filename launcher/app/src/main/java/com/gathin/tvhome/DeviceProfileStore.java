package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Display metadata only. Account chooser results select a cache; no credentials, tokens or per-user layouts. */
final class DeviceProfileStore {
    private static final String PREFS = "device_profile";
    static final String NAME = "name", ACCOUNT = "account", AVATAR_REVISION = "avatar_revision";
    private static final String VERIFIED_NAME = "verified_name";

    private DeviceProfileStore() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File avatarFile(Context context) { return new File(context.getFilesDir(), "profile-avatar.png"); }

    static Profile read(Context context) {
        SharedPreferences prefs = preferences(context);
        String account = prefs.getString(ACCOUNT, "");
        String name = prefs.getBoolean(VERIFIED_NAME, false) ? prefs.getString(NAME, "") : "";
        return new Profile(name, account);
    }

    /** Only explicit trusted metadata sets a real name; old email prefixes are not promoted. */
    static synchronized boolean set(Context context, String name, String account) {
        String cleanAccount = clean(account, 320);
        if (cleanAccount.isEmpty()) throw new IllegalArgumentException("Account metadata required");
        String cleanName = clean(name, 80);
        if (cleanName.indexOf('@') >= 0) cleanName = "";
        SharedPreferences prefs = preferences(context);
        String previous = prefs.getString(ACCOUNT, "");
        SharedPreferences.Editor edit = prefs.edit();
        rememberCurrent(context, prefs, edit);
        String prefix = prefix(cleanAccount);
        edit.putString(prefix + ".account", cleanAccount).putString(prefix + ".name", cleanName)
                .putBoolean(prefix + ".verified", !cleanName.isEmpty());
        if (!previous.equals(cleanAccount)) restoreAvatar(context, cleanAccount);
        edit.putString(NAME, cleanName).putBoolean(VERIFIED_NAME, !cleanName.isEmpty())
                .putString(ACCOUNT, cleanAccount).putLong("updated_at", System.currentTimeMillis())
                .putLong(AVATAR_REVISION, avatarFile(context).isFile() ? nextRevision(prefs) : 0);
        return edit.commit();
    }

    /** Call only with a com.google identity returned by the system account chooser. */
    static synchronized boolean selectAccount(Context context, String account) {
        String cleanAccount = clean(account, 320);
        if (cleanAccount.isEmpty()) return false;
        SharedPreferences prefs = preferences(context);
        String previous = prefs.getString(ACCOUNT, "");
        SharedPreferences.Editor edit = prefs.edit();
        rememberCurrent(context, prefs, edit);
        if (previous.equals(cleanAccount)) return edit.commit();
        String prefix = prefix(cleanAccount);
        boolean verified = prefs.getBoolean(prefix + ".verified", false);
        String name = verified ? prefs.getString(prefix + ".name", "") : "";
        restoreAvatar(context, cleanAccount);
        edit.putString(prefix + ".account", cleanAccount).putString(ACCOUNT, cleanAccount)
                .putString(NAME, name).putBoolean(VERIFIED_NAME, verified)
                .putLong("updated_at", System.currentTimeMillis())
                .putLong(AVATAR_REVISION, avatarFile(context).isFile() ? nextRevision(prefs) : 0);
        return edit.commit();
    }

    static synchronized void avatarUpdated(Context context) {
        SharedPreferences prefs = preferences(context);
        String account = prefs.getString(ACCOUNT, "");
        if (!account.isEmpty()) copyAvatar(avatarFile(context), cachedAvatar(context, account));
        prefs.edit().putLong(AVATAR_REVISION, nextRevision(prefs)).commit();
    }

    /** Account check and publication share the same lock as selection to prevent crossed photos. */
    static synchronized boolean publishAvatar(Context context, File pending, String account) {
        if (!account.equals(preferences(context).getString(ACCOUNT, ""))) return false;
        if (!pending.renameTo(avatarFile(context))) return false;
        avatarUpdated(context);
        return true;
    }

    /**
     * Cached display metadata, current first. This is NOT an inventory of accounts signed in on TV;
     * only Android's account chooser may supply the actual available identities.
     */
    static synchronized List<Profile> currentAccountsMetadataList(Context context) {
        SharedPreferences prefs = preferences(context);
        List<Profile> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Profile current = read(context);
        if (current.available()) { result.add(current); seen.add(current.account); }
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("cache.") || !key.endsWith(".account")
                    || !(entry.getValue() instanceof String)) continue;
            String account = (String)entry.getValue();
            if (account.isEmpty() || !seen.add(account)) continue;
            String prefix = prefix(account);
            String name = prefs.getBoolean(prefix + ".verified", false)
                    ? prefs.getString(prefix + ".name", "") : "";
            result.add(new Profile(name, account));
        }
        return result;
    }

    static synchronized boolean clear(Context context) {
        avatarFile(context).delete();
        File[] files = new File(context.getFilesDir(), "profile-cache").listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        return preferences(context).edit().clear().commit();
    }

    private static void rememberCurrent(Context context, SharedPreferences prefs, SharedPreferences.Editor edit) {
        String account = prefs.getString(ACCOUNT, "");
        if (account.isEmpty()) return;
        String prefix = prefix(account);
        edit.putString(prefix + ".account", account);
        if (prefs.getBoolean(VERIFIED_NAME, false)) {
            edit.putString(prefix + ".name", prefs.getString(NAME, "")).putBoolean(prefix + ".verified", true);
        }
        if (avatarFile(context).isFile()) copyAvatar(avatarFile(context), cachedAvatar(context, account));
    }

    private static void restoreAvatar(Context context, String account) {
        File active = avatarFile(context);
        File cached = cachedAvatar(context, account);
        if (!cached.isFile() || !copyAvatar(cached, active)) active.delete();
    }

    private static File cachedAvatar(Context context, String account) {
        return new File(new File(context.getFilesDir(), "profile-cache"), fingerprint(account) + ".png");
    }

    private static boolean copyAvatar(File source, File target) {
        File pending = null;
        try {
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            pending = File.createTempFile("avatar-", ".tmp", parent);
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[8192]; int count; long bytes = 0;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count; if (bytes > 2 * 1024 * 1024) throw new IOException("Image too large");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            return pending.renameTo(target);
        } catch (IOException failure) {
            return false;
        } finally {
            if (pending != null) pending.delete();
        }
    }

    private static long nextRevision(SharedPreferences prefs) {
        return Math.max(System.currentTimeMillis(), prefs.getLong(AVATAR_REVISION, 0) + 1);
    }

    private static String prefix(String account) { return "cache." + fingerprint(account); }

    private static String fingerprint(String account) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2], digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[2 * i] = digits[(digest[i] >>> 4) & 15]; hex[2 * i + 1] = digits[digest[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Profile cache unavailable"); }
    }

    private static String clean(String value, int limit) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > limit) throw new IllegalArgumentException("Account metadata too long");
        for (int i = 0; i < clean.length(); i++)
            if (Character.isISOControl(clean.charAt(i))) throw new IllegalArgumentException("Invalid account metadata");
        return clean;
    }

    static final class Profile {
        final String name, account;
        Profile(String name, String account) { this.name = name; this.account = account; }
        boolean available() { return !account.isEmpty(); }
        String displayName() { return !name.isEmpty() ? name : available() ? "Google account" : "Account"; }
    }
}
