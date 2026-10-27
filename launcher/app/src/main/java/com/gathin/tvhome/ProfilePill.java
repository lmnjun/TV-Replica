package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real cached profile photo and name, with a neutral account fallback instead of invented initials. */
final class ProfilePill extends LinearLayout {
    private static final ExecutorService PHOTOS = Executors.newSingleThreadExecutor();
    private static final int PHOTO_EDGE = 160;
    private static volatile CachedPhoto latestPhoto;

    private static final class PhotoKey {
        final String path, account;
        final long revision, length;
        PhotoKey(String path, String account, long revision, long length) {
            this.path = path; this.account = account; this.revision = revision; this.length = length;
        }
        boolean matches(PhotoKey other) {
            return other != null && revision == other.revision && length == other.length
                    && path.equals(other.path) && account.equals(other.account);
        }
    }
    private static final class CachedPhoto {
        final PhotoKey key;
        final Bitmap bitmap;
        CachedPhoto(PhotoKey key, Bitmap bitmap) { this.key = key; this.bitmap = bitmap; }
        boolean matches(PhotoKey requested) { return key.matches(requested) && !bitmap.isRecycled(); }
    }
    private final ImageView avatar;
    private final TextView name;
    private final ConnectionBadge connectionBadge;
    private final ConnectionMonitor connectionMonitor;
    private final float density;
    private final SharedPreferences preferences;
    private PhotoKey avatarKey;
    private boolean avatarResolved;
    private int photoGeneration;
    private String avatarAccount = "", currentDisplayName = "Account", currentAccount = "";
    private long avatarMetadataRevision = Long.MIN_VALUE;
    private boolean sharedSurface, heroAppearance, menuAppearance, showConnection;
    private TextView heroDetail;
    private ConnectionMonitor.Snapshot connection;
    private final Runnable profileUpdate = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener updates = (prefs, key) -> {
        if (isAttachedToWindow()) { removeCallbacks(profileUpdate); post(profileUpdate); }
    };
    private final ConnectionMonitor.Listener connectionUpdates = this::connectionChanged;

    ProfilePill(Context context, Runnable clickAction) {
        this(context, clickAction, false);
    }

    /** Fresh menu identity, built once in its final shape without a disposable compact pill. */
    static ProfilePill forMenu(Context context, Runnable clickAction) {
        return new ProfilePill(context, clickAction, true);
    }

    private ProfilePill(Context context, Runnable clickAction, boolean menu) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        preferences = DeviceProfileStore.preferences(context);
        menuAppearance = heroAppearance = sharedSurface = menu;
        connectionMonitor = menu ? null : ConnectionMonitor.get(context);
        connection = connectionMonitor == null ? null : connectionMonitor.cachedSnapshot();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setMinimumHeight(dp(30));
        setFocusable(true);
        setClickable(true);
        if (!menu) { setPadding(dp(5), 0, dp(12), 0); setBackground(surface()); }
        avatar = new CircularAvatarView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name = new TextView(context);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFFFFFFFF, 0xD9FFFFFF}));
        name.setDuplicateParentStateEnabled(true);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (menu) {
            connectionBadge = null;
            addHeroChildren();
        } else {
            addView(avatar, new LinearLayout.LayoutParams(dp(24), dp(24)));
            name.setTextSize(11);
            // Keep the compact account label within the wallpaper header safe area.
            name.setMaxWidth(dp(156)); InterTypography.apply(name, 500);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-2, -2);
            label.setMarginStart(dp(8)); addView(name, label);
            connectionBadge = new ConnectionBadge(context);
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(dp(28), dp(28));
            badge.setMarginStart(dp(12)); addView(connectionBadge, badge);
            connectionBadge.setVisibility(GONE);
        }
        setOnClickListener(v -> { if (clickAction != null) clickAction.run(); });
        refresh();
    }

    void useSharedSurface(boolean shared) {
        sharedSurface = shared;
        setBackground(shared ? null : surface());
    }

    /** Only Home's compact pill displays the connection badge; menu identity rows remain simple. */
    void showConnectionBadge(boolean show) {
        if (connectionBadge == null) return;
        boolean visible = show && !heroAppearance;
        if (showConnection == visible) return;
        showConnection = visible; connectionBadge.setVisibility(visible ? VISIBLE : GONE);
        if (isAttachedToWindow()) {
            if (visible) connectionMonitor.subscribe(connectionUpdates);
            else connectionMonitor.unsubscribe(connectionUpdates);
        }
        connectionChanged(connectionMonitor.cachedSnapshot());
    }

    private void connectionChanged(ConnectionMonitor.Snapshot snapshot) {
        if (connectionBadge == null) return;
        connection = snapshot;
        boolean changed = connectionBadge.setSnapshot(snapshot);
        updateDescription();
        if (showConnection && changed) {
            // Re-record one immutable pill snapshot on a real status change, never every frame.
            if (getParent() instanceof HeaderPillContainer)
                ((HeaderPillContainer)getParent()).profileContentChanged();
            invalidate();
        }
    }

    private void updateDescription() {
        String description = menuAppearance ? "Current profile, " + currentDisplayName
                + (currentAccount.isEmpty() ? "" : ", " + currentAccount) : "Google profile: " + currentDisplayName;
        if (showConnection) description += ". Connection: " + connection.label
                + (connection.ssid.isEmpty() ? "" : ", " + connection.ssid) + ". " + connection.status;
        if (!TextUtils.equals(getContentDescription(), description)) setContentDescription(description);
    }

    void useMenuAppearance() {
        menuAppearance = true; useHeroAppearance(); refresh();
    }

    void useHeroAppearance() {
        if (heroAppearance) return;
        showConnectionBadge(false); heroAppearance = true;
        removeView(connectionBadge);
        removeView(avatar);
        removeView(name);
        addHeroChildren();
    }

    private void addHeroChildren() {
        // The circular photo extends into the row's start padding but stays inside its bounds.
        setClipToPadding(false);
        // Give the photo its full drawing bounds. Split the extra width into negative
        // margins so its layout column still matches the other rows' 21dp icons.
        int photoSize = dp(32), extra = photoSize - dp(21);
        LinearLayout.LayoutParams photo = new LinearLayout.LayoutParams(photoSize, photoSize);
        photo.setMarginStart(-(extra / 2));
        photo.setMarginEnd(-(extra - extra / 2));
        addView(avatar, photo);
        LinearLayout identity = new LinearLayout(getContext()); identity.setOrientation(VERTICAL);
        name.setTextSize(16); name.setMaxWidth(dp(180)); InterTypography.apply(name, 600);
        // Reset the compact pill's 8dp start margin rather than carrying it into this column.
        identity.addView(name, new LinearLayout.LayoutParams(-1, -2));
        heroDetail = new TextView(getContext());
        heroDetail.setTextSize(11); heroDetail.setTextColor(0xB8FFFFFF); heroDetail.setIncludeFontPadding(false);
        heroDetail.setSingleLine(true); heroDetail.setEllipsize(TextUtils.TruncateAt.END);
        InterTypography.apply(heroDetail, 400);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2); detailLp.topMargin = dp(4);
        identity.addView(heroDetail, detailLp);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(0, -2, 1); identityLp.setMarginStart(dp(12));
        addView(identity, identityLp); setPadding(dp(12), 0, dp(12), 0);
    }

    void avatarBounds(Rect bounds) {
        avatar.getDrawingRect(bounds);
        offsetDescendantRectToMyCoords(avatar, bounds);
    }

    void refresh() {
        DeviceProfileStore.Profile profile = DeviceProfileStore.read(getContext());
        currentDisplayName = profile.displayName();
        currentAccount = profile.account;
        if (!TextUtils.equals(name.getText(), currentDisplayName)) name.setText(currentDisplayName);
        String detail = menuAppearance ? profile.available() ? currentAccount : "Add a Google account" : "Current profile";
        if (heroDetail != null && !TextUtils.equals(heroDetail.getText(), detail)) heroDetail.setText(detail);
        updateDescription();
        setVisibility(VISIBLE);
        final String account = profile.account;
        final long revision = preferences.getLong(DeviceProfileStore.AVATAR_REVISION, Long.MIN_VALUE);
        CachedPhoto cached = latestPhoto;
        if (cached != null && (!cached.key.account.equals(account) || revision == 0
                || revision > 0 && cached.key.revision != revision)) { latestPhoto = null; cached = null; }
        if (cached != null && revision > 0 && cached.key.revision == revision && !cached.bitmap.isRecycled()) {
            // The private provider advances the revision for publication, removal and account changes.
            // A warm page needs no filesystem stat, decode or late fallback-to-photo replacement.
            if (!avatarResolved || !cached.key.matches(avatarKey)) {
                ++photoGeneration; avatarKey = cached.key; showPhoto(cached.bitmap);
            }
            avatarResolved = true; avatarAccount = account; avatarMetadataRevision = revision; return;
        }
        if (avatarResolved && account.equals(avatarAccount) && revision == avatarMetadataRevision) return;
        final int request = ++photoGeneration;
        avatarResolved = true; avatarAccount = account; avatarMetadataRevision = revision; avatarKey = null;
        // A different account/revision must never show the previous identity while loading.
        fallbackAvatar();
        if (account.isEmpty() || revision == 0) return;
        // Cold construction can precede attachment; start work on attach, not for abandoned pages.
        if (!isAttachedToWindow()) { avatarResolved = false; return; }
        Context application = getContext().getApplicationContext();
        PHOTOS.execute(() -> {
            File photo = DeviceProfileStore.avatarFile(application);
            PhotoKey requested = photoKey(photo, account, revision);
            CachedPhoto finished = latestPhoto;
            Bitmap image = requested == null ? null : finished != null && finished.matches(requested)
                    ? finished.bitmap : decodePhoto(photo);
            post(() -> {
                if (request != photoGeneration || !isAttachedToWindow()) return;
                if (!account.equals(preferences.getString(DeviceProfileStore.ACCOUNT, ""))
                        || revision != preferences.getLong(DeviceProfileStore.AVATAR_REVISION, Long.MIN_VALUE)) {
                    avatarKey = null; avatarResolved = false; refresh(); return;
                }
                if (requested == null || image == null) { fallbackAvatar(); return; }
                avatarKey = requested;
                CachedPhoto shared = latestPhoto;
                if (shared == null || !shared.matches(requested)) {
                    // Publish only a finished, current decode. Existing views may retain the old
                    // entry, so replacing this one-entry cache never recycles either bitmap.
                    shared = new CachedPhoto(requested, image); latestPhoto = shared;
                }
                showPhoto(shared.bitmap);
            });
        });
    }

    private static PhotoKey photoKey(File photo, String account, long revision) {
        if (account.isEmpty() || !photo.isFile()) return null;
        if (revision == Long.MIN_VALUE) revision = photo.lastModified();
        long length = photo.length();
        return revision > 0 && length > 0 ? new PhotoKey(photo.getAbsolutePath(), account, revision, length) : null;
    }

    private void showPhoto(Bitmap image) {
        avatar.setImageTintList(null); avatar.setImageBitmap(image); invalidate();
    }

    private static Bitmap decodePhoto(File photo) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getPath(), bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = false; options.inSampleSize = 1;
        while (longest / options.inSampleSize > PHOTO_EDGE * 2) options.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeFile(photo.getPath(), options);
        if (image == null) return null;
        longest = Math.max(image.getWidth(), image.getHeight());
        if (longest > PHOTO_EDGE) {
            float scale = PHOTO_EDGE / (float)longest;
            image = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                    Math.max(1, Math.round(image.getHeight() * scale)), true);
        }
        return image.isMutable() ? image.copy(Bitmap.Config.ARGB_8888, false) : image;
    }

    private void fallbackAvatar() {
        avatar.setImageResource(R.drawable.profile_account_fallback);
        avatar.setImageTintList(ColorStateList.valueOf(0xBFFFFFFF));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences.registerOnSharedPreferenceChangeListener(updates);
        refresh();
        if (showConnection) connectionMonitor.subscribe(connectionUpdates);
    }

    @Override protected void onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(updates);
        if (connectionMonitor != null) connectionMonitor.unsubscribe(connectionUpdates);
        removeCallbacks(profileUpdate);
        // Any decode still running must not update a detached/replaced profile view.
        photoGeneration++;
        avatarKey = null; avatarResolved = false;
        super.onDetachedFromWindow();
    }

    private StateListDrawable surface() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0x20484848, 0xC9FFFFFF));
        states.addState(new int[]{android.R.attr.state_hovered}, shape(0x18484848, 0x40FFFFFF));
        states.addState(new int[]{}, shape(0x0C181818, 0x24FFFFFF));
        return states;
    }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(15));
        shape.setStroke(Math.max(1, dp(stroke == 0xC9FFFFFF ? 1.2f : .5f)), stroke);
        return shape;
    }

    /** Explicit Canvas clipping also works when the pill is copied into a software bitmap. */
    private static final class CircularAvatarView extends ImageView {
        private final Path circle = new Path();
        CircularAvatarView(Context context) { super(context); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            circle.rewind();
            circle.addCircle(width * .5f, height * .5f, Math.min(width, height) * .5f, Path.Direction.CW);
        }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(circle);
            super.onDraw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private int dp(float value) { return Math.round(value * density); }
}
