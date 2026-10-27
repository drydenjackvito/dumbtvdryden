package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * What music app is playing (Spotify, or any app with a media session), and its buttons: play,
 * pause, next, previous. Needs the notification access that {@link Listener} stands for; without
 * it, nothing is found and nothing happens.
 */
final class Music {
    interface Watcher {
        /** The song or its state changed; {@code changedSong} when it is a different song. */
        void changed(boolean changedSong);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final Watcher watcher;
    private MediaController controller;
    private String song;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata m) {
            String now = key(m);
            boolean changed = now != null && !now.equals(song);
            song = now;
            watcher.changed(changed);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            watcher.changed(false);
        }

        @Override
        public void onSessionDestroyed() {
            pick();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener moved = list -> pick();

    Music(Context c, Watcher watcher) {
        this.watcher = watcher;
        sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(c, Listener.class);
    }

    /** Starts following the music app; false when access was not given. */
    boolean start() {
        try {
            sessions.addOnActiveSessionsChangedListener(moved, listener, ui);
        } catch (SecurityException | NullPointerException e) {
            return false;
        }
        pick();
        return true;
    }

    void stop() {
        try {
            sessions.removeOnActiveSessionsChangedListener(moved);
        } catch (RuntimeException ignored) {
        }
        follow(null);
    }

    /** The playing session, or else the first one that has a song loaded. */
    private void pick() {
        List<MediaController> all;
        try {
            all = sessions.getActiveSessions(listener);
        } catch (SecurityException e) {
            all = null;
        }
        MediaController best = null;
        if (all != null) {
            for (MediaController m : all) {
                if (playing(m)) {
                    best = m;
                    break;
                }
                if (best == null && m.getMetadata() != null) best = m;
            }
        }
        follow(best);
        watcher.changed(false);
    }

    private void follow(MediaController m) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = m;
        song = m == null ? null : key(m.getMetadata());
        if (m != null) m.registerCallback(callback, ui);
    }

    boolean has() {
        return controller != null && controller.getMetadata() != null;
    }

    boolean playing() {
        return playing(controller);
    }

    /** Whether music is playing right now, in any app. */
    boolean anyPlaying() {
        try {
            for (MediaController m : sessions.getActiveSessions(listener)) {
                if (playing(m)) return true;
            }
        } catch (SecurityException | NullPointerException ignored) {
        }
        return false;
    }

    private static boolean playing(MediaController m) {
        PlaybackState s = m == null ? null : m.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    String title() {
        return text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    String artist() {
        return text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    }

    Bitmap art() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        if (m == null) return null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART}) {
            Bitmap b = m.getBitmap(k);
            if (b != null && !logo(b)) return b;
        }
        return null;
    }

    /**
     * Whether {@code b} is the app's logo rather than a cover: Spotify sends its round logo until
     * the album art loads. Covers are solid squares; a logo has see-through corners.
     */
    private static boolean logo(Bitmap b) {
        if (!b.hasAlpha() || b.isRecycled() || "HARDWARE".equals(String.valueOf(b.getConfig()))) return false;
        int w = b.getWidth() - 1, h = b.getHeight() - 1;
        int[][] corners = {{0, 0}, {w, 0}, {0, h}, {w, h}};
        for (int[] c : corners) {
            if (android.graphics.Color.alpha(b.getPixel(c[0], c[1])) < 128) return true;
        }
        return false;
    }

    private String text(String a, String b) {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        if (m == null) return null;
        CharSequence v = m.getText(a);
        if (v == null || v.length() == 0) v = m.getText(b);
        return v == null ? null : v.toString();
    }

    private static String key(MediaMetadata m) {
        if (m == null) return null;
        return m.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + m.getString(MediaMetadata.METADATA_KEY_ARTIST);
    }

    /** The playing app's package, for its audio session. */
    String app() {
        return controller == null ? null : controller.getPackageName();
    }

    void playPause() {
        if (controller == null) return;
        if (playing()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }
}
