package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Physeek's square icon button: white with a soft shadow, the icon drawn in ink as a 24-unit
 * stroked path. Focused, it inverts to ink with a white icon, like Physeek's home button.
 */
final class IconButton extends View {
    static final String MENU = "menu", APPS = "apps", SETTINGS = "settings";

    private final Path icon = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int size;
    private final float radius;

    IconButton(Context c, String kind, String label) {
        super(c);
        size = Ui.dp(c, 52);
        radius = Ui.dp(c, 16);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case MENU: // M4 7h16M4 12h16M4 17h16
                for (int y : new int[] {7, 12, 17}) {
                    icon.moveTo(4, y);
                    icon.lineTo(20, y);
                }
                break;
            case APPS: // Physeek's Categories icon: four rounded squares
                for (float[] r : new float[][] {{3.5f, 3.5f}, {13.5f, 3.5f}, {3.5f, 13.5f}, {13.5f, 13.5f}}) {
                    icon.addRoundRect(new RectF(r[0], r[1], r[0] + 7, r[1] + 7), 1.5f, 1.5f, Path.Direction.CW);
                }
                break;
            default: // sliders
                float[][] rows = {{6, 15}, {12, 8}, {18, 13}};
                for (float[] r : rows) {
                    icon.moveTo(4, r[0]);
                    icon.lineTo(r[1] - 2.2f, r[0]);
                    icon.moveTo(r[1] + 2.2f, r[0]);
                    icon.lineTo(20, r[0]);
                    icon.addCircle(r[1], r[0], 2.2f, Path.Direction.CW);
                }
        }
        setFocusable(true);
        setClickable(true);
        setContentDescription(label);
        setElevation(Ui.dp(c, 2));
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, size, size, radius);
                o.setAlpha(0.35f);
            }
        });
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(110).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean on = isFocused();
        fill.setColor(on ? Ui.INK : Ui.SURFACE);
        box.set(0, 0, size, size);
        canvas.drawRoundRect(box, radius, radius, fill);
        stroke.setColor(on ? Ui.SURFACE : Ui.INK);
        float scale = size * 0.5f / 24f; // a 24-unit icon in half the button, like 24 px in 48
        canvas.save();
        canvas.translate(size / 4f, size / 4f);
        canvas.scale(scale, scale);
        canvas.drawPath(icon, stroke);
        canvas.restore();
    }
}
