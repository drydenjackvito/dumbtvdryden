package tv.dumbtv.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * A YouTube video in a corner of the input screen, over the game: YouTube's embedded player in a
 * web view. It stands in for SmartTube, which can't float over an HDMI input here (the TV frees
 * its memory and closes it the moment the input's picture starts), and starts where SmartTube
 * had got to. Part of this screen's own window, it lives as long as the screen does.
 */
final class VideoWindow extends FrameLayout {
    interface Ended {
        /** The player gave up: the video may not be embedded (YouTube's errors 101, 150, 152, 153). */
        void failed(int code);
    }

    private final WebView web;
    private final NativeVideo decoder = new NativeVideo();
    private final Ended ended;
    private boolean ready;

    @SuppressLint("SetJavaScriptEnabled")
    VideoWindow(Context c, float scale, Ended ended) {
        super(c);
        this.ended = ended;
        int w = Ui.dp(c, 300 * scale), h = Math.round(w * 9f / 16f);
        setLayoutParams(new FrameLayout.LayoutParams(w, h));
        float radius = Ui.dp(c, 12 * scale);
        // Rounded by drawing, not by clipping: the video view ignores a rounded clip, and the
        // corner mask on top (Corners) must be free to draw outside it.
        setBackground(Ui.round(Color.BLACK, radius));
        web = new WebView(c);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v,
                    android.webkit.WebResourceRequest r) {
                return embedPage(r);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(this, "Tomboy");
        addView(web, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Over the page: the picture, decoded by Android's software decoder (see NativeVideo).
        android.view.TextureView screen = new android.view.TextureView(c);
        screen.setSurfaceTextureListener(decoder);
        addView(screen, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Its corners cut out on top of everything: cleared pixels in this window, through which
        // the game shows.
        addView(new Corners(c, radius), new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /**
     * Plays {@code id} from {@code atMs}, at {@code volume} (0 to 100), with YouTube's embed page
     * opened on its own so the script in res/raw/tube.js can run in it first: it hands the
     * compressed video and the player's clock to {@link NativeVideo}, which shows the picture
     * (the hardware decoder's lands on the video plane the HDMI input holds, and shows black).
     */
    void play(String id, long atMs, int volume) {
        ready = false;
        decoder.reset();
        startS = atMs / 1000;
        this.volume = volume;
        String url = "https://www.youtube.com/embed/" + id + "?autoplay=1&controls=0&rel=0&fs=0"
                + "&iv_load_policy=3&playsinline=1&disablekb=1&start=" + startS;
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", REFERER); // YouTube refuses embeds that come from no site at all
        web.loadUrl(url, headers);
        setVisibility(VISIBLE);
    }

    private static final String REFERER = "https://tv.dumbtv.home/";
    private volatile long startS;
    private volatile int volume = 100;
    private String tube;

    /**
     * The embed page, fetched here with the script put first in its head; null for every other
     * request, which the web view makes as usual. Runs on the web view's network thread.
     */
    private android.webkit.WebResourceResponse embedPage(android.webkit.WebResourceRequest r) {
        android.net.Uri u = r.getUrl();
        if (!"GET".equals(r.getMethod()) || !r.isForMainFrame() || !"www.youtube.com".equals(u.getHost())
                || u.getPath() == null || !u.getPath().startsWith("/embed/")) {
            return null;
        }
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u.toString()).openConnection();
            for (java.util.Map.Entry<String, String> h : r.getRequestHeaders().entrySet()) {
                // Compression is left to the connection, which undoes only what it asked for.
                if (!"accept-encoding".equalsIgnoreCase(h.getKey())) c.setRequestProperty(h.getKey(), h.getValue());
            }
            c.setRequestProperty("Referer", REFERER);
            String cookies = android.webkit.CookieManager.getInstance().getCookie(u.toString());
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            String html = new String(out.toByteArray(), "UTF-8");
            String script = "<script>" + script() + "</script>";
            int head = html.toLowerCase(java.util.Locale.ROOT).indexOf("<head>");
            html = head < 0 ? script + html : html.substring(0, head + 6) + script + html.substring(head + 6);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new android.webkit.WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new java.io.ByteArrayInputStream(html.getBytes("UTF-8")));
        } catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("dumbtv", "video: embed page", e);
            return null; // the web view fetches it itself: no picture, but the sound plays
        }
    }

    private synchronized String script() {
        if (tube == null) {
            try (java.io.InputStream in = getResources().openRawResource(R.raw.tube)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                tube = new String(out.toByteArray(), "UTF-8");
            } catch (java.io.IOException e) {
                tube = "";
            }
        }
        boolean sharp = "480".equals(Config.get(getContext()).get(Config.VIDEO_QUALITY));
        return tube.replace("__START__", String.valueOf(startS)).replace("__VOL__", String.valueOf(volume))
                .replace("__MAXW__", sharp ? "854" : "640").replace("__MAXH__", sharp ? "480" : "360");
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    void toggle() {
        js("var v=window.__v;if(v){v.paused?v.play():v.pause();}");
    }

    void seek(int seconds) {
        js("var v=window.__v;if(v)v.currentTime=Math.max(0,v.currentTime+(" + seconds + "));");
    }

    void volume(int v) {
        volume = v;
        js("var v=window.__v;if(v)v.volume=" + v + "/100;");
    }

    void close() {
        decoder.reset();
        web.loadUrl("about:blank");
        setVisibility(GONE);
    }

    void destroy() {
        decoder.stop();
        web.destroy();
    }

    private void js(String call) {
        if (ready) web.evaluateJavascript(call, null);
    }

    /** Clears the four corners outside the rounded rectangle. */
    private static final class Corners extends View {
        private final android.graphics.Paint clear = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final float radius;

        Corners(Context c, float radius) {
            super(c);
            this.radius = radius;
            clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
            path.addRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, android.graphics.Path.Direction.CW);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawPath(path, clear);
        }
    }

    /** A compressed frame, as the page caught it: {@code t} in microseconds. */
    @JavascriptInterface
    public void chunk(String base64, double t, boolean key) {
        decoder.add((long) t, key, android.util.Base64.decode(base64, android.util.Base64.NO_WRAP));
    }

    /** The page's player clock, in seconds. */
    @JavascriptInterface
    public void clock(double seconds, boolean paused) {
        decoder.clock(seconds, paused);
    }

    @JavascriptInterface
    public void log(String what) {
        android.util.Log.i("dumbtv", "video: " + what);
    }

    @JavascriptInterface
    public void ready() {
        post(() -> ready = true);
    }

    @JavascriptInterface
    public void error(int code) {
        post(() -> {
            close();
            ended.failed(code);
        });
    }
}
