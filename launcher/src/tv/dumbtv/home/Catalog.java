package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where tiles come from, and which of them the user wants on the home screen. */
final class Catalog {
    static final String APPS = "apps", INPUTS = "inputs";

    private Catalog() {}

    /** Every launchable app, TV apps first; drawables are left for {@link #art}. */
    static List<Item> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Item> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, true, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, false, c.getPackageName());
        List<Item> out = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    private static void collect(PackageManager pm, Map<String, Item> found, String category,
            boolean leanback, String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name);
            Item it = new Item(ai.packageName, ri.loadLabel(pm), launch);
            it.info = ai;
            it.leanback = leanback;
            found.put(ai.packageName, it);
        }
    }

    /** Loads a banner, or an icon when the app ships no banner. Slow: call off the UI thread. */
    static void art(PackageManager pm, Item it) {
        if (it.info == null) return;
        it.banner = it.info.loadBanner(pm);
        if (it.banner == null) it.icon = it.info.loadIcon(pm);
    }

    /**
     * HDMI, AV and other passthrough inputs; HDMI first in port order. The intent is the TV's own
     * app for the input; {@link Input#intent} decides whether a tile opens that or the built-in
     * screen.
     */
    static List<Item> inputs(Context c, TvInputManager tv) {
        List<Item> out = new ArrayList<>();
        if (tv == null) return out;
        List<Item> other = new ArrayList<>();
        // A device found over HDMI-CEC (a PS5, a soundbar) is listed as a child input of its
        // port, labelled with the name the device reports. Its port's tile takes that name, so
        // it reads "PS5" rather than "HDMI 2", and the device is not listed a second time.
        Map<String, CharSequence> device = new HashMap<>();
        Map<Item, String> port = new HashMap<>();
        for (TvInputInfo in : tv.getTvInputList()) {
            if (in.getParentId() == null || !in.isPassthroughInput()) continue;
            CharSequence name = in.loadLabel(c);
            if (!TextUtils.isEmpty(name) && !device.containsKey(in.getParentId())) device.put(in.getParentId(), name);
        }
        for (TvInputInfo in : tv.getTvInputList()) {
            if (!in.isPassthroughInput() || in.isHidden(c) || in.getParentId() != null) continue;
            CharSequence label = device.get(in.getId());
            if (TextUtils.isEmpty(label)) label = in.loadCustomLabel(c);
            if (TextUtils.isEmpty(label)) label = in.loadLabel(c);
            Intent view = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(in.getId()));
            Item it = new Item(in.getId(), label, view);
            it.state = tv.getInputState(in.getId());
            port.put(it, in.loadLabel(c).toString());
            (in.getType() == TvInputInfo.TYPE_HDMI ? out : other).add(it);
        }
        Collator collator = Collator.getInstance();
        // By port ("HDMI 1", "HDMI 2"), so a tile keeps its place when its device's name changes.
        Collections.sort(out, (a, b) -> collator.compare(port.get(a), port.get(b)));
        Collections.sort(other, (a, b) -> collator.compare(port.get(a), port.get(b)));
        out.addAll(other);
        return out;
    }

    /**
     * The AirPlay panel. Fire TVs with built-in "AirPlay & HomeKit" receive AirPlay by
     * themselves; the panel opens their AirPlay screen. It looks, in order, for: the package
     * named by {@code airplay.app} in home.conf; a launchable app whose name mentions AirPlay or
     * HomeKit (built in, or a receiver such as AirScreen); any installed package with "airplay"
     * in its name that can be launched. Failing those it opens the TV's settings, where AirPlay
     * & HomeKit is set up. Package names are never assumed.
     */
    static Item airplay(Context c, Config cfg, List<Item> apps) {
        PackageManager pm = c.getPackageManager();
        String pinned = cfg.get("airplay.app", "");
        if (pinned.contains("/")) {
            // A screen, as package/activity: e.g. the "Apple AirPlay & HomeKit" settings page,
            // read off the TV with `adb shell dumpsys window | grep mCurrentFocus`.
            android.content.ComponentName screen = android.content.ComponentName.unflattenFromString(pinned);
            if (screen != null) {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(screen);
                if (i.resolveActivity(pm) != null) return airplayItem(i, screen.getPackageName());
            }
        } else if (!pinned.isEmpty()) {
            Intent i = launchFor(pm, pinned);
            if (i != null) return airplayItem(i, pinned);
        }
        for (Item app : apps) {
            String name = app.label.toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("airplay") || name.contains("homekit") || name.contains("airscreen")
                    || name.contains("airreceiver") || name.contains("air receiver")) {
                return airplayItem(app.intent, app.id);
            }
        }
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!ai.packageName.toLowerCase(java.util.Locale.ROOT).contains("airplay")) continue;
            Intent i = launchFor(pm, ai.packageName);
            if (i != null) return airplayItem(i, ai.packageName);
        }
        Intent settings = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (settings.resolveActivity(pm) == null) settings = new Intent(android.provider.Settings.ACTION_SETTINGS);
        return airplayItem(settings, null);
    }

    private static Intent launchFor(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static Item airplayItem(Intent open, String pkg) {
        Item it = new Item(Item.AIRPLAY, "AirPlay", open);
        it.receiver = pkg;
        return it;
    }

    static CharSequence label(Config cfg, Item it) {
        return cfg.get("name." + it.id, it.label.toString());
    }

    static boolean shown(Config cfg, String kind, Item it) {
        if (cfg.list(kind + ".hide").contains(it.id)) return false;
        return !APPS.equals(kind) || it.leanback || cfg.list("apps.show").contains(it.id);
    }

    static void setShown(Config cfg, String kind, Item it, boolean on) {
        List<String> hide = cfg.list(kind + ".hide");
        hide.remove(it.id);
        if (!on && (it.leanback || !APPS.equals(kind))) hide.add(it.id);
        cfg.setList(kind + ".hide", hide);
        if (APPS.equals(kind) && !it.leanback) {
            List<String> show = cfg.list("apps.show");
            show.remove(it.id);
            if (on) show.add(it.id);
            cfg.setList("apps.show", show);
        }
    }

    /** The shown items, in the user's order; anything never moved keeps catalog order. */
    static List<Item> visible(Config cfg, String kind, List<Item> all) {
        return visible(cfg, kind, all, null);
    }

    /** As above, then newest first by {@code recent} (package to last-used time) when it is set. */
    static List<Item> visible(Config cfg, String kind, List<Item> all, Map<String, Long> recent) {
        List<String> order = cfg.list(kind + ".order");
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (shown(cfg, kind, it)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Integer.compare(rank(order, a.id), rank(order, b.id)));
        // The sort is stable, so apps with no record keep the user's order after the rest.
        if (recent != null) Collections.sort(out, (a, b) -> Recent.compare(recent, a.id, b.id));
        return out;
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Saves {@code ids} as the new order, keeping the old positions of hidden items after them. */
    static void saveOrder(Config cfg, String kind, List<String> ids) {
        List<String> order = new ArrayList<>(ids);
        for (String id : cfg.list(kind + ".order")) {
            if (!order.contains(id)) order.add(id);
        }
        cfg.setList(kind + ".order", order);
    }
}
