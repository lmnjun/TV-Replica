package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order,
 * then empty slots up to a full row, see {@link DockSlots}), then the module cards the owner chose to show
 * (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        boolean onTileActionsRequested(int position);

        /** Return true after opening launcher settings with the activity's result callback. */
        default boolean onLauncherSettingsRequested(){return false;}

        void onLibraryRequested();
        void onLibraryFocused();
        void onLibraryUp();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3, INPUT = 4, LAUNCHER_SETTINGS = 5;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) (cell - 12 * density);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return itemHeightPx(tileHeightPx, true, density);
    }

    /** The same for either layout: the plain grid (dock_row false) uses the compact spacing. */
    static int itemHeightPx(int tileHeightPx, boolean dockRow, float density) {
        return tileHeightPx + Math.round((dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density);
    }

    /**
     * dock_row false: 4dp padding above and below, a 4dp gap and a 20dp name label, and 16dp kept under the last
     * row. The plain grid fits more rows on the screen, and with item_app.xml's roomier spacing the tiles came
     * out small with wide gaps between them.
     */
    static final float COMPACT_ITEM_EXTRA_DP = 4 + 4 + 4 + 20;
    static final float COMPACT_BOTTOM_ROOM_DP = 16;

    /** item_app.xml: 8dp padding above and below, a 7dp gap and the 22dp name label under the tile. */
    static final float ITEM_EXTRA_DP = 20;
    /** DockDecoration's offsets above and below the dock row. */
    static final float DOCK_EXTRA_DP = 0;
    /** Room kept under the last row rows_below_dock asks for (MainActivity places the dock with the same). */
    static final float BOTTOM_ROOM_DP = 8;
    /** Room kept above the dock for the dock title (48sp hero name plus its 40dp gap) when dock_title is on. */
    static final float TITLE_ROOM_DP = 104;
    /** dock_row false: room above the first row for its zoom and glass (MainActivity places the grid with it). */
    static final float TOP_ROOM_DP = 12;

    /**
     * Tile width that fits the columns across and, with rows_below_dock > 0, the dock (with its title above it,
     * if shown) plus that many rows down the grid's height; the smaller wins. With rows_below_dock = 0, or before
     * the grid has a height, only the width counts, as before. Without this, 2 or 3 rows below the dock did not
     * fit: the dock was pushed to the top and its title drew over the clock. With dock_row false there is no band
     * or title to make room for: the first row plus rows_below_dock rows fit under a small top margin.
     */
    static int tileWidthPx(int innerWidthPx, int gridHeightPx, int columns, int rowsBelowDock, boolean dockTitle,
                           boolean dockRow, float density) {
        int w = tileWidthPx(innerWidthPx, columns, density);
        if (gridHeightPx <= 0 || rowsBelowDock <= 0) return w;
        float reserved = (dockRow ? DOCK_EXTRA_DP + BOTTOM_ROOM_DP + (dockTitle ? TITLE_ROOM_DP : 0)
                : TOP_ROOM_DP + COMPACT_BOTTOM_ROOM_DP) * density;
        float perItem = (gridHeightPx - reserved) / (rowsBelowDock + 1);
        float tileH = perItem - (dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density;
        int byHeight = Math.round(tileH * DESIGN_W_DP / DESIGN_H_DP);
        // Shrink at most to MIN_HEIGHT_FIT of the width-only size. Below that the tiles look lost in their wide
        // columns (2 rows under a titled dock came out at 80dp); the rows that still don't fit run off the bottom
        // and scroll, and MainActivity keeps the title's room above the dock.
        return Math.max(Math.max(Math.round(80 * density), Math.round(w * MIN_HEIGHT_FIT)), Math.min(w, byHeight));
    }

    /** The smallest share of the width-only tile size that fitting rows_below_dock into the height may go to. */
    static final float MIN_HEIGHT_FIT = 0.85f;

    private final Context ctx;
    private final int rowsBelowDock;
    private final boolean dockTitle;
    /** The owner's saved order (reorder button, Order): key -> rank. Empty when there is none or it no longer applies. */
    private Map<String, Integer> savedRanks = Collections.emptyMap();
    private Order layoutOrder;
    private final Map<Integer,HdmiInputs.Port> inputStates=new HashMap<>();
    private static final Object INPUT_STATUS_PAYLOAD=new Object();
    private static final Object EDIT_STATE_PAYLOAD=new Object();
    /** Inline layout editing keeps every fixed Dock slot available as a placement target. */
    private boolean reordering;
    private final TileListener listener;
    private final int dockSize;
    /** config dock_row: false makes the dock apps the first tiles of one plain grid (names shown, no empty slots). */
    private final boolean dockRow;
    /** config always_show_names: the alpha of an unfocused tile's name (0 hides it). */
    private final float restNameAlpha;
    /** cinematic_home: only then does a launch record the last-opened app the shelf uses. */
    private final boolean cinematic;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** Package to the action its tile sends instead of the start screen (config.xml, launch_actions). */
    private final Map<String, String> launchActions;
    /** Tile names and bundled banners that replace the app's own (config.xml, app_labels and app_banners). */
    private final Map<String, String> appLabels, appBanners;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.showLibrary = cfg.appLibrary;
        this.reducedMotion = cfg.reducedMotion;
        this.rowsBelowDock = cfg.rowsBelowDock;
        this.dockTitle = cfg.dockTitle;
        this.dockRow = cfg.dockRow;
        this.restNameAlpha = cfg.alwaysShowNames ? 0.75f : 0f;
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        // The LEANBACK query is shared with the module-card check (MainActivity); copy it before adding
        // library apps so the caller's list stays as it was.
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        Set<String> added = LibraryApps.load(ctx);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> seen = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported
                    || !LibraryApps.visible(tvPackages.contains(pkg), hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117).
            String label = card != null ? card.label : Cards.cleanLabel(
                    appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = bundledBanner(appBanners.get(pkg));
            if (banner == null) banner = loadBanner(pm, ri);
            if (nativeLabelCard(pkg)) banner = null;
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(label, component, banner, icon));
            }
        }
        for (int port=1;port<=3;port++) fresh.add(new Entry(Entry.INPUT, "HDMI " + port,
                new ComponentName("tv.input.hdmi"+port, "Port"+port), null, ctx.getDrawable(R.drawable.ic_input), null, 0));
        fresh.add(new Entry(Entry.LAUNCHER_SETTINGS,"Settings",new ComponentName(ctx,LauncherSettingsActivity.class),
                null,ctx.getDrawable(R.drawable.ic_settings_outline),null,0));
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the rule below.
            int saved = Order.compare(savedRanks, a.component.flattenToShortString(), b.component.flattenToShortString());
            if (saved != 0) return saved;
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        docked = 0;
        while (docked < fresh.size() && favourites.contains(fresh.get(docked).pkg)) docked++;
        emptySlots = dockRow ? DockSlots.empty(docked, dockSize, fresh.size(), fillDock) : 0;
        for (int i = 0; i < emptySlots; i++) {
            fresh.add(docked + i, emptyDockEntry(docked+i));
        }
        if(layoutOrder!=null&&layoutOrder.dockSlots!=null)fresh=explicitLayout(fresh);
        items.clear();
        items.addAll(fresh);
        readInputStates();
        notifyDataSetChanged();
    }

    /** V2 slots are authoritative; neither a favourite prefix nor later installs fill an explicit gap. */
    private List<Entry> explicitLayout(List<Entry> discovered){
        return composeLayout(discovered,layoutOrder.dockSlots,layoutOrder.apps);
    }
    private List<Entry> composeLayout(List<Entry> discovered,List<String> slots,List<String> apps){
        Map<String,Entry> available=new java.util.LinkedHashMap<>();
        Entry home=null;
        for(Entry entry:discovered){
            if(entry.kind==Entry.HOME_SETTINGS){home=entry;continue;}
            if(entry.kind!=Entry.EMPTY)available.put(entry.component.flattenToShortString(),entry);
        }
        List<String> keys=layoutKeys(new ArrayList<>(available.keySet()),slots,apps,dockSize,dockRow);
        List<Entry> result=new ArrayList<>();
        for(int i=0;i<keys.size();i++)result.add(keys.get(i)==null?emptyDockEntry(i):available.get(keys.get(i)));
        docked=0;emptySlots=0;
        for(int i=0;i<Math.min(dockSize,result.size());i++){
            if(result.get(i).kind==Entry.EMPTY)emptySlots++;else docked++;
        }
        if(home!=null)result.add(home);
        return result;
    }
    private Entry emptyDockEntry(int slot){return new Entry(Entry.EMPTY,"",new ComponentName(ctx,"empty-dock-slot-"+slot),null,null,null,0);}
    /** Key-only mapping is shared by stored layouts and previews, including column-count changes. */
    static List<String> layoutKeys(List<String> available,List<String> slots,List<String> apps,int dockSize,boolean dockRow){
        Set<String> known=new HashSet<>(available),used=new HashSet<>();List<String> result=new ArrayList<>();
        List<String> dock=slots==null?Collections.emptyList():slots;
        if(dockRow){
            for(int slot=0;slot<Math.max(0,dockSize);slot++){
                String key=slot<dock.size()?dock.get(slot):null;
                result.add(key!=null&&known.contains(key)&&used.add(key)?key:null);
            }
            for(int i=Math.max(0,dockSize);i<dock.size();i++)appendKey(result,known,used,dock.get(i));
        }else for(String key:dock)appendKey(result,known,used,key);
        if(apps!=null)for(String key:apps)appendKey(result,known,used,key);
        for(String key:available)appendKey(result,known,used,key);
        return result;
    }
    private static void appendKey(List<String> result,Set<String> known,Set<String> used,String key){
        if(key!=null&&known.contains(key)&&used.add(key))result.add(key);
    }

    /** Pure in-memory preview: reuse artwork and input state, retain gaps, and animate only real changes. */
    void previewLayout(LayoutDraft layout){
        if(layout==null)return;
        final List<Entry> previous=new ArrayList<>(items);
        final List<Entry> next=composeLayout(previous,layout.dockSlots(),layout.apps());
        DiffUtil.DiffResult diff=DiffUtil.calculateDiff(new DiffUtil.Callback(){
            @Override public int getOldListSize(){return previous.size()+(showLibrary?1:0);}
            @Override public int getNewListSize(){return next.size()+(showLibrary?1:0);}
            @Override public boolean areItemsTheSame(int oldPosition,int newPosition){
                boolean oldFooter=oldPosition==previous.size(),newFooter=newPosition==next.size();
                if(oldFooter||newFooter)return oldFooter&&newFooter;
                return previous.get(oldPosition).component.equals(next.get(newPosition).component);
            }
            @Override public boolean areContentsTheSame(int oldPosition,int newPosition){
                if(oldPosition==previous.size())return true;
                Entry old=previous.get(oldPosition),fresh=next.get(newPosition);
                return old.kind==fresh.kind&&old.label.equals(fresh.label)&&old.name.equals(fresh.name)
                        &&old.banner==fresh.banner&&old.icon==fresh.icon
                        &&(!dockRow||(oldPosition<dockSize)==(newPosition<dockSize))
                        &&(!reordering||!dockRow||newPosition>=dockSize||oldPosition==newPosition);
            }
        },true);
        items.clear();items.addAll(next);diff.dispatchUpdatesTo(this);
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    private static boolean nativeLabelCard(String pkg) { return pkg.startsWith("com.tcl."); }
    /** Keep system-app artwork consistent in Home and the shared layout editor. */
    static int nativeGlyph(Entry e) {
        if(e!=null&&e.kind==Entry.LAUNCHER_SETTINGS)return R.drawable.ic_settings_outline;
        if (e == null || !nativeLabelCard(e.pkg)) return 0;
        return "com.tcl.ui_mediaCenter".equals(e.pkg) ? R.drawable.ic_liquid_media
                : "com.tcl.browser".equals(e.pkg) ? R.drawable.ic_liquid_browser
                : "com.tcl.gallery".equals(e.pkg) ? R.drawable.ic_liquid_gallery
                : "com.tcl.MultiScreenInteraction_TV".equals(e.pkg) ? R.drawable.ic_liquid_cast
                : e.label.toLowerCase(java.util.Locale.ROOT).contains("solo") ? R.drawable.ic_liquid_music
                : e.label.toLowerCase(java.util.Locale.ROOT).contains("quick") ? R.drawable.ic_liquid_layers : 0;
    }


    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.row_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setOnClickListener(v -> listener.onLibraryRequested());
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        LayoutInflater.from(parent.getContext()).inflate(R.layout.hdmi_tile_content,(ViewGroup)v.findViewById(R.id.card),true);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, parent.getHeight(), dockSize, rowsBelowDock, dockTitle, dockRow, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round((dockRow ? 7 : 4) * density);
        if (!dockRow) {
            // Compact spacing (COMPACT_ITEM_EXTRA_DP): 4dp item padding and a 20dp name instead of 8dp and 22dp.
            nameLp.height = Math.round(20 * density);
            int pad = Math.round(4 * density);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        }
        name.setLayoutParams(nameLp);
        if (restNameAlpha == 0f) {
            name.setVisibility(View.GONE);
            ViewGroup.LayoutParams itemLp = v.getLayoutParams();
            itemLp.height = itemHeightPx(h, dockRow, density);
            v.setLayoutParams(itemLp);
        }
        return new Holder(v, listener, animations, advancedEffects, reducedMotion, restNameAlpha);
    }

    @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder instanceof Holder) {
            Holder tile = (Holder)holder;
            tile.cacheAttached = true; updateCardLayer(tile);
        }
    }

    @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof Holder) {
            Holder tile = (Holder)holder;
            tile.cacheAttached = false; updateCardLayer(tile);
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof Holder) {
            Holder tile = (Holder)holder;
            tile.cacheAttached = false; tile.cacheEligible = false; updateCardLayer(tile);
        }
        super.onViewRecycled(holder);
    }

    /** Cache static, multi-part artwork only while attached; the animated name is outside this card. */
    private static void updateCardLayer(Holder tile) {
        int layer = tile.cacheAttached && tile.cacheEligible ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (tile.card.getLayerType() != layer) tile.card.setLayerType(layer, null);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isLibraryAt(position)) return;
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY) {
            bindEmptySlot(h,position);
            return;
        }
        h.cacheEligible = e.banner == null || e.kind == Entry.INPUT || nativeLabelCard(e.pkg);
        updateCardLayer(h);
        h.card.setVisibility(View.VISIBLE);
        h.hdmiContent.setVisibility(View.GONE);
        h.card.setFocusable(true);
        h.inputCaption=e.kind==Entry.INPUT;
        h.name.setText(e.name);
        h.name.setTextSize(h.inputCaption?11:14);
        InterTypography.apply(h.name,500);
        ViewGroup.MarginLayoutParams nameLayout=(ViewGroup.MarginLayoutParams)h.name.getLayoutParams();
        float density=ctx.getResources().getDisplayMetrics().density;
        nameLayout.topMargin=h.card.getLayoutParams().height+Math.round((h.inputCaption?3:dockRow?7:4)*density);
        nameLayout.height=Math.round((h.inputCaption?14:22)*density);h.name.setLayoutParams(nameLayout);
        if(h.inputCaption)h.name.setTranslationY(0);
        // The dock has no labels, unless always_show_names asks for them (DockDecoration then makes room in the band).
        h.name.setVisibility(h.inputCaption||restNameAlpha==0f ? View.GONE : View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : restNameAlpha);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(0f);
        h.card.setTranslationZ(0f);
        h.card.setElevation(0f);
        h.banner.setScaleX(1f);
        h.banner.setScaleY(1f);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        if (e.kind == Entry.INPUT) {
            h.card.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xFF16202C,0xFF35465B}));
        } else if(e.kind==Entry.LAUNCHER_SETTINGS){
            h.card.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xFF4F4F4F,0xFF888888}));
        } else if (nativeLabelCard(e.pkg)) {
            int color = "com.tcl.MultiScreenInteraction_TV".equals(e.pkg) ? 0xFFBA413A : e.label.toLowerCase().contains("solo") ? 0xFF7730BF : e.label.toLowerCase().contains("quick") ? 0xFF566A85 : 0xFF345CE6;
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{color, "com.tcl.MultiScreenInteraction_TV".equals(e.pkg) ? 0xFFE86B52 : e.label.toLowerCase().contains("solo") ? 0xFFAE66F1 : e.label.toLowerCase().contains("quick") ? 0xFF91A2BB : 0xFF6390FF});
            h.card.setBackground(fill);
        } else h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            if (nativeLabelCard(e.pkg)) {
                int glyph = nativeGlyph(e);
                if(glyph!=0)h.icon.setImageResource(glyph);else h.icon.setImageDrawable(e.icon);
                h.icon.setAlpha(.90f);
            } else { h.icon.setImageDrawable(e.icon); h.icon.setAlpha(1f); }
            h.icon.setVisibility(View.VISIBLE);
            android.widget.FrameLayout.LayoutParams iconLp=(android.widget.FrameLayout.LayoutParams)h.icon.getLayoutParams();
            boolean centered=nativeLabelCard(e.pkg)||e.kind==Entry.LAUNCHER_SETTINGS;
            iconLp.bottomMargin=centered?0:Math.round(14*ctx.getResources().getDisplayMetrics().density);
            iconLp.width=iconLp.height=Math.round((centered?36:30)*ctx.getResources().getDisplayMetrics().density);
            h.icon.setLayoutParams(iconLp);
            h.label.setText(e.label);InterTypography.apply(h.label,500);
            h.label.setVisibility(centered?View.GONE:View.VISIBLE);
        }
        h.card.setContentDescription(e.label);
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos=h.getBindingAdapterPosition();
            if(listener!=null&&pos!=RecyclerView.NO_POSITION&&listener.onTileActionsRequested(pos))return true;
            if(reordering||e.kind==Entry.INPUT)return true;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
        bindEditState(h,e,position);
    }

    private void bindEmptySlot(Holder holder,int position){
        holder.cacheEligible = false; updateCardLayer(holder);
        holder.card.animate().cancel();holder.banner.animate().cancel();holder.name.animate().cancel();
        holder.inputCaption=false;
        holder.card.setVisibility(reordering?View.VISIBLE:View.INVISIBLE);
        holder.card.setFocusable(reordering);holder.card.setClickable(reordering);
        holder.card.setScaleX(1f);holder.card.setScaleY(1f);holder.card.setTranslationY(0f);holder.card.setTranslationZ(0f);holder.card.setElevation(0f);
        holder.hdmiContent.setVisibility(View.GONE);holder.name.setVisibility(View.INVISIBLE);
        holder.banner.setImageDrawable(null);holder.banner.setVisibility(View.GONE);holder.label.setVisibility(View.GONE);
        holder.icon.setAlpha(1f);holder.icon.setImageResource(R.drawable.ic_add);
        holder.icon.setVisibility(reordering?View.VISIBLE:View.GONE);
        android.widget.FrameLayout.LayoutParams iconLayout=(android.widget.FrameLayout.LayoutParams)holder.icon.getLayoutParams();
        iconLayout.bottomMargin=0;iconLayout.width=iconLayout.height=Math.round(27*ctx.getResources().getDisplayMetrics().density);
        holder.icon.setLayoutParams(iconLayout);
        holder.card.setBackgroundColor(0xFF292929);
        holder.card.setContentDescription("Empty Dock slot "+(position+1)+(reordering?" · Drop app here":""));
        holder.card.setOnClickListener(reordering?v->{ }:null);holder.card.setOnLongClickListener(null);
        holder.card.setClickable(reordering);holder.card.setLongClickable(false);
    }
    private void bindEditState(Holder holder,Entry entry,int position){
        if(entry.kind==Entry.EMPTY){bindEmptySlot(holder,position);return;}
        if(entry.kind==Entry.INPUT)bindInputStatus(holder,entry);
        else holder.card.setContentDescription(entry.label);
        if(reordering){
            CharSequence description=holder.card.getContentDescription();
            holder.card.setContentDescription(description+(entry.kind==Entry.HOME_SETTINGS?" · Fixed shortcut"
                    :dockRow&&position<dockSize?" · Dock slot "+(position+1)+" · Arrange app":" · Arrange app"));
        }
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position,@NonNull List<Object> payloads){
        if(!payloads.isEmpty()&&!isLibraryAt(position)){
            boolean stateOnly=true;
            for(Object payload:payloads)if(payload!=INPUT_STATUS_PAYLOAD&&payload!=EDIT_STATE_PAYLOAD){stateOnly=false;break;}
            if(stateOnly){bindEditState((Holder)holder,items.get(position),position);return;}
        }
        super.onBindViewHolder(holder,position,payloads);
    }
    /** Main-thread hook for resume and TvInputCallback state/info changes; never rebuilds the app order. */
    void refreshInputStates(){
        Map<Integer,HdmiInputs.Port> previous=new HashMap<>(inputStates);readInputStates();
        for(int i=0;i<items.size();i++)if(items.get(i).kind==Entry.INPUT){
            int port=portOf(items.get(i));
            if(!java.util.Objects.equals(previous.get(port),inputStates.get(port)))notifyItemChanged(i,INPUT_STATUS_PAYLOAD);
        }
    }
    private void readInputStates(){inputStates.clear();for(HdmiInputs.Port port:HdmiInputs.read(ctx))inputStates.put(port.number,port);}
    private static int portOf(Entry entry){return entry.pkg.charAt(entry.pkg.length()-1)-'0';}
    private void bindInputStatus(Holder holder,Entry entry){
        int number=portOf(entry);HdmiInputs.Port port=inputStates.get(number);
        if(port==null)port=new HdmiInputs.Port(number,HdmiInputs.UNKNOWN,"",null,null);
        holder.hdmiContent.setVisibility(View.VISIBLE);holder.banner.setVisibility(View.GONE);holder.icon.setVisibility(View.GONE);holder.label.setVisibility(View.GONE);
        holder.hdmiTitle.setVisibility(View.GONE);
        HdmiDeviceKind deviceKind=port.deviceKind;
        boolean logoOnly=port.state==android.media.tv.TvInputManager.INPUT_STATE_CONNECTED&&deviceKind!=HdmiDeviceKind.UNKNOWN;
        holder.hdmiDeviceIcon.setImageResource(hdmiDeviceGlyph(deviceKind));
        holder.hdmiDeviceIcon.setVisibility(logoOnly?View.VISIBLE:View.GONE);
        android.widget.FrameLayout.LayoutParams logoLayout=(android.widget.FrameLayout.LayoutParams)holder.hdmiDeviceIcon.getLayoutParams();
        logoLayout.bottomMargin=0;holder.hdmiDeviceIcon.setLayoutParams(logoLayout);
        holder.hdmiGenericWordmark.setText(port.title);
        holder.hdmiGenericWordmark.setVisibility(logoOnly?View.GONE:View.VISIBLE);
        holder.hdmiStatusPill.setVisibility(logoOnly?View.GONE:View.VISIBLE);
        holder.hdmiState.setText(HdmiInputs.stateLabel(port.state));
        holder.name.setVisibility(View.GONE);
        holder.card.setBackground(hdmiCardBackground(deviceKind));
        android.graphics.drawable.GradientDrawable dot=new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);dot.setColor(HdmiInputs.stateColor(port.state));holder.hdmiDot.setBackground(dot);
        holder.card.setContentDescription(port.description());
    }

    private static android.graphics.drawable.GradientDrawable hdmiCardBackground(HdmiDeviceKind kind){
        int top,bottom;
        switch(kind){
            case SWITCH:top=0xFFE60012;bottom=0xFFAF0010;break;
            case XBOX:top=0xFF107C10;bottom=0xFF075F19;break;
            case PLAYSTATION:top=0xFF003791;bottom=0xFF071F5E;break;
            case APPLE_TV:top=0xFF24272C;bottom=0xFF0D1015;break;
            case PC:top=0xFF53697F;bottom=0xFF293B50;break;
            default:top=0xFF303030;bottom=0xFF1B1B1B;
        }
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
    }

    private static int hdmiDeviceGlyph(HdmiDeviceKind deviceKind){
        switch(deviceKind){
            case SWITCH:return R.drawable.ic_hdmi_device_switch;
            case XBOX:return R.drawable.ic_hdmi_device_xbox;
            case PLAYSTATION:return R.drawable.ic_hdmi_device_playstation;
            case APPLE_TV:return R.drawable.ic_hdmi_device_apple_tv;
            case PC:return R.drawable.ic_hdmi_device_computer;
            default:return R.drawable.ic_hdmi_device_unknown;
        }
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if(reordering||e.kind==Entry.EMPTY)return;
        if(e.kind==Entry.LAUNCHER_SETTINGS){
            if(listener!=null&&listener.onLauncherSettingsRequested())return;
            ctx.startActivity(new Intent(ctx,LauncherSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
        }
        if (e.kind == Entry.INPUT && ctx instanceof MainActivity) {
            ((MainActivity)ctx).openHdmiPort(Integer.parseInt(e.pkg.substring(e.pkg.length()-1)));return;
        }
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        if (action != null) {
            Intent byAction = new Intent(action).setPackage(e.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (cinematic) LastOpened.record(ctx, e.pkg);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (showLibrary ? 1 : 0);
    }

    int appCount() { return items.size(); }

    boolean isLibraryAt(int position) { return showLibrary && position == items.size(); }

    @Override public int getItemViewType(int position) { return isLibraryAt(position) ? 1 : 0; }

    boolean containsPackage(String pkg) {
        for (Entry entry : items) if (entry.pkg.equals(pkg)) return true;
        return false;
    }

    @Override
    public long getItemId(int position) {
        return isLibraryAt(position) ? Long.MIN_VALUE : items.get(position).component.flattenToShortString().hashCode();
    }

    /** Uses this saved order from the next reload() (Order.ranks). */
    void setSavedOrder(Map<String, Integer> ranks) {
        layoutOrder=null;savedRanks = ranks;
    }
    /** Accept only a layout recorded for the current configured Dock signature. */
    void setLayoutOrder(Order order){
        layoutOrder=order!=null&&order.dock.equals(Order.dockKey(favourites))?order:null;
        savedRanks=Order.ranks(layoutOrder,favourites);
    }
    List<String> layoutDockKeys(){
        List<String> result=new ArrayList<>();if(!dockRow)return result;
        for(int i=0;i<dockSize;i++)result.add(i<items.size()&&items.get(i).kind!=Entry.EMPTY&&items.get(i).kind!=Entry.HOME_SETTINGS?keyAt(i):null);
        return result;
    }
    List<String> layoutAppKeys(){
        List<String> result=new ArrayList<>();
        for(int i=dockRow?Math.min(dockSize,items.size()):0;i<items.size();i++){
            Entry entry=items.get(i);if(entry.kind!=Entry.EMPTY&&entry.kind!=Entry.HOME_SETTINGS)result.add(keyAt(i));
        }
        return result;
    }

    /** Reorder state changes do not reload artwork, read storage, or compact fixed Dock gaps. */
    void setReordering(boolean on) {
        if(reordering==on)return;
        reordering = on;
        if(!items.isEmpty())notifyItemRangeChanged(0,items.size(),EDIT_STATE_PAYLOAD);
    }

    boolean hasEmptySlots() {
        return emptySlots > 0;
    }

    /** Tiles that can be moved: everything but the "Default home" tile, which always stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Keys of the movable tiles in their current order, for Order (empty dock slots are not tiles). */
    List<String> movableKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.EMPTY) keys.add(e.component.flattenToShortString());
        }
        return keys;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view (and its focus). */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock row hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Exclusive position of the last occupied Dock slot; gaps may be anywhere in a V2 Dock. */
    int dockFilled() {
        for(int i=Math.min(dockSize,items.size())-1;i>=0;i--)if(items.get(i).kind!=Entry.EMPTY&&items.get(i).kind!=Entry.HOME_SETTINGS)return i+1;
        return 0;
    }

    /** Resolve arbitrary gaps to the nearest actual tile, preferring another slot in the same row. */
    int focusablePosition(int position) {
        if(items.isEmpty())return 0;
        int wanted=Math.max(0,Math.min(position,items.size()-1));if(reordering||items.get(wanted).kind!=Entry.EMPTY)return wanted;
        int rowStart=(wanted/dockSize)*dockSize,rowEnd=Math.min(items.size(),rowStart+dockSize);
        for(int delta=1;delta<dockSize;delta++){
            if(wanted-delta>=rowStart&&items.get(wanted-delta).kind!=Entry.EMPTY)return wanted-delta;
            if(wanted+delta<rowEnd&&items.get(wanted+delta).kind!=Entry.EMPTY)return wanted+delta;
        }
        for(int delta=1;delta<items.size();delta++){
            if(wanted+delta<items.size()&&items.get(wanted+delta).kind!=Entry.EMPTY)return wanted+delta;
            if(wanted-delta>=0&&items.get(wanted-delta).kind!=Entry.EMPTY)return wanted-delta;
        }
        return 0;
    }

    List<Entry> editableEntries(){
        List<Entry> result=new ArrayList<>();
        for(Entry entry:items)if(entry.kind!=Entry.EMPTY&&entry.kind!=Entry.HOME_SETTINGS)result.add(entry);
        return result;
    }

    String labelAt(int position) {
        if(reordering&&position>=0&&position<items.size()&&items.get(position).kind==Entry.EMPTY)return "Empty Dock slot "+(position+1);
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind!=Entry.LAUNCHER_SETTINGS&&items.get(position).kind!=Entry.EMPTY ? items.get(position).pkg : null;
    }
    /** Focus-only text; card artwork stays simple while HDMI state remains available. */
    String focusDescriptionAt(int position){
        if(position<0||position>=items.size())return "";
        Entry entry=items.get(position);
        if(entry.kind==Entry.EMPTY)return reordering?"Empty Dock slot "+(position+1)+" · Drop app here":"";
        if(entry.kind==Entry.INPUT){
            HdmiInputs.Port port=inputStates.get(portOf(entry));
            if(port==null)return entry.label+" · Unknown";
            return (port.deviceName.isEmpty()?"":port.deviceName+" · ")+port.title+" · "+HdmiInputs.stateLabel(port.state);
        }
        return entry.kind==Entry.LAUNCHER_SETTINGS?"Settings · Appearance, motion and layout":entry.name;
    }

    /** The tile's artwork, its banner or else its icon, for the cinematic shelf's backdrop (HeroArt). */
    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    float focusScale() {
        return 1f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 12f * density;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final View hdmiContent,hdmiDot,hdmiStatusPill;
        final TextView hdmiTitle,hdmiState,hdmiGenericWordmark;
        final ImageView hdmiDeviceIcon;
        final TextView name;
        boolean inputCaption;
        boolean cacheEligible, cacheAttached;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        /** Alpha of the name while the tile is not focused (always_show_names). */
        final float restNameAlpha;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, float restNameAlpha) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            hdmiContent=v.findViewById(R.id.hdmiTileContent);hdmiDot=v.findViewById(R.id.hdmiTileDot);hdmiStatusPill=v.findViewById(R.id.hdmiTileStatusPill);
            hdmiGenericWordmark=v.findViewById(R.id.hdmiTileGenericWordmark);
            hdmiTitle=v.findViewById(R.id.hdmiTileTitle);hdmiState=v.findViewById(R.id.hdmiTileState);hdmiDeviceIcon=v.findViewById(R.id.hdmiTileDeviceIcon);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            card.setStateListAnimator(null);
            card.setDefaultFocusHighlightEnabled(false);
            card.setElevation(0f);

            final boolean[] heldOk={false};
            card.setOnKeyListener((view, keyCode, event) -> {
                if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)heldOk[0]=false;
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.isLongPress()){
                        if(!heldOk[0]){heldOk[0]=true;view.cancelLongPress();view.setPressed(false);view.performLongClick();}
                        return true;
                    }
                    if(event.getAction()==KeyEvent.ACTION_UP&&heldOk[0]){heldOk[0]=false;return true;}
                    return false;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();banner.animate().cancel();name.animate().cancel();
                // Selection changes only the outline: no lift, depth shadow, zoom or transform animator.
                view.setScaleX(1f);view.setScaleY(1f);view.setTranslationY(0f);view.setTranslationZ(0f);
                view.setElevation(0f);banner.setScaleX(1f);banner.setScaleY(1f);
                float targetAlpha=focused?1f:inputCaption?0f:restNameAlpha;
                name.setTranslationY(!advancedEffects||inputCaption?0f:focused?density*4f:-density*6f);
                if(animations&&name.getVisibility()==View.VISIBLE&&Math.abs(name.getAlpha()-targetAlpha)>.005f)
                    name.animate().alpha(targetAlpha).setDuration(reducedMotion?70:85).start();
                else name.setAlpha(targetAlpha);
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
