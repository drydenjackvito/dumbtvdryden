package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;

import java.util.Map;

/**
 * Draws the face of one cover, the same on the shelves and in the grid: an app's TV banner, or
 * its icon and name on a white card when it has none; an input's name on a white card.
 */
final class Covers {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path port = new Path(), cast = new Path();
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shadedFor = -1;
    private final android.graphics.RectF bounds = new android.graphics.RectF();
    private final float pad, dotR, radius;

    Covers(Context c) {
        fill.setColor(Ui.SURFACE);
        dot.setColor(Ui.CONNECTED);
        name.setColor(Ui.INK);
        name.setTypeface(Ui.SEMIBOLD);
        name.setTextAlign(Paint.Align.CENTER);
        name.setLetterSpacing(-0.01f);
        pad = Ui.dp(c, 12);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setColor(Ui.MUTED);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        // An HDMI port, in a 24-unit box.
        port.moveTo(3, 8);
        port.lineTo(21, 8);
        port.lineTo(21, 13);
        port.lineTo(18, 16);
        port.lineTo(6, 16);
        port.lineTo(3, 13);
        port.close();
        port.moveTo(7, 11.5f);
        port.lineTo(17, 11.5f);
        // AirPlay: a screen with a triangle rising into its bottom edge.
        cast.moveTo(7, 17);
        cast.lineTo(4, 17);
        cast.quadTo(2, 17, 2, 15);
        cast.lineTo(2, 5);
        cast.quadTo(2, 3, 4, 3);
        cast.lineTo(20, 3);
        cast.quadTo(22, 3, 22, 5);
        cast.lineTo(22, 15);
        cast.quadTo(22, 17, 20, 17);
        cast.lineTo(17, 17);
        cast.moveTo(12, 14);
        cast.lineTo(17, 21);
        cast.lineTo(7, 21);
        cast.close();
        dotR = Ui.dp(c, 4);
        label.setColor(0xFFA3A3AB); // light grey
        label.setTypeface(Ui.MEDIUM);
        label.setTextAlign(Paint.Align.LEFT);
        label.setLetterSpacing(-0.01f);
        radius = Ui.dp(c, 12); // the corner the shelf and the grid clip covers to
    }

    /** The face of {@code it} in 0..w, 0..h; the caller clips to the card's rounded corners. */
    void draw(Canvas canvas, Item it, String kind, CharSequence title, float w, float h) {
        canvas.drawRect(0, 0, w, h, fill);
        Drawable banner = it.banner, icon = it.icon;
        if (Catalog.APPS.equals(kind)) {
            if (banner != null) {
                banner.setBounds(0, 0, Math.round(w), Math.round(h));
                banner.draw(canvas);
            } else if (icon != null) {
                int size = Math.round(h * 0.42f);
                int left = Math.round((w - size) / 2), top = Math.round(h * 0.14f);
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(canvas);
                name.setTextSize(h * 0.10f);
                line(canvas, title, w / 2, h * 0.84f, w);
            } else {
                name.setTextSize(h * 0.13f);
                line(canvas, title, w / 2, h / 2 - (name.ascent() + name.descent()) / 2, w);
            }
            return;
        }
        // Inputs: a white card when something is live on it (and AirPlay, always ready), grey when
        // not. The icon, the same width for every panel, sits in the middle; the name, in light
        // grey, at the bottom left.
        boolean live = it.state == TvInputManager.INPUT_STATE_CONNECTED || Item.AIRPLAY.equals(it.id);
        // A subtle top-to-bottom gradient instead of an outline: brighter at the top, a touch
        // deeper at the bottom, white for live inputs and grey for idle ones.
        shade(live, h);
        canvas.drawRect(0, 0, w, h, live ? liveFill : idle);
        Path mark = Item.AIRPLAY.equals(it.id) ? cast : port;
        mark.computeBounds(bounds, true);
        float size = h * 0.34f, scale = size / Math.max(bounds.width(), bounds.height());
        canvas.save();
        canvas.translate(w / 2, h / 2);
        canvas.scale(scale, scale);
        canvas.translate(-bounds.centerX(), -bounds.centerY());
        glyph.setStrokeWidth(1.8f);
        canvas.drawPath(mark, glyph);
        canvas.restore();
        float inset = h * 0.09f;
        label.setTextSize(h * 0.12f);
        CharSequence t = TextUtils.ellipsize(title, label, w - 2 * inset, TextUtils.TruncateAt.END);
        canvas.drawText(t, 0, t.length(), inset, h - inset, label);
        // Every input has its LED in its bezel: green when something is live on it, blue for
        // AirPlay while something is cast, dark otherwise.
        float r = Math.max(dotR, h * 0.03f);
        led(canvas, w - r * 3.2f, r * 3.2f, r, light(it));
    }

    /** Set while AirPlay (or a cast from a phone) is starting or playing: its LED lights blue. */
    static volatile boolean airplayOn;

    /** Which light {@code it} shows: 0 off, 1 green, 2 blue. */
    static int light(Item it) {
        if (Item.AIRPLAY.equals(it.id)) return airplayOn ? 2 : 0;
        return it.state == TvInputManager.INPUT_STATE_CONNECTED ? 1 : 0;
    }

    private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint socket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float ledFor = -1;
    private int ledColour = -1;

    /** Bloom, core, rim and beam colours for green (1) and blue (2). */
    private static final int[][] HUES = {
            {},
            {0x38E05A, 0xFFB8FFC2, 0xFF33E461, 0xFF1BB546, 0xC8FFD0},
            {0x3AA0FF, 0xFFBFE2FF, 0xFF3D9BFF, 0xFF1F6FE0, 0xC8E6FF},
    };

    /**
     * An LED set into the panel: a small socket whose upper rim is in shade and whose lower rim
     * catches light, and in it either a dark lens (off) or a white-hot centre and vivid colour
     * with a tight bloom (on); an on LED's flare is drawn over the panel by {@link #flare}.
     */
    private void led(Canvas canvas, float x, float y, float r, int on) {
        if (r != ledFor) {
            ledFor = r;
            ledColour = -1;
            // The socket: shade at the top of the hole, light along its bottom lip.
            socket.setShader(new android.graphics.LinearGradient(0, -r * 1.4f, 0, r * 1.4f,
                    new int[] {0x38000000, 0x14000000, 0x00FFFFFF, 0xE6FFFFFF}, new float[] {0f, 0.45f, 0.62f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            // Off: dark glass, a faint highlight up and to the left.
            lens.setShader(new android.graphics.RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.3f,
                    new int[] {0xFF9A9AA2, 0xFF5E5E66, 0xFF3C3C42}, new float[] {0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
        }
        if (on > 0 && on != ledColour) {
            ledColour = on;
            int[] c = HUES[on];
            bloom.setShader(new android.graphics.RadialGradient(0, 0, r * 2.2f,
                    new int[] {0x99000000 | c[0], 0x33000000 | c[0], c[0]}, new float[] {0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            dot.setShader(new android.graphics.RadialGradient(0, 0, r,
                    new int[] {0xFFFFFFFF, c[1], c[2], c[3]}, new float[] {0f, 0.45f, 0.8f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            flare.setShader(new android.graphics.LinearGradient(0, -r * 7, 0, r * 7,
                    new int[] {c[0], 0x70000000 | c[0], 0xB0000000 | c[4], 0x70000000 | c[0], c[0]},
                    new float[] {0f, 0.38f, 0.5f, 0.62f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.drawCircle(0, 0, r * 1.4f, socket);
        if (on == 0) {
            canvas.drawCircle(0, 0, r, lens);
        } else {
            // Breathing: a slow 3 s swell of the flare, and a little of the bloom.
            float t = (android.os.SystemClock.uptimeMillis() % 3000) / 3000f;
            float breathe = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2);
            bloom.setAlpha(Math.round(255 * (0.75f + 0.25f * breathe)));
            canvas.drawCircle(0, 0, r * 2.2f, bloom);
            canvas.drawCircle(0, 0, r, dot);
        }
        canvas.restore();
    }

    /**
     * The LED's flare, drawn over the panel rather than within it, so the beam runs past the
     * panel's edges. A thin beam at 35 degrees from the horizontal: three streaks, a bright narrow
     * core inside wider fainter ones, so it feathers; it swells and fades with the LED's breath.
     */
    void flare(Canvas canvas, Item it, float w, float h) {
        int on = light(it);
        if (on == 0) return;
        float r = Math.max(dotR, h * 0.03f);
        if (r != ledFor || on != ledColour) return; // the face, drawn first, sets the shaders up
        float t = (android.os.SystemClock.uptimeMillis() % 3000) / 3000f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2);
        float strength = 0.25f + 0.75f * breathe;
        canvas.save();
        canvas.translate(w - r * 3.2f, r * 3.2f);
        canvas.rotate(55); // the vertical beam turned to 35 degrees above the horizontal
        float[][] streaks = {{0.55f, 0.18f}, {0.28f, 0.35f}, {0.1f, 0.8f}}; // half-width, strength
        for (float[] st : streaks) {
            flare.setAlpha(Math.round(255 * strength * st[1]));
            canvas.drawRect(-r * st[0], -r * 7, r * st[0], r * 7, flare);
        }
        canvas.restore();
    }

    /** Whether {@code it} shows a lit LED, which breathes and so needs redrawing. */
    static boolean glows(Item it) {
        return light(it) > 0;
    }

    /** The two gradients, made again only when the panel height changes. */
    private void shade(boolean live, float h) {
        if (h == shadedFor) return;
        shadedFor = h;
        liveFill.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFFF0F0F4,
                android.graphics.Shader.TileMode.CLAMP));
        idle.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFEBEBEF, 0xFFDEDEE4,
                android.graphics.Shader.TileMode.CLAMP));
    }

    private void line(Canvas canvas, CharSequence title, float x, float baseline, float w) {
        CharSequence s = TextUtils.ellipsize(title, name, w - 2 * pad, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, name);
    }

    /** The grey line under a cover's name, like Physeek's creator line. */
    static String subtitle(Item it, String kind, Map<String, Long> recent) {
        if (Catalog.APPS.equals(kind)) {
            Long t = recent == null ? null : recent.get(it.id);
            if (t == null || t <= 0) return "Not opened yet";
            return "Opened " + DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
        }
        switch (it.state) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                return "Connected";
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                return "Standby";
            case TvInputManager.INPUT_STATE_DISCONNECTED:
                return "Nothing connected";
            default:
                return "Input";
        }
    }
}
