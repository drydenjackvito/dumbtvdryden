package tv.dumbtv.home;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Decodes the video of the YouTube page in {@link VideoWindow} with Android's own software VP9
 * decoder, into a TextureView: drawn by the GPU with the rest of this window, so it shows over
 * the HDMI input. (The TV's hardware decoder draws on the video plane the input holds, and the
 * web view's own software decoding is too slow on this TV next to an HDMI input.)
 *
 * The page hands over each compressed frame as YouTube appends it, and the player's clock; this
 * decodes just ahead of the clock and shows each frame when it is due.
 */
final class NativeVideo implements TextureView.SurfaceTextureListener {
    private static final String MIME = "video/x-vnd.on2.vp9";
    private static final long AHEAD_US = 1_000_000, LATE_US = 100_000, BEHIND_US = 700_000;

    private static final class Chunk {
        final long t;
        final boolean key;
        final byte[] data;

        Chunk(long t, boolean key, byte[] data) {
            this.t = t;
            this.key = key;
            this.data = data;
        }
    }

    /** In time order; guarded by itself. */
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private volatile double clockS;
    private volatile long clockAt;
    private volatile boolean paused = true, clockKnown;
    private volatile Surface surface;
    private volatile boolean running;
    private Thread thread;
    private int decoded, drawn, late, skips;
    /** Told once a new video's first frame is on screen (from the decoding thread). */
    volatile Runnable onFirstFrame;
    private volatile boolean firstShown;
    private String codecName = "-";

    /** A frame as YouTube appended it; {@code t} in microseconds. */
    void add(long t, boolean key, byte[] data) {
        Chunk c = new Chunk(t, key, data);
        synchronized (chunks) {
            int i = indexAtOrAfter(t);
            if (i < chunks.size() && chunks.get(i).t == t) return; // appended again
            chunks.add(i, c);
        }
    }

    /**
     * The player's clock, in seconds, as of now. While playing on, a reading close to where this
     * clock already is only nudges it: the readings come late by varying amounts, and following
     * each one would show frames unevenly.
     */
    void clock(double seconds, boolean paused) {
        long at = SystemClock.elapsedRealtimeNanos();
        if (clockKnown && !this.paused && !paused) {
            double predicted = clockS + (at - clockAt) / 1e9, off = seconds - predicted;
            if (Math.abs(off) < 0.15) {
                clockS = predicted + off * 0.1;
                clockAt = at;
                return;
            }
        }
        clockS = seconds;
        clockAt = at;
        this.paused = paused;
        clockKnown = true;
    }

    /** Forgets the video: a new one is coming. */
    void reset() {
        firstShown = false;
        synchronized (chunks) {
            chunks.clear();
        }
        clockKnown = false;
        paused = true;
    }

    private long nowUs() {
        double s = clockS + (paused ? 0 : (SystemClock.elapsedRealtimeNanos() - clockAt) / 1e9);
        return (long) (s * 1e6);
    }

    /** The first chunk at or after {@code t}; call holding the lock. */
    private int indexAtOrAfter(long t) {
        int lo = 0, hi = chunks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (chunks.get(mid).t < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Where to start decoding to show {@code now}: the last keyframe at or before it, else the next. */
    private long startFor(long now) {
        synchronized (chunks) {
            long best = -1;
            for (Chunk c : chunks) {
                if (c.t > now) {
                    if (best < 0 && c.key) return c.t;
                    if (best >= 0) break;
                    continue;
                }
                if (c.key) best = c.t;
            }
            return best;
        }
    }

    /** The next keyframe after {@code now}; -1 when none has come yet. */
    private long keyAfter(long now) {
        synchronized (chunks) {
            for (int i = indexAtOrAfter(now + 1); i < chunks.size(); i++) {
                if (chunks.get(i).key) return chunks.get(i).t;
            }
            return -1;
        }
    }

    /** Drops what is well behind the clock, keeping the last keyframe before it. */
    private void prune(long now) {
        synchronized (chunks) {
            int keep = -1;
            for (int i = 0; i < chunks.size() && chunks.get(i).t < now - 3_000_000; i++) {
                if (chunks.get(i).key) keep = i;
            }
            if (keep > 0) chunks.subList(0, keep).clear();
        }
    }

    private static String softwareDecoder() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            String n = info.getName().toLowerCase(java.util.Locale.ROOT);
            if (!n.startsWith("c2.android.") && !n.startsWith("omx.google.")) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MIME)) return info.getName();
            }
        }
        return null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        Log.i("dumbtv", "native video: window " + w + "x" + h + " px");
        surface = new Surface(st);
        start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop();
        Surface s = surface;
        surface = null;
        if (s != null) s.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private synchronized void start() {
        if (running || surface == null) return;
        running = true;
        thread = new Thread(this::run, "tomboy-video");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void run() {
        String name = softwareDecoder();
        codecName = name == null ? "none" : name;
        Log.i("dumbtv", "native video: decoder " + codecName);
        if (name == null) return;
        MediaCodec codec;
        try {
            codec = MediaCodec.createByCodecName(name);
            MediaFormat f = MediaFormat.createVideoFormat(MIME, 854, 480);
            codec.configure(f, surface, null, 0);
            codec.start();
        } catch (java.io.IOException | RuntimeException e) {
            Log.w("dumbtv", "native video: no decoder", e);
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long nextT = -1, target = 0, lastNow = -1, lastOut = -1, lastSkip = 0, lastLog = 0, lastPrune = 0;
        boolean needKey = true, fedAny = false;
        int pending = -1;
        long pendingPts = 0;
        try {
            while (running) {
                if (!clockKnown) {
                    SystemClock.sleep(20);
                    continue;
                }
                long now = nowUs(), wall = SystemClock.elapsedRealtime();
                // Started, sought, or fallen behind: flush and start again at a keyframe.
                long restartAt = -2;
                if (lastNow < 0 || Math.abs(now - lastNow) > 1_500_000) {
                    restartAt = startFor(now);
                } else if (lastOut >= 0 && lastOut < now - BEHIND_US && wall - lastSkip > 2000) {
                    long k = keyAfter(now);
                    if (k >= 0) {
                        restartAt = k;
                        lastSkip = wall;
                        skips++;
                    }
                } else if (nextT < 0) {
                    restartAt = startFor(now); // waiting for a keyframe to arrive
                }
                lastNow = now;
                if (restartAt != -2) {
                    if (pending >= 0) pending = -1; // flush hands every buffer back
                    if (fedAny) codec.flush();
                    fedAny = false;
                    nextT = restartAt;
                    target = restartAt < 0 ? now : Math.max(now, restartAt);
                    needKey = true;
                    lastOut = -1;
                }

                // Feed what is due within the next second.
                while (nextT >= 0) {
                    Chunk c;
                    synchronized (chunks) {
                        int i = indexAtOrAfter(nextT);
                        c = i < chunks.size() ? chunks.get(i) : null;
                    }
                    if (c == null || c.t > now + AHEAD_US) break;
                    if (needKey && !c.key) {
                        nextT = c.t + 1;
                        continue;
                    }
                    int in = codec.dequeueInputBuffer(0);
                    if (in < 0) break;
                    ByteBuffer b = codec.getInputBuffer(in);
                    b.clear();
                    b.put(c.data);
                    codec.queueInputBuffer(in, 0, c.data.length, c.t, 0);
                    needKey = false;
                    fedAny = true;
                    nextT = c.t + 1;
                }

                // Show the decoded frame when it is due; drop it when it came too late.
                if (pending < 0) {
                    int out = codec.dequeueOutputBuffer(info, 5000);
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        Log.i("dumbtv", "native video: video " + of.getInteger(MediaFormat.KEY_WIDTH) + "x"
                                + of.getInteger(MediaFormat.KEY_HEIGHT));
                    }
                    if (out >= 0) {
                        decoded++;
                        lastOut = info.presentationTimeUs;
                        pending = out;
                        pendingPts = info.presentationTimeUs;
                    }
                } else {
                    SystemClock.sleep(2);
                }
                if (pending >= 0) {
                    now = nowUs();
                    if (pendingPts < target - 40_000 || pendingPts < now - LATE_US) {
                        codec.releaseOutputBuffer(pending, false);
                        pending = -1;
                        late++;
                    } else if (pendingPts <= now + 40_000) {
                        // Handed over a little early with the time it is due: the screen shows it
                        // on the refresh that time falls in, evenly spaced.
                        codec.releaseOutputBuffer(pending, System.nanoTime() + Math.max(0, pendingPts - now) * 1000);
                        pending = -1;
                        drawn++;
                        if (!firstShown) {
                            firstShown = true;
                            Runnable r = onFirstFrame;
                            if (r != null) r.run();
                        }
                    }
                }

                if (wall - lastPrune > 1000) {
                    lastPrune = wall;
                    prune(now);
                }
                if (wall - lastLog > 3000) {
                    lastLog = wall;
                    int n;
                    synchronized (chunks) {
                        n = chunks.size();
                    }
                    Log.i("dumbtv", "native video: t=" + (now / 100_000) / 10.0 + " paused=" + paused
                            + " chunks=" + n + " decoded=" + decoded + " drawn=" + drawn + " late=" + late
                            + " skips=" + skips + " decoder=" + codecName);
                }
            }
        } catch (RuntimeException e) {
            Log.w("dumbtv", "native video: stopped", e);
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            codec.release();
        }
    }
}
