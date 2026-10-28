package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tomboy as a screen the YouTube app can cast to: YouTube's "lounge", the service behind
 * Settings › Watch on TV › Enter TV code, as SmartTube uses it (MediaServiceCore's LoungeService).
 *
 * Linked once with a TV code (Options › Link a phone), Tomboy shows up in the phone's cast list
 * while this keeps a long-poll connection open. A cast plays in the corner over a game, or full
 * screen anywhere else ({@link Theater}); the phone's play, pause, seek, volume and stop reach
 * the player, and the player's state is reported back so the phone's controls follow it.
 *
 * The protocol is YouTube's own and unpublished; it has been stable for years, but may change.
 */
final class Lounge {
    /** Whatever plays the cast video now. */
    interface Player {
        void castLoad(String videoId, long atMs);

        void castPlay();

        void castPause();

        void castSeek(double seconds);

        void castVolume(int volume);

        void castStop();
    }

    private static final String TAG = "dumbtv";
    private static final String SCREEN_NAME = "Tomboy";
    private static final String APP = "lb-v4";
    private static final String BASE = "https://www.youtube.com/api/lounge/bc/bind?device=LOUNGE_SCREEN"
            + "&theme=cl&capabilities=dsp%2Cmic%2Cdpa&mdxVersion=2&VER=8&v=2&t=1&app=" + APP
            + "&AID=42&zx=xxxxxxxxxxxx";
    private static final long TOKEN_MS = 30 * 60 * 1000;
    static final int STATE_PLAYING = 1, STATE_PAUSED = 2, STATE_IDLE = 3;

    private static Lounge instance;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private volatile String screenId, deviceId, token, sid, gsid;
    private volatile long tokenAt;
    private volatile HttpURLConnection listening;
    private int ofs;

    /** The player of the cast video, on the main thread. */
    private Player player;
    private String playingId, ctt, listId, index;
    private int lastState = -1;
    private long lastReport;

    private Lounge(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized Lounge get(Context c) {
        if (instance == null) instance = new Lounge(c);
        return instance;
    }

    /** Starts listening for casts, for good: the home screen's process stays running. */
    void start() {
        Thread t = new Thread(this::listen, "tomboy-lounge");
        t.setDaemon(true);
        t.start();
    }

    // ---- Pairing ----

    /** A fresh TV code, "123 456 789 012", for the phone to enter; null without the network. */
    String pairingCode() {
        try {
            ids();
            if (!tokenFresh()) refreshToken();
            Map<String, String> f = new LinkedHashMap<>();
            f.put("lounge_token", token);
            f.put("screen_id", screenId);
            f.put("screen_name", SCREEN_NAME);
            f.put("access_type", "permanent");
            f.put("app", APP);
            f.put("device_id", deviceId);
            f.put("qr", "1");
            String body = post("https://www.youtube.com/api/lounge/pairing/get_pairing_code?ctx=pair", f);
            String code = new JSONObject(body).optString("code", null);
            reconnect(); // as SmartTube does: a new session picks the pairing up
            if (code == null) return null;
            return code.replaceAll("(\\d{3})(?=\\d)", "$1 ");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cast: pairing code", e);
            return null;
        }
    }

    // ---- Listening ----

    private void listen() {
        for (;;) {
            try {
                ids();
                if (!tokenFresh()) refreshToken();
                session();
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "cast: " + e);
                token = null; // a stale token is the usual cause: fetch a new one
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private synchronized void ids() throws IOException {
        Config cfg = Config.get(app);
        if (deviceId == null) {
            deviceId = cfg.get("cast.device", "");
            if (deviceId.isEmpty()) {
                deviceId = UUID.randomUUID().toString();
                cfg.set("cast.device", deviceId);
            }
        }
        if (screenId == null) {
            screenId = cfg.get("cast.screen", "");
            if (screenId.isEmpty()) {
                screenId = read(open("https://www.youtube.com/api/lounge/pairing/generate_screen_id", null)).trim();
                cfg.set("cast.screen", screenId);
            }
        }
    }

    private boolean tokenFresh() {
        return token != null && System.currentTimeMillis() - tokenAt < TOKEN_MS;
    }

    private synchronized void refreshToken() throws IOException, JSONException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("screen_ids", screenId);
        JSONObject screen = new JSONObject(post("https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch", f))
                .getJSONArray("screens").getJSONObject(0);
        token = screen.getString("loungeToken");
        tokenAt = System.currentTimeMillis();
    }

    private String ident() throws IOException {
        return "&name=" + enc(SCREEN_NAME) + "&id=" + enc(deviceId) + "&loungeIdToken=" + enc(token);
    }

    /** One session: open it, then read its commands until YouTube closes the long poll. */
    private void session() throws IOException, JSONException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("count", "0");
        String first = post(BASE + "&RID=1337" + ident(), f);
        sid = gsid = null;
        for (JSONArray cmd : commands(first)) {
            if ("c".equals(cmd.optString(0))) sid = cmd.optString(1);
            if ("S".equals(cmd.optString(0))) gsid = cmd.optString(1);
        }
        if (sid == null || gsid == null) throw new IOException("no session");
        Log.i(TAG, "cast: listening as " + SCREEN_NAME);
        HttpURLConnection c = open(BASE + "&RID=rpc&CI=0" + ident() + "&SID=" + enc(sid) + "&gsessionid=" + enc(gsid), null);
        c.setReadTimeout(60000); // a minute without even a noop: the connection hangs
        listening = c;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder chunk = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) {
                chunk.append(line).append('\n');
                if (!line.equals("]")) continue;
                for (JSONArray cmd : commands(chunk.toString())) handle(cmd);
                chunk.setLength(0);
            }
        } finally {
            listening = null;
            c.disconnect();
        }
    }

    /** Drops the session, so the listening loop opens a new one (after pairing). */
    private void reconnect() {
        HttpURLConnection c = listening;
        if (c != null) c.disconnect();
    }

    /** The commands in a chunk: "123\n[[0,["c","…"]]\n,[1,["S","…"]]\n]". */
    private static java.util.List<JSONArray> commands(String chunk) throws JSONException {
        java.util.List<JSONArray> list = new java.util.ArrayList<>();
        int start = chunk.indexOf('[');
        if (start < 0) return list;
        JSONArray all = new JSONArray(chunk.substring(start));
        for (int i = 0; i < all.length(); i++) {
            JSONArray item = all.optJSONArray(i);
            JSONArray cmd = item == null ? null : item.optJSONArray(1);
            if (cmd != null) list.add(cmd);
        }
        return list;
    }

    private void handle(JSONArray cmd) {
        String type = cmd.optString(0);
        JSONObject p = cmd.optJSONObject(1);
        if (!"noop".equals(type)) Log.i(TAG, "cast: " + type + (p == null ? "" : " " + p));
        ui.post(() -> {
            switch (type) {
                case "setPlaylist":
                case "setVideo":
                    if (p != null && p.optString("videoId", null) != null) {
                        ctt = p.optString("ctt", ctt);
                        listId = p.optString("listId", listId);
                        index = p.optString("currentIndex", index);
                        long at = (long) (1000 * parse(p.optString("currentTime", "0")));
                        load(p.optString("videoId"), at);
                    }
                    break;
                case "play":
                    if (player != null) player.castPlay();
                    break;
                case "pause":
                    if (player != null) player.castPause();
                    break;
                case "seekTo":
                    if (player != null && p != null) player.castSeek(parse(p.optString("newTime", "0")));
                    break;
                case "setVolume":
                    if (player != null && p != null) {
                        int v = (int) parse(p.optString("volume", "-1"));
                        if (v >= 0) {
                            player.castVolume(v);
                            send(volumeChanged(v));
                        }
                    }
                    break;
                case "stopVideo":
                    if (player != null) player.castStop();
                    break;
                case "getNowPlaying":
                case "remoteConnected":
                    lastState = -1; // tell the phone where things stand
                    if (playingId == null) send(nowPlaying(null, -1, -1, STATE_IDLE));
                    break;
                default:
                    break;
            }
        });
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Players ----

    /** A cast arrived: to the player already playing one, the game's corner, or full screen. */
    private void load(String videoId, long atMs) {
        playingId = videoId;
        lastState = -1;
        if (player != null) {
            player.castLoad(videoId, atMs);
        } else if (Input.front != null) {
            Input.front.cast(videoId, atMs);
        } else {
            app.startActivity(new Intent(app, Theater.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Theater.EXTRA_ID, videoId)
                    .putExtra(Theater.EXTRA_AT, atMs));
        }
    }

    /** The cast's player, once it shows; null when it is gone. */
    void attach(Player p) {
        player = p;
    }

    void detach(Player p) {
        if (player != p) return;
        player = null;
        if (playingId != null) send(nowPlaying(playingId, -1, -1, STATE_PAUSED));
        playingId = null;
    }

    /** The player's clock: reported when its state changes, and every ten seconds. */
    void progress(Player p, double seconds, double duration, boolean paused) {
        if (p != player || playingId == null) return;
        int state = paused ? STATE_PAUSED : STATE_PLAYING;
        long now = System.currentTimeMillis();
        if (state == lastState && now - lastReport < 10000) return;
        boolean first = lastState == -1;
        lastState = state;
        lastReport = now;
        long pos = (long) (seconds * 1000), dur = (long) (duration * 1000);
        if (first) send(nowPlaying(playingId, pos, dur, state));
        send(stateChange(pos, dur, state));
    }

    // ---- Telling the phone ----

    private Map<String, String> base(long posMs, long durMs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("count", "1");
        m.put("ofs", String.valueOf(ofs++));
        if (posMs >= 0) m.put("req0_currentTime", String.valueOf(posMs / 1000f));
        if (durMs > 0) {
            m.put("req0_duration", String.valueOf(durMs / 1000f));
            m.put("req0_seekableEndTime", String.valueOf(durMs / 1000f));
        }
        m.put("req0_loadedTime", "0");
        m.put("req0_seekableStartTime", "0");
        return m;
    }

    private Map<String, String> nowPlaying(String videoId, long posMs, long durMs, int state) {
        Map<String, String> m = base(posMs, durMs);
        m.put("req0__sc", "nowPlaying");
        m.put("req0_state", String.valueOf(state));
        if (videoId != null) m.put("req0_videoId", videoId);
        if (ctt != null) m.put("req0_ctt", ctt);
        if (listId != null) m.put("req0_listId", listId);
        if (index != null) m.put("req0_currentIndex", index);
        return m;
    }

    private Map<String, String> stateChange(long posMs, long durMs, int state) {
        Map<String, String> m = base(posMs, durMs);
        m.put("req0__sc", "onStateChange");
        m.put("req0_state", String.valueOf(state));
        m.put("req0_cpn", "foo");
        return m;
    }

    private Map<String, String> volumeChanged(int volume) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("count", "1");
        m.put("ofs", String.valueOf(ofs++));
        m.put("req0__sc", "onVolumeChanged");
        m.put("req0_volume", String.valueOf(volume));
        m.put("req0_muted", "false");
        return m;
    }

    private void send(Map<String, String> fields) {
        out.execute(() -> {
            String s = sid, g = gsid;
            if (s == null || g == null || token == null) return;
            try {
                post(BASE + "&RID=1337" + ident() + "&SID=" + enc(s) + "&gsessionid=" + enc(g), fields);
            } catch (IOException e) {
                Log.w(TAG, "cast: report " + e);
            }
        });
    }

    // ---- HTTP ----

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static HttpURLConnection open(String url, Map<String, String> form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (form != null) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (b.length() > 0) b.append('&');
                b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
            byte[] body = b.toString().getBytes("UTF-8");
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        return c;
    }

    private static String post(String url, Map<String, String> form) throws IOException {
        HttpURLConnection c = open(url, form);
        try {
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static String read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + c.getURL().getPath());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        }
    }
}
