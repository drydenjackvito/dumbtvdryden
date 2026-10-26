package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The window above the panels. For an input it tunes a muted {@link TvView} to it and fades the
 * picture in once video arrives, so a device that is on shows what selecting it would show and
 * one that is off shows nothing. For an app it fades in the app's banner. It waits for the
 * selection to rest before tuning, so sliding past inputs does not switch the TV's input on
 * every press, and it lets go of the input whenever the home screen is not in front.
 */
final class Preview extends FrameLayout {
    private static final long SETTLE_MS = 450;
    private static final long FADE_MS = 350;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TvView tv;
    private final ImageView art;
    private final View veil;
    private String tuned, showing;
    private boolean video;
    private Runnable pending;

    Preview(Context c) {
        super(c);
        boolean hasTv = c.getSystemService(Context.TV_INPUT_SERVICE) != null;
        tv = hasTv ? new TvView(c) : null;
        if (tv != null) {
            tv.setFocusable(false);
            tv.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onVideoAvailable(String id) {
                    if (!id.equals(tuned)) return;
                    video = true;
                    reveal();
                }

                @Override
                public void onVideoUnavailable(String id, int reason) {
                    if (!id.equals(tuned)) return;
                    video = false;
                    conceal();
                }

                @Override
                public void onConnectionFailed(String id) {
                    if (!id.equals(tuned)) return;
                    video = false;
                    conceal();
                }

                @Override
                public void onDisconnected(String id) {
                    if (!id.equals(tuned)) return;
                    video = false;
                    conceal();
                }
            });
            addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setVisibility(GONE);
        addView(art, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        veil = new View(c);
        veil.setBackgroundColor(Ui.PAGE);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(new Corners(c), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setFocusable(false);
    }

    /** Shows {@code it} once the selection has rested; {@code live} false skips tuning inputs. */
    void show(Item it, String kind, boolean live) {
        if (it.id.equals(showing) && (pending != null || tuned != null || art.getVisibility() == VISIBLE)) return;
        showing = it.id;
        if (pending != null) ui.removeCallbacks(pending);
        conceal();
        pending = () -> {
            pending = null;
            if (Catalog.APPS.equals(kind)) {
                release();
                art.setImageDrawable(it.banner != null ? it.banner : it.icon);
                art.setVisibility(VISIBLE);
                if (art.getDrawable() != null) reveal();
                return;
            }
            art.setVisibility(GONE);
            if (!live || tv == null || Item.AIRPLAY.equals(it.id) || it.intent.getData() == null) {
                release();
                return;
            }
            if (it.id.equals(tuned)) {
                if (video) reveal(); // still tuned, picture already there
                return;
            }
            Uri channel = it.intent.getData();
            tuned = it.id;
            video = false;
            tv.setStreamVolume(0f); // a preview, never sound
            tv.tune(it.id, channel);
            tv.setStreamVolume(0f);
        };
        ui.postDelayed(pending, Catalog.APPS.equals(kind) ? 150 : SETTLE_MS);
    }

    /** Hides the preview and lets go of the input. */
    void stop() {
        if (pending != null) ui.removeCallbacks(pending);
        pending = null;
        showing = null;
        art.setVisibility(GONE);
        veil.animate().cancel();
        veil.setAlpha(1f);
        release();
    }

    private void release() {
        if (tuned != null && tv != null) tv.reset();
        tuned = null;
        video = false;
    }

    private void reveal() {
        veil.animate().alpha(0f).setDuration(FADE_MS).start();
    }

    private void conceal() {
        veil.animate().cancel();
        veil.animate().alpha(1f).setDuration(120).start();
    }

    /** Page-coloured corners over the picture, so the window reads as a rounded card. */
    private static final class Corners extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float radius;

        Corners(Context c) {
            super(c);
            paint.setColor(Ui.PAGE);
            radius = Ui.dp(c, 16);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }
    }
}
