package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * A volume ceiling for the music app alone, under a game: an equalizer on the music app's own
 * audio session with every band cut by the same amount. The TV's volume stays the master; the
 * input's sound is untouched, so the game can be heard over the music.
 *
 * Music apps announce their audio session for equalizers (ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
 * when they start playing; the home screen, always running, listens and remembers them. With no
 * announcement heard, the ceiling goes on the output mix (session 0), which on a TV carries the
 * apps' sound and, on most, not the HDMI input's.
 */
final class Ceiling {
    /** 0 dB down to the equalizer's floor, in 1 dB steps. */
    static final int STEP_DB = 1, FLOOR_DB = 15;
    static final String KEY = "music.ceiling";

    private static final Map<String, Integer> SESSIONS = new HashMap<>();

    private static final BroadcastReceiver HEARD = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            if (pkg == null || session < 0) return;
            synchronized (SESSIONS) {
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) SESSIONS.put(pkg, session);
                else if (Integer.valueOf(session).equals(SESSIONS.get(pkg))) SESSIONS.remove(pkg);
            }
            SHARED.follow(pkg);
        }
    };
    private static boolean listening;

    /** Starts remembering music apps' sessions; called by the home screen, which stays running. */
    static void listen(Context c) {
        if (listening) return;
        IntentFilter f = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        c.getApplicationContext().registerReceiver(HEARD, f);
        listening = true;
    }

    /**
     * The one ceiling, kept by the home screen's process rather than the input screen, so the
     * music stays where it was set when HOME leaves the game, and follows the app onto each new
     * audio session it announces.
     */
    static final Ceiling SHARED = new Ceiling();

    private Equalizer eq;
    private String pkg;
    private int db;
    private int on = -1;
    /** The level the equalizer has now; -1 when it is new. */
    private int set = -1;

    /** Puts the ceiling, {@code db} below full (0 to FLOOR_DB), on {@code pkg}'s music. */
    void apply(String pkg, int db) {
        if (Radio.capIfMine(pkg, db)) { // Untitled, played by Tomboy: its page turns itself down
            release();
            this.pkg = null;
            return;
        }
        this.pkg = pkg;
        this.db = db;
        Integer session;
        synchronized (SESSIONS) {
            session = pkg == null ? null : SESSIONS.get(pkg);
        }
        int want = session != null ? session : 0;
        // Follow the session the app plays on now: its own once announced (it can come after the
        // music starts, and changes when the app starts a new one), else the output mix. An
        // equalizer left on an old session turns down nothing.
        if (eq != null && (on != want || !alive())) release();
        if (eq != null && db == set) return; // nothing changed: the app's updates come many a second
        if (eq == null) {
            eq = make(want);
            on = want;
            if (eq == null && session != null) {
                eq = make(0);
                on = 0;
            }
            if (eq == null) return;
        }
        try {
            short[] range = eq.getBandLevelRange();
            short level = (short) Math.max(range[0], -db * 100);
            for (short b = 0; b < eq.getNumberOfBands(); b++) eq.setBandLevel(b, level);
            eq.setEnabled(db > 0);
            set = db;
            Log.i("dumbtv", "music ceiling " + db + " dB on session " + on);
        } catch (RuntimeException e) {
            Log.w("dumbtv", "music ceiling", e);
            release(); // made again on the next press
        }
    }

    /** Whether the equalizer still works and still has control (another may have taken it). */
    private boolean alive() {
        try {
            return eq.hasControl();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Equalizer make(int session) {
        try {
            Equalizer e = new Equalizer(1000, session);
            Log.i("dumbtv", "music ceiling on session " + session);
            return e;
        } catch (RuntimeException e) {
            Log.w("dumbtv", "no equalizer on session " + session, e);
            return null;
        }
    }

    /** The set app announced a new audio session: the ceiling moves onto it. */
    private void follow(String announced) {
        if (pkg != null && pkg.equals(announced) && db > 0) apply(pkg, db);
    }

    /** Lets go of the equalizer; the music is back to full. */
    void release() {
        set = -1;
        if (eq == null) return;
        try {
            eq.setEnabled(false);
            eq.release();
        } catch (RuntimeException ignored) {
        }
        eq = null;
    }
}
