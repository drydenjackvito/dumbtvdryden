package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

/**
 * The music card over an input: the album art, square, in a frosted glass box, and under it one
 * line, as wide as the art, with the song and artist scrolling when they don't fit. While the
 * ceiling is being changed, the line is the ceiling's bar instead.
 *
 * The TV gives no pixels of the input to blur, so the frost is the album art itself, blurred
 * and washed with white, which tints the glass with the song's colours.
 */
final class MiniPlayer extends View {
    private static final long CEILING_MS = 2000, SCROLL_WAIT_MS = 1200;

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG), frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), artist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), artBox = new RectF();
    private final int w, h, pad, artSize, lineH, gap, speed;
    private final float radius;
    private String song, by;
    private Bitmap art, frost;
    private boolean playing;
    private float level = -1;
    private long ceilingUntil, scrollFrom;

    MiniPlayer(Context c) {
        super(c);
        pad = Ui.dp(c, 10);
        artSize = Ui.dp(c, 132);
        lineH = Ui.dp(c, 30);
        w = artSize + 2 * pad;
        h = pad + artSize + lineH + Ui.dp(c, 2);
        radius = Ui.dp(c, 16);
        gap = Ui.dp(c, 36);
        speed = Ui.dp(c, 32); // per second
        glass.setColor(0x52FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(c, 1));
        rim.setColor(0x66FFFFFF);
        frostPaint.setAlpha(150);
        glyph.setColor(0xF2FFFFFF);
        title.setColor(0xFFFFFFFF);
        title.setTypeface(Ui.MEDIUM);
        title.setTextSize(Ui.dp(c, 14));
        title.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 0.5f), 0x59000000);
        artist.setColor(0xCCFFFFFF);
        artist.setTypeface(Ui.REGULAR);
        artist.setTextSize(Ui.dp(c, 14));
        artist.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 0.5f), 0x59000000);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setAlpha(0f);
        setVisibility(GONE);
    }

    /** The music's ceiling, 0 to 1. */
    void level(float v) {
        level = v;
        invalidate();
    }

    /** Shows the ceiling's bar in place of the song for a moment. */
    void ceiling() {
        ceilingUntil = SystemClock.uptimeMillis() + CEILING_MS;
        invalidate();
    }

    void set(String song, String by, Bitmap art, boolean playing) {
        if (!same(song, this.song) || !same(by, this.by)) scrollFrom = SystemClock.uptimeMillis();
        if (art != this.art) frost = art == null ? null : Bitmap.createScaledBitmap(
                Bitmap.createScaledBitmap(art, 6, 6, true), 24, 24, true); // tiny, then up: a blur
        this.song = song;
        this.by = by;
        this.art = art;
        this.playing = playing;
        invalidate();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /*
     * A fade, no slide: the window cuts a hole for the video wherever no view covers it, and works
     * that out from where the card stands when it is laid out. Slid in from the side, the card
     * left its first 40 dp in the hole, showing the game through it.
     */
    void reveal() {
        animate().cancel();
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(220).withEndAction(this::recut).start();
        recut();
    }

    void conceal() {
        animate().cancel();
        animate().alpha(0f).setDuration(260).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Asks the window to work out the video's hole again, around the card as it stands now. */
    private void recut() {
        if (getParent() != null) getParent().requestTransparentRegion(this);
    }

    @Override
    protected void onMeasure(int a, int b) {
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;

        // The glass: the blurred art, washed with white, and a thin bright rim.
        box.set(0, 0, w, h);
        canvas.save();
        android.graphics.Path round = new android.graphics.Path();
        round.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW);
        canvas.clipPath(round);
        if (frost != null) canvas.drawBitmap(frost, null, box, frostPaint);
        canvas.drawRect(box, glass);
        canvas.restore();
        box.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
        canvas.drawRoundRect(box, radius, radius, rim);

        // The art, square.
        artBox.set(pad, pad, pad + artSize, pad + artSize);
        if (art != null) {
            canvas.drawBitmap(art, null, artBox, artPaint);
        } else {
            track.setColor(0x33FFFFFF);
            canvas.drawRect(artBox, track);
        }
        if (!playing && art != null) { // paused: the art dims under a pause sign
            track.setColor(0x66000000);
            canvas.drawRect(artBox, track);
            float s = artSize * 0.11f, cx = artBox.centerX(), cy = artBox.centerY();
            canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.25f, cy + s, glyph);
            canvas.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.8f, cy + s, glyph);
        }

        float top = pad + artSize, mid = top + lineH / 2f;
        if (now < ceilingUntil && level >= 0) {
            // The ceiling: a bar as wide as the art.
            float bh = Ui.dp(getContext(), 4);
            track.setColor(0x47FFFFFF);
            canvas.drawRoundRect(pad, mid - bh / 2, pad + artSize, mid + bh / 2, bh, bh, track);
            track.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(pad, mid - bh / 2, pad + Math.max(bh, artSize * level), mid + bh / 2, bh, bh, track);
            postInvalidateDelayed(ceilingUntil - now);
        } else {
            again = line(canvas, now, mid);
        }
        if (again && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    /** Song and artist on one line, scrolling in a loop when wider than the art. */
    private boolean line(Canvas canvas, long now, float mid) {
        String s = song == null ? "" : song, a = by == null ? "" : by;
        String sep = a.isEmpty() || s.isEmpty() ? "" : "  ·  ";
        float sw = title.measureText(s), seW = artist.measureText(sep), aw = artist.measureText(a);
        float total = sw + seW + aw;
        float base = mid - (title.ascent() + title.descent()) / 2;
        boolean scroll = total > artSize;
        float x = 0;
        if (scroll) {
            long t = Math.max(0, now - scrollFrom - SCROLL_WAIT_MS);
            x = -((t * speed / 1000f) % (total + gap));
        }
        int layer = canvas.saveLayer(pad, mid - lineH / 2f, pad + artSize, mid + lineH / 2f, null);
        canvas.clipRect(pad, mid - lineH / 2f, pad + artSize, mid + lineH / 2f);
        for (int copy = 0; copy < (scroll ? 2 : 1); copy++) {
            float at = pad + x + copy * (total + gap);
            canvas.drawText(s, at, base, title);
            canvas.drawText(sep, at + sw, base, artist);
            canvas.drawText(a, at + sw + seW, base, artist);
        }
        if (scroll) { // soft ends, so the text slides in and out of the glass
            float f = Ui.dp(getContext(), 14);
            fade.setShader(new LinearGradient(pad, 0, pad + f, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(pad, mid - lineH / 2f, pad + f, mid + lineH / 2f, fade);
            fade.setShader(new LinearGradient(pad + artSize - f, 0, pad + artSize, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(pad + artSize - f, mid - lineH / 2f, pad + artSize, mid + lineH / 2f, fade);
        }
        canvas.restoreToCount(layer);
        return scroll;
    }
}
