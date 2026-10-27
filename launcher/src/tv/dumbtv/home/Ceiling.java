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
    /** 0 dB down to the equalizer's floor, in 3 dB steps. */
    static final int STEP_DB = 3, FLOOR_DB = 15;
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

    private Equalizer eq;
    private int on = -1;

    /** Puts the ceiling, {@code db} below full (0 to FLOOR_DB), on {@code pkg}'s music. */
    void apply(String pkg, int db) {
        Integer session;
        synchronized (SESSIONS) {
            session = pkg == null ? null : SESSIONS.get(pkg);
        }
        int want = session != null ? session : 0;
        // The app's own session can be announced after the music starts: move onto it then.
        if (eq != null && on != want && session != null) release();
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
        } catch (RuntimeException e) {
            Log.w("dumbtv", "music ceiling", e);
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

    /** Lets go: the music is back to full the moment the input leaves. */
    void release() {
        if (eq == null) return;
        try {
            eq.setEnabled(false);
            eq.release();
        } catch (RuntimeException ignored) {
        }
        eq = null;
    }
}
