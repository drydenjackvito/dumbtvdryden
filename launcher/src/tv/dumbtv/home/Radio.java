package tv.dumbtv.home;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/**
 * Untitled's website, played by Tomboy itself: one web view kept for as long as Tomboy runs, so
 * the music goes on when its screen ({@link Untitled}) is left for a game. What the page plays is
 * published as an Android media session, so the input screen's card, the remote's media keys,
 * the music ceiling and Menu (back to this page) treat it as they treat Spotify.
 */
final class Radio {
    static final String HOME = "https://untitled.stream/";

    private static Radio one;

    static Radio get(Context c) {
        if (one == null) one = new Radio(c.getApplicationContext());
        return one;
    }

    /** The ceiling for a session this app plays; nothing when Untitled has not been opened. */
    static boolean capIfMine(String pkg, int db) {
        if (one == null || !one.app.getPackageName().equals(pkg)) return false;
        one.cap(db);
        return true;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MutableContextWrapper where;
    final WebView web;
    private final MediaSession session;
    private final String script;
    private String artUrl = "";
    private Bitmap artBitmap;
    private String title = "", artist = "", album = "";
    private long durationMs;
    private boolean capped;

    private Radio(Context app) {
        this.app = app;
        where = new MutableContextWrapper(app);
        web = new WebView(where);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        // The site as a computer sees it, not the phone layout that sends people to an app store.
        String ua = ws.getUserAgentString();
        String chrome = ua.replaceAll(".*(Chrome/[0-9.]+).*", "$1");
        if (!chrome.startsWith("Chrome/")) chrome = "Chrome/120.0.0.0";
        ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + chrome + " Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFF000000);
        script = read(app);
        web.addJavascriptInterface(this, "TomboyRadio");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                inject();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                inject();
                CookieManager.getInstance().flush(); // stay signed in
            }
        });

        session = new MediaSession(app, "Untitled");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                run("play", null);
            }

            @Override
            public void onPause() {
                run("pause", null);
            }

            @Override
            public void onSkipToNext() {
                run("next", null);
            }

            @Override
            public void onSkipToPrevious() {
                run("previous", null);
            }

            @Override
            public void onSeekTo(long ms) {
                run("seek", String.valueOf(ms));
            }

            @Override
            public void onStop() {
                run("pause", null);
            }
        }, ui);
        Intent back = new Intent(app, Untitled.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(app, 0, back,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        web.loadUrl(HOME);
    }

    private static String read(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.untitled);
             Scanner s = new Scanner(in, "UTF-8")) {
            return s.useDelimiter("\\A").hasNext() ? s.next() : "";
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    private void inject() {
        web.evaluateJavascript(script, null);
        if (capped) cap(Integer.parseInt(Config.get(app).get(Ceiling.KEY, "6")));
    }

    private void run(String what, String arg) {
        web.evaluateJavascript("window.__tomboyDo && __tomboyDo('" + what + "'," + (arg == null ? "null" : arg) + ")",
                null);
    }

    /** The music ceiling, {@code db} below full; the page's own volume is kept under it. */
    void cap(int db) {
        capped = db > 0;
        run("cap", String.valueOf(Math.pow(10, -db / 20.0)));
    }

    /** Lifts the ceiling: off the input, Untitled plays at the volume it was set to. */
    static void uncap() {
        if (one != null && one.capped) {
            one.capped = false;
            one.run("cap", "1");
        }
    }

    /** Shows the page in {@code host}, an activity's view; it leaves any other first. */
    void showIn(Context activity, ViewGroup host) {
        hide();
        where.setBaseContext(activity); // the keyboard and pop-ups need an activity
        host.addView(web, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.onResume();
        web.resumeTimers();
    }

    /** Takes the page off screen; it keeps playing. */
    void hide() {
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        where.setBaseContext(app);
        CookieManager.getInstance().flush();
    }

    /** From the page, about once a second while something changes: what plays, and how far in. */
    @JavascriptInterface
    public void state(String title, String artist, String album, String art, String state, long durationMs,
                      long atMs) {
        ui.post(() -> publish(title, artist, album, art, state, durationMs, atMs));
    }

    private void publish(String title, String artist, String album, String art, String state, long durationMs,
                         long atMs) {
        boolean song = !title.equals(this.title) || !artist.equals(this.artist) || !album.equals(this.album);
        this.title = title;
        this.artist = artist;
        this.album = album;
        if (!art.equals(artUrl)) {
            artUrl = art;
            artBitmap = null;
            fetchArt(art);
        }
        if (song || durationMs != this.durationMs) {
            this.durationMs = durationMs;
            metadata();
        }
        int s;
        switch (state) {
            case "playing":
                s = PlaybackState.STATE_PLAYING;
                break;
            case "paused":
            case "ended":
                s = PlaybackState.STATE_PAUSED;
                break;
            default:
                s = PlaybackState.STATE_NONE;
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(s, atMs, s == PlaybackState.STATE_PLAYING ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        // In the system's list of sessions once there is a song, as Spotify is.
        boolean active = s != PlaybackState.STATE_NONE && !title.isEmpty();
        if (active != session.isActive()) {
            session.setActive(active);
            Log.i("dumbtv", "untitled: session " + (active ? "on" : "off"));
        }
        if (song) Log.i("dumbtv", "untitled: " + state + " \"" + title + "\" by " + artist);
    }

    private void metadata() {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album);
        if (durationMs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        if (artBitmap != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap);
        session.setMetadata(b.build());
    }

    private void fetchArt(String url) {
        if (url.isEmpty()) {
            metadata();
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        new Thread(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                c.setRequestProperty("Referer", HOME);
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    b = BitmapFactory.decodeStream(in, null, o);
                }
                if (b != null && b.getWidth() > 600) {
                    int h = Math.round(600f * b.getHeight() / b.getWidth());
                    b = Bitmap.createScaledBitmap(b, 600, h, true);
                }
            } catch (java.io.IOException | RuntimeException e) {
                Log.w("dumbtv", "untitled: no artwork from " + url + ": " + e);
            }
            Bitmap got = b;
            ui.post(() -> {
                if (!url.equals(artUrl)) return; // a newer song's art is coming
                artBitmap = got;
                metadata();
            });
        }, "untitled-art").start();
    }
}
