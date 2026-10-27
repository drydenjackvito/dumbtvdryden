package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * The music card over an input: album art, song, artist, and whether it plays, in the home
 * screen's style (a white card with a soft shadow). It slides in from the right and leaves on its
 * own after a few seconds.
 */
final class MiniPlayer extends View {
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), artPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), artist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF(), artBox = new RectF();
    private final int w, h, pad, artSize;
    private final float radius;
    private String song, by;
    private Bitmap art;
    private boolean playing;
    private float level = -1;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);

    MiniPlayer(Context c) {
        super(c);
        w = Ui.dp(c, 300);
        h = Ui.dp(c, 92);
        pad = Ui.dp(c, 14);
        artSize = h - 2 * pad;
        radius = Ui.dp(c, 16);
        card.setColor(Ui.SURFACE);
        glyph.setColor(Ui.INK);
        title.setColor(Ui.INK);
        title.setTypeface(Ui.MEDIUM);
        title.setTextSize(Ui.dp(c, 18));
        artist.setColor(Ui.MUTED);
        artist.setTypeface(Ui.REGULAR);
        artist.setTextSize(Ui.dp(c, 15));
        setElevation(Ui.dp(c, 10));
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, w, h, radius);
            }
        });
        setAlpha(0f);
        setVisibility(GONE);
    }

    /** The music's volume, 0 to 1, shown as a thin bar along the card's bottom; -1 hides it. */
    void level(float v) {
        level = v;
        invalidate();
    }

    void set(String song, String by, Bitmap art, boolean playing) {
        this.song = song;
        this.by = by;
        this.art = art;
        this.playing = playing;
        invalidate();
    }

    void reveal() {
        animate().cancel();
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setTranslationX(Ui.dp(getContext(), 40));
        }
        animate().alpha(1f).translationX(0).setDuration(220).start();
    }

    void conceal() {
        animate().cancel();
        animate().alpha(0f).translationX(Ui.dp(getContext(), 40)).setDuration(260)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    @Override
    protected void onMeasure(int a, int b) {
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        box.set(0, 0, w, h);
        canvas.drawRoundRect(box, radius, radius, card);
        artBox.set(pad, pad, pad + artSize, pad + artSize);
        if (art != null) {
            clip.reset();
            clip.addRoundRect(artBox, radius * 0.6f, radius * 0.6f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, null, artBox, artPaint);
            canvas.restore();
        } else {
            card.setColor(0xFFEBEBEF);
            canvas.drawRoundRect(artBox, radius * 0.6f, radius * 0.6f, card);
            card.setColor(Ui.SURFACE);
        }
        float x = artBox.right + pad, room = w - x - pad - Ui.dp(getContext(), 30);
        float mid = h / 2f;
        if (song != null) {
            CharSequence t = TextUtils.ellipsize(song, title, room, TextUtils.TruncateAt.END);
            canvas.drawText(t, 0, t.length(), x, mid - Ui.dp(getContext(), 4), title);
        }
        if (by != null) {
            CharSequence a = TextUtils.ellipsize(by, artist, room, TextUtils.TruncateAt.END);
            canvas.drawText(a, 0, a.length(), x, mid + artist.getTextSize() + Ui.dp(getContext(), 2), artist);
        }
        if (level >= 0) {
            float bx = x, bw = w - pad - x, by0 = h - pad * 0.55f, bh = Ui.dp(getContext(), 3);
            track.setColor(0xFFE6E6EB);
            canvas.drawRoundRect(bx, by0 - bh, bx + bw, by0, bh, bh, track);
            track.setColor(Ui.INK);
            canvas.drawRoundRect(bx, by0 - bh, bx + Math.max(bh, bw * level), by0, bh, bh, track);
        }
        // Play or pause, at the right.
        float s = Ui.dp(getContext(), 9), cx = w - pad - s, cy = mid;
        if (playing) {
            canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.25f, cy + s, glyph);
            canvas.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.8f, cy + s, glyph);
        } else {
            Path p = new Path();
            p.moveTo(cx - s * 0.7f, cy - s);
            p.lineTo(cx + s, cy);
            p.lineTo(cx - s * 0.7f, cy + s);
            p.close();
            canvas.drawPath(p, glyph);
        }
    }
}
