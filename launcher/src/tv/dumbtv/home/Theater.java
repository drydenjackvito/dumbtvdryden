package tv.dumbtv.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * A video cast from the YouTube app while no game is on: full screen, like AirPlay. YouTube's
 * own player plays it (see {@link VideoWindow}); the phone and the remote both control it.
 */
public final class Theater extends Activity {
    static final String EXTRA_ID = "video", EXTRA_AT = "at";

    private VideoWindow video;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoWindow(this, 1f, code -> {
            Toast.makeText(this, "That video only plays in YouTube's own app", Toast.LENGTH_LONG).show();
            finish();
        }, true);
        video.onCastStop = this::finish;
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        cast(getIntent().getStringExtra(EXTRA_ID), getIntent().getLongExtra(EXTRA_AT, 0));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        cast(intent.getStringExtra(EXTRA_ID), intent.getLongExtra(EXTRA_AT, 0));
    }

    private void cast(String id, long atMs) {
        if (id == null) {
            finish();
            return;
        }
        video.cast(id, atMs, 100);
    }

    /** Leaving stops it: a cast is watched, not left playing behind something else. */
    @Override
    protected void onStop() {
        super.onStop();
        video.close();
        finish();
    }

    @Override
    protected void onDestroy() {
        video.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) video.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) video.seek(10);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) video.seek(-10);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }
}
