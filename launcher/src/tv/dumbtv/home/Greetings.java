package tv.dumbtv.home;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The greeting in the middle of the home screen, picked at random from the lines for the time of
 * day. The lines come from greetings.txt: one pushed into the launcher's media folder
 * (/sdcard/Android/media/tv.dumbtv.home/, where adb can write on Fire OS 8) wins over the one
 * built in (res/raw/greetings.txt). Sections look like "[evening] 17:00-21:00"; a section may
 * run past midnight, as "[night] 21:00-05:00" does.
 */
final class Greetings {
    static final String FILE = "greetings.txt";
    /** Whose TV this is; greeting.name in home.conf overrides it. */
    static final String DEFAULT_NAME = "Dryden";
    private static final Random RANDOM = new Random();

    private Greetings() {}

    static String pick(Context c, Config cfg) {
        String text = read(cfg.sharedFile(FILE));
        if (text == null) text = builtIn(c);
        String line = pick(text, minuteOfDay());
        String name = cfg.get("greeting.name", DEFAULT_NAME).trim();
        return name(line == null ? "Hello, {name}" : line, name);
    }

    /** A random line from the section that covers {@code minute} (minutes since midnight). */
    static String pick(String text, int minute) {
        List<String> lines = new ArrayList<>();
        boolean inside = false;
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("[")) {
                inside = covers(l, minute);
                continue;
            }
            if (inside) lines.add(l);
        }
        return lines.isEmpty() ? null : lines.get(RANDOM.nextInt(lines.size()));
    }

    /** Whether a header such as "[night] 21:00-05:00" covers {@code minute}. */
    static boolean covers(String header, int minute) {
        String range = header.substring(header.indexOf(']') + 1).trim();
        int dash = range.indexOf('-');
        if (dash < 0) return false;
        int from = minutes(range.substring(0, dash)), to = minutes(range.substring(dash + 1));
        if (from < 0 || to < 0) return false;
        return from <= to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.trim().split(":");
        try {
            return Integer.parseInt(p[0].trim()) * 60 + (p.length > 1 ? Integer.parseInt(p[1].trim()) : 0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Puts the name in; with no name, "{name}" goes with the ", " or space before it. */
    static String name(String line, String name) {
        if (!name.isEmpty()) return line.replace("{name}", name);
        return line.replaceAll(",?\\s*\\{name\\}", "").trim();
    }

    private static int minuteOfDay() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private static String builtIn(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.greetings)) {
            return slurp(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return slurp(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String slurp(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
