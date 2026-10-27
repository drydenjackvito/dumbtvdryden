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
        holo.stop();
        release();
    }

    private void release() {
        if (tuned != null && tv != null) tv.reset();
        tuned = null;
        video = false;
    }

    private void reveal() {
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
        flicker.clear();
        ui.removeCallbacks(flickerStep);
        holo.stop();
        veil.animate().cancel();
        veil.animate().alpha(1f).setDuration(120).start();
    }
}
