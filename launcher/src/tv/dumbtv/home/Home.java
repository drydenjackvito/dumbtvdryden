package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The home screen: a top bar (menu, the wordmark, Apps and Settings at the left, the clock at the
 * right), a live preview of the selected input, and a row of sliding panels near the bottom.
 * Apps opens a grid of every app.
 */
public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** The name in the top bar. */
    static final String WORDMARK = "Tomboy";
    /** The clock's light grey. */
    private static final int CLOCK = 0xFFB4B4BC;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private TvInputManager tv;
    private Covers covers;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    private static final long HOME_TWICE_MS = 4000;
    /** When HOME last brought this screen back from another app; 0 when it did not. */
    private long homeFromAway;
    private static final long AWAY_MS = 1500;
    /** When this screen was last hidden. */
    private long stoppedAt;
    /** Whether it had been hidden a while before this start (onStart runs before onNewIntent). */
    private boolean wasAway;

    @Override
    protected void onStart() {
        super.onStart();
        wasAway = stoppedAt != 0 && android.os.SystemClock.uptimeMillis() - stoppedAt > AWAY_MS;
    }

    @Override
    protected void onStop() {
        stoppedAt = android.os.SystemClock.uptimeMillis();
        super.onStop();
    }

    private FrameLayout root;
    private LinearLayout bar;
    private IconButton menuBtn, appsBtn, settingsBtn;
    private TextView clock;
    private Preview preview;
    private Glow glow;
    /** The greeting in the middle of the screen, until the first press on the remote. */
    private TextView greeting;
    private boolean greeted;
    /** The key whose press woke the screen: its release must not act either. */
    private int wakeKey = -1;
    private LinearLayout home;
    private Shelf apps, inputs;
    private TextView noSignal;
    private static Home shown;

    /** AirPlay's light changed (Covers.airplayOn): redraw the panels if they are on screen. */
    static void lightsChanged() {
        Home h = shown;
        if (h != null && h.inputs != null) h.inputs.invalidate();
    }

    /** AirPlay playing, by its media session: its LED lights blue while it does. */
    private final Runnable airplayWatch = new Runnable() {
        @Override
        public void run() {
            boolean on = false;
            try {
                android.media.session.MediaSessionManager sm = (android.media.session.MediaSessionManager)
                        getSystemService(Context.MEDIA_SESSION_SERVICE);
                for (android.media.session.MediaController m
                        : sm.getActiveSessions(new android.content.ComponentName(Home.this, Listener.class))) {
                    if (!m.getPackageName().startsWith(AIRPLAY_PACKAGE)) continue;
                    android.media.session.PlaybackState st = m.getPlaybackState();
                    on |= st != null && (st.getState() == android.media.session.PlaybackState.STATE_PLAYING
                            || st.getState() == android.media.session.PlaybackState.STATE_BUFFERING);
                }
            } catch (SecurityException ignored) {
                // No notification access: AirPlay's light stays as it is.
            }
            if (on != Covers.airplayOn && !Lounge.casting) {
                Covers.airplayOn = on;
                lightsChanged();
            }
            ui.postDelayed(this, 2000);
        }
    };
    private static final String AIRPLAY_PACKAGE = "com.apple.airplay";
    private TextView empty;
    private ScrollView page;
    private TextView pageTitle, pageCount;
    private Grid pageGrid;
    private String pageKind;
    private View pageOpener;

    private List<Item> allApps = new ArrayList<>(), allInputs = new ArrayList<>();
    private Map<String, Long> recent = new HashMap<>();
    private Tile movingTile;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    /** Waking the TV: a fresh greeting, even when this screen never left the front. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (resumed) greet();
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final Shelf.Listener shelfListener = new Shelf.Listener() {
        @Override
        public void moved(Shelf shelf, List<String> ids) {
            ids.remove(Item.AIRPLAY);
            Catalog.saveOrder(cfg, shelf.kind, ids);
            applied = cfg.version();
        }

        @Override
        public void selected(Shelf shelf, Item item) {
            if (resumed && pageKind == null && greeted) {
                preview.show(item, shelf.kind, cfg.get(Config.PREVIEW));
                // An input with nothing on it: "No signal" where the greeting was.
                boolean dead = Catalog.INPUTS.equals(shelf.kind) && !Item.AIRPLAY.equals(item.id)
                        && item.state != android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
                noSignal.setVisibility(dead ? View.VISIBLE : View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        Future<Map<String, Long>> times = io.submit(() -> Recent.times(this));
        cfg = Config.get(this);
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        covers = new Covers(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.PAGE));
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        Keeper.start(this);
        Ceiling.listen(this); // remembers music apps' audio sessions for the ceiling over inputs
        Tube.watch(this); // notes SmartTube's video, for carrying it over a game
        Lounge.get(this).start(); // a screen the YouTube app can cast to (Options › Link a phone)
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        allInputs = Catalog.inputs(this, tv);
    }

    /**
     * Waits for the app list and loads the art that shows first, so the first frame is complete
     * rather than popping panels in a moment later.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        try {
            allApps = catalog.get();
            recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = 8;
        for (Item it : visibleApps()) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it);
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        if (cfg.version() != applied) apply();
        refreshRecent();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        greet();
        shown = this;
        ui.removeCallbacks(airplayWatch);
        ui.post(airplayWatch);
        backFromDetour(); // after the setup above, which onPause undoes
        if (warming) {
            warming = false;
            ui.postDelayed(Guard::shown, 400); // back from Spotify's warm-up: lift the cover
        } else {
            ui.postDelayed(this::warmSpotify, 4000);
        }
        wasAway = false;
    }

    private boolean warming;

    /**
     * Once a boot: opens Spotify behind a picture of this screen and comes back, so the TV is on
     * the phone's Spotify Connect list (it is only while Spotify has run since the boot). Nothing
     * is seen of it: the guard's cover shows this screen as it was the whole time.
     */
    private void warmSpotify() {
        if (!resumed) return;
        if (!"on".equals(cfg.get(Config.MUSIC))) return;
        int boot = android.provider.Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        if (boot < 0 || String.valueOf(boot).equals(cfg.get("spotify.warmed", ""))) return;
        Intent spotify = getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY);
        if (spotify == null) spotify = getPackageManager().getLaunchIntentForPackage(SPOTIFY);
        if (spotify == null) return;
        View root = getWindow().getDecorView();
        if (root.getWidth() == 0) return;
        android.graphics.Bitmap look;
        try {
            look = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(look));
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        Intent open = spotify.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        cfg.set("spotify.warmed", String.valueOf(boot));
        warming = Guard.holdCover(look, SPOTIFY_WARM_MS, () -> {
            try {
                startActivity(open, android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            } catch (ActivityNotFoundException | SecurityException e) {
                warming = false;
            }
        });
        android.util.Log.i("dumbtv", "spotify warm-up: " + (warming ? "started" : "no guard"));
    }

    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final long SPOTIFY_WARM_MS = 4000;

    /** Shows the greeting; the preview waits behind it until the first press. */
    private void greet() {
        greeting.setText(Greetings.pick(this, cfg));
        greeting.animate().cancel();
        greeting.setAlpha(1f);
        greeting.setVisibility(pageKind == null ? View.VISIBLE : View.GONE);
        greeted = false;
        preview.stop();
        noSignal.setVisibility(View.GONE);
        quiet(true);
    }

    private void quiet(boolean on) {
        for (Shelf s : new Shelf[] {apps, inputs}) {
            if (s != null) s.setQuiet(on);
        }
    }

    /** The first press: the greeting fades and the selected panel's preview takes its place. */
    private void ungreet() {
        if (greeted) return;
        greeted = true;
        quiet(false);
        greeting.animate().alpha(0f).setDuration(250).withEndAction(() -> greeting.setVisibility(View.GONE)).start();
        View focus = getCurrentFocus();
        if (focus instanceof Shelf && ((Shelf) focus).current() != null) {
            shelfListener.selected((Shelf) focus, ((Shelf) focus).current());
        }
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    /** Lets go of the previewed input, so the app or input being opened can have it. */
    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(airplayWatch);
        if (shown == this) shown = null;
        resumed = false;
        preview.stop();
        unregisterReceiver(time);
        if (movingTile != null) stopMovingTile();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(screenOn);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    /**
     * Back from an app that Menu on an input sent the viewer to: straight on to that input.
     * Runs from onNewIntent, or from onResume when HOME brought this screen back without one.
     */
    private boolean backFromDetour() {
        if (!wasAway) return false;
        boolean detour = Input.onDetour(this);
        android.util.Log.i("dumbtv", "home from away: detour=" + detour);
        if (!detour || !"game".equals(cfg.get(Config.HOME_TWICE))) return false;
        Intent back = Input.last(this);
        if (back == null) return false;
        back.putExtra(Input.EXTRA_DETOUR, true);
        wasAway = false;
        homeFromAway = 0;
        startActivity(back);
        return true;
    }

    /** HOME while the home screen is showing: back to the first panel. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME twice from another app: straight back to the input last shown (the game, with
        // music picked in Spotify). The first press, from away, only arms it.
        long now = android.os.SystemClock.uptimeMillis();
        boolean backToGame = "game".equals(cfg.get(Config.HOME_TWICE));
        // Menu on an input sent the viewer off to pick music or a video: one HOME comes back.
        android.util.Log.i("dumbtv", "home intent: away=" + wasAway
                + " sinceArmed=" + (homeFromAway == 0 ? -1 : now - homeFromAway));
        if (backFromDetour()) return;
        boolean twice = homeFromAway != 0 && now - homeFromAway < HOME_TWICE_MS;
        // Away means hidden for a while, in another app; Amazon's home flashing over this one on
        // the second press hides it only for a moment.
        homeFromAway = !twice && wasAway && !warming ? now : 0; // not armed by the warm-up's return
        wasAway = false;
        if (twice && backToGame) {
            Intent back = Input.last(this);
            if (back != null) {
                homeFromAway = 0;
                startActivity(back);
                return;
            }
        }
        if (movingTile != null) stopMovingTile();
        if (resumed) {
            closePage(false);
            focusFirst(true);
            greet();
        }
    }

    @Override
    public void onBackPressed() {
        if (pageKind != null) closePage(true);
        else focusFirst(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // The first press only wakes the screen: the greeting goes and the selection appears,
        // without moving or opening anything. Up is the exception: it goes to the top bar.
        if (!greeted && e.getAction() == KeyEvent.ACTION_DOWN) {
            ungreet();
            if (e.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP) {
                wakeKey = e.getKeyCode();
                return true;
            }
        }
        if (wakeKey != -1 && e.getKeyCode() == wakeKey) {
            if (e.getAction() == KeyEvent.ACTION_UP) wakeKey = -1;
            return true; // its repeats and release
        }
        if (movingTile == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shiftTile(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shiftTile(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    int cols = pageGrid.columns();
                    shiftTile(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    break;
                default:
                    stopMovingTile();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Shelf) itemMenu((Shelf) f, null);
            else if (f instanceof Tile) itemMenu(null, (Tile) f);
            else mainMenu();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------- Building ----------

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAGE);
        glow = new Glow(this);
        root.addView(glow, new FrameLayout.LayoutParams(0, 0));
        preview = new Preview(this);
        preview.setGlow(glow);
        root.addView(preview, new FrameLayout.LayoutParams(0, 0));
        noSignal = Ui.text(this, 28, 0xFFB4B4BC, Ui.REGULAR);
        noSignal.setText("No signal");
        noSignal.setGravity(Gravity.CENTER);
        noSignal.setVisibility(View.GONE);
        root.addView(noSignal, new FrameLayout.LayoutParams(0, 0));
        greeting = Ui.text(this, 40, 0xFFB4B4BC, Ui.REGULAR);
        greeting.setLetterSpacing(-0.02f);
        greeting.setGravity(Gravity.CENTER);
        root.addView(greeting, new FrameLayout.LayoutParams(0, 0));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        root.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top bar: menu, wordmark and clock at the left; Apps and Settings at the right.
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(gutter(), dp(18), gutter(), dp(6)); // a slim bar: more room for the preview
        menuBtn = new IconButton(this, IconButton.MENU, "Menu");
        menuBtn.setOnClickListener(v -> mainMenu());
        bar.addView(menuBtn);
        TextView word = Ui.title(this, 32);
        word.setText(WORDMARK);
        // 34 sp: the word, "y" included, about fills the buttons' height.
        float wordSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 34,
                getResources().getDisplayMetrics());
        LinearLayout.LayoutParams wordLp = centreInk(word, wordSize, WORDMARK, dp(IconButton.SIZE_DP));
        wordLp.leftMargin = dp(12); // the same space as between the buttons
        bar.addView(word, wordLp);
        appsBtn = new IconButton(this, IconButton.APPS, "Apps");
        appsBtn.setOnClickListener(v -> openPage(Catalog.APPS, v));
        LinearLayout.LayoutParams after = new LinearLayout.LayoutParams(WRAP, WRAP);
        after.leftMargin = dp(12);
        bar.addView(appsBtn, after);
        settingsBtn = new IconButton(this, IconButton.SETTINGS, "Settings");
        settingsBtn.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(WRAP, WRAP);
        gap.leftMargin = dp(12);
        bar.addView(settingsBtn, gap);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock = Ui.text(this, 36, CLOCK, Ui.SEMIBOLD);
        clock.setLetterSpacing(-0.02f);
        bar.addView(clock, centreInk(clock, wordSize, "0", dp(IconButton.SIZE_DP)));
        for (View b : new View[] {menuBtn, appsBtn, settingsBtn}) b.setId(View.generateViewId());
        column.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout body = new FrameLayout(this);
        body.setClipChildren(false);
        column.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // The panels sit at the bottom; the preview takes the room above them.
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setGravity(Gravity.BOTTOM);
        home.setClipChildren(false);
        home.setClipToPadding(false);
        body.addView(home, new FrameLayout.LayoutParams(MATCH, MATCH));
        empty = Ui.text(this, 18, Ui.MUTED, Ui.MEDIUM);
        empty.setText("Nothing here yet. Press ▲ and pick Apps.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 0, 0, dp(160));
        home.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));

        // The Apps page: a title with a count, then a grid.
        page = new ScrollView(this);
        page.setFocusable(false);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        LinearLayout pageColumn = new LinearLayout(this);
        pageColumn.setOrientation(LinearLayout.VERTICAL);
        pageColumn.setClipChildren(false);
        pageColumn.setClipToPadding(false);
        pageColumn.setPadding(gutter(), dp(10), gutter(), dp(48));
        LinearLayout pageHeader = new LinearLayout(this);
        pageHeader.setBaselineAligned(true);
        pageTitle = Ui.title(this, 30);
        pageCount = Ui.text(this, 17, Ui.MUTED, Ui.MEDIUM);
        pageHeader.addView(pageTitle);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        countLp.leftMargin = dp(10);
        pageHeader.addView(pageCount, countLp);
        pageColumn.addView(pageHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
        pageGrid = new Grid(this);
        pageGrid.setPadding(0, dp(22), 0, 0);
        pageColumn.addView(pageGrid, new LinearLayout.LayoutParams(MATCH, WRAP));
        page.addView(pageColumn, new FrameLayout.LayoutParams(MATCH, WRAP));
        page.setVisibility(View.GONE);
        body.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> ui.post(this::placePreview));
        return root;
    }

    /**
     * The preview fills the room between the top bar and the panels, as a 16:9 window centred
     * across the screen. Too little room (both shelves shown) hides it.
     */
    private void placePreview() {
        View first = null;
        for (View s : new View[] {apps, inputs}) {
            if (s != null && s.getVisibility() == View.VISIBLE && first == null) first = s;
        }
        int top = bar.getBottom() + dp(6);
        int bottom = first == null ? 0 : topIn(first) - dp(10);
        int h = bottom - top;
        int w = Math.min(Math.round(h * 16f / 9f), root.getWidth() - 2 * gutter());
        h = Math.round(w * 9f / 16f);
        boolean room = first != null && h >= dp(120) && pageKind == null;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        int want = room ? w : 0, wantH = room ? h : 0;
        int left = (root.getWidth() - w) / 2, y = bottom - h;
        if (lp.width != want || lp.height != wantH || lp.leftMargin != left || lp.topMargin != y) {
            lp.width = want;
            lp.height = wantH;
            lp.leftMargin = left;
            lp.topMargin = y;
            preview.setLayoutParams(lp);
        }
        preview.setVisibility(room ? View.VISIBLE : View.INVISIBLE);
        // The glow: the preview's rectangle, spread by the glow's padding.
        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) glow.getLayoutParams();
        int ex = Math.round(w * Glow.PAD_X / (float) Glow.SAMPLE_W), ey = Math.round(h * Glow.PAD_Y / (float) Glow.SAMPLE_H);
        int gw = room ? w + 2 * ex : 0, ghh = room ? h + 2 * ey : 0;
        if (gl.width != gw || gl.height != ghh || gl.leftMargin != left - ex || gl.topMargin != y - ey) {
            gl.width = gw;
            gl.height = ghh;
            gl.leftMargin = left - ex;
            gl.topMargin = y - ey;
            glow.setLayoutParams(gl);
        }
        // The greeting takes the preview's place, between the top bar and the panels.
        FrameLayout.LayoutParams g = (FrameLayout.LayoutParams) greeting.getLayoutParams();
        int gh = Math.max(0, bottom - top);
        if (g.width != root.getWidth() || g.height != gh || g.topMargin != top) {
            g.width = root.getWidth();
            g.height = gh;
            g.topMargin = top;
            greeting.setLayoutParams(g);
            noSignal.setLayoutParams(new FrameLayout.LayoutParams(g));
        }
    }

    /**
     * Sets {@code v} to {@code size} px and centres its ink (not its line box) on a row of
     * {@code height}, without the text box making the row taller.
     */
    private static LinearLayout.LayoutParams centreInk(TextView v, float size, String sample, int height) {
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        android.graphics.Paint.FontMetrics fm = v.getPaint().getFontMetrics();
        android.graphics.Rect r = new android.graphics.Rect();
        v.getPaint().getTextBounds(sample, 0, sample.length(), r);
        float box = fm.descent - fm.ascent;
        int squeeze = Math.max(0, Math.round((box - height) / 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = -squeeze;
        lp.bottomMargin = -squeeze;
        float inkCentre = -fm.ascent + (r.top + r.bottom) / 2f;
        v.setTranslationY(box / 2 - inkCentre);
        return lp;
    }

    private int topIn(View v) {
        int y = 0;
        while (v != null && v != root) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    /** The margin round the screen: the same at the top, the sides and under the panels. */
    private int gutter() {
        return dp(28);
    }

    private int panelHeight() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return 81;
            case "large":
                return 126;
            default:
                return 99;
        }
    }

    // ---------- Contents ----------

    private void apply() {
        applied = cfg.version();
        apps = remake(apps, Catalog.APPS);
        inputs = remake(inputs, Catalog.INPUTS);
        render();
    }

    /** (Re)makes a shelf, for a new panel size, keeping the panel it was on. */
    private Shelf remake(Shelf old, String kind) {
        String keep = old == null || old.current() == null ? null : old.current().id;
        int index = Catalog.APPS.equals(kind) ? 0 : 1;
        if (old != null) {
            index = home.indexOfChild(old);
            home.removeView(old);
        }
        Shelf s = new Shelf(this, kind, dp(panelHeight()), covers);
        s.setId(View.generateViewId());
        s.setTag(keep);
        s.setListener(shelfListener);
        s.setQuiet(!greeted);
        s.setOnClickListener(v -> {
            Item it = s.current();
            if (it != null) open(it, kind);
        });
        s.setOnLongClickListener(v -> {
            itemMenu(s, null);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        if (Catalog.INPUTS.equals(kind)) lp.bottomMargin = 0;
        home.addView(s, Math.min(index, home.getChildCount()), lp);
        return s;
    }

    private List<Item> visibleApps() {
        return Catalog.visible(cfg, Catalog.APPS, allApps, "recent".equals(cfg.get(Config.ORDER)) ? recent : null);
    }

    private List<Item> visibleInputs() {
        return "on".equals(cfg.get(Config.INPUTS)) ? Catalog.visible(cfg, Catalog.INPUTS, allInputs)
                : new ArrayList<>();
    }

    private List<Item> visible(String kind) {
        return Catalog.APPS.equals(kind) ? visibleApps() : Catalog.visible(cfg, Catalog.INPUTS, allInputs);
    }

    /** Fills both shelves and decides which show. */
    private void render() {
        if (apps == null) return;
        List<Item> in = visibleInputs();
        List<Item> ap = visibleApps();
        // With no inputs to show, the apps shelf shows anyway, so home is never empty.
        boolean showApps = "on".equals(cfg.get(Config.APPS_SHELF)) || in.isEmpty();
        // Inputs with something live on them jump the line, ahead of the rest (stable: each group
        // keeps your order).
        java.util.Collections.sort(in, (a, b) -> Boolean.compare(live(b), live(a)));
        // AirPlay always closes the inputs row.
        if ("on".equals(cfg.get(Config.INPUTS))) in.add(Catalog.airplay(this, cfg, allApps));
        fill(inputs, in);
        fill(apps, showApps ? ap : new ArrayList<>());
        for (Item it : ap) {
            if (!it.artRequested) loadArt(it);
        }
        boolean appsOn = !apps.isEmpty(), inputsOn = !inputs.isEmpty();
        apps.setVisibility(appsOn ? View.VISIBLE : View.GONE);
        inputs.setVisibility(inputsOn ? View.VISIBLE : View.GONE);
        // Two shelves overlap a little: the lower panels stand in the upper one's faded reflection.
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) inputs.getLayoutParams();
        int overlap = appsOn && inputsOn ? -Math.round(dp(panelHeight()) * 0.45f) : 0;
        if (lp.topMargin != overlap) {
            lp.topMargin = overlap;
            inputs.setLayoutParams(lp);
        }
        // The lowest panels' bottom edge sits one margin above the screen's bottom, like the top
        // bar below its top; their reflection fades out past the screen's edge.
        Shelf lowest = inputsOn ? inputs : apps;
        int drop = -Math.max(0, Math.round(dp(panelHeight()) * 0.7f) - gutter());
        for (Shelf sh : new Shelf[] {apps, inputs}) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) sh.getLayoutParams();
            int want = sh == lowest ? drop : 0;
            if (p.bottomMargin != want) {
                p.bottomMargin = want;
                sh.setLayoutParams(p);
            }
        }
        empty.setVisibility(appsOn || inputsOn ? View.GONE : View.VISIBLE);
        wireFocus();
        rebuildPage();
        View f = getCurrentFocus();
        if (pageKind == null && (f == null || !landed || (f instanceof Shelf && ((Shelf) f).isEmpty()) || !f.isShown())) {
            focusFirst(false);
        }
        placePreview();
    }

    private static boolean live(Item it) {
        return it.state == TvInputManager.INPUT_STATE_CONNECTED;
    }

    private void fill(Shelf s, List<Item> items) {
        List<CharSequence> names = new ArrayList<>();
        for (Item it : items) names.add(Catalog.label(cfg, it));
        s.setItems(items, names, (String) s.getTag());
        s.setTag(null);
    }

    /** Down from the top bar lands on the first shelf; up and down move shelf to shelf. */
    private void wireFocus() {
        List<Shelf> shown = new ArrayList<>();
        for (Shelf s : new Shelf[] {apps, inputs}) {
            if (s.getVisibility() == View.VISIBLE) shown.add(s);
        }
        int firstShelf = shown.isEmpty() ? View.NO_ID : shown.get(0).getId();
        for (View v : new View[] {menuBtn, appsBtn, settingsBtn}) v.setNextFocusDownId(firstShelf);
        for (int i = 0; i < shown.size(); i++) {
            Shelf s = shown.get(i);
            s.setNextFocusDownId(i + 1 < shown.size() ? shown.get(i + 1).getId() : s.getId());
            s.setNextFocusUpId(i > 0 ? shown.get(i - 1).getId() : appsBtn.getId());
        }
    }

    private void focusFirst(boolean toStart) {
        Shelf first = apps.getVisibility() == View.VISIBLE ? apps
                : inputs.getVisibility() == View.VISIBLE ? inputs : null;
        if (first != null && !first.isEmpty()) {
            if (toStart) first.jumpTo(0);
            first.requestFocus();
            landed = true;
        } else {
            appsBtn.requestFocus();
        }
    }

    private void tick() {
        Date now = new Date();
        clock.setText(DateFormat.is24HourFormat(this) ? DateFormat.format("HH:mm", now)
                : DateFormat.format("h:mm", now));
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : allApps) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                        }
                    }
                }
                allApps = list;
                render();
            });
        });
    }

    private void loadInputs() {
        allInputs = Catalog.inputs(this, tv);
        render();
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(() -> {
                if (apps != null) apps.invalidate();
                for (int i = 0; i < pageGrid.getChildCount(); i++) pageGrid.getChildAt(i).invalidate();
            });
        });
    }

    /** Re-reads when apps were last opened: the grey lines on the Apps page, and the order. */
    private void refreshRecent() {
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed()) return;
                boolean changed = !times.equals(recent);
                recent = times;
                if (changed && "recent".equals(cfg.get(Config.ORDER))) render();
            });
        });
    }

    // ---------- Apps page ----------

    private void openPage(String kind, View opener) {
        pageKind = kind;
        pageOpener = opener;
        preview.stop();
        noSignal.setVisibility(View.GONE);
        pageTitle.setText(Catalog.APPS.equals(kind) ? "Apps" : "Inputs");
        home.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        page.scrollTo(0, 0);
        placePreview();
        rebuildPage();
        View focus = pageGrid.getChildCount() > 0 ? pageGrid.getChildAt(0) : appsBtn;
        focus.requestFocus();
    }

    private void closePage(boolean refocus) {
        if (pageKind == null) return;
        if (movingTile != null) stopMovingTile();
        pageKind = null;
        page.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        pageGrid.removeAllViews();
        placePreview();
        if (refocus && pageOpener != null && pageOpener.isShown()) pageOpener.requestFocus();
        else if (refocus) focusFirst(false);
    }

    private void rebuildPage() {
        if (pageKind == null) return;
        View f = getCurrentFocus();
        String keep = f instanceof Tile ? ((Tile) f).item.id : null;
        pageGrid.removeAllViews();
        List<Item> items = visible(pageKind);
        pageCount.setText(String.valueOf(items.size()));
        int cols = "small".equals(cfg.get(Config.TILES)) ? 5 : "large".equals(cfg.get(Config.TILES)) ? 3 : 4;
        int width = getResources().getDisplayMetrics().widthPixels - 2 * gutter();
        int cardW = (width - (cols - 1) * dp(18)) / cols - 1;
        View restore = null;
        for (Item it : items) {
            Tile t = new Tile(this, it, pageKind, Catalog.label(cfg, it),
                    Covers.subtitle(it, pageKind, recent), cardW, covers);
            t.setOnClickListener(v -> open(((Tile) v).item, ((Tile) v).kind));
            t.setOnLongClickListener(v -> {
                itemMenu(null, (Tile) v);
                return true;
            });
            pageGrid.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            if (it.id.equals(keep)) restore = t;
            if (Catalog.APPS.equals(pageKind) && !it.artRequested) loadArt(it);
        }
        if (restore != null) restore.requestFocus();
        else if (keep != null && pageGrid.getChildCount() > 0) pageGrid.getChildAt(0).requestFocus();
    }

    // ---------- Menus ----------

    private void mainMenu() {
        InkMenu.show(this, menuBtn,
                new String[] {"Options", null, "Amazon's home screen"},
                new Runnable[] {
                    () -> startActivity(new Intent(this, Options.class)),
                    null,
                    () -> Guard.amazon(this),
                });
    }

    /** Move, Rename, Hide, App info: for the selected panel, or a tile on the Apps page. */
    private void itemMenu(Shelf shelf, Tile tile) {
        Item it = shelf != null ? shelf.current() : tile.item;
        String kind = shelf != null ? shelf.kind : tile.kind;
        if (it == null || Item.AIRPLAY.equals(it.id)) return;
        boolean app = Catalog.APPS.equals(kind);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Move");
        actions.add(() -> {
            if (app && "recent".equals(cfg.get(Config.ORDER))) {
                Toast.makeText(this, "App order is Recently used. Pick Your order in Options to move panels.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (shelf != null) {
                shelf.setMoving(true);
            } else if (tile.getParent() != null) {
                movingTile = tile;
                tile.setMoving(true);
            }
        });
        labels.add("Rename");
        actions.add(() -> rename(it));
        labels.add("Hide");
        actions.add(() -> {
            Catalog.setShown(cfg, kind, it, false);
            applied = cfg.version();
            render();
            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
        });
        if (app) {
            labels.add("App info");
            actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", it.id, null)), null));
        }
        // Above the selected panel, lined up with its left edge.
        int[] at = new int[2];
        int x, y;
        if (shelf != null) {
            shelf.getLocationOnScreen(at);
            x = Math.round(at[0] + shelf.getWidth() / 2f - shelf.coverWidth() / 2);
            y = Math.max(0, at[1] - dp(50) * labels.size() - dp(20));
        } else {
            tile.getLocationOnScreen(at);
            x = at[0];
            y = at[1] + tile.getHeight();
        }
        InkMenu.show(this, x, y, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void rename(Item it) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, it));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + it.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(it.label.toString()) ? null : name);
                    applied = cfg.version();
                    render();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    applied = cfg.version();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shiftTile(int by) {
        ViewGroup grid = (ViewGroup) movingTile.getParent();
        int from = grid.indexOfChild(movingTile), to = from + by;
        if (to < 0 || to >= grid.getChildCount()) return;
        ViewGroup.LayoutParams lp = movingTile.getLayoutParams();
        grid.removeViewAt(from);
        grid.addView(movingTile, to, lp);
        movingTile.requestFocus();
    }

    private void stopMovingTile() {
        Tile t = movingTile;
        movingTile = null;
        t.setMoving(false);
        ViewGroup grid = (ViewGroup) t.getParent();
        if (grid == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) ids.add(((Tile) grid.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
        render();
    }

    // ---------- Opening things ----------

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Item it, String kind) {
        if (Item.AIRPLAY.equals(it.id)) {
            // AirPlay itself needs nothing opened: the TV receives whenever it is on. When its screen
            // cannot be opened from here, the TV's settings are the next best place.
            if (it.receiver == null || !launch(new Intent(it.intent), it.receiver, false)) {
                Toast.makeText(this, "AirPlay is ready whenever this TV is on. Its settings: "
                        + "Display & Sounds › Apple AirPlay & HomeKit.", Toast.LENGTH_LONG).show();
                if (it.receiver != null) openSettings();
            }
            return;
        }
        if (Catalog.APPS.equals(kind)) {
            launch(new Intent(it.intent), it.id);
            return;
        }
        Intent own = Input.intent(this, cfg, it, Catalog.label(cfg, it));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(it.intent), null);
    }

    private void launch(Intent i, String pkg) {
        launch(i, pkg, true);
    }

    /** Starts {@code i}; false when Android refuses it. */
    private boolean launch(Intent i, String pkg, boolean complain) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            android.util.Log.w("dumbtvtest", "could not open " + i, e);
            if (complain) Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
