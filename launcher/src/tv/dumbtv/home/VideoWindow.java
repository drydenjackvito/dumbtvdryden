package tv.dumbtv.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
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
    private final Ended ended;
    private boolean ready;

    @SuppressLint("SetJavaScriptEnabled")
    VideoWindow(Context c, float scale, Ended ended) {
        super(c);
        this.ended = ended;
        int w = Ui.dp(c, 400 * scale), h = Math.round(w * 9f / 16f);
        setLayoutParams(new FrameLayout.LayoutParams(w, h));
        float radius = Ui.dp(c, 12 * scale);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        setBackgroundColor(Color.BLACK);
        web = new WebView(c);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (url.contains("/embed/")) v.evaluateJavascript(script(), null);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(this, "Tomboy");
        addView(web, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /**
     * Plays {@code id} from {@code atMs}, at {@code volume} (0 to 100). The embed page is opened
     * on its own (not in a frame) so a script can reach its video element: each frame is copied
     * onto a canvas over it. The TV's hardware decoder draws on the video plane the HDMI input
     * holds, so the element itself shows black; a canvas is drawn like the rest of this window.
     */
    void play(String id, long atMs, int volume) {
        ready = false;
        this.volume = volume;
        String url = "https://www.youtube.com/embed/" + id + "?autoplay=1&controls=0&rel=0&fs=0"
                + "&iv_load_policy=3&playsinline=1&disablekb=1&start=" + (atMs / 1000);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        // YouTube refuses embeds that come from no site at all.
        headers.put("Referer", "https://tv.dumbtv.home/");
        web.loadUrl(url, headers);
        setVisibility(VISIBLE);
    }

    private int volume = 100;

    /** Copies the video onto a canvas each frame, hides YouTube's overlays, reports errors. */
    private String script() {
        return "(function(){if(window.__tb)return;window.__tb=1;"
                + "var css=document.createElement('style');css.textContent="
                + "'.ytp-chrome-top,.ytp-chrome-bottom,.ytp-pause-overlay,.ytp-gradient-top,"
                + ".ytp-gradient-bottom,.ytp-watermark,.ytp-large-play-button,.ytp-spinner,"
                + ".ytp-cued-thumbnail-overlay,.ytp-paid-content-overlay{display:none!important}"
                + "html,body{background:#000!important}';document.head.appendChild(css);"
                + "var c=document.createElement('canvas');c.style.cssText='position:fixed;left:0;top:0;"
                + "width:100vw;height:100vh;z-index:2147483647;pointer-events:none;background:#000';"
                + "document.body.appendChild(c);var x=c.getContext('2d'),last=0,told=0;"
                + "function frame(){var v=document.querySelector('video');"
                + "if(v){window.__v=v;if(!told){told=1;v.volume=" + volume + "/100;v.play();Tomboy.ready();}"
                + "if(c.width!=innerWidth){c.width=innerWidth;c.height=innerHeight;}"
                + "try{x.drawImage(v,0,0,c.width,c.height);}catch(e){}"
                + "var n=Date.now();if(n-last>3000){last=n;try{var d=x.getImageData(c.width>>1,c.height>>1,1,1).data;"
                + "Tomboy.log('t='+v.currentTime.toFixed(1)+' paused='+v.paused+' px='+d[0]+','+d[1]+','+d[2]);}"
                + "catch(e){Tomboy.log('read '+e);}}}"
                + "var er=document.querySelector('.ytp-error');if(er&&er.offsetParent&&!window.__err){window.__err=1;"
                + "Tomboy.log('error: '+er.innerText.slice(0,80));Tomboy.error(150);}"
                + "requestAnimationFrame(frame);}requestAnimationFrame(frame);})();";
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
        web.loadUrl("about:blank");
        setVisibility(GONE);
    }

    void destroy() {
        web.destroy();
    }

    private void js(String call) {
        if (ready) web.evaluateJavascript(call, null);
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
