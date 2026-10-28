package tv.dumbtv.home;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All settings live in one plain {@code key=value} file in the app's external files dir,
 * so {@code adb pull}/{@code adb push} is the whole export/import story and a hand edit is
 * picked up on the next return to the home screen. Where adb cannot reach that folder
 * (Fire OS 8), the same file can be pushed into the app's media folder instead.
 */
final class Config {
    static final String FILE = "home.conf";

    static final class Choice {
        final String key, title;
        final String[] values, names;
        final String fallback;

        Choice(String key, String title, String fallback, String[] values, String[] names) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.values = values;
            this.names = names;
        }

        String name(String value) {
            int i = Arrays.asList(values).indexOf(value);
            return i < 0 ? value : names[i];
        }
    }

    static final Choice TILES = new Choice("tiles", "Panel size", "medium",
            new String[] {"small", "medium", "large"},
            new String[] {"Small", "Medium", "Large"});
    static final Choice ORDER = new Choice("order", "App order", "custom",
            new String[] {"custom", "recent"},
            new String[] {"Your order", "Recently used"});
    /** The Apps button is always there; this adds a coverflow shelf of apps above Inputs. */
    static final Choice APPS_SHELF = new Choice("apps.shelf", "Apps shelf", "off",
            new String[] {"off", "on"},
            new String[] {"Hidden", "Shown"});
    static final Choice INPUTS = new Choice("inputs", "Inputs shelf", "on",
            new String[] {"on", "off"},
            new String[] {"Shown", "Hidden"});
    /** The live picture of the selected input above the panels. */
    static final Choice PREVIEW = new Choice("preview", "Input preview", "holo",
            new String[] {"holo", "on", "off"},
            new String[] {"Hologram", "Plain", "Off"});
    /** Over an input: a music card, and the remote's media keys skip songs in Spotify and the like. */
    static final Choice MUSIC = new Choice("music", "Music over inputs", "on",
            new String[] {"on", "off"},
            new String[] {"On", "Off"});
    /** The music card over an input: shown for a few seconds on a change, or all the time. */
    static final Choice MINI_SHOW = new Choice("mini.show", "Mini-player", "auto",
            new String[] {"auto", "always"},
            new String[] {"On changes", "Always"});
    static final Choice MINI_SIZE = new Choice("mini.size", "Mini-player size", "medium",
            new String[] {"small", "medium", "large"},
            new String[] {"Small", "Medium", "Large"});
    static final Choice MINI_PLACE = new Choice("mini.place", "Mini-player position", "br",
            new String[] {"br", "bl", "tr", "tl"},
            new String[] {"Bottom right", "Bottom left", "Top right", "Top left"});
    /** The video carried over a game: decoded in software, so sharper costs more of the TV. */
    static final Choice VIDEO_QUALITY = new Choice("video.quality", "Video quality", "720",
            new String[] {"360", "480", "720", "1080"},
            new String[] {"360p", "480p", "720p", "1080p"});
    /**
     * Who draws the video carried over a game: Tomboy, decoding it in software (works wherever
     * the TV's hardware decoder can't show beside the input), or YouTube's own player.
     */
    static final Choice VIDEO_PICTURE = new Choice("video.picture", "Video picture", "youtube",
            new String[] {"tomboy", "youtube"},
            new String[] {"Tomboy's decoder", "YouTube's player"});
    /** HOME pressed twice from another app (Spotify, say) goes back to the input last shown. */
    static final Choice HOME_TWICE = new Choice("home.twice", "Home twice", "game",
            new String[] {"game", "off"},
            new String[] {"Back to the last input", "Home screen"});
    static final Choice SWITCH = new Choice("switch", "Open inputs with", "auto",
            new String[] {"auto", "tv", "home"},
            new String[] {"Automatic", "The TV's app", "dumbtv home"});

    static final Choice[] CHOICES = {TILES, ORDER, APPS_SHELF, INPUTS, PREVIEW, MUSIC, MINI_SHOW, MINI_SIZE,
            MINI_PLACE, VIDEO_QUALITY, VIDEO_PICTURE, HOME_TWICE, SWITCH};

    private static Config instance;

    final File dir;
    private final File file;
    /** The app's media folder, /sdcard/Android/media/tv.dumbtv.home: adb can write there. */
    private final File shared;
    private final File sharedFile;
    /** What was last applied from the media folder, so the same push is not applied twice. */
    private final File appliedFile;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long signature = Long.MIN_VALUE;
    private int version;

    static Config get(Context c) {
        if (instance == null) instance = new Config(c.getApplicationContext());
        return instance;
    }

    private Config(Context c) {
        File external = c.getExternalFilesDir(null);
        dir = external != null ? external : c.getFilesDir();
        file = new File(dir, FILE);
        // Fire OS 8 (Android 11) hides /sdcard/Android/data/<pkg> from adb, so nothing can be
        // pushed into the app's own folder. The app's media folder is still writable by adb,
        // so it is used as a second source: a settings file
        // pushed there is merged in once.
        File[] media = c.getExternalMediaDirs();
        shared = (media != null && media.length > 0 && media[0] != null) ? media[0] : null;
        sharedFile = shared == null ? null : new File(shared, FILE);
        appliedFile = new File(dir, FILE + ".applied");
        refresh();
    }

    /** {@code name} in the media folder adb can write to, or in the app's own folder without one. */
    File sharedFile(String name) {
        File f = shared == null ? null : new File(shared, name);
        return f != null && f.isFile() ? f : new File(dir, name);
    }

    /** Changes every time a setting changes, from the UI or from the file on disk. */
    int version() {
        return version;
    }

    void refresh() {
        long sig = signature();
        if (sig == signature) return;
        signature = sig;
        values.clear();
        if (file.isFile()) read();
        if (mergeShared()) save();
        version++;
    }

    /** Both files count: a settings file pushed into the media folder has to be noticed too. */
    private long signature() {
        long sig = file.lastModified() * 31 + file.length();
        if (sharedFile != null) sig = sig * 31 + hash(sharedFile);
        return sig;
    }

    /** Content hash, so a push is noticed even when it carries an old timestamp. */
    private static long hash(File f) {
        if (!f.isFile()) return 0;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Arrays.hashCode(out.toByteArray());
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Takes the settings from a file pushed into the media folder, the one folder adb can still
     * write on Fire OS 8. The push is applied once: what was applied is remembered, so the same
     * file is not applied again and a later change on the TV is not overwritten by it.
     */
    private boolean mergeShared() {
        if (sharedFile == null || !sharedFile.isFile()) return false;
        Map<String, String> incoming = parse(sharedFile);
        if (incoming.isEmpty()) return false;
        if (incoming.equals(parse(appliedFile))) return false;
        values.putAll(incoming);
        writeSettings(appliedFile, incoming);
        return true;
    }

    private void read() {
        values.putAll(parse(file));
    }

    private static Map<String, String> parse(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not read " + f, e);
        }
        return out;
    }

    private void save() {
        writeSettings(file, values);
        signature = signature();
    }

    private static void writeSettings(File target, Map<String, String> settings) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# dumbtv home. Edit on the TV (Options) or here; unknown keys are kept.\n");
            for (Map.Entry<String, String> e : settings.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not write " + tmp, e);
            return;
        }
        if (!tmp.renameTo(target)) Log.w("dumbtv", "could not replace " + target);
    }

    String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    String get(Choice c) {
        String v = values.get(c.key);
        return v != null && Arrays.asList(c.values).contains(v) ? v : c.fallback;
    }

    void set(String key, String value) {
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
        version++;
        save();
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    void setList(String key, List<String> items) {
        // String.join is Android 8+; TextUtils.join keeps Fire OS 6 (Android 7.1) working.
        set(key, TextUtils.join(",", items));
    }

    void removePrefix(String... prefixes) {
        for (Iterator<String> it = values.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            for (String p : prefixes) {
                if (k.startsWith(p)) {
                    it.remove();
                    break;
                }
            }
        }
        version++;
        save();
    }

    /** The page colour, also used by Guard's cover so HOME shows no flash of another colour. */
    int background() {
        return 0xFFF5F5F7;
    }
}
