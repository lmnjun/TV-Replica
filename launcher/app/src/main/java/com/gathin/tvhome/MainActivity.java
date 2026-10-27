package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.TextClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * TV home with a pinned Dock, an app grid, and an immediate, lightly faded selection outline.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, SceneSettingsPages.ExploreHost {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    private static final int GOOGLE_PROFILE_REQUEST = 43;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private SaturnScene saturnScene;
    private AmbientScenes ambientScenes;
    private EarthExploreState earthExploreState;
    private AmbientScenes earthExploreHost, earthExplorePreview;
    private TextView earthExploreHelp;
    private int earthGridVisibility, earthGridDescendants, earthGridFocusable, earthSkyVisibility;
    private boolean earthGridTouchFocus;
    private String earthReturnKey;
    private int earthReturnPosition;
    private SharedPreferences visualChoices;
    private String selectedScene;
    private boolean sceneForeground;
    private boolean menuWarmupAttempted;
    private int menuWarmupGeneration;
    private ViewTreeObserver menuWarmupObserver;
    private Runnable menuWarmupCommit, menuWarmupWork;
    private View.OnAttachStateChangeListener menuWarmupAttach;
    private final SharedPreferences.OnSharedPreferenceChangeListener visualPreferenceChanges = (prefs, key) -> {
        if (ScenePreferences.SCENE_KEY.equals(key)) applySelectedScene();
        else if (ClockFormat.KEY.equals(key)) applyClockFormat();
    };
    private WeatherWidget weatherWidget;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    private ImageView heroArt;
    private static final float HERO_ART_ALPHA = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    private ProfilePill profilePill;
    private HeaderPillContainer headerPill;
    private HeaderStackPopup headerControls;
    private boolean headerSettingsReloadPending;
    private ConnectionMonitor connectionMonitor;
    private TextView connectionName, connectionStatus, connectionAddress;
    private ProfileSwitcherDialog profileSwitchDialog;
    private LauncherSounds launcherSounds;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private static final int CARD_SCROLL_MS = 170;
    private int pendingTilePosition = RecyclerView.NO_POSITION;
    private boolean suppressPendingSelect;
    private boolean navigationMotion, overlayMotion;
    private final Runnable endNavigationMotion = () -> {
        navigationMotion = false;
        updateSceneMotion();
    };
    private float glassTargetX, glassTargetY;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    private final Rect headerSafeMeasure = new Rect(), gridHeaderClip = new Rect(), glassHeaderClip = new Rect();
    private int headerSafeBottomRoot, gridTopInRoot, gridSafeTop;
    private final View.OnLayoutChangeListener headerSafeLayout = (v, l, t, r, b, ol, ot, or, ob) -> updateHeaderSafeViewport();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): shorter fades and no entrance replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    private int layoutEditGeneration;
    /** All on-screen moves are previews until Done/Back commits this session. */
    private InlineArrangeSession arrangeSession;
    private RecyclerView.ItemAnimator normalItemAnimator;
    private boolean suppressArrangeConfirmUp;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    private View arrangeBar;
    private TextView arrangeTitle,arrangeHint,arrangeDone;
    private Runnable arrangeSelectionTracker;
    private AppLibraryDialog appLibrary;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The shared monitor publishes cached state; neither menu construction nor drawing queries networking. */
    private final ConnectionMonitor.Listener connectionUpdates = snapshot -> {
        updateWifiLabel();
        updateConnectionPage(snapshot);
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private void refreshInputTiles() {
        updateInputsButton();
        if (adapter != null) adapter.refreshInputStates();
        if (headerControls != null && headerControls.isShowing() && "Inputs".equals(headerControls.pageTitle()))
            headerControls.refreshPage();
    }
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String id) { refreshInputTiles(); }
        @Override public void onInputRemoved(String id) { refreshInputTiles(); }
        @Override public void onInputStateChanged(String id, int state) { refreshInputTiles(); }
        @Override public void onInputUpdated(String id) { refreshInputTiles(); }
        @Override public void onTvInputInfoUpdated(TvInputInfo info) { refreshInputTiles(); }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A window-local vote; the TV remains free to choose its supported display mode.
        WindowManager.LayoutParams homeWindow = getWindow().getAttributes();
        homeWindow.preferredRefreshRate = 60f;
        getWindow().setAttributes(homeWindow);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        launcherSounds = LauncherSounds.acquire(this);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        weatherWidget = new WeatherWidget(this, root);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientGlow = cfg.ambientGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        if (cinematic) setUpCinematicShelf();
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll only inside the header-safe viewport; dock tiles are left
             * to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int dy = HeaderSafeViewport.scrollDelta(top, bottom, gridSafeTop, getHeight(), getPaddingBottom());
                if (dy == 0) return false;
                if (!animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy, new android.view.animation.DecelerateInterpolator(1.3f), CARD_SCROLL_MS);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                LauncherSounds.muteSystemEffects(v);
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) grid.addItemDecoration(new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedLayout = Order.load(Order.file(this));
        Order.backupBeforeLayoutMigration(this, savedLayout);
        adapter.setLayoutOrder(savedLayout);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            updateHeaderSafeViewport();
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
                updateGlassHeaderClip();
            }
        });

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        iconOnlyButtons = cfg.iconOnlyButtons;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        btnInputs.setVisibility(View.GONE);
        btnWifi.setVisibility(View.GONE);
        btnReorder.setVisibility(View.GONE);
        btnLibraryTop.setVisibility(View.GONE);
        btnSettings.getLayoutParams().width = Math.round(30 * density);
        btnSettings.getLayoutParams().height = Math.round(30 * density);
        btnSettings.setPadding(Math.round(4 * density), 0, Math.round(4 * density), 0);
        ((TextView)btnSettings).setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        btnSettings.setBackgroundResource(R.drawable.workshop_control);
        profilePill = new ProfilePill(this, this::showProfileSwitcher);
        profilePill.setId(R.id.profilePill);
        RelativeLayout header = (RelativeLayout)btnSettings.getParent();
        headerPill = new HeaderPillContainer(this, profilePill, (SettingsPill)btnSettings);
        RelativeLayout.LayoutParams accountLayout = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(34 * density));
        accountLayout.addRule(RelativeLayout.ALIGN_PARENT_START);
        accountLayout.addRule(RelativeLayout.CENTER_VERTICAL);
        header.addView(headerPill, accountLayout);
        root.addOnLayoutChangeListener(headerSafeLayout);
        headerPill.addOnLayoutChangeListener(headerSafeLayout);
        weatherWidget.addOnLayoutChangeListener(headerSafeLayout);
        glass.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateGlassHeaderClip());
        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (v == profilePill && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        profilePill.setOnKeyListener(pillKeys);
        if (rtlMenuBar) {
            profilePill.setNextFocusLeftId(R.id.btnSettings);
            btnSettings.setNextFocusRightId(R.id.profilePill);
        } else {
            profilePill.setNextFocusRightId(R.id.btnSettings);
            btnSettings.setNextFocusLeftId(R.id.profilePill);
        }
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibraryTop, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }
        ((SettingsPill)btnSettings).configure();
        btnSettings.setOnFocusChangeListener((view,focused)->((SettingsPill)view).refresh());
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        connectionMonitor = ConnectionMonitor.get(this);
        connectionMonitor.subscribe(connectionUpdates);
        visualChoices = ScenePreferences.preferences(this);
        if (visualChoices.contains("live_tv_visible")) visualChoices.edit().remove("live_tv_visible").apply();
        if (!visualChoices.contains(ScenePreferences.SCENE_KEY)) visualChoices.edit()
                .putString(ScenePreferences.SCENE_KEY, cfg.saturnScene ? ScenePreferences.SATURN : ScenePreferences.THEME).apply();
        visualChoices.registerOnSharedPreferenceChangeListener(visualPreferenceChanges);
        applyClockFormat();
        applySelectedScene();
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
        LauncherSounds.muteWindowRoot(getWindow().getDecorView());
        runDeferredHeaderAction();
    }

    /** A scene change keeps Home, its menu stack and the owner's app layout alive. */
    private void applySelectedScene() {
        if (root == null || isFinishing()) return;
        String next = ScenePreferences.current(this);
        if (next.equals(selectedScene)) return;
        finishEarthExplore(false);
        String previous = selectedScene;
        AmbientScenes replacement = null;
        boolean saturn = ScenePreferences.SATURN.equals(next);
        try {
            // Prepare separately: a failed replacement must not destroy the visible scene.
            if (AmbientScenes.supports(next)) {
                replacement = new AmbientScenes(this, root);
                if (!replacement.select(next)) throw new IllegalStateException("Scene could not be prepared");
                if (sceneForeground) replacement.start();
            } else if (saturn) {
                if (saturnScene == null) saturnScene = new SaturnScene(this, root);
                saturnScene.setVisible(true);
                if (sceneForeground) saturnScene.start();
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            if (replacement != null) replacement.close();
            if (saturnScene != null && !ScenePreferences.SATURN.equals(previous)) saturnScene.setVisible(false);
            selectedScene = previous == null ? ScenePreferences.THEME : previous;
            if (visualChoices != null) visualChoices.edit().putString(ScenePreferences.SCENE_KEY, selectedScene).apply();
            wallpaperPlayer.setVisible(ScenePreferences.THEME.equals(selectedScene));
            android.util.Log.w("Home.Scene", "Keeping previous scene after failed selection: " + next, unavailable);
            Toast.makeText(this, "Scene unavailable", Toast.LENGTH_SHORT).show();
            updateClockVisibility();
            return;
        }
        if (saturnScene != null && !saturn) {
            if (saturnScene.exploring()) setSceneExplore(false);
            saturnScene.setVisible(false);
        }
        if (ambientScenes != null) ambientScenes.close();
        ambientScenes = replacement;
        selectedScene = next;
        boolean theme = ScenePreferences.THEME.equals(next);
        wallpaperPlayer.setVisible(theme);
        if (theme && sceneForeground) wallpaperPlayer.start();
        sky.setVisibility(theme ? View.VISIBLE : View.GONE);
        updateClockVisibility();
        android.util.Log.i("Home.Scene", "selected=" + next + " previous=" + previous);
    }

    private void applyClockFormat() {
        TextClock clock = findViewById(R.id.clock);
        if (clock != null) ClockFormat.apply(clock);
        if (saturnScene != null && ScenePreferences.SATURN.equals(selectedScene)) saturnScene.refreshClockFormat();
    }

    /** Prepare the same window's menu pipelines after its first real submitted frame. */
    private void prepareStartupMenu() {
        if (Build.VERSION.SDK_INT < 29 || menuWarmupAttempted || !sceneForeground || isFinishing()
                || menuWarmupCommit != null || menuWarmupWork != null || menuWarmupAttach != null) return;
        final int ticket = ++menuWarmupGeneration;
        if (!root.isAttachedToWindow()) {
            menuWarmupAttach = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    if (menuWarmupAttach != this || ticket != menuWarmupGeneration) return;
                    root.removeOnAttachStateChangeListener(this); menuWarmupAttach = null;
                    prepareStartupMenu();
                }
                @Override public void onViewDetachedFromWindow(View view) { }
            };
            root.addOnAttachStateChangeListener(menuWarmupAttach);
            return;
        }
        menuWarmupObserver = root.getViewTreeObserver();
        menuWarmupCommit = () -> {
            if (ticket != menuWarmupGeneration) return;
            menuWarmupCommit = null; menuWarmupObserver = null;
            menuWarmupWork = new Runnable() {
                @Override public void run() {
                    if (menuWarmupWork != this || ticket != menuWarmupGeneration) return;
                    menuWarmupWork = null;
                    if (!sceneForeground || isFinishing() || !root.isAttachedToWindow() || !root.hasWindowFocus()
                            || headerControls != null || (openPopup != null && openPopup.isShowing())
                            || (profileSwitchDialog != null && profileSwitchDialog.isShowing())
                            || (cardPrompt != null && cardPrompt.isShowing()) || reordering) return;
                    menuWarmupAttempted = true;
                    long begin = System.nanoTime();
                    boolean prepared = HeaderStackPopup.warmUpRenderer(MainActivity.this, root, headerPill,
                            p -> headerHome(p), p -> headerSettings(p));
                    android.util.Log.i("Menu.Perf", "phase=startup_warmup prepared=" + prepared
                            + " duration_ms=" + (System.nanoTime() - begin) / 1_000_000.0);
                }
            };
            root.post(menuWarmupWork);
        };
        menuWarmupObserver.registerFrameCommitCallback(menuWarmupCommit);
        root.invalidate();
    }

    private void cancelStartupMenu() {
        menuWarmupGeneration++;
        if (root != null) {
            if (menuWarmupWork != null) root.removeCallbacks(menuWarmupWork);
            if (menuWarmupAttach != null) root.removeOnAttachStateChangeListener(menuWarmupAttach);
        }
        if (Build.VERSION.SDK_INT >= 29 && menuWarmupObserver != null && menuWarmupObserver.isAlive()
                && menuWarmupCommit != null) menuWarmupObserver.unregisterFrameCommitCallback(menuWarmupCommit);
        menuWarmupObserver = null; menuWarmupCommit = null; menuWarmupWork = null; menuWarmupAttach = null;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        onLauncherSettingsRequested();
        return true;
    }

    @Override public boolean onLauncherSettingsRequested() {
        if (headerSettingsReloadPending) {
            // Preserve this request across the configuration refresh instead of opening a card
            // which onResume would immediately destroy. The new window resumes it when ready.
            getIntent().putExtra("header_after_reload", "settings");
            getIntent().removeExtra("header_action_key");
            headerSettingsReloadPending = false;
            recreate();
            return true;
        }
        HeaderStackPopup popup = ensureHeaderControls(p -> new LauncherSettingsStack(this, p, this::headerExternal,
                () -> showSaturnSettings(p)).show());
        if (popup == null) {
            if (!isFinishing()) root.postOnAnimation(this::onLauncherSettingsRequested);
            return true;
        }
        return true;
    }

    private void showProfileSwitcher() {
        if (profilePill != null) profilePill.requestFocusFromTouch();
        ensureHeaderControls();
    }

    @SuppressWarnings("deprecation")
    private void chooseGoogleProfile() {
        DeviceProfileStore.Profile profile = DeviceProfileStore.read(this);
        Account selected = profile.available() ? new Account(profile.account, "com.google") : null;
        Intent chooser = AccountManager.newChooseAccountIntent(selected, null, new String[]{"com.google"},
                true, "Choose a Google profile", null, null, null);
        boolean stockHomeEnabled;
        try {
            stockHomeEnabled = getPackageManager().getApplicationInfo("com.google.android.apps.tv.launcherx", 0).enabled;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            stockHomeEnabled = false;
        }
        if (!stockHomeEnabled || getPackageManager().resolveActivity(chooser, 0) == null) {
            // The public picker may resolve to a Play services trampoline which only discovers
            // the disabled Google TV chooser after launch. Avoid that trampoline as well.
            chooser.setComponent(new ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"));
        }
        try {
            startActivityForResult(chooser, GOOGLE_PROFILE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            open(systemIntent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean dispatchQuietly(KeyEvent event) {
        boolean handled = super.dispatchKeyEvent(event);
        return handled || LauncherSounds.handleUnhandledNavigation(getWindow().getDecorView(), event);
    }

    private HeaderStackPopup ensureHeaderControls() {
        return ensureHeaderControls(null);
    }

    private HeaderStackPopup ensureHeaderControls(Consumer<HeaderStackPopup> initialPage) {
        finishEarthExplore(false);
        if (reordering) endReorder();
        if (headerControls != null && headerControls.isShowing()) {
            if (initialPage != null) initialPage.accept(headerControls);
            return headerControls;
        }
        if (root.getWidth() <= 0 || root.getWindowToken() == null || isFinishing()) return null;
        cancelPendingFocusScroll(); grid.stopScroll();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null && openPopup.isShowing()) openPopup.dismiss();
        final HeaderStackPopup popup = new HeaderStackPopup(this, root, headerPill, animations, reducedMotion);
        headerControls = popup; openPopup = popup;
        popup.setTransitionListener(this::setOverlayMotionActive);
        popup.setMotionListener(progress -> {
            grid.setAlpha(1f - .72f * (float)progress);
            if (glassShown) { glass.animate().cancel(); glass.setAlpha(1f - (float)progress); }
        });
        if (initialPage == null) popup.setRoot("Home", () -> headerHome(popup));
        else {
            popup.setRootMetadata("Home", () -> headerHome(popup));
            initialPage.accept(popup);
        }
        popup.setOnDismissListener(() -> {
            grid.setAlpha(1);
            if (glassShown && grid.hasFocus()) glass.setAlpha(1);
            if (headerControls == popup) {
                headerControls = null;
                connectionName = connectionStatus = connectionAddress = null;
            }
            if (openPopup == popup) openPopup = null;
            if (headerPill != null) headerPill.refresh();
            if (popup.changed()) {
                if (popup.hasPendingAction() || popup.wasInterrupted()) headerSettingsReloadPending = true;
                else grid.post(() -> { if (started && !isFinishing()) recreate(); else headerSettingsReloadPending = true; });
            }
        });
        popup.show();
        return popup;
    }

    private View headerHome(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        ProfilePill current = ProfilePill.forMenu(this, () -> popup.dismissThen(this::chooseGoogleProfile));
        popup.control(current);
        current.setTag("Current profile");
        current.setOnKeyListener((v, key, event) -> key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP);
        LinearLayout.LayoutParams identity = new LinearLayout.LayoutParams(-1, (int)(64 * density));
        identity.bottomMargin = Math.round(8 * density); rows.addView(current, identity);
        popup.row(rows, R.drawable.profile_add_account, "Add profile", null,
                () -> popup.dismissThen(this::addGoogleProfile));
        ConnectionMonitor.Snapshot connection = connectionMonitor.cachedSnapshot();
        popup.row(rows, connection.transport == ConnectionMonitor.Transport.WIFI
                        ? R.drawable.ic_wifi : R.drawable.ic_ethernet, "Connection", null,
                () -> popup.push("Connection", () -> headerConnection(popup)));
        popup.row(rows, R.drawable.ic_input, "Input", null,
                () -> popup.push("Inputs", () -> headerInputs(popup)));
        popup.row(rows, R.drawable.ic_settings_outline, "All Settings", null,
                () -> popup.push("All Settings", () -> headerSettings(popup)));
        return rows;
    }

    private View headerConnection(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPaddingRelative(popup.textStartInset(), (int)(8 * density), popup.textEndInset(), (int)(16 * density));
        connectionName = popup.text("", 17, 600, 0xFFFFFFFF);
        connectionStatus = popup.text("", 11, 400, 0xBBFFFFFF);
        connectionAddress = popup.text("", 13, 500, 0xE6FFFFFF);
        summary.addView(connectionName, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
        statusLayout.topMargin = (int)(6 * density);
        summary.addView(connectionStatus, statusLayout);
        TextView addressLabel = popup.text("IP address", 10, 500, 0x99FFFFFF);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-1, -2);
        labelLayout.topMargin = (int)(18 * density);
        summary.addView(addressLabel, labelLayout);
        LinearLayout.LayoutParams addressLayout = new LinearLayout.LayoutParams(-1, -2);
        addressLayout.topMargin = (int)(5 * density);
        summary.addView(connectionAddress, addressLayout);
        rows.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        updateConnectionPage(connectionMonitor.cachedSnapshot());
        popup.row(rows, R.drawable.ic_settings_outline, "Network settings", null,
                () -> popup.dismissThen(this::openNetworkSettings));
        return rows;
    }

    private void openNetworkSettings() {
        Intent network = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        if (network.resolveActivity(getPackageManager()) == null)
            network = systemIntent(Settings.ACTION_WIFI_SETTINGS);
        open(network);
    }

    /** Update only the current page's copy; a link change must not restart its morph or move focus. */
    private void updateConnectionPage(ConnectionMonitor.Snapshot snapshot) {
        if (connectionName == null || headerControls == null
                || !"Connection".equals(headerControls.pageTitle())) return;
        setConnectionCopy(connectionName, snapshot.ssid.isEmpty() ? snapshot.label : snapshot.ssid);
        setConnectionCopy(connectionStatus, snapshot.status);
        setConnectionCopy(connectionAddress, snapshot.ipAddress.isEmpty() ? "—" : snapshot.ipAddress);
    }

    private void setConnectionCopy(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void addGoogleProfile() {
        try {
            startActivity(new Intent(Settings.ACTION_ADD_ACCOUNT)
                    .putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"}));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            open(systemIntent(Settings.ACTION_SETTINGS));
        }
    }

    private View headerSettings(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        popup.row(rows, R.drawable.ic_settings_outline, "TV settings", null,
                () -> headerExternal(systemIntent(Settings.ACTION_SETTINGS)));
        popup.row(rows, R.drawable.ic_input, "Inputs", null, this::showInputs);
        popup.row(rows, R.drawable.ic_wifi, "Wi-Fi", null,
                () -> headerExternal(systemIntent(Settings.ACTION_WIFI_SETTINGS)));
        popup.row(rows, ScenePreferences.icon(selectedScene), "Theme", ScenePreferences.title(selectedScene), this::showSaturnMenu);
        popup.row(rows, R.drawable.ic_picture, "Launcher Setting", null,
                () -> new LauncherSettingsStack(this, popup, this::headerExternal,
                        () -> showSaturnSettings(popup)).show());
        if (moduleCards) popup.row(rows, R.drawable.ic_apps, "Module cards", null, this::showModuleCards);
        popup.row(rows, R.drawable.ic_apps, "Arrange App Cards", "Move cards on Home",
                () -> headerLocalAction("arrange", lastFocusedKey, () -> showLayoutEditor(lastFocusedKey)));
        return rows;
    }

    private View headerInputs(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        for (HdmiInputs.Port port : HdmiInputs.read(this)) {
            String detail = HdmiInputs.stateLabel(port.state) + (port.deviceName.isEmpty() ? "" : " · " + port.deviceName);
            popup.row(rows, R.drawable.ic_input, port.title, detail, () -> {
                String input = HdmiInputs.resolveInputId(this, port.number);
                if (input != null) popup.dismissThen(() -> openInput(input));
                else if (!sourcePanelService.isEmpty()) popup.dismissThen(this::openSourcePanel);
                else Toast.makeText(this, port.title + " unavailable", Toast.LENGTH_SHORT).show();
            });
        }
        TvInputManager manager = (TvInputManager)getSystemService(TV_INPUT_SERVICE);
        for (TvInputInfo input : HdmiInputs.list(manager)) {
            if (input.getType() == TvInputInfo.TYPE_HDMI || !input.isPassthroughInput()) continue;
            try { if (input.isHidden(this)) continue; } catch (RuntimeException ignored) {}
            String label = HdmiInputs.label(this, input, true);
            if (label.isEmpty()) label = HdmiInputs.label(this, input, false);
            String id = input.getId();
            popup.row(rows, R.drawable.ic_av, label.isEmpty() ? "External input" : label, "External input",
                    () -> popup.dismissThen(() -> openInput(id)));
        }
        return rows;
    }

    private View headerScenes(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        String current = ScenePreferences.current(this);
        SceneSettingsPages settings = new SceneSettingsPages(this, popup, () -> showSaturnSettings(popup));
        String[] ids = {ScenePreferences.SATURN, ScenePreferences.EARTH, ScenePreferences.WEATHER, ScenePreferences.FLIP};
        String[] descriptions = {"Saturn and its moons", "Your location · day and night", "Current local weather", "Animated split-flap clock"};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            View row = popup.row(rows, ScenePreferences.icon(id),
                    ScenePreferences.title(id), id.equals(current) ? "Selected" : descriptions[i], () -> {
                        visualChoices.edit().putString(ScenePreferences.SCENE_KEY, id).apply();
                        popup.refreshPage();
                    });
            row.setSelected(id.equals(current));
            row.setTag("scene:" + id);
            if (id.equals(current)) settings.addEntry(rows, id);
        }
        return rows;
    }

    private void showSaturnSettings(HeaderStackPopup popup) {
        popup.push("Saturn settings", () -> headerWallpaper(popup));
    }

    private View headerWallpaper(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        if (saturnScene == null) return rows;
        popup.row(rows, R.drawable.ic_picture, "Explore scene", null,
                () -> headerLocalAction("explore", null, () -> setSceneExplore(true)));
        popup.row(rows, R.drawable.ic_saturn, "View", saturnScene.distance < 15 ? "Saturn" : "Whole system",
                () -> { saturnScene.systemView(); popup.refreshPage(); });
        popup.row(rows, saturnScene.paused ? R.drawable.ic_scene_play : R.drawable.ic_scene_pause,
                saturnScene.paused ? "Resume simulation" : "Pause simulation", null,
                () -> { saturnScene.togglePause(); popup.refreshPage(); }).setTag("scene-pause");
        popup.row(rows, R.drawable.ic_scene_speed, "Speed", saturnScene.speed + "×",
                () -> { saturnScene.cycleSpeed(); popup.refreshPage(); });
        popup.row(rows, R.drawable.ic_scene_clock, "Clock", saturnScene.simulationClock ? "Simulation" : "Local time",
                () -> { saturnScene.toggleClock(); popup.refreshPage(); });
        popup.row(rows, R.drawable.ic_scene_orbit, "Orbit guides", saturnScene.orbits ? "On" : "Off",
                () -> { saturnScene.toggleOrbits(); popup.refreshPage(); });
        popup.row(rows, R.drawable.ic_scene_names, "Moon names", saturnScene.labels ? "On" : "Off",
                () -> { saturnScene.toggleLabels(); popup.refreshPage(); });
        popup.row(rows, R.drawable.ic_scene_reset, "Reset this scene", "View and simulation time",
                () -> { saturnScene.reset(); popup.refreshPage(); }).setTag("saturn:reset");
        return rows;
    }

    private View headerModuleCards(HeaderStackPopup popup) {
        LinearLayout rows = popup.list();
        List<Cards.Card> listed = new ArrayList<>(cards); Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = popup.row(rows, R.drawable.ic_apps, card.label,
                    card.state == Cards.State.SHOWN ? "Shown" : "Hidden", () -> CardFeature.run(() -> {
                        Cards.Card current = cardFor(card.pkg);
                        if (current != null) decideCard(current, current.state != Cards.State.SHOWN);
                        popup.refreshPage(); return null;
                    }, null));
            row.setTag(card.pkg);
        }
        return rows;
    }

    private void headerExternal(Intent intent) {
        HeaderStackPopup popup = headerControls;
        if (popup == null) open(intent); else popup.dismissThen(() -> open(intent));
    }

    private void headerLocalAction(String action, String key, Runnable run) {
        HeaderStackPopup popup = headerControls;
        if (popup == null) { run.run(); return; }
        popup.dismissThen(() -> {
            if (popup.changed()) {
                getIntent().putExtra("header_after_reload", action);
                if (key != null) getIntent().putExtra("header_action_key", key);
                headerSettingsReloadPending = false; recreate();
            } else run.run();
        });
    }

    private void runDeferredHeaderAction() {
        String action = getIntent().getStringExtra("header_after_reload");
        if (action == null) return;
        String key = getIntent().getStringExtra("header_action_key");
        getIntent().removeExtra("header_after_reload"); getIntent().removeExtra("header_action_key");
        final int generation = layoutEditGeneration;
        grid.postOnAnimation(new Runnable() {
            int frames;
            @Override public void run() {
                if (generation != layoutEditGeneration || isFinishing()) return;
                if (!started || !hasWindowFocus() || grid.getWidth() == 0 || !tilesReady
                        || grid.getAdapter() == null || grid.hasPendingAdapterUpdates()) {
                    if (++frames < 120) grid.postOnAnimation(this); return;
                }
                if ("settings".equals(action)) onLauncherSettingsRequested();
                else if ("arrange".equals(action)) showLayoutEditor(key);
                else if ("explore".equals(action) && saturnScene != null) setSceneExplore(true);
                else if ("earth-explore".equals(action)) startEarthExplore();
            }
        });
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finishEarthExplore(false);
        if (saturnScene != null && saturnScene.exploring()) setSceneExplore(false);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        cancelPendingFocusScroll();
        suppressPendingSelect = false;
        layoutEditGeneration++;
        if (headerControls != null) headerControls.dismissImmediately();
        if(openPopup instanceof HeaderStackPopup)((HeaderStackPopup)openPopup).dismissImmediately();
        else if(openPopup instanceof SettingsMorphPopup)((SettingsMorphPopup)openPopup).dismissImmediately();
        else if(openPopup instanceof InputsPopup)((InputsPopup)openPopup).dismissImmediately();
        else if(openPopup!=null)openPopup.dismiss();
        if(appLibrary!=null)appLibrary.close();
        if(profileSwitchDialog!=null)profileSwitchDialog.dismissImmediately();
        cancelReorder();
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            rebuildOnResume = true;
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sceneForeground = true;
        LauncherSounds.muteWindowRoot(getWindow().getDecorView());
        if (headerSettingsReloadPending) {
            headerSettingsReloadPending = false; recreate(); return;
        }
        if (headerPill != null) headerPill.refresh();
        connectionMonitor.refresh();
        applyClockFormat();
        applySelectedScene();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        adapter.refreshInputStates();
        updateGreeting();
        updateRace();
        // Network copy is cached. Input discovery still goes through the TV service, so defer the
        // catch-up refresh until after the first frame when returning to an existing Home.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        sky.update();
        if (ScenePreferences.THEME.equals(selectedScene)) wallpaperPlayer.start();
        weatherWidget.start();
        if (saturnScene != null && ScenePreferences.SATURN.equals(selectedScene)) saturnScene.start();
        if (ambientScenes != null) ambientScenes.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
        prepareStartupMenu();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GOOGLE_PROFILE_REQUEST && resultCode == RESULT_OK && data != null) {
            String account = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            String type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
            if (account != null && !account.trim().isEmpty() && (type == null || "com.google".equals(type))) {
                DeviceProfileStore.selectAccount(this, account);
                if (headerPill != null) headerPill.refresh();
            }
            return;
        }
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) cancelReorder();   // Keep an in-progress edit from overwriting a changed app list.
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        sceneForeground = false;
        finishEarthExplore(false);
        cancelStartupMenu();
        super.onPause();
        handler.removeCallbacks(endNavigationMotion);
        navigationMotion = false;
        overlayMotion = false;
        layoutEditGeneration++;
        root.removeCallbacks(showIdleClock);
        if(profileSwitchDialog!=null)profileSwitchDialog.dismissImmediately();
        cancelReorder();   // A layout is committed only with Done/Back, never by an external app switch.
        wallpaperPlayer.stop();
        weatherWidget.stop();
        if (ambientScenes != null) ambientScenes.stop();
        if (saturnScene != null) { if (saturnScene.exploring()) setSceneExplore(false); saturnScene.stop(); }
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (headerControls != null) headerControls.dismissImmediately();
        if (openPopup instanceof HeaderStackPopup) ((HeaderStackPopup)openPopup).dismissImmediately();
        else if (openPopup instanceof SettingsMorphPopup) ((SettingsMorphPopup)openPopup).dismissImmediately();
        else if (openPopup instanceof InputsPopup) ((InputsPopup)openPopup).dismissImmediately();
        else if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        rebuildOnResume = true;
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtCache.clear();
        heroArtKey = null;
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            prepareStartupMenu();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        sceneForeground = false;
        finishEarthExplore(false);
        cancelStartupMenu();
        super.onDestroy();
        if (launcherSounds != null) { launcherSounds.close(); launcherSounds = null; }
        wallpaperPlayer.close();
        weatherWidget.close();
        if (ambientScenes != null) ambientScenes.close();
        if (visualChoices != null) visualChoices.unregisterOnSharedPreferenceChangeListener(visualPreferenceChanges);
        if (saturnScene != null) saturnScene.close();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (connectionMonitor != null) connectionMonitor.unsubscribe(connectionUpdates);
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Neutral emphasis keeps the carried card clear without a glow or scale animation. */
    private static final int REORDER_ACCENT = 0xFFFFFFFF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher cancels its uncommitted preview.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The modal card shares this window, but owns its keys before Home's sound/select/scene handlers.
        if (headerControls != null && headerControls.dispatchMenuKeyEvent(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                suppressPendingSelect = false; suppressArrangeConfirmUp = false;
            }
            return true;
        }
        if (launcherSounds != null) launcherSounds.onKeyEvent(event);
        if (earthExploreState != null && handleEarthExploreKey(event)) return true;
        boolean select = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // A not-yet-visible target must never launch the app which still owns the old focus.
        if (select && (suppressPendingSelect || pendingTilePosition != RecyclerView.NO_POSITION)) {
            suppressPendingSelect = event.getAction() != KeyEvent.ACTION_UP;
            if (event.getAction() == KeyEvent.ACTION_UP) suppressArrangeConfirmUp = false;
            return true;
        }
        if (select && suppressArrangeConfirmUp) {
            if (event.getAction() == KeyEvent.ACTION_UP) suppressArrangeConfirmUp = false;
            return true;
        }
        if (saturnScene != null && ScenePreferences.SATURN.equals(selectedScene)) {
            if (saturnScene.exploring() && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) setSceneExplore(false);
                return true;
            }
            if (saturnScene.handle(event)) return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (reordering) { if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) endReorder(); return true; }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showSettingsMenu();
            return true;
        }
        if (handleSettingsKey(event)) return true;
        if (!reordering) return dispatchQuietly(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if ((ok || back) && event.isCanceled()) return true;
        if (carriedKey() != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) { if(back)endReorder();else drop(); }
                        return true;
                    }
                    return dispatchQuietly(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return dispatchQuietly(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() { showLayoutEditor(lastFocusedKey); }

    private String carriedKey() { return arrangeSession == null ? null : arrangeSession.movingKey(); }

    /** Begin editing the real Home grid. No second picker or copy of the cards is opened. */
    private boolean beginReorder() {
        if (adapter.editableEntries().isEmpty()) return false;
        cancelPendingFocusScroll(); grid.stopScroll();
        arrangeSession = new InlineArrangeSession(adapter.layoutDockKeys(), adapter.layoutAppKeys());
        reordering = true; layoutEditGeneration++;
        normalItemAnimator = grid.getItemAnimator();
        if (animations) {
            DefaultItemAnimator moves = new DefaultItemAnimator();
            moves.setSupportsChangeAnimations(false);
            moves.setMoveDuration(reducedMotion ? 80 : 140);
            moves.setAddDuration(80); moves.setRemoveDuration(80);
            grid.setItemAnimator(moves);
        }
        adapter.setReordering(true);
        glass.setAccent(REORDER_ACCENT); btnReorder.setActivated(true);
        showArrangeBar();
        return true;
    }

    private void startDockEdit() { showLayoutEditor(adapter.keyAt(0)); }

    private void showLayoutEditor(String initialKey) { startInlineArrange(initialKey, false); }

    private void startInlineArrange(String initialKey, boolean carryImmediately) {
        if (reordering) return;
        if (!beginReorder()) return;
        int position = adapter.positionOf(initialKey);
        if (position < 0) position = Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1));
        if (carryImmediately) pickUp(position);
        final int target = position, generation = layoutEditGeneration;
        grid.postOnAnimation(() -> {
            if (reordering && generation == layoutEditGeneration && started && hasWindowFocus()) focusTile(target);
        });
    }

    private void showArrangeBar(){
        if(arrangeBar!=null)return;
        FrostedPanel bar=new FrostedPanel(this,null);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int)(16*density),(int)(10*density),(int)(12*density),(int)(10*density));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
        arrangeTitle=new TextView(this);arrangeTitle.setTextSize(14);arrangeTitle.setTextColor(0xFFFFFFFF);arrangeTitle.setTypeface(getResources().getFont(R.font.inter_medium));arrangeTitle.setIncludeFontPadding(false);
        arrangeHint=new TextView(this);arrangeHint.setTextSize(10);arrangeHint.setTextColor(0xBBFFFFFF);arrangeHint.setTypeface(getResources().getFont(R.font.inter_regular));arrangeHint.setIncludeFontPadding(false);
        copy.addView(arrangeTitle);LinearLayout.LayoutParams hintLp=new LinearLayout.LayoutParams(-1,-2);hintLp.topMargin=(int)(5*density);copy.addView(arrangeHint,hintLp);
        bar.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView cancel=arrangeButton("Cancel",this::cancelReorder);arrangeDone=arrangeButton("Done",this::endReorder);
        bar.addView(cancel,new LinearLayout.LayoutParams((int)(64*density),(int)(36*density)));bar.addView(arrangeDone,new LinearLayout.LayoutParams((int)(64*density),(int)(36*density)));
        View.OnKeyListener keys=(v,k,e)->{if(e.getAction()!=KeyEvent.ACTION_DOWN)return false;if(k==KeyEvent.KEYCODE_DPAD_DOWN){focusTile(lastFocused);return true;}if(k==KeyEvent.KEYCODE_DPAD_UP)return true;return k==KeyEvent.KEYCODE_DPAD_LEFT&&v==cancel||k==KeyEvent.KEYCODE_DPAD_RIGHT&&v==arrangeDone;};cancel.setOnKeyListener(keys);arrangeDone.setOnKeyListener(keys);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams((int)Math.min(560*density,root.getWidth()-192*density),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=(int)(26*density);root.addView(bar,lp);arrangeBar=bar;updateArrangeBar();
        LauncherSounds.muteSystemEffects(bar);
        if (animations) { bar.setAlpha(0); bar.animate().alpha(1).setDuration(120).start(); }
    }
    private TextView arrangeButton(String title,Runnable action){
        TextView button=new TextView(this);button.setText(title);button.setContentDescription(title+" arranging apps");button.setTextSize(12);button.setTextColor(getResources().getColorStateList(R.color.settings_ink,getTheme()));button.setTypeface(getResources().getFont(R.font.inter_medium));button.setGravity(Gravity.CENTER);button.setIncludeFontPadding(false);button.setBackgroundResource(R.drawable.glass_row_focus);button.setFocusable(true);button.setClickable(true);button.setSoundEffectsEnabled(false);button.setOnClickListener(v->action.run());return button;
    }
    private void updateArrangeBar(){
        if(arrangeBar==null)return;
        boolean moving=carriedKey()!=null;int position=adapter.positionOf(carriedKey());
        arrangeTitle.setText(moving?"Moving · "+adapter.labelAt(position):"Arrange App Cards");
        arrangeHint.setText(moving?"Arrow keys move  ·  OK places  ·  Back saves and finishes":"Select an app  ·  OK to move  ·  Back saves and finishes");
    }
    private void cancelReorder() { finishReorder(false); }

    private void pickUp(int pos) {
        if (arrangeSession == null || !arrangeSession.pickUp(pos)) return;
        dimOthers(); updateArrangeBar();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        if (arrangeSession != null) arrangeSession.drop();
        dimOthers();
        updateArrangeBar();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (carriedKey() != null) {
            return pos != RecyclerView.NO_POSITION && carriedKey().equals(adapter.keyAt(pos)) ? 1f : 0.72f;
        }
        return 1f;
    }

    /** Done/Back commit; Cancel, Home and leaving this activity restore the exact preview snapshot. */
    private void endReorder() { finishReorder(true); }

    private void finishReorder(boolean commit) {
        if (!reordering || arrangeSession == null) return;
        InlineArrangeSession session = arrangeSession;
        String returnKey = carriedKey() != null ? carriedKey() : lastFocusedKey;
        boolean saved = false;
        if (commit && session.changed()) {
            LayoutDraft preview = session.layout();
            Set<String> available = new HashSet<>();
            for (AppAdapter.Entry entry : adapter.editableEntries()) available.add(entry.component.flattenToShortString());
            if (available.equals(new HashSet<>(preview.keys()))) {
                Order order = new Order(Order.dockKey(dockApps), preview.dockSlots(), preview.apps());
                saved = Order.save(Order.file(this), order);
                if (saved) adapter.setLayoutOrder(order);
            }
            if (!saved) {
                adapter.previewLayout(session.cancel());
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_LONG).show();
            }
        } else if (!commit) adapter.previewLayout(session.cancel());
        session.drop(); arrangeSession = null; reordering = false; suppressArrangeConfirmUp = false;
        int generation = ++layoutEditGeneration;
        if (arrangeSelectionTracker != null) { grid.removeCallbacks(arrangeSelectionTracker); arrangeSelectionTracker = null; }
        if (grid.getItemAnimator() != null) grid.getItemAnimator().endAnimations();
        grid.setItemAnimator(normalItemAnimator); normalItemAnimator = null;
        adapter.setReordering(false); dimOthers();
        glass.setAccent(festive ? festival.accentColor() : 0); btnReorder.setActivated(false);
        if (arrangeBar != null) {
            arrangeBar.animate().cancel(); root.removeView(arrangeBar);
            arrangeBar = null; arrangeTitle = null; arrangeHint = null; arrangeDone = null;
        }
        if (saved) Toast.makeText(this, "Layout saved", Toast.LENGTH_SHORT).show();
        final String key = returnKey;
        grid.postOnAnimation(() -> {
            if (!started || !hasWindowFocus() || generation != layoutEditGeneration || reordering) return;
            int position = adapter.positionOf(key);
            focusTile(position >= 0 ? position : Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        });
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        if (arrangeSession == null) return;
        int from = arrangeSession.movingPosition();
        int n = arrangeSession.layout().size();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if((keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)
                &&GridNavigation.atHorizontalEdge(from,n,COLUMNS,rtlAppGrid,keyCode==KeyEvent.KEYCODE_DPAD_LEFT))return;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        if (!arrangeSession.moveTo(to)) return;
        holdSceneForNavigation();
        keepScroll(() -> adapter.previewLayout(arrangeSession.layout()));
        refocusAfterLayout(to);
        updateArrangeBar();
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        final int generation=layoutEditGeneration;final String key=adapter.keyAt(pos);
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if(generation!=layoutEditGeneration||!reordering||!started||!hasWindowFocus())return;
                if(carriedKey()!=null&&!carriedKey().equals(key))return;
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                int current=adapter.positionOf(key);if(current<0)return;
                focusTile(current);
                dimOthers();   // views that came into view or were rebound during the move
                followArrangeSelection(generation);
            }
        });
    }

    /** Track only a live rearrangement; ordinary navigation never starts a frame loop. */
    private void followArrangeSelection(int generation) {
        if (arrangeSelectionTracker != null) grid.removeCallbacks(arrangeSelectionTracker);
        arrangeSelectionTracker = new Runnable() {
            @Override public void run() {
                if (!reordering || generation != layoutEditGeneration || !started || !hasWindowFocus()) return;
                View focused = getCurrentFocus();
                if (focused != null && focused.getId() == R.id.card && focused.isShown()) {
                    tileRect.set(0, 0, focused.getWidth(), focused.getHeight());
                    root.offsetDescendantRectToMyCoords(focused, tileRect);
                    float pad = 6 * density;
                    glass.setTranslationX(tileRect.left - pad);
                    glass.setTranslationY(tileRect.top - pad);
                    updateGlassHeaderClip();
                }
                if (grid.getItemAnimator() != null && grid.getItemAnimator().isRunning()) grid.postOnAnimation(this);
                else arrangeSelectionTracker = null;
            }
        };
        grid.postOnAnimation(arrangeSelectionTracker);
    }

    @Override
    public void onBackPressed() {
        if(lastFocused>=COLUMNS){focusTile(lastFocused%COLUMNS);return;}
        if(getCurrentFocus()==findViewById(R.id.btnSettings)||getCurrentFocus()==profilePill)focusTile(lastFocused%COLUMNS);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Header geometry changes on layout, never as part of a scroll frame. */
    private void updateHeaderSafeViewport() {
        if (root == null || grid == null || root.getHeight() <= 0 || grid.getHeight() <= 0) return;
        int bottom = headerBottomInRoot(headerPill);
        bottom = Math.max(bottom, headerBottomInRoot(weatherWidget));
        headerSafeBottomRoot = Math.min(root.getHeight(), bottom + Math.round(12 * density));
        headerSafeMeasure.set(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, headerSafeMeasure);
        gridTopInRoot = headerSafeMeasure.top;
        int previousTop = gridSafeTop;
        gridSafeTop = Math.max(0, Math.min(grid.getHeight(), headerSafeBottomRoot - gridTopInRoot));
        gridHeaderClip.set(0, gridSafeTop, grid.getWidth(), grid.getHeight());
        grid.setClipBounds(gridHeaderClip);
        updateGlassHeaderClip();
        if (gridSafeTop > previousTop) {
            grid.removeCallbacks(revealHeaderSafeFocus);
            grid.post(revealHeaderSafeFocus);
        }
    }

    private int headerBottomInRoot(View header) {
        if (header == null || header.getVisibility() != View.VISIBLE || header.getHeight() <= 0) return 0;
        headerSafeMeasure.set(0, 0, header.getWidth(), header.getHeight());
        root.offsetDescendantRectToMyCoords(header, headerSafeMeasure);
        return headerSafeMeasure.bottom;
    }

    private final Runnable revealHeaderSafeFocus = () -> {
        if (!started || !sceneForeground || !hasWindowFocus() || grid == null || grid.getWindowToken() == null) return;
        View focused = getCurrentFocus();
        if (focused == null || focused.getId() != R.id.card || !(focused.getParent() instanceof View)) return;
        View item = (View)focused.getParent();
        int position = grid.getChildAdapterPosition(item);
        if (position == RecyclerView.NO_POSITION || dockRow && position < COLUMNS) return;
        revealRect.set(0, -Math.round(12 * density), item.getWidth(), item.getHeight() + Math.round(6 * density));
        grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
    };

    /** Glass is a root sibling of the list and needs the same physical header boundary. */
    private void updateGlassHeaderClip() {
        if (glass == null) return;
        int top = Math.max(0, Math.min(glass.getHeight(), (int)Math.ceil(headerSafeBottomRoot - glass.getY())));
        glassHeaderClip.set(0, top, glass.getWidth(), glass.getHeight());
        glass.setClipBounds(glassHeaderClip);
    }

    @Override
    public void onLibraryRequested() {
        if (reordering) cancelReorder();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        boolean changed = !glassShown || position != lastFocused;
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = 1f;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid.
        int accent = cinematic && !festive && position < COLUMNS ? adapter.ambientColorAt(position) : 0;
        if (cinematic && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        // The stronger edge is visible on the first frame, with no extra animation per key press.
        glass.animate().cancel();
        glass.setTranslationX(x);
        glass.setTranslationY(y);
        updateGlassHeaderClip();
        glass.setAlpha(1f);
        glassShown = true;
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop();
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy, new android.view.animation.DecelerateInterpolator(1.3f), CARD_SCROLL_MS);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = 1f;
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        // A wallpaper-only Home has no shelf work to animate or artwork colour to sample.
        if (hero.getVisibility() != View.VISIBLE && !ambientGlow && !cinematic) return;
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                if (!animations) {
                    ambient.setAlpha(1f);
                } else if (!heroShown) {
                    ambient.setAlpha(0f);
                    ambient.animate().alpha(1f).setDuration(160).start();
                }
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /** The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim. */
    private void setUpCinematicShelf() {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(View.VISIBLE);
        heroArt.setVisibility(View.VISIBLE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        View scrim = findViewById(R.id.heroScrim);
        scrim.setBackground(scrimDrawable());
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(HERO_ART_ALPHA);
        else heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(reducedMotion ? Math.min(ms, 160) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations) {
            hero.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
            heroArt.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long mem = memAvailableMb();
        if (mem >= 0) appendStat(line, getString(R.string.stats_memory, mem));
        String free = formatGb(Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private long memAvailableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] t = line.split("\\s+");
                    return t.length >= 2 ? Long.parseLong(t[1]) / 1024 : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? String.format(Locale.US, "%.0f GB", gb)
                : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        // A fast second key should advance from the requested tile, even before it is attached.
        if (pendingTilePosition != RecyclerView.NO_POSITION) position = pendingTilePosition;
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                if (!reordering) focusLibrary();
                return true;
            }
            List<String> slots = adapter.layoutDockKeys();
            if (position < slots.size()) {
                int step = forward ? 1 : -1;
                for (int i = position + step; i >= 0 && i < slots.size(); i += step) if (reordering || slots.get(i) != null) {
                    focusTile(i); return true;
                }
                return true;
            }
            if (!GridNavigation.atHorizontalEdge(position, count, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
                focusTile(position + (forward ? 1 : -1));
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) { cancelPendingFocusScroll(); if(reordering&&arrangeDone!=null)arrangeDone.requestFocus();else firstTopButton().requestFocus();return true; }
            target = position - COLUMNS;
            if (target < COLUMNS && !adapter.layoutDockKeys().isEmpty()
                    && !reordering && adapter.layoutDockKeys().stream().allMatch(java.util.Objects::isNull)) {
                cancelPendingFocusScroll();
                firstTopButton().requestFocus(); return true;
            }
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (!reordering && adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** The visible account control is the sole top-level focus target. */
    private View firstTopButton() {
        if (profilePill != null && profilePill.getVisibility() == View.VISIBLE) return profilePill;
        if (btnWifi.getVisibility() == View.VISIBLE) return btnWifi;
        if (btnReorder.getVisibility() == View.VISIBLE) return btnReorder;
        return findViewById(R.id.btnSettings);
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        smoothScrollToCard(position);
    }

    private void cancelPendingFocusScroll() {
        pendingTilePosition = RecyclerView.NO_POSITION;
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
        }
        if (grid != null) grid.stopScroll();
    }

    private void smoothScrollToCard(int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(this) {
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics metrics) {
                return 12f / metrics.densityDpi;
            }
            @Override protected int calculateTimeForDeceleration(int distance) {
                return Math.min(CARD_SCROLL_MS, super.calculateTimeForDeceleration(distance));
            }
            @Override public int calculateDyToMakeVisible(View view, int snapPreference) {
                RecyclerView.LayoutManager manager = getLayoutManager();
                if (manager == null || dockRow && manager.getPosition(view) < COLUMNS)
                    return super.calculateDyToMakeVisible(view, snapPreference);
                RecyclerView.LayoutParams margins = (RecyclerView.LayoutParams)view.getLayoutParams();
                int top = manager.getDecoratedTop(view) - margins.topMargin - Math.round(12 * density);
                int bottom = manager.getDecoratedBottom(view) + margins.bottomMargin + Math.round(6 * density);
                // SmoothScroller expects the view's movement, opposite to RecyclerView's scroll delta.
                return -HeaderSafeViewport.scrollDelta(top, bottom, gridSafeTop,
                        manager.getHeight(), manager.getPaddingBottom());
            }
        };
        scroller.setTargetPosition(position);
        grid.getLayoutManager().startSmoothScroll(scroller);
    }

    private void updateSceneMotion() {
        if (saturnScene != null) {
            saturnScene.setNavigationMotionActive(navigationMotion);
            saturnScene.setUiMotionActive(overlayMotion);
        }
    }

    private void setOverlayMotionActive(boolean active) {
        overlayMotion = active;
        updateSceneMotion();
    }

    private void holdSceneForNavigation() {
        if (!animations || saturnScene == null) return;
        navigationMotion = true;
        updateSceneMotion();
        handler.removeCallbacks(endNavigationMotion);
        // Keep the same wallpaper detail while recording navigation activity for interaction timing.
        handler.postDelayed(endNavigationMotion, CARD_SCROLL_MS + 20L);
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        if (pos != lastFocused) holdSceneForNavigation();
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Focus as soon as the requested card enters view, without waiting for the scroll's tail.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingTilePosition = pos;
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            private final Rect visible = new Rect();
            private void focusIfReady(RecyclerView rv, boolean settled) {
                if (pendingFocusScroll != this) return;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h == null ? null : h.itemView.findViewById(R.id.card);
                if (target == null || !target.isShown() || !hasWindowFocus()) return;
                visible.set(0, 0, target.getWidth(), target.getHeight());
                root.offsetDescendantRectToMyCoords(target, visible);
                int height = HeaderSafeViewport.visibleHeight(visible.top, visible.bottom,
                        Math.max(headerSafeBottomRoot, gridTopInRoot), gridTopInRoot + rv.getHeight());
                if (height <= 0 || !settled && height < target.getHeight() / 2) return;
                rv.removeOnScrollListener(this);
                pendingFocusScroll = null;
                pendingTilePosition = RecyclerView.NO_POSITION;
                target.requestFocus();
            }
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                focusIfReady(rv, false);
            }
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                focusIfReady(rv, true);
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        smoothScrollToCard(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(ScenePreferences.FLIP.equals(selectedScene) ? View.GONE : valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        String festiveGreeting = festive ? festival.greetingText() : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title()));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName()).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Legacy hidden network copy also consumes the shared cache, never a network or Wi-Fi binder. */
    private void updateWifiLabel() {
        if (connectionMonitor == null || btnWifi == null) return;
        ConnectionMonitor.Snapshot state = connectionMonitor.cachedSnapshot();
        boolean ethernet = state.transport == ConnectionMonitor.Transport.ETHERNET;
        int icon = ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
        String text = state.label;
        if (!TextUtils.equals(btnWifi.getContentDescription(), text + ", " + state.status))
            btnWifi.setContentDescription(text + ", " + state.status);
        if (!TextUtils.equals(btnWifi.getText(), wifiIconOnly ? null : text))
            btnWifi.setText(wifiIconOnly ? null : text);
        if (festive && diya != null) {
            diya.setSteady(ethernet);
            diya.setFlame(!state.connected ? 0 : ethernet ? 4 : 3);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        cancelPendingFocusScroll();
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Inputs now live inside the account stack, so callbacks never reveal a second header control. */
    private void updateInputsButton() { btnInputs.setVisibility(View.GONE); }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    public void openHdmiPort(int port) {
        String id = HdmiInputs.resolveInputId(this, port);
        if (id != null) { openInput(id); return; }
        Toast.makeText(this, "HDMI " + port + " unavailable", Toast.LENGTH_SHORT).show();
    }

    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** HDMI ports and external inputs share the fixed header control card. */
    private void showInputs() {
        ensureHeaderControls(p -> p.push("Inputs", () -> headerInputs(p)));
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        grid.animate().alpha(0.24f).setDuration(180).start();
        popup.setOnDismissListener(() -> {
            grid.animate().alpha(1f).setDuration(180).start();
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        int margin=Math.round(48*density), maxWidth=root.getWidth()-2*margin;
        int width=Math.min(popup.getWidth(),maxWidth);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight()-2*margin,View.MeasureSpec.AT_MOST));
        int height=panel.getMeasuredHeight();int[] loc=new int[2];anchor.getLocationInWindow(loc);
        int x=loc[0]+anchor.getWidth()/2<root.getWidth()/2?loc[0]:loc[0]+anchor.getWidth()-width;
        x=Math.max(margin,Math.min(root.getWidth()-margin-width,x));
        int y=Math.max(margin,Math.min(root.getHeight()-margin-height,loc[1]+anchor.getHeight()+Math.round(12*density)));
        popup.setWidth(width);popup.setHeight(height);popup.setClippingEnabled(true);
        popup.showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void addMenuSection(LinearLayout rows, String name) {
        TextView text = new TextView(this);
        text.setText(name); text.setTextSize(9); text.setTextColor(0x99FFFFFF);
        InterTypography.apply(text, 600);
        text.setLetterSpacing(0.10f); text.setPadding((int)(11*density), (int)(10*density), 0, (int)(5*density));
        rows.addView(text);
    }

    private void showSettingsMenu() {
        ensureHeaderControls();
    }

    @Override public boolean onTileActionsRequested(int position) {
        if (reordering || adapter.keyAt(position) == null) return true;
        // The opening long-press must not become an OK-up that immediately drops the card.
        suppressArrangeConfirmUp = true;
        startInlineArrange(adapter.keyAt(position), true);
        if (!reordering) suppressArrangeConfirmUp = false;
        return true;
    }

    private void setSceneExplore(boolean value) {
        if (saturnScene == null || (value && !ScenePreferences.SATURN.equals(selectedScene))) return;
        if (value) finishEarthExplore(false);
        saturnScene.explore(value);
        grid.animate().cancel(); grid.setAlpha(1f);
        grid.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
        if (value) hideGlass(); else focusTile(lastFocused);
    }

    @Override public void requestEarthExplore() {
        headerLocalAction("earth-explore", null, this::startEarthExplore);
    }

    private void startEarthExplore() {
        if (earthExploreState != null || !sceneForeground || !started || isFinishing()) return;
        if (saturnScene != null && saturnScene.exploring()) setSceneExplore(false);
        cancelPendingFocusScroll();
        suppressPendingSelect = false; suppressArrangeConfirmUp = false;
        earthReturnKey = lastFocusedKey; earthReturnPosition = lastFocused;
        earthGridVisibility = grid.getVisibility(); earthGridDescendants = grid.getDescendantFocusability();
        earthGridFocusable = grid.getFocusable(); earthGridTouchFocus = grid.isFocusableInTouchMode();
        earthSkyVisibility = sky.getVisibility();
        earthExploreState = new EarthExploreState(SceneOptions.earthZoom(this),
                SceneOptions.earthYaw(this), SceneOptions.earthPitch(this));
        try {
            if (ScenePreferences.EARTH.equals(selectedScene) && ambientScenes != null) {
                earthExploreHost = ambientScenes;
            } else {
                // Saver Earth can be edited while Home remains Weather/Saturn/Theme. Preview
                // in a separately owned host; never change the owner's selected-scene key.
                earthExplorePreview = new AmbientScenes(this, root);
                if (!earthExplorePreview.select(ScenePreferences.EARTH)) throw new IllegalStateException("Earth unavailable");
                if (ambientScenes != null) ambientScenes.stop();
                if (saturnScene != null) saturnScene.setVisible(false);
                wallpaperPlayer.stop(); wallpaperPlayer.setVisible(false); sky.setVisibility(View.GONE);
                earthExplorePreview.start(); earthExploreHost = earthExplorePreview;
            }
            if (!earthExploreHost.beginEarthViewExplore()) throw new IllegalStateException("Earth not active");
            earthExploreHost.previewEarthView(earthExploreState.zoom(), earthExploreState.yaw(), earthExploreState.pitch());
            grid.animate().cancel(); grid.setAlpha(1f); hideGlass(); hideHero();
            grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            grid.setFocusable(View.NOT_FOCUSABLE); grid.setVisibility(View.INVISIBLE);
            earthExploreHelp = new TextView(this);
            earthExploreHelp.setTextColor(0xE6FFFFFF); earthExploreHelp.setTextSize(12);
            InterTypography.apply(earthExploreHelp, 400); earthExploreHelp.setGravity(Gravity.CENTER);
            earthExploreHelp.setPadding(Math.round(18 * density), Math.round(12 * density),
                    Math.round(18 * density), Math.round(12 * density));
            earthExploreHelp.setBackgroundColor(0xB80A101A);
            earthExploreHelp.setFocusableInTouchMode(true); earthExploreHelp.setClickable(false);
            FrameLayout.LayoutParams help = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            help.setMargins(Math.round(40 * density), 0, Math.round(40 * density), Math.round(24 * density));
            root.addView(earthExploreHelp, help);
            updateEarthExploreHelp(); earthExploreHelp.requestFocusFromTouch(); restartIdleClock();
            android.util.Log.i("Home.Scene", "earth_explore=enter zoom=" + earthExploreState.zoom()
                    + " yaw=" + earthExploreState.yaw() + " pitch=" + earthExploreState.pitch()
                    + " preview=" + (earthExplorePreview != null) + " owner=" + selectedScene);
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            finishEarthExplore(true, false);
            android.util.Log.w("Home.Scene", "Earth explore unavailable", unavailable);
            Toast.makeText(this, "Earth explore unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleEarthExploreKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) { finishEarthExplore(false); return false; }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) finishEarthExplore(true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                finishEarthExplore(true); showSettingsMenu();
            }
            return true;
        }
        if (!EarthExploreState.accepts(code)) return false; // Volume, microphone and other media keep their normal route.
        if (event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled()) {
            restartIdleClock();
            earthExploreState.handle(code, event.getRepeatCount());
            earthExploreHost.previewEarthView(earthExploreState.zoom(), earthExploreState.yaw(), earthExploreState.pitch());
            updateEarthExploreHelp();
        }
        return true;
    }

    private void updateEarthExploreHelp() {
        if (earthExploreState == null || earthExploreHelp == null) return;
        int zoom = Math.round(100 * earthExploreState.zoom()), yaw = Math.round(earthExploreState.yaw());
        int pitch = Math.round(earthExploreState.pitch());
        earthExploreHelp.setText("← → Rotate   ↑ ↓ Tilt   CH ± Zoom   OK 1× / 1.5×   Back Exit\n"
                + zoom + "%  ·  " + yaw + "°  ·  " + pitch + "°");
        earthExploreHelp.setContentDescription("Earth explore. Zoom " + zoom + "%. Horizontal " + yaw
                + "°. Tilt " + pitch + "°. Left and right rotate. Up and down tilt. Channel keys zoom. Back saves and exits.");
    }

    private void finishEarthExplore(boolean restoreFocus) { finishEarthExplore(restoreFocus, true); }

    private void finishEarthExplore(boolean restoreFocus, boolean save) {
        EarthExploreState state = earthExploreState;
        if (state == null) return;
        earthExploreState = null; // A repeated Home/pause/scene callback must not commit twice.
        AmbientScenes host = earthExploreHost, preview = earthExplorePreview;
        earthExploreHost = null; earthExplorePreview = null;
        boolean saved = false;
        try {
            if (save) { SceneOptions.setEarthView(this, state.zoom(), state.yaw(), state.pitch()); saved = true; }
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            android.util.Log.w("Home.Scene", "Earth view could not be saved", unavailable);
        }
        try { if (host != null) host.endEarthViewExplore(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            android.util.Log.w("Home.Scene", "Earth preview could not end normally", unavailable);
        }
        try { if (preview != null) preview.close(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            android.util.Log.w("Home.Scene", "Earth preview cleanup failed", unavailable);
        }
        if (earthExploreHelp != null) {
            earthExploreHelp.clearFocus(); root.removeView(earthExploreHelp); earthExploreHelp = null;
        }
        grid.setDescendantFocusability(earthGridDescendants);
        grid.setFocusableInTouchMode(earthGridTouchFocus); grid.setFocusable(earthGridFocusable);
        grid.setVisibility(earthGridVisibility); grid.setAlpha(1f);
        sky.setVisibility(earthSkyVisibility);
        if (preview != null) {
            try {
                boolean saturn = ScenePreferences.SATURN.equals(selectedScene), theme = ScenePreferences.THEME.equals(selectedScene);
                if (saturnScene != null) { saturnScene.setVisible(saturn); if (saturn && sceneForeground) saturnScene.start(); }
                wallpaperPlayer.setVisible(theme);
                if (theme && sceneForeground) wallpaperPlayer.start();
                if (ambientScenes != null && sceneForeground) ambientScenes.start();
            } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                android.util.Log.w("Home.Scene", "Owner scene could not resume after Earth preview", unavailable);
            }
        }
        android.util.Log.i("Home.Scene", "earth_explore=exit zoom=" + state.zoom() + " yaw=" + state.yaw()
                + " pitch=" + state.pitch() + " saved=" + saved + " preview=" + (preview != null) + " owner=" + selectedScene);
        if (restoreFocus && sceneForeground && started && hasWindowFocus()) {
            int position = earthReturnKey == null ? -1 : adapter.positionOf(earthReturnKey);
            focusTile(position >= 0 ? position : earthReturnPosition);
        }
        earthReturnKey = null;
    }

    private void addSceneRow(LinearLayout rows, PopupWindow popup, String label, Runnable action) {
        addSettingsMenuRow(rows, popup, R.string.saturn_controls, action);
        View row = rows.getChildAt(rows.getChildCount() - 1);
        ((TextView)row.findViewById(R.id.title)).setText(label);
        int glyph = label.startsWith("View") ? R.drawable.ic_picture
                : label.startsWith("Pause") ? R.drawable.ic_scene_pause
                : label.startsWith("Resume") ? R.drawable.ic_scene_play
                : label.startsWith("Speed") ? R.drawable.ic_scene_speed
                : label.startsWith("Clock") ? R.drawable.ic_scene_clock
                : label.startsWith("Orbit") ? R.drawable.ic_scene_orbit
                : label.startsWith("Moon") ? R.drawable.ic_scene_names
                : label.startsWith("Reset") ? R.drawable.ic_scene_reset : R.drawable.ic_saturn;
        ((android.widget.ImageView)row.findViewById(R.id.icon)).setImageResource(glyph);
    }

    private void showSaturnMenu() {
        HeaderStackPopup popup = ensureHeaderControls();
        if (popup != null) popup.push("Theme", () -> headerScenes(popup));
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_home_setting, rows, false);
        int icon = title == R.string.saturn_controls ? R.drawable.ic_saturn : title == R.string.inputs ? R.drawable.ic_input
                : title == R.string.wifi ? R.drawable.ic_wifi
                : title == R.string.dock_edit ? R.drawable.ic_apps
                : title == R.string.reorder ? R.drawable.ic_swap_vert
                : title == R.string.launcher_settings ? R.drawable.ic_picture : R.drawable.ic_settings;
        ((android.widget.ImageView)row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            if(popup instanceof SettingsMorphPopup) ((SettingsMorphPopup)popup).dismissThen(action);
            else {popup.dismiss();action.run();}
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if(code==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP)popup.dismiss();return true;}
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                if(popup instanceof SettingsMorphPopup) ((SettingsMorphPopup)popup).dismissThen(action);
                else {popup.dismiss();action.run();}
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (launcherSounds != null) launcherSounds.onKeyEvent(event);
                boolean handled = super.dispatchKeyEvent(event);
                return handled || LauncherSounds.handleUnhandledNavigation(getWindow().getDecorView(), event);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        LauncherSounds.muteWindowRoot(dialog.getWindow().getDecorView());
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        HeaderStackPopup popup = ensureHeaderControls();
        if (popup != null) popup.push("Module cards", () -> headerModuleCards(popup));
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
