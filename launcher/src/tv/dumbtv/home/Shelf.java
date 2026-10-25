package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One Physeek shelf: a coverflow. The cover in the middle is full size; the others stack behind
 * it, each 20% smaller per step out, with a set slice of each showing past the one in front.
 * Covers stand on a floor and are reflected in it. The middle cover's name and a grey line
 * sit under it. Left and right glide to the next cover on a critically damped spring.
 *
 * Ported from Physeek's app.js (createShelf, peekX); the numbers are Physeek's, scaled from its
 * 150 px covers to this shelf's cover height.
 */
final class Shelf extends View {
    interface Listener {
        /** The order after a move, as item ids. */
        void moved(Shelf shelf, List<String> ids);
    }

    private static final int WINDOW = 5;
    private static final float PEEK_FALLOFF = 0.75f;
    private static final float CROSS_FROM = 0.3f, CROSS_TO = 0.65f;
    private static final float OMEGA = 9f;

    final String kind;
    private final Covers covers;
    private final float coverW, coverH, radius, peek, reflect, top, ringGap, ringW;
    private final Path clip = new Path();
    private final Paint fade = new Paint(), flat = new Paint(), solid = new Paint(), contact = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG), sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float metaGap, lineGap;

    private List<Item> items = new ArrayList<>();
    private List<CharSequence> titles = new ArrayList<>();
    private List<String> subs = new ArrayList<>();
    private Listener listener;

    private float pos;
    private int target;
    private float velocity, springFrom, springB;
    private long springStart;
    private boolean springing, moving;
    /** The key that ended a move: its release must not also open the cover. */
    private boolean swallowUp;

    Shelf(Context c, String kind, float height, Covers covers) {
        super(c);
        this.kind = kind;
        this.covers = covers;
        coverH = height;
        coverW = Math.round(height * 16f / 9f);
        float unit = height / 150f; // Physeek's numbers are for 150 px covers
        peek = 50 * unit;
        reflect = 40 * unit;
        radius = Ui.dp(c, 12);
        ringGap = Ui.dp(c, 3);
        ringW = Ui.dp(c, 3);
        top = ringGap + ringW + Ui.dp(c, 6);
        clip.addRoundRect(new RectF(0, 0, coverW, coverH), radius, radius, Path.Direction.CW);

        // The reflection's veil, in the cover's own coordinates: the page colour letting through
        // 40% of the art at the floor, easing out as 40% x (1 - t)^2 to nothing at `reflect`.
        int[] colors = new int[8];
        float[] stops = new float[8];
        for (int i = 0; i < 8; i++) {
            float t = i / 7f;
            float through = 0.4f * (1 - t) * (1 - t);
            colors[i] = (Math.round((1 - through) * 255) << 24) | (Ui.PAGE & 0x00FFFFFF);
            stops[i] = t;
        }
        fade.setShader(new LinearGradient(0, coverH, 0, coverH + reflect, colors, stops, Shader.TileMode.CLAMP));
        flat.setColor(Ui.PAGE);
        solid.setColor(Ui.PAGE);
        contact.setShader(new LinearGradient(0, coverH, 0, coverH + reflect * 0.075f,
                0x21000000, 0x00000000, Shader.TileMode.CLAMP));
        shade.setColor(0xB3F5F5F7);

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringW);
        ring.setColor(Ui.INK);
        dots.setColor(Ui.MUTED);
        name.setColor(Ui.INK);
        name.setTypeface(Ui.MEDIUM);
        name.setTextSize(Ui.dp(c, 20));
        sub.setColor(Ui.MUTED);
        sub.setTypeface(Ui.REGULAR);
        sub.setTextSize(Ui.dp(c, 17));
        metaGap = Ui.dp(c, 14);
        lineGap = Ui.dp(c, 5);

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** New contents; stays on the item with id {@code keep} when it is still there. */
    void setItems(List<Item> list, List<CharSequence> names, List<String> lines, String keep) {
        String was = keep != null ? keep : current() == null ? null : current().id;
        items = new ArrayList<>(list);
        titles = new ArrayList<>(names);
        subs = new ArrayList<>(lines);
        int at = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(was)) at = i;
        }
        jumpTo(Math.min(at, Math.max(0, items.size() - 1)));
        requestLayout();
    }

    /** Refreshes the grey lines (e.g. "Opened 5 minutes ago") without moving. */
    void setSubtitles(List<String> lines) {
        if (lines.size() == items.size()) subs = new ArrayList<>(lines);
        invalidate();
    }

    Item current() {
        return items.isEmpty() ? null : items.get(target);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    void jumpTo(int index) {
        target = index;
        pos = index;
        velocity = 0;
        springing = false;
        describe();
        invalidate();
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    boolean isMoving() {
        return moving;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float meta = metaGap + (name.descent() - name.ascent()) + lineGap + (sub.descent() - sub.ascent());
        int h = Math.round(top + coverH + reflect + meta + ringGap);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (moving) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    swap(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    swap(1);
                    return true;
                default:
                    swallowUp = true;
                    stopMoving();
                    return true;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int next = target + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (next >= 0 && next < items.size()) glideTo(next);
            return true; // nothing to the sides of a shelf: never let focus jump sideways
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (moving || swallowUp) {
            swallowUp = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void glideTo(int index) {
        target = index;
        describe();
        if (Settings.Global.getFloat(getContext().getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            jumpTo(index);
            return;
        }
        // Starts at the current speed, never overshoots, eases into place.
        springFrom = pos - target;
        springB = velocity + OMEGA * springFrom;
        springStart = SystemClock.uptimeMillis();
        springing = true;
        postInvalidateOnAnimation();
    }

    private void step() {
        if (!springing) return;
        float t = (SystemClock.uptimeMillis() - springStart) / 1000f;
        float e = (float) Math.exp(-OMEGA * t);
        float x = (springFrom + springB * t) * e;
        velocity = (springB - OMEGA * (springFrom + springB * t)) * e;
        pos = target + x;
        if (Math.abs(x) < 0.0005f && Math.abs(velocity) < 0.005f) {
            pos = target;
            velocity = 0;
            springing = false;
        } else {
            postInvalidateOnAnimation();
        }
    }

    private void swap(int by) {
        int to = target + by;
        if (to < 0 || to >= items.size()) return;
        Collections.swap(items, target, to);
        Collections.swap(titles, target, to);
        Collections.swap(subs, target, to);
        jumpTo(to);
    }

    private void stopMoving() {
        moving = false;
        invalidate();
        if (listener == null) return;
        List<String> ids = new ArrayList<>();
        for (Item it : items) ids.add(it.id);
        listener.moved(this, ids);
    }

    private void describe() {
        Item it = current();
        setContentDescription(it == null ? null : titles.get(target));
    }

    private static float scaleAt(float d) {
        return 1 - 0.2f * Math.min(d, 2.5f);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.min(1, Math.max(0, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Cover i's centre, from the middle, when cover m is centred (Physeek's peekX). */
    private float peekX(int i, int m) {
        if (i == m) return 0;
        int dir = i > m ? 1 : -1;
        float half = coverW / 2;
        float edge = half;
        float slice = peek;
        for (int j = m + dir, n = 1; ; j += dir) {
            edge += slice;
            if (j == i) return dir * (edge - half * scaleAt(Math.abs(j - m)));
            n++;
            if (n <= 3) slice *= PEEK_FALLOFF;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        step();
        int n = items.size();
        if (n == 0) return;
        float cx = getWidth() / 2f, floor = top + coverH;
        int base = Math.round(pos), lo = (int) Math.floor(pos);
        float f = pos - lo;
        int focus = f < CROSS_TO ? lo : lo + 1;

        // Back to front: the cover in focus on top, the incoming one just under it, then by
        // steps from the focus.
        List<int[]> order = new ArrayList<>();
        for (int k = Math.max(0, base - WINDOW); k <= Math.min(n - 1, base + WINDOW); k++) {
            int z = k == focus ? 100 : focus == lo && k == lo + 1 ? 99 : 98 - 2 * Math.abs(k - focus);
            order.add(new int[] {z, k});
        }
        Collections.sort(order, (a, b) -> Integer.compare(a[0], b[0]));
        for (int[] o : order) drawCover(canvas, o[1], cx, floor, lo, f, 1f);

        // The cover coming into the middle fades in over the one leaving it, clipped to it.
        float cross = smooth(CROSS_FROM, CROSS_TO, f);
        if (lo + 1 < n && cross > 0.001f && cross < 1f) {
            float s = scaleAt(Math.abs(lo - pos));
            float x = cx + xAt(lo, lo, f);
            canvas.save();
            canvas.clipRect(x - coverW / 2 * s, 0, x + coverW / 2 * s, getHeight());
            drawCover(canvas, lo + 1, cx, floor, lo, f, cross);
            canvas.restore();
        }

        int c = Math.min(n - 1, Math.max(0, focus));
        float s = scaleAt(Math.abs(c - pos));
        float x = cx + xAt(c, lo, f);
        if (isFocused() || moving) {
            float out = ringGap + ringW / 2;
            box.set(x - coverW / 2 * s - out, floor - coverH * s - out, x + coverW / 2 * s + out, floor + out);
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {ringW * 3, ringW * 2}, 0) : null);
            canvas.drawRoundRect(box, radius * s + out, radius * s + out, ring);
        }
        drawMeta(canvas, Math.min(n - 1, Math.max(0, base)), cx, floor + reflect + metaGap);
    }

    private float xAt(int k, int lo, float f) {
        return peekX(k, lo) * (1 - f) + peekX(k, lo + 1) * f;
    }

    private void drawCover(Canvas canvas, int k, float cx, float floor, int lo, float f, float alpha) {
        float dist = Math.abs(k - pos);
        float s = scaleAt(dist);
        float near = Math.max(0.35f, 1 - 0.4f * dist);
        if (dist > WINDOW - 0.5f) alpha *= Math.max(0, WINDOW + 0.5f - dist);
        if (alpha <= 0) return;
        Item it = items.get(k);
        CharSequence title = titles.get(k);

        canvas.save();
        canvas.translate(cx + xAt(k, lo, f) - coverW / 2, floor - coverH);
        canvas.scale(s, s, coverW / 2, coverH); // shrinks toward the floor
        if (alpha < 1f) canvas.saveLayerAlpha(-2, 0, coverW + 2, coverH * 2, Math.round(alpha * 255));

        // The reflection: the art flipped under the floor, under a veil of the page colour that is
        // solid from `reflect` down, so it also hides the reflections of covers behind.
        canvas.save();
        canvas.scale(1, -1, 0, coverH);
        canvas.clipPath(clip);
        covers.draw(canvas, it, kind, title, coverW, coverH);
        canvas.restore();
        float bleed = 1.5f;
        canvas.drawRect(-bleed, coverH, coverW + bleed, coverH + reflect, fade);
        flat.setAlpha(Math.round((1 - near) * 255)); // weaker away from the focus
        canvas.drawRect(-bleed, coverH, coverW + bleed, coverH + reflect, flat);
        canvas.drawRect(-bleed, coverH + reflect - 0.5f, coverW + bleed, coverH * 2, solid);
        contact.setAlpha(Math.round(near * 255));
        canvas.drawRect(0, coverH, coverW, coverH + reflect * 0.075f, contact);

        canvas.save();
        canvas.clipPath(clip);
        covers.draw(canvas, it, kind, title, coverW, coverH);
        if (moving && k == target) canvas.drawRect(0, 0, coverW, coverH, shade);
        canvas.restore();
        if (moving && k == target) drawArrows(canvas);

        if (alpha < 1f) canvas.restore();
        canvas.restore();
    }

    private void drawArrows(Canvas canvas) {
        float h = coverH / 6f, w = h * 0.7f, mid = coverH / 2f, pad = h * 0.8f;
        Path p = new Path();
        p.moveTo(pad, mid);
        p.lineTo(pad + w, mid - h / 2);
        p.lineTo(pad + w, mid + h / 2);
        p.close();
        p.moveTo(coverW - pad, mid);
        p.lineTo(coverW - pad - w, mid - h / 2);
        p.lineTo(coverW - pad - w, mid + h / 2);
        p.close();
        dots.setColor(Ui.INK);
        canvas.drawPath(p, dots);
        dots.setColor(Ui.MUTED);
    }

    /** Name, the "more" dots (Menu), and the grey line, left-aligned under the middle cover. */
    private void drawMeta(Canvas canvas, int k, float cx, float y) {
        float left = cx - coverW / 2, width = coverW;
        float r = Ui.dp(getContext(), 2.2f), dotsW = r * 2 * 3 + r * 2 * 2;
        float baseline = y - name.ascent();
        CharSequence t = TextUtils.ellipsize(titles.get(k), name, width - dotsW - r * 4, TextUtils.TruncateAt.END);
        canvas.drawText(t, 0, t.length(), left, baseline, name);
        float dy = baseline + (name.ascent() + name.descent()) / 2 + name.descent() * 0.2f;
        for (int i = 0; i < 3; i++) canvas.drawCircle(left + width - r - i * r * 4, dy, r, dots);
        String line = subs.get(k);
        if (line == null) return;
        float subBase = baseline + name.descent() + lineGap - sub.ascent();
        CharSequence l = TextUtils.ellipsize(line, sub, width, TextUtils.TruncateAt.END);
        canvas.drawText(l, 0, l.length(), left, subBase, sub);
    }
}
