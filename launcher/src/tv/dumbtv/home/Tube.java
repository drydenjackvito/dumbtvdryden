package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps a note of the video SmartTube is playing, from the home screen's process, which stays
 * running. Without background playback SmartTube ends its session the moment it is left, before
 * the input screen can ask; the note still says which video it was and how far in.
 */
final class Tube {
    private static final long FRESH_MS = 120_000;
    private static boolean watching;
    private static final List<MediaController> watched = new ArrayList<>();

    private static volatile String id;
    private static volatile long position, updatedAt, frozenAt;
    private static volatile float speed;
    private static volatile boolean playing;

    private Tube() {}

    static void watch(Context c) {
        if (watching) return;
        MediaSessionManager sm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(c, Listener.class);
        Handler ui = new Handler(Looper.getMainLooper());
        try {
            sm.addOnActiveSessionsChangedListener(Tube::sessions, listener, ui);
            sessions(sm.getActiveSessions(listener));
            watching = true;
        } catch (SecurityException e) {
            // No notification access: nothing to note, the input screen asks SmartTube directly.
        }
    }

    private static void sessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController m : list) {
            if (!m.getPackageName().toLowerCase(Locale.ROOT).contains("smarttube")) continue;
            boolean known = false;
            for (MediaController w : watched) known |= w.getSessionToken().equals(m.getSessionToken());
            if (known) continue;
            watched.add(m);
            m.registerCallback(new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata md) {
                    note(m);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState st) {
                    note(m);
                }

                @Override
                public void onSessionDestroyed() {
                    freeze();
                    watched.remove(m);
                }
            });
            note(m);
        }
    }

    private static void note(MediaController m) {
        MediaMetadata md = m.getMetadata();
        String v = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        PlaybackState st = m.getPlaybackState();
        if (v == null || !v.matches("[A-Za-z0-9_-]{6,20}") || st == null) return;
        id = v;
        position = st.getPosition();
        updatedAt = st.getLastPositionUpdateTime() > 0 ? st.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
        speed = st.getPlaybackSpeed();
        playing = st.getState() == PlaybackState.STATE_PLAYING;
        frozenAt = 0;
    }

    /** SmartTube let its session go: the video stopped where it had got to by now. */
    private static void freeze() {
        if (id == null) return;
        position = at();
        playing = false;
        frozenAt = updatedAt = SystemClock.elapsedRealtime();
    }

    private static long at() {
        long p = position;
        if (playing) p += (long) ((SystemClock.elapsedRealtime() - updatedAt) * speed);
        return Math.max(0, p);
    }

    /** The video SmartTube played in the last two minutes, with no session to pause; or null. */
    static Music.Clip last() {
        String v = id;
        if (v == null || SystemClock.elapsedRealtime() - updatedAt > FRESH_MS) return null;
        return new Music.Clip(v, at(), null);
    }
}
