package tv.dumbtv.home;

import android.content.Context;
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
    private final Holo holo;
    private Glow glow;
    private int copyFailures;
    private final Runnable sample = this::sample;
    private boolean hologram;
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
        holo = new Holo(c);
        holo.jolts(tv != null ? new View[] {tv, art} : new View[] {art});
        addView(holo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setFocusable(false);
    }

    /** The coloured glow behind this window, fed from its picture. */
    void setGlow(Glow g) {
        glow = g;
    }

    /**
     * Copies the picture into a tiny frame for the glow, twice a second while it shows. The TV's
     * video hardware may refuse (often for HDCP sources): after two refusals the glow goes neutral
     * and stops asking.
     */
    private void sample() {
        if (glow == null) return;
        if (art.getVisibility() == VISIBLE && art.getDrawable() != null) {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable d = art.getDrawable();
            d.setBounds(0, 0, 64, 36);
            d.draw(new android.graphics.Canvas(b));
            glow.feed(b);
            return; // a banner does not change
        }
        android.view.SurfaceView surface = surfaceOf(tv);
        if (surface == null || !video || copyFailures >= 2) {
            if (copyFailures >= 2) glow.neutral();
            return;
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(surface, b, result -> {
                if (result == android.view.PixelCopy.SUCCESS) {
                    copyFailures = 0;
                    glow.feed(b);
                } else if (++copyFailures >= 2) {
                    glow.neutral();
                    return;
                }
                if (video) ui.postDelayed(sample, 500);
            }, ui);
        } catch (RuntimeException e) {
            copyFailures = 2;
            glow.neutral();
        }
    }

    private static android.view.SurfaceView surfaceOf(android.view.ViewGroup g) {
        if (g == null) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) instanceof android.view.SurfaceView) return (android.view.SurfaceView) g.getChildAt(i);
        }
        return null;
    }

    /**
     * Shows {@code it} once the selection has rested. {@code mode} is Options › Input preview:
     * "holo" (the hologram), "on" (plain), or "off" (no live picture of inputs).
     */
    void show(Item it, String kind, String mode) {
        boolean live = !"off".equals(mode);
        hologram = "holo".equals(mode);
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
        flicker.clear();
        ui.removeCallbacks(flickerStep);
        ui.removeCallbacks(sample);
        if (glow != null) glow.hide();
        holo.stop();
        release();
    }

    private void release() {
        if (tuned != null && tv != null) tv.reset();
        tuned = null;
        video = false;
    }

    private void reveal() {
        ui.removeCallbacks(sample);
        ui.postDelayed(sample, 250);
        if (!hologram) {
            veil.animate().alpha(0f).setDuration(FADE_MS).start();
            return;
        }
        // Flickers on like a projector catching: hard cuts between levels, then steady.
        veil.animate().cancel();
        holo.start();
        flicker.clear();
        float[][] steps = {{0.35f, 0}, {0.9f, 60}, {0.15f, 110}, {0.75f, 170}, {0.05f, 240},
            {0.5f, 300}, {0.1f, 360}, {0.3f, 430}, {0f, 520}};
        for (float[] st : steps) flicker.add(st);
        flickerStart = android.os.SystemClock.uptimeMillis();
        ui.removeCallbacks(flickerStep);
        ui.post(flickerStep);
    }

    private final java.util.List<float[]> flicker = new java.util.ArrayList<>();
    private long flickerStart;
    private final Runnable flickerStep = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.uptimeMillis() - flickerStart;
            while (!flicker.isEmpty() && flicker.get(0)[1] <= now) veil.setAlpha(flicker.remove(0)[0]);
            if (!flicker.isEmpty()) ui.postDelayed(this, Math.max(1, (long) flicker.get(0)[1] - now));
        }
    };

    private void conceal() {
        ui.removeCallbacks(sample);
        if (glow != null) glow.hide();
        flicker.clear();
        ui.removeCallbacks(flickerStep);
        holo.stop();
        veil.animate().cancel();
        veil.animate().alpha(1f).setDuration(120).start();
    }
}
