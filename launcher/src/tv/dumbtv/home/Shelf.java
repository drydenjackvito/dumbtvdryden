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
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A row of equal panels that slides: the selected panel sits in the middle, the others line up
 * beside it, and left and right glide the row on a critically damped spring (Physeek's). Each
 * panel stands on a floor that reflects it.
 */
final class Shelf extends View {
    interface Listener {
        /** The order after a move, as item ids. */
        void moved(Shelf shelf, List<String> ids);

        /** A different panel is now in the middle. */
        void selected(Shelf shelf, Item item);
    }

    private static final float OMEGA = 9f;
    /** Share of the art the reflection shows at the floor; it eases out to none. */
    private static final float REFLECT_STRENGTH = 0.65f;

    final String kind;
    private final Covers covers;
    private final float coverW, coverH, gap, radius, reflect, top, ringGap, ringW;
    private final Path clip = new Path();
    private final Paint fade = new Paint(), flat = new Paint(), solid = new Paint(), contact = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final RectF box = new RectF();
    private final android.graphics.Bitmap contactShadow;
    private final float shadowReach;
    private final Paint occlusion = new Paint();

    private List<Item> items = new ArrayList<>();
    private List<CharSequence> titles = new ArrayList<>();
    private Listener listener;

    private float pos;
    private int target;
    private float velocity, springFrom, springB;
    private long springStart;
    private boolean springing, moving;
    /** Before the first press: nothing looks selected. */
    private boolean quiet;
    private final android.graphics.Bitmap glow;
    private final float glowR;
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The key that ended a move: its release must not also open the panel. */
    private boolean swallowUp;

    Shelf(Context c, String kind, float height, Covers covers) {
        super(c);
        this.kind = kind;
        this.covers = covers;
        coverH = height;
        coverW = Math.round(height * 16f / 9f);
        gap = Ui.dp(c, 22);
        reflect = height * 0.7f;
        radius = Ui.dp(c, 12);
        ringGap = Ui.dp(c, 3);
        ringW = Ui.dp(c, 1.75f);
        glowR = Ui.dp(c, 6);
        top = glowR;
        glow = focusGlow(Math.round(coverW), Math.round(coverH), radius, glowR);
        clip.addRoundRect(new RectF(0, 0, coverW, coverH), radius, radius, Path.Direction.CW);

        // The reflection's veil, in the panel's own coordinates: the page colour letting through
        // REFLECT_STRENGTH of the art at the floor, easing out as (1 - t)^2 to nothing at `reflect`.
        int[] colors = new int[10];
        float[] stops = new float[10];
        for (int i = 0; i < 10; i++) {
            float t = i / 9f;
            float through = REFLECT_STRENGTH * (1 - t) * (1 - t);
            colors[i] = (Math.round((1 - through) * 255) << 24) | (Ui.PAGE & 0x00FFFFFF);
            stops[i] = t;
        }
        fade.setShader(new LinearGradient(0, coverH, 0, coverH + reflect, colors, stops, Shader.TileMode.CLAMP));
        flat.setColor(Ui.PAGE);
        solid.setColor(Ui.PAGE);
        shadowReach = Ui.dp(c, 8);
        contactShadow = underShadow(Math.round(coverW), shadowReach, Ui.dp(c, 3), 0.38f);
        occlusion.setShader(new LinearGradient(0, coverH * 0.88f, 0, coverH, 0x00000000, 0x0B000000,
                Shader.TileMode.CLAMP));
        contact.setFilterBitmap(true);
        shade.setColor(0xB3F5F5F7);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringW);
        ring.setColor(Ui.INK);
        arrow.setColor(Ui.INK);

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
    }

    /**
     * The contact shadow, on the floor plane: it starts at the line where the panel stands and
     * spreads only down (toward the viewer), never up. Its thickness follows a lens, deepest under
     * the panel's middle and narrowing to a point a little past each side; it fades softly with
     * distance from the panel. Made once, pixel by pixel.
     */
    private static android.graphics.Bitmap underShadow(int w, float reach, float depth, float strength) {
        int ext = Math.round(reach), bw = w + 2 * ext, bh = Math.max(2, Math.round(depth * 2.5f));
        int[] px = new int[bw * bh];
        float half = bw / 2f;
        for (int x = 0; x < bw; x++) {
            float u = (x + 0.5f - half) / half; // -1 at the far left tip, 1 at the far right
            float lens = (float) Math.pow(Math.max(0, 1 - u * u), 0.75);
            float t = depth * lens;
            if (t <= 0.05f) continue;
            for (int y = 0; y < bh; y++) {
                float d = (y + 0.5f) / t;
                float a = strength * lens * (float) Math.exp(-d * d * 1.6f);
                px[y * bw + x] = Math.round(Math.min(1, a) * 255) << 24;
            }
        }
        return android.graphics.Bitmap.createBitmap(px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
    }

    /**
     * The selection: a soft white glow round the top and sides of the panel, fading out toward
     * its bottom so the panel seems lit from above. Blurred once in software (Fire OS 6 cannot
     * blur on the GPU).
     */
    private static android.graphics.Bitmap focusGlow(int w, int h, float radius, float r) {
        int g = Math.round(r), bw = w + 2 * g, bh = h + 2 * g;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        // Solid white for a few dp round the panel, then a soft fade: the page is a light grey,
        // so the white reads as light spilling out from the panel.
        float core = r * 0.3f;
        p.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.55f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        RectF card = new RectF(g - core, g - core, g + w + core, g + h + core);
        c.drawRoundRect(card, radius + core, radius + core, p);
        c.drawRoundRect(card, radius + core, radius + core, p);
        int[] px = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        float fadeFrom = g + h * 0.45f, fadeTo = g + h;
        for (int y = 0; y < bh; y++) {
            float k = y <= fadeFrom ? 1 : y >= fadeTo ? 0 : 1 - (y - fadeFrom) / (fadeTo - fadeFrom);
            k = k * k;
            for (int x = 0; x < bw; x++) {
                int a = px[y * bw + x] >>> 24;
                px[y * bw + x] = (Math.round(a * k) << 24) | 0xFFFFFF;
            }
        }
        b.setPixels(px, 0, bw, 0, 0, bw, bh);
        return b;
    }

    /** Quiet: nothing looks selected until the first press on the remote. */
    void setQuiet(boolean on) {
        quiet = on;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** New contents; stays on the item with id {@code keep} when it is still there. */
    void setItems(List<Item> list, List<CharSequence> names, String keep) {
        String was = keep != null ? keep : current() == null ? null : current().id;
        items = new ArrayList<>(list);
        titles = new ArrayList<>(names);
        int at = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(was)) at = i;
        }
        jumpTo(Math.min(at, Math.max(0, items.size() - 1)));
    }

    Item current() {
        return items.isEmpty() ? null : items.get(target);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    float coverWidth() {
        return coverW;
    }

    void jumpTo(int index) {
        target = index;
        pos = index;
        velocity = 0;
        springing = false;
        selected();
        invalidate();
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(top + coverH + reflect));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focused) selected();
        invalidate();
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
            return true; // nothing beside a shelf: never let focus jump sideways
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
        selected();
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
        if (Item.AIRPLAY.equals(items.get(to).id) || Item.AIRPLAY.equals(items.get(target).id)) return; // stays last
        Collections.swap(items, target, to);
        Collections.swap(titles, target, to);
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

    private void selected() {
        Item it = current();
        setContentDescription(it == null ? null : titles.get(target));
        if (listener != null && it != null && isFocused()) listener.selected(this, it);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        step();
        int n = items.size();
        if (n == 0) return;
        float cx = getWidth() / 2f, floor = top + coverH, pitch = coverW + gap;
        int reach = (int) Math.ceil(getWidth() / 2f / pitch) + 1;
        int from = Math.max(0, (int) Math.floor(pos) - reach), to = Math.min(n - 1, (int) Math.ceil(pos) + reach);
        if (isFocused() && !quiet && !moving) {
            float x = cx + (target - pos) * pitch;
            canvas.drawBitmap(glow, x - coverW / 2 - glowR, floor - coverH - glowR, glowPaint);
        }
        boolean breathing = false;
        for (int k = from; k <= to; k++) {
            drawPanel(canvas, k, cx + (k - pos) * pitch, floor);
            breathing |= !Catalog.APPS.equals(kind) && Covers.glows(items.get(k));
        }
        // A live input's light breathes: keep drawing, at about 20 frames a second, while one shows.
        if (breathing && !springing) postInvalidateDelayed(50);
        if (moving) {
            float x = cx + (target - pos) * pitch, out = ringGap + ringW / 2;
            box.set(x - coverW / 2 - out, floor - coverH - out, x + coverW / 2 + out, floor + out);
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {ringW * 3, ringW * 2}, 0) : null);
            canvas.drawRoundRect(box, radius + out, radius + out, ring);
        }
    }

    private void drawPanel(Canvas canvas, int k, float x, float floor) {
        Item it = items.get(k);
        CharSequence title = titles.get(k);
        // Reflections away from the selected panel are a little weaker, as on Physeek's shelves.
        float near = Math.max(0.75f, 1 - 0.15f * Math.abs(k - pos));
        canvas.save();
        canvas.translate(x - coverW / 2, floor - coverH);

        canvas.save();
        canvas.scale(1, -1, 0, coverH);
        canvas.clipPath(clip);
        covers.draw(canvas, it, kind, title, coverW, coverH);
        canvas.restore();
        float bleed = 1.5f;
        canvas.drawRect(-bleed, coverH, coverW + bleed, coverH + reflect, fade);
        flat.setAlpha(Math.round((1 - near) * 255));
        canvas.drawRect(-bleed, coverH, coverW + bleed, coverH + reflect, flat);
        canvas.drawRect(-bleed, coverH + reflect - 0.5f, coverW + bleed, coverH * 2, solid);
        // The contact shadow lies on the floor from the panel's bottom edge down.
        contact.setAlpha(Math.round(near * 255));
        canvas.drawBitmap(contactShadow, -Math.round(shadowReach), coverH, contact);

        canvas.save();
        canvas.clipPath(clip);
        covers.draw(canvas, it, kind, title, coverW, coverH);
        // A touch of ambient occlusion along the bottom edge, where the panel meets the floor.
        canvas.drawRect(0, coverH * 0.88f, coverW, coverH, occlusion);
        if (moving && k == target) canvas.drawRect(0, 0, coverW, coverH, shade);
        canvas.restore();
        if (moving && k == target) drawArrows(canvas);
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
        canvas.drawPath(p, arrow);
    }
}
