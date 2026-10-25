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
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path port = new Path();
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float pad, dotR, radius;

    Covers(Context c) {
        fill.setColor(Ui.SURFACE);
        dot.setColor(Ui.CONNECTED);
        name.setColor(Ui.INK);
        name.setTypeface(Ui.SEMIBOLD);
        name.setTextAlign(Paint.Align.CENTER);
        name.setLetterSpacing(-0.01f);
        pad = Ui.dp(c, 12);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0x1A111114);
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
        dotR = Ui.dp(c, 4);
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
        // White cards on the light page need an edge to read as separate covers when stacked.
        float g = h * 0.26f;
        canvas.save();
        canvas.translate((w - g) / 2, h * 0.17f);
        canvas.scale(g / 24f, g / 24f);
        glyph.setStrokeWidth(1.8f);
        canvas.drawPath(port, glyph);
        canvas.restore();
        name.setTextSize(h * 0.15f);
        line(canvas, title, w / 2, h * 0.74f, w);
        float e = Math.max(1f, h / 150f);
        edge.setStrokeWidth(e);
        canvas.drawRoundRect(e / 2, e / 2, w - e / 2, h - e / 2, radius - e / 2, radius - e / 2, edge);
        if (it.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Math.max(dotR, h * 0.03f);
            canvas.drawCircle(w - r * 3, r * 3, r, dot);
        }
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
