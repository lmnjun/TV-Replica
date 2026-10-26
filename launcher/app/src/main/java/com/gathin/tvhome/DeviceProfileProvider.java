package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Privileged, write-only account-display metadata bridge. No identity is returned over IPC. */
public final class DeviceProfileProvider extends ContentProvider {
    @Override public boolean onCreate() { return getContext() != null; }

    private Context authorizedContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Profile storage unavailable");
        if (Binder.getCallingUid() != Process.myUid()) {
            // ContentProvider.call() requires an explicit permission check even with a manifest gate.
            context.enforceCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    "Privileged profile metadata access required");
        }
        return context;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = authorizedContext();
        if ("avatar_status".equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            result.putBoolean("avatar", DeviceProfileStore.avatarFile(context).isFile());
            result.putLong("revision", DeviceProfileStore.preferences(context)
                    .getLong(DeviceProfileStore.AVATAR_REVISION, 0));
            return result;
        }
        boolean stored;
        if ("clear".equals(method)) {
            stored = DeviceProfileStore.clear(context);
        } else if ("set".equals(method)) {
            String name, account;
            if (arg != null && !arg.isEmpty()) {
                if (arg.length() > 2048) throw new IllegalArgumentException("Metadata payload too long");
                try {
                    JSONObject data = new JSONObject(arg);
                    name = data.optString(DeviceProfileStore.NAME, "");
                    account = data.optString(DeviceProfileStore.ACCOUNT, "");
                } catch (JSONException invalid) {
                    throw new IllegalArgumentException("Invalid metadata payload");
                }
            } else {
                name = extras == null ? "" : extras.getString(DeviceProfileStore.NAME, "");
                account = extras == null ? "" : extras.getString(DeviceProfileStore.ACCOUNT, "");
            }
            stored = DeviceProfileStore.set(context, name, account);
        } else {
            throw new IllegalArgumentException("Unsupported profile method");
        }
        Bundle result = new Bundle();
        result.putBoolean("ok", stored);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        Context context = authorizedContext();
        if (values == null) throw new IllegalArgumentException("Metadata payload required");
        boolean stored = DeviceProfileStore.set(context, values.getAsString(DeviceProfileStore.NAME),
                values.getAsString(DeviceProfileStore.ACCOUNT));
        return stored ? Uri.parse("content://" + context.getPackageName() + ".profile") : null;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return insert(uri, values) != null ? 1 : 0;
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        return DeviceProfileStore.clear(authorizedContext()) ? 1 : 0;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("Profile reads are not available");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = authorizedContext();
        if (!"/avatar".equals(uri.getPath())) throw new FileNotFoundException("Unknown profile image");
        if ("r".equals(mode)) return ParcelFileDescriptor.open(DeviceProfileStore.avatarFile(context),
                ParcelFileDescriptor.MODE_READ_ONLY);
        if (!"w".equals(mode) && !"wt".equals(mode) && !"rwt".equals(mode))
            throw new FileNotFoundException("Unsupported image mode");
        try {
            File pending = File.createTempFile("profile-avatar-", ".tmp", context.getCacheDir());
            String account = DeviceProfileStore.preferences(context).getString(DeviceProfileStore.ACCOUNT, "");
            return ParcelFileDescriptor.open(pending, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE,
                    new Handler(Looper.getMainLooper()), error -> {
                        try {
                            if (error != null || pending.length() == 0 || pending.length() > 2 * 1024 * 1024)
                                return;
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeFile(pending.getPath(), bounds);
                            boolean image = "image/jpeg".equals(bounds.outMimeType)
                                    || "image/png".equals(bounds.outMimeType);
                            if (!image || bounds.outWidth <= 0 || bounds.outHeight <= 0
                                    || bounds.outWidth > 2048 || bounds.outHeight > 2048) return;
                            DeviceProfileStore.publishAvatar(context, pending, account);
                        } catch (RuntimeException invalidImage) {
                            // Keep the previous validated image; never publish a partial/corrupt upload.
                        } finally {
                            pending.delete();
                        }
                    });
        } catch (IOException unavailable) {
            throw new FileNotFoundException("Profile image storage unavailable");
        }
    }

    @Override public String getType(Uri uri) { return "/avatar".equals(uri.getPath()) ? "image/*" : null; }
}
