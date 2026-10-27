package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * One cover on the View All page, as in Physeek's grid: the cover with a soft shadow, then the
 * name and a grey line under it, left-aligned. Focus is an ink outline just outside the cover.
 */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.06f;

    final Item item;
    final String kind;
    private final CharSequence title;
    private final String line;
    private final Covers covers;
    private final int cardW, cardH, textH;
    private final float radius, ringGap, ringW;
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;

    Tile(Context c, Item item, String kind, CharSequence title, String line, int cardW, Covers covers) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.line = line;
        this.covers = covers;
        this.cardW = cardW;
        cardH = Math.round(cardW * 9f / 16f);
        radius = Ui.dp(c, 12);
        ringGap = Ui.dp(c, 3);
        ringW = Ui.dp(c, 1.75f);
        name.setColor(Ui.INK);
        name.setTypeface(Ui.MEDIUM);
        name.setTextSize(Ui.dp(c, 16));
        sub.setColor(Ui.MUTED);
        sub.setTypeface(Ui.REGULAR);
        sub.setTextSize(Ui.dp(c, 14));
        textH = Math.round(Ui.dp(c, 10) + name.descent() - name.ascent() + Ui.dp(c, 2) + sub.descent() - sub.ascent());
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringW);
        ring.setColor(Ui.INK);
        arrow.setColor(Ui.INK);
        clip.addRoundRect(new RectF(0, 0, cardW, cardH), radius, radius, Path.Direction.CW);

        // Physeek's --shadow, under the cover only, not the text below it.
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, Tile.this.cardW, Tile.this.cardH, radius);
                o.setAlpha(0.3f);
            }
        });
        setElevation(Ui.dp(c, 3));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + textH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().cancel();
        if (focused) {
            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(Ui.dp(getContext(), 8)).setDuration(120).start();
        } else {
            setScaleX(1f);
            setScaleY(1f);
            setTranslationZ(0f);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        covers.draw(canvas, item, kind, title, cardW, cardH);
        if (moving) canvas.drawColor(0xB3F5F5F7);
        canvas.restore();
        covers.flare(canvas, item, cardW, cardH);
        if (isFocused() || moving) {
            float out = ringGap + ringW / 2;
            box.set(-out, -out, cardW + out, cardH + out);
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {ringW * 3, ringW * 2}, 0) : null);
            canvas.drawRoundRect(box, radius + out, radius + out, ring);
        }
        if (moving) drawArrows(canvas);
        float baseline = cardH + Ui.dp(getContext(), 10) - name.ascent();
        CharSequence t = TextUtils.ellipsize(title, name, cardW, TextUtils.TruncateAt.END);
        canvas.drawText(t, 0, t.length(), 0, baseline, name);
        if (line != null) {
            CharSequence l = TextUtils.ellipsize(line, sub, cardW, TextUtils.TruncateAt.END);
            canvas.drawText(l, 0, l.length(), 0, baseline + name.descent() + Ui.dp(getContext(), 2) - sub.ascent(), sub);
        }
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        Path p = new Path();
        p.moveTo(pad, mid);
        p.lineTo(pad + w, mid - h / 2);
        p.lineTo(pad + w, mid + h / 2);
        p.close();
        p.moveTo(cardW - pad, mid);
        p.lineTo(cardW - pad - w, mid - h / 2);
        p.lineTo(cardW - pad - w, mid + h / 2);
        p.close();
        canvas.drawPath(p, arrow);
    }
}
