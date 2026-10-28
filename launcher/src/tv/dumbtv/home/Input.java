package tv.dumbtv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Shows an HDMI, AV or other passthrough input inside the launcher, through Android's TvView.
 *
 * Handing the passthrough channel URI to the TV's own app is not enough everywhere: on a Fire TV
 * Edition television that app is Amazon's Live TV, which lands in its channel-scan settings when
 * no channels are scanned, and Amazon's home opens inputs through a service that needs a system
 * permission. TvView is the public API for the same thing: it asks the TV input service for a
 * session and hands it this window's surface. No permission is involved, and the picture goes away
 * with this screen, so HOME and BACK behave like everywhere else in the launcher.
 */
public final class Input extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String EXTRA_ID = "input", EXTRA_NAME = "name";
    private static final long NAME_MS = 2500, SIGNAL_MS = 6000;
    private static final String FAILED = "Could not open input", NO_SIGNAL = "No signal";
    private static final String AMAZON_LIVE_TV = "com.amazon.tv.livetv";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener focus = change -> { };
    private TvView view;
    private Music music;
    private final Ceiling ceiling = Ceiling.SHARED; // outlives this screen: HOME keeps the music down
    private int ceilingDb;
    private MiniPlayer player;
    private VideoWindow video;
    /** Set by HOME coming back from an app Menu opened: carry SmartTube's video over. */
    static final String EXTRA_DETOUR = "detour";
    private final Runnable hidePlayer = () -> player.conceal();
    private static final long PLAYER_MS = 5000;
    /** Options › Mini-player › Always: the card stays while there is music. */
    private boolean always;
    /** Whether music was playing at the last look, to catch it starting. */
    private boolean wasPlaying;

    /**
     * Back from SmartTube by HOME (after Menu sent the viewer there): its video goes on in the
     * corner from where it was, and SmartTube is paused, as the TV is about to close it anyway.
     */
    private boolean carryVideo() {
        boolean detour = getIntent().getBooleanExtra(EXTRA_DETOUR, false);
        getIntent().removeExtra(EXTRA_DETOUR); // once: not again on coming back to this screen
        if (!detour) return false;
        Music.Clip clip = music == null ? null : music.clip();
        if (clip == null) clip = Tube.last(); // SmartTube may have let its session go already
        android.util.Log.i("dumbtv", "carry video: " + (clip == null ? "none" : clip.id + " at " + clip.atMs));
        if (clip == null) return false;
        try {
            if (clip.from != null) clip.from.getTransportControls().pause();
        } catch (RuntimeException ignored) {
        }
        ceilingDb = Integer.parseInt(Config.get(this).get(Ceiling.KEY, "6"));
        video.play(clip.id, clip.atMs, videoVolume());
        return true;
    }

    /** The ceiling as the player's volume, 0 to 100: -6 dB is half as loud. */
    private int videoVolume() {
        return (int) Math.round(100 * Math.pow(10, -ceilingDb / 20.0));
    }

    /** Puts the remembered ceiling on the music playing now. */
    private void capMusic() {
        ceilingDb = Integer.parseInt(Config.get(this).get(Ceiling.KEY, "6"));
        ceiling.apply(music.app(), ceilingDb);
    }
    private TextView name, status;
    private String id;
    private final Runnable hideName = () -> name.animate().alpha(0f).setDuration(250)
            .withEndAction(() -> name.setVisibility(View.GONE)).start();
    private final Runnable noSignal = () -> status(NO_SIGNAL);

    private final TvView.TvInputCallback watch = new TvView.TvInputCallback() {
        @Override
        public void onConnectionFailed(String inputId) {
            status(FAILED);
        }

        @Override
        public void onDisconnected(String inputId) {
            status(FAILED);
        }

        @Override
        public void onVideoAvailable(String inputId) {
            status(null);
        }

        @Override
        public void onVideoUnavailable(String inputId, int reason) {
            // Tuning and buffering are on the way to a picture; everything else means none is coming.
            boolean pending = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                    || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING;
            if (!pending) status(NO_SIGNAL);
        }
    };

    /**
     * The intent that opens {@code it} on this screen, or null when the TV's own app should get the
     * passthrough URI: always for "tv" if one exists, and for "auto" only when a single app other
     * than Amazon's Live TV claims it.
     */
    static Intent intent(Context c, Config cfg, Item it, CharSequence name) {
        String mode = cfg.get(Config.SWITCH);
        List<ResolveInfo> apps = c.getPackageManager().queryIntentActivities(it.intent, 0);
        boolean handOver = "tv".equals(mode) ? !apps.isEmpty()
                : "auto".equals(mode) && apps.size() == 1 && !viewerIsLiveTv(apps.get(0));
        if (handOver) return null;
        return new Intent(c, Input.class).putExtra(EXTRA_ID, it.id).putExtra(EXTRA_NAME, name);
    }

    private static final String LAST_ID = "last.input", LAST_NAME = "last.input.name";

    /** Remembers the input on screen, for HOME twice to come back to. */
    private static void remember(Context c, String id, CharSequence name) {
        Config cfg = Config.get(c);
        cfg.set(LAST_ID, id);
        cfg.set(LAST_NAME, name == null ? "" : name.toString());
    }

    private static final String DETOUR = "detour.at";
    private static final long DETOUR_MS = 30 * 60 * 1000;

    /**
     * Whether Menu sent the viewer from this screen to another app (to pick music or a video)
     * not long ago: HOME then comes straight back here. Asking uses it up.
     */
    static boolean onDetour(Context c) {
        Config cfg = Config.get(c);
        long at;
        try {
            at = Long.parseLong(cfg.get(DETOUR, "0"));
        } catch (NumberFormatException e) {
            at = 0;
        }
        if (at == 0) return false;
        cfg.set(DETOUR, "0");
        long ago = System.currentTimeMillis() - at;
        return ago >= 0 && ago < DETOUR_MS;
    }

    /** The input last shown on this screen, opened again; null when there was none. */
    static Intent last(Context c) {
        Config cfg = Config.get(c);
        String id = cfg.get(LAST_ID, "");
        if (id.isEmpty()) return null;
        return new Intent(c, Input.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, cfg.get(LAST_NAME, ""));
    }

    /**
     * Amazon's Live TV claims every passthrough URI, then redirects to its own settings or a
     * channel scan instead of showing the input (seen on Fire OS 7 and 8 televisions).
     */
    private static boolean viewerIsLiveTv(ResolveInfo app) {
        return app.activityInfo != null && AMAZON_LIVE_TV.equals(app.activityInfo.packageName);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(EXTRA_ID);
        // The video is a layer behind this window; a transparent window lets the text over it blend
        // instead of punching black boxes into the picture.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK); // the home screen behind is light now
        if (getSystemService(Context.TV_INPUT_SERVICE) != null) {
            view = new TvView(this);
            view.setCallback(watch);
            view.setFocusable(true);
            root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        status = Ui.text(this, 22, Ui.DIM_TEXT, Ui.MEDIUM);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        name = Ui.text(this, 20, Ui.TEXT, Ui.MEDIUM);
        name.setText(getIntent().getCharSequenceExtra(EXTRA_NAME));
        name.setPadding(dp(22), dp(10), dp(22), dp(10));
        name.setBackground(Ui.round(0xB3000000, dp(10)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(56), dp(40), 0, 0);
        root.addView(name, lp);

        // Music over the input: a card at the bottom right, and the remote's media keys for it.
        Config cfg = Config.get(this);
        String size = cfg.get(Config.MINI_SIZE), place = cfg.get(Config.MINI_PLACE);
        always = "always".equals(cfg.get(Config.MINI_SHOW));
        player = new MiniPlayer(this, "small".equals(size) ? 0.8f : "large".equals(size) ? 1.3f : 1f);
        int gravity = (place.startsWith("t") ? Gravity.TOP : Gravity.BOTTOM)
                | (place.endsWith("l") ? Gravity.START : Gravity.END);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(WRAP, WRAP, gravity);
        pl.setMargins(dp(28), dp(28), dp(28), dp(28)); // the home screen's own margin
        root.addView(player, pl);
        // A SmartTube video, carried over when HOME comes back from it (see onStart).
        video = new VideoWindow(this, "small".equals(size) ? 0.8f : "large".equals(size) ? 1.3f : 1f, code ->
                android.widget.Toast.makeText(this, "That video only plays in YouTube's own app",
                        android.widget.Toast.LENGTH_LONG).show());
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(video.getLayoutParams().width,
                video.getLayoutParams().height, gravity);
        vl.setMargins(dp(28), dp(28), dp(28), dp(28));
        root.addView(video, vl);
        if ("on".equals(Config.get(this).get(Config.MUSIC))) {
            music = new Music(this, changedSong -> {
                refreshPlayer();
                // Music can start after the input opened: from Spotify on a phone (Connect), say.
                // The card then appears and the ceiling goes on, as if it had been playing.
                boolean playing = music.has() && music.playing();
                boolean started = playing && !wasPlaying;
                wasPlaying = playing;
                if (playing) capMusic(); // again on each change: the app's session may come later
                if (!music.has()) {
                    ui.removeCallbacks(hidePlayer);
                    player.conceal();
                } else if (changedSong || started || (always && player.getVisibility() != View.VISIBLE)) {
                    autoShowPlayer();
                }
            });
        }
        setContentView(root);
    }

    @Override
    @SuppressWarnings("deprecation") // the AudioFocusRequest replacement is Android 8+, Fire OS 6 is 7.1
    protected void onStart() {
        super.onStart();
        showName();
        if (view == null || id == null) {
            status(FAILED);
            return;
        }
        boolean musicOn = music != null && music.start() && music.anyPlaying();
        boolean videoOn = carryVideo();
        remember(this, id, getIntent().getCharSequenceExtra(EXTRA_NAME));
        Config.get(this).set(DETOUR, "0"); // back on the input: no detour pending
        wasPlaying = musicOn;
        if (musicOn) {
            if (always) autoShowPlayer();
            capMusic();
        }
        // Taking audio focus stops other apps' sound; with music playing, it is left alone so the
        // music keeps going over the game.
        if (!musicOn && !videoOn) {
            ((AudioManager) getSystemService(Context.AUDIO_SERVICE))
                    .requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        view.setStreamVolume(1f);
        view.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        view.requestFocus();
        // Most input services report why there is no picture; this covers one that stays silent.
        ui.postDelayed(noSignal, SIGNAL_MS);
    }

    @Override
    protected void onDestroy() {
        video.destroy();
        super.onDestroy();
    }

    /** Releases the input so the TV stops routing it the moment this screen leaves. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onStop() {
        ui.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (music != null) music.stop();
        video.close();
        if (view != null) {
            view.reset();
            ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(focus);
        }
        super.onStop();
    }

    /**
     * BACK leaves; Menu or Info shows the music card (or, without music, the input's name).
     * Play/pause, fast-forward and rewind control the music, channel up and down its ceiling.
     * Other keys go to the input.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        // The video in the corner, while it shows, has the media keys, channel up and down (its
        // volume, as the ceiling), and BACK (which closes it, not the input).
        if (video.showing()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (down) video.toggle();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (e.getAction() == KeyEvent.ACTION_DOWN) video.seek(10);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (e.getAction() == KeyEvent.ACTION_DOWN) video.seek(-10);
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (down) {
                        int step = e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP ? -Ceiling.STEP_DB : Ceiling.STEP_DB;
                        ceilingDb = Math.max(0, Math.min(Ceiling.FLOOR_DB, ceilingDb + step));
                        Config.get(this).set(Ceiling.KEY, String.valueOf(ceilingDb));
                        video.volume(videoVolume());
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (e.getAction() == KeyEvent.ACTION_UP) video.close();
                    return true;
                default:
                    break;
            }
        }
        if (music != null && music.has()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (down) {
                        music.playPause();
                        showPlayer();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (down) {
                        music.next();
                        showPlayer();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (down) {
                        music.previous();
                        showPlayer();
                    }
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    // The music's ceiling, 1 dB a step; the game's sound is left alone.
                    if (down) {
                        int step = e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP ? -Ceiling.STEP_DB : Ceiling.STEP_DB;
                        ceilingDb = Math.max(0, Math.min(Ceiling.FLOOR_DB, ceilingDb + step));
                        ceiling.apply(music.app(), ceilingDb);
                        Config.get(this).set(Ceiling.KEY, String.valueOf(ceilingDb));
                        showPlayer();
                        player.ceiling();
                    }
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    if (down) showPlayer();
                    return true;
                default:
                    break;
            }
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Menu: off to the app the card shows (Spotify, SmartTube) to pick something
                // else; with nothing playing, to SmartTube. HOME twice comes back here (a
                // SmartTube video, left with HOME, floats over the game in picture-in-picture).
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    boolean opened = music != null && music.has() && music.open(this);
                    if (!opened) {
                        Intent tube = videoApp(this);
                        if (tube != null) startActivity(tube);
                        opened = tube != null;
                    }
                    if (opened) Config.get(this).set(DETOUR, String.valueOf(System.currentTimeMillis()));
                    else showName();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (music != null && music.has()) showPlayer();
                    else showName();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void refreshPlayer() {
        if (music == null) return;
        player.set(music.title(), music.artist(), music.art(), music.playing());
        player.level(1f - ceilingDb / (float) Ceiling.FLOOR_DB);
    }

    /**
     * SmartTube's launch intent, whichever of its builds is installed (found by name, as its
     * package differs between stable, beta and older builds); null without it.
     */
    static Intent videoApp(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String pkg = r.activityInfo.packageName;
                if (!pkg.toLowerCase(java.util.Locale.ROOT).contains("smarttube")) continue;
                return new Intent(q).setClassName(pkg, r.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
        }
        return null;
    }

    /** Whether the playing app shows its own picture (SmartTube in picture-in-picture). */
    private boolean videoPlaying() {
        String app = music == null ? null : music.app();
        return app != null && app.toLowerCase(java.util.Locale.ROOT).contains("smarttube");
    }

    /** The card on its own accord: not over a video that is already on screen. */
    private void autoShowPlayer() {
        if (!videoPlaying()) showPlayer();
    }

    private void showPlayer() {
        refreshPlayer();
        ui.removeCallbacks(hidePlayer);
        player.reveal();
        if (!always) ui.postDelayed(hidePlayer, PLAYER_MS);
    }

    private void showName() {
        ui.removeCallbacks(hideName);
        name.animate().cancel();
        name.setAlpha(1f);
        name.setVisibility(View.VISIBLE);
        ui.postDelayed(hideName, NAME_MS);
    }

    /**
     * Keeps the TV awake while the input shows a picture, as Amazon's own input player does;
     * otherwise the screensaver and then sleep come on mid-film or mid-game. With no
     * signal the normal timeout applies again, so an input left on a dead source still sleeps.
     */
    private void status(String text) {
        Log.i("dumbtv", "input " + id + ": " + (text == null ? "video" : text));
        if (text == null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(noSignal);
        status.setText(text);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
