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
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(this, "Tomboy");
        addView(web, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /** Plays {@code id} from {@code atMs}, at {@code volume} (0 to 100). */
    void play(String id, long atMs, int volume) {
        ready = false;
        String page = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                + "<style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style>"
                + "</head><body><div id=p></div><script>"
                + "var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{width:'100%',height:'100%',"
                + "videoId:'" + id + "',playerVars:{autoplay:1,controls:0,rel:0,fs:0,iv_load_policy:3,"
                + "playsinline:1,disablekb:1,start:" + (atMs / 1000) + "},events:{"
                + "onReady:function(e){e.target.setVolume(" + volume + ");e.target.playVideo();Tomboy.ready();},"
                + "onError:function(e){Tomboy.error(e.data);}}});}"
                + "function toggle(){if(!p)return;p.getPlayerState()==1?p.pauseVideo():p.playVideo();}"
                + "function seek(d){if(p)p.seekTo(Math.max(0,p.getCurrentTime()+d),true);}"
                + "function vol(v){if(p)p.setVolume(v);}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
        // An https origin: YouTube refuses to play embeds that come from no site at all.
        web.loadDataWithBaseURL("https://tv.dumbtv.home/", page, "text/html", "utf-8", null);
        setVisibility(VISIBLE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    void toggle() {
        js("toggle()");
    }

    void seek(int seconds) {
        js("seek(" + seconds + ")");
    }

    void volume(int v) {
        js("vol(" + v + ")");
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
