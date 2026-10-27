package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * The coloured glow round the preview: a tiny copy of the picture, blurred and spread a little
 * past the preview's edges, behind it, so the picture's colours spill onto the page. Fed a new
 * frame about twice a second; it eases between them.
 */
final class Glow extends View {
    /** Reach past the 32x18 frame, in sample pixels: wide at the sides, less above. */
    static final int PAD_X = 10, PAD_Y = 6;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private Bitmap shown;

    Glow(Context c) {
        super(c);
        setAlpha(0f);
        setFocusable(false);
    }

    /** A new frame (any size; it is reduced to 32x18 first). */
    void feed(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, 32, 18, true);
        shown = blur(small);
        invalidate();
        if (getAlpha() < 1f) animate().alpha(1f).setDuration(600).start();
    }

    /** No colours to go on (the TV would not share the frame): a soft neutral glow. */
    void neutral() {
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        feed(b);
    }

    void hide() {
        animate().cancel();
        animate().alpha(0f).setDuration(200).start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shown == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(shown, null, dst, paint);
    }

    /** Box blur, three passes each way, on premultiplied colour so the edges fade, not darken. */
    private static Bitmap blur(Bitmap src) {
        int w = src.getWidth() + 2 * PAD_X, h = src.getHeight() + 2 * PAD_Y;
        int[] in = new int[src.getWidth() * src.getHeight()];
        src.getPixels(in, 0, src.getWidth(), 0, 0, src.getWidth(), src.getHeight());
        float[][] ch = new float[4][w * h];
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int c = in[y * src.getWidth() + x], i = (y + PAD_Y) * w + x + PAD_X;
                float a = (c >>> 24) / 255f;
                ch[0][i] = a;
                ch[1][i] = ((c >> 16) & 0xFF) * a;
                ch[2][i] = ((c >> 8) & 0xFF) * a;
                ch[3][i] = (c & 0xFF) * a;
            }
        }
        float[] tmp = new float[w * h];
        int r = 3;
        for (float[] c : ch) {
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        float sum = 0;
                        for (int k = -r; k <= r; k++) {
                            int xx = Math.min(w - 1, Math.max(0, x + k));
                            sum += c[y * w + xx];
                        }
                        tmp[y * w + x] = sum / (2 * r + 1);
                    }
                }
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        float sum = 0;
                        for (int k = -r; k <= r; k++) {
                            int yy = Math.min(h - 1, Math.max(0, y + k));
                            sum += tmp[yy * w + x];
                        }
                        c[y * w + x] = sum / (2 * r + 1);
                    }
                }
            }
        }
        // Nothing below the picture: the glow would run into the panels and their reflections.
        // It fades out over the picture's lowest rows, so the sides end softly too.
        int bottom = PAD_Y + src.getHeight();
        float[] colourAlpha = ch[0].clone();
        for (int y = 0; y < h; y++) {
            float k = y < bottom - 5 ? 1 : y >= bottom ? 0 : (bottom - y) / 5f;
            for (int x = 0; x < w; x++) ch[0][y * w + x] *= k;
        }
        int[] out = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            float a = ch[0][i], ca = colourAlpha[i];
            if (a <= 0.002f || ca <= 0.002f) continue;
            int A = Math.min(255, Math.round(a * 255 * 0.55f));
            int R = Math.min(255, Math.round(ch[1][i] / ca)), G = Math.min(255, Math.round(ch[2][i] / ca)),
                    B = Math.min(255, Math.round(ch[3][i] / ca));
            out[i] = (A << 24) | (R << 16) | (G << 8) | B;
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

}
