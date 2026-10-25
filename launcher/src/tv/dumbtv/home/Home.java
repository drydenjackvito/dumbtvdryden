package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The home screen, laid out like the Physeek app: a top bar (menu, the wordmark, Apps and
 * Settings) over coverflow shelves, and a View All page that shows one shelf as a grid.
 */
public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** The name in the middle of the top bar. */
    static final String WORDMARK = "Tomboy";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private TvInputManager tv;
    private Covers covers;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;

    private IconButton menuBtn, appsBtn, settingsBtn;
    private ScrollView home, page;
    private LinearLayout homeColumn;
    private Block apps, inputs;
    private TextView empty;
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

    /** One shelf on the home screen: its title, View All, and the coverflow. */
    private final class Block extends LinearLayout {
        final String kind;
        final TextView viewAll;
        Shelf shelf;
        /** The middle cover's id across a rebuild of the shelf. */
        String keep;

        Block(String kind, String name) {
            super(Home.this);
            this.kind = kind;
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout header = new LinearLayout(Home.this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(gutter(), 0, gutter() - dp(10), 0);
            TextView title = Ui.title(Home.this, 28);
            title.setText(name);
            header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
            viewAll = Ui.text(Home.this, 16, Ui.MUTED, Ui.MEDIUM);
            viewAll.setText("View All  ›");
            viewAll.setPadding(dp(14), dp(8), dp(14), dp(8));
            viewAll.setFocusable(true);
            viewAll.setClickable(true);
            viewAll.setId(View.generateViewId());
            pill(viewAll);
            viewAll.setOnClickListener(v -> openPage(kind, v));
            header.addView(viewAll);
            addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** (Re)makes the shelf, for a new cover size. */
        void makeShelf() {
            if (shelf != null) {
                Item at = shelf.current();
                keep = at == null ? null : at.id;
                removeView(shelf);
            }
            shelf = new Shelf(Home.this, kind, dp(coverHeight()), covers);
            shelf.setId(View.generateViewId());
            shelf.setOnClickListener(v -> {
                Item it = shelf.current();
                if (it != null) open(it, kind);
            });
            shelf.setOnLongClickListener(v -> {
                itemMenu(shelf, null);
                return true;
            });
            shelf.setListener((s, ids) -> {
                Catalog.saveOrder(cfg, kind, ids);
                applied = cfg.version();
                rebuildPage();
            });
            viewAll.setNextFocusDownId(shelf.getId());
            shelf.setNextFocusUpId(viewAll.getId());
            addView(shelf, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

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
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        allInputs = Catalog.inputs(this, tv);
    }

    /**
     * Waits for the app list and loads the art that shows first, so the first frame is complete
     * rather than popping covers in a moment later.
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
        int fit = 12;
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
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (movingTile != null) stopMovingTile();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
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

    /** HOME while the home screen is showing: back to the first shelf. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (movingTile != null) stopMovingTile();
        if (resumed) {
            closePage(false);
            focusFirst(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (pageKind != null) closePage(true);
        else focusFirst(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAGE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        root.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top bar: menu at the left, the wordmark in the middle, Apps and Settings at the right.
        FrameLayout bar = new FrameLayout(this);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(gutter(), dp(28), gutter(), dp(10));
        menuBtn = new IconButton(this, IconButton.MENU, "Menu");
        menuBtn.setOnClickListener(v -> mainMenu());
        bar.addView(menuBtn, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView word = Ui.title(this, 32);
        word.setText(WORDMARK);
        bar.addView(word, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        LinearLayout actions = new LinearLayout(this);
        actions.setClipChildren(false);
        appsBtn = new IconButton(this, IconButton.APPS, "Apps");
        appsBtn.setOnClickListener(v -> openPage(Catalog.APPS, v));
        settingsBtn = new IconButton(this, IconButton.SETTINGS, "Settings");
        settingsBtn.setOnClickListener(v -> openSettings());
        actions.addView(appsBtn);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(WRAP, WRAP);
        gap.leftMargin = dp(12);
        actions.addView(settingsBtn, gap);
        bar.addView(actions, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END | Gravity.CENTER_VERTICAL));
        for (View b : new View[] {menuBtn, appsBtn, settingsBtn}) b.setId(View.generateViewId());
        column.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout body = new FrameLayout(this);
        body.setClipChildren(false);
        column.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        home = new ScrollView(this) {
            /** Scrolls a whole shelf at a time: the focused one starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return shelfTarget(this, r) - getScrollY();
            }

            /** Up and down only move the focus; scrolling follows it. */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        scrollStyle(home);
        homeColumn = new LinearLayout(this);
        homeColumn.setOrientation(LinearLayout.VERTICAL);
        homeColumn.setClipChildren(false);
        homeColumn.setClipToPadding(false);
        homeColumn.setPadding(0, dp(14), 0, dp(40));
        home.addView(homeColumn, new FrameLayout.LayoutParams(MATCH, WRAP));
        body.addView(home, new FrameLayout.LayoutParams(MATCH, MATCH));

        apps = new Block(Catalog.APPS, "Apps");
        inputs = new Block(Catalog.INPUTS, "Inputs");
        homeColumn.addView(apps, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(MATCH, WRAP);
        below.topMargin = dp(10);
        homeColumn.addView(inputs, below);
        empty = Ui.text(this, 18, Ui.MUTED, Ui.MEDIUM);
        empty.setText("Nothing here yet. Press ▲ and pick Apps.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(120), 0, 0);
        homeColumn.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));

        // The View All page: a title with a count, then a grid.
        page = new ScrollView(this);
        scrollStyle(page);
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
        return root;
    }

    private void scrollStyle(ScrollView s) {
        s.setFocusable(false);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
    }

    /** Physeek's "View All": grey text, filled with ink when focused. */
    private void pill(TextView v) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(Ui.INK, dp(18)));
        bg.addState(new int[0], new ColorDrawable(0));
        v.setBackground(bg);
        v.setTextColor(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {Ui.SURFACE, Ui.MUTED}));
    }

    /** The scroll position that shows the shelf holding {@code r} from its title down. */
    private int shelfTarget(ScrollView s, Rect r) {
        int range = Math.max(0, homeColumn.getHeight() - s.getHeight());
        int target = 0;
        boolean first = true;
        for (Block b : new Block[] {apps, inputs}) {
            if (b.getVisibility() != View.VISIBLE) continue;
            if (r.centerY() >= b.getTop()) target = first ? 0 : b.getTop() - dp(6);
            first = false;
        }
        return Math.max(0, Math.min(range, target));
    }

    private int gutter() {
        return dp(48);
    }

    private int coverHeight() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return 126;
            case "large":
                return 180;
            default:
                return 153;
        }
    }

    // ---------- Contents ----------

    private void apply() {
        applied = cfg.version();
        apps.makeShelf();
        inputs.makeShelf();
        render();
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
        if (apps.shelf == null) return;
        List<Item> in = visibleInputs();
        List<Item> ap = visibleApps();
        // With no inputs to show, the apps shelf shows anyway, so home is never empty.
        boolean showApps = "on".equals(cfg.get(Config.APPS_SHELF)) || in.isEmpty();
        fill(inputs, in);
        fill(apps, showApps ? ap : new ArrayList<>());
        for (Item it : ap) {
            if (!it.artRequested) loadArt(it);
        }
        boolean appsOn = !apps.shelf.isEmpty(), inputsOn = !inputs.shelf.isEmpty();
        apps.setVisibility(appsOn ? View.VISIBLE : View.GONE);
        inputs.setVisibility(inputsOn ? View.VISIBLE : View.GONE);
        empty.setVisibility(appsOn || inputsOn ? View.GONE : View.VISIBLE);
        wireFocus();
        rebuildPage();
        View f = getCurrentFocus();
        if (pageKind == null && (f == null || !landed || (f instanceof Shelf && ((Shelf) f).isEmpty()) || !f.isShown())) {
            focusFirst(false);
        }
    }

    private void fill(Block b, List<Item> items) {
        List<CharSequence> names = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Item it : items) {
            names.add(Catalog.label(cfg, it));
            lines.add(Covers.subtitle(it, b.kind, recent));
        }
        b.shelf.setItems(items, names, lines, b.keep);
        b.keep = null;
    }

    /** Down from the top bar lands on the first shelf; up and down move shelf to shelf. */
    private void wireFocus() {
        List<Block> shown = new ArrayList<>();
        for (Block b : new Block[] {apps, inputs}) {
            if (b.getVisibility() == View.VISIBLE) shown.add(b);
        }
        int firstShelf = shown.isEmpty() ? View.NO_ID : shown.get(0).shelf.getId();
        for (View v : new View[] {menuBtn, appsBtn, settingsBtn}) v.setNextFocusDownId(firstShelf);
        for (int i = 0; i < shown.size(); i++) {
            Block b = shown.get(i);
            b.shelf.setNextFocusDownId(i + 1 < shown.size() ? shown.get(i + 1).shelf.getId() : b.shelf.getId());
            b.viewAll.setNextFocusUpId(i > 0 ? shown.get(i - 1).shelf.getId() : appsBtn.getId());
            b.viewAll.setNextFocusLeftId(b.viewAll.getId());
            b.viewAll.setNextFocusRightId(b.viewAll.getId());
        }
    }

    private void focusFirst(boolean toStart) {
        home.scrollTo(0, 0);
        Shelf first = apps.getVisibility() == View.VISIBLE ? apps.shelf
                : inputs.getVisibility() == View.VISIBLE ? inputs.shelf : null;
        if (first != null && !first.isEmpty()) {
            if (toStart) first.jumpTo(0);
            first.requestFocus();
            landed = true;
        } else {
            appsBtn.requestFocus();
        }
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
                apps.shelf.invalidate();
                for (int i = 0; i < pageGrid.getChildCount(); i++) pageGrid.getChildAt(i).invalidate();
            });
        });
    }

    /** Re-reads when apps were last opened: the grey lines, and the order if it follows them. */
    private void refreshRecent() {
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed()) return;
                boolean reorder = "recent".equals(cfg.get(Config.ORDER)) && !times.equals(recent);
                recent = times;
                if (reorder) {
                    render();
                } else if (!apps.shelf.isEmpty()) {
                    List<String> lines = new ArrayList<>();
                    for (Item it : visibleApps()) lines.add(Covers.subtitle(it, Catalog.APPS, recent));
                    apps.shelf.setSubtitles(lines);
                }
            });
        });
    }

    // ---------- View All page ----------

    private void openPage(String kind, View opener) {
        pageKind = kind;
        pageOpener = opener;
        pageTitle.setText(Catalog.APPS.equals(kind) ? "Apps" : "Inputs");
        home.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        page.scrollTo(0, 0);
        rebuildPage();
        // Open on the cover that was in the middle of the shelf, if there is one.
        Block b = Catalog.APPS.equals(kind) ? apps : inputs;
        Item at = b.getVisibility() == View.VISIBLE ? b.shelf.current() : null;
        View focus = pageGrid.getChildCount() > 0 ? pageGrid.getChildAt(0) : appsBtn;
        for (int i = 0; i < pageGrid.getChildCount(); i++) {
            if (at != null && ((Tile) pageGrid.getChildAt(i)).item == at) focus = pageGrid.getChildAt(i);
        }
        focus.requestFocus();
    }

    private void closePage(boolean refocus) {
        if (pageKind == null) return;
        if (movingTile != null) stopMovingTile();
        pageKind = null;
        page.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        pageGrid.removeAllViews();
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
                new String[] {"Options", "Fire TV settings", null, "Amazon's home screen"},
                new Runnable[] {
                    () -> startActivity(new Intent(this, Options.class)),
                    this::openSettings,
                    null,
                    () -> Guard.amazon(this),
                });
    }

    /** Move, Rename, Hide, App info: for the middle cover of a shelf, or a tile on the page. */
    private void itemMenu(Shelf shelf, Tile tile) {
        Item it = shelf != null ? shelf.current() : tile.item;
        String kind = shelf != null ? shelf.kind : tile.kind;
        if (it == null) return;
        boolean app = Catalog.APPS.equals(kind);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Move");
        actions.add(() -> {
            if (app && "recent".equals(cfg.get(Config.ORDER))) {
                Toast.makeText(this, "App order is Recently used. Pick Your order in Options to move covers.",
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
        // Under the cover, lined up with its left edge, like Physeek's "more" menu.
        int[] at = new int[2];
        int x, y;
        if (shelf != null) {
            shelf.getLocationOnScreen(at);
            float coverW = dp(coverHeight()) * 16f / 9f;
            x = Math.round(at[0] + shelf.getWidth() / 2f - coverW / 2);
            y = at[1] + shelf.getHeight() - dp(40);
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
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
