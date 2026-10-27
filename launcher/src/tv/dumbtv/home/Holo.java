package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The hologram over the preview. The picture of an HDMI input is composed by the TV's video
 * hardware under this window, so its pixels cannot be warped; this layer works on top of it
 * and moves it: a cyan wash, fine drifting scan lines, soft bands of transparency rising through
 * it, a slow breathing of the whole image, a bright sweep now and then, and short glitches that
 * jolt the picture sideways and tear a few bands through it. Runs at about 30 frames a second,
 * only while a picture shows.
 */
final class Holo extends View {
    private static final int TINT = 0x1F38C8FF;
    private static final long FRAME_MS = 33;

    private final Paint paint = new Paint();
    private final Paint band = new Paint();
    private final Paint sweep = new Paint();
    private final float line, pitch, bandH, jolt;
    private View[] targets = new View[0];
    private long start;
    private boolean running;

    Holo(Context c) {
        super(c);
        line = Math.max(1, Ui.dp(c, 1));
        pitch = Ui.dp(c, 3.5f);
        bandH = Ui.dp(c, 70);
        jolt = Ui.dp(c, 5);
        int clear = Ui.PAGE & 0x00FFFFFF;
        band.setShader(new LinearGradient(0, 0, 0, bandH,
                new int[] {clear, (0x26 << 24) | clear, clear}, null, Shader.TileMode.CLAMP));
        sweep.setShader(new LinearGradient(0, 0, 0, bandH * 0.6f,
                new int[] {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        setVisibility(GONE);
    }

    /** The views a glitch jolts sideways: the picture itself. */
    void jolts(View... views) {
        targets = views;
    }

    void start() {
        if (running) return;
        running = true;
        start = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        invalidate();
    }

    void stop() {
        running = false;
        setVisibility(GONE);
        for (View v : targets) v.setTranslationX(0);
    }

    /** A steady pseudo-random number in [0, 1) for slot {@code n}, so frames agree within a slot. */
    private static float noise(long n) {
        n = (n ^ 0x5DEECE66DL) * 0x2545F4914F6CDD1DL;
        n ^= n >>> 29;
        return ((n >>> 40) & 0xFFFF) / 65536f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) return;
        float w = getWidth(), h = getHeight();
        float t = (SystemClock.uptimeMillis() - start) / 1000f;

        // Breathing: the page shows through a little, more and less, so it never looks solid.
        float breathe = 0.065f + 0.02f * (float) Math.sin(t * 2.3f) + 0.01f * (float) Math.sin(t * 7.1f);
        paint.setColor((Math.round(breathe * 255) << 24) | (Ui.PAGE & 0x00FFFFFF));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(TINT);
        canvas.drawRect(0, 0, w, h, paint);

        // Bands of transparency rising through the picture at different speeds.
        float[][] bands = {{38, 0f}, {23, 0.45f}, {61, 0.8f}};
        for (float[] b : bands) {
            float span = h + bandH;
            float y = span - ((t * Ui.dp(getContext(), b[0]) + b[1] * span) % span) - bandH;
            canvas.save();
            canvas.translate(0, y);
            canvas.drawRect(0, 0, w, bandH, band);
            canvas.restore();
        }

        // Scan lines, drifting down.
        paint.setColor(0x22000000);
        float off = (t * Ui.dp(getContext(), 10)) % pitch;
        for (float y = off - pitch; y < h; y += pitch) canvas.drawRect(0, y, w, y + line, paint);

        // A bright sweep down the picture every 4.5 s.
        float cycle = t % 4.5f;
        if (cycle < 1.2f) {
            float y = (cycle / 1.2f) * (h + bandH) - bandH;
            canvas.save();
            canvas.translate(0, y);
            canvas.drawRect(0, 0, w, bandH * 0.6f, sweep);
            canvas.restore();
        }

        // Glitches: about one 120 ms slot in 30 jolts the picture and tears a few bands.
        long slot = (long) (t / 0.12f);
        boolean glitch = t > 1 && noise(slot) < 0.033f;
        float dx = glitch ? (noise(slot * 7 + 1) * 2 - 1) * jolt : 0;
        for (View v : targets) v.setTranslationX(dx);
        if (glitch) {
            for (int i = 0; i < 3; i++) {
                float y = noise(slot * 13 + i) * h, th = Ui.dp(getContext(), 2 + 10 * noise(slot * 17 + i));
                paint.setColor(i == 1 ? 0x5538C8FF : (0x8C << 24) | (Ui.PAGE & 0x00FFFFFF));
                float shift = (noise(slot * 19 + i) * 2 - 1) * jolt * 2;
                canvas.drawRect(Math.max(0, shift), y, Math.min(w, w + shift), y + th, paint);
            }
        }
        postInvalidateDelayed(FRAME_MS);
    }
}
