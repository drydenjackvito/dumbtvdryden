package tv.dumbtv.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Untitled's website, full screen, worked with the remote: the arrows move a pointer (faster the
 * longer they are held), OK clicks, and the pointer pushed against the top or bottom edge scrolls.
 * BACK goes back a page, and from the first page leaves; the music keeps playing either way
 * ({@link Radio} holds the page).
 */
public final class Untitled extends Activity {
    private FrameLayout root;
    private Pointer pointer;
    private WebView web;
    private float x, y;
    private long heldSince;
    private int heldKey;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        x = getResources().getDisplayMetrics().widthPixels / 2f;
        y = getResources().getDisplayMetrics().heightPixels / 2f;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Radio r = Radio.get(this);
        web = r.web;
        r.showIn(this, root);
        Radio.uncap(); // the ceiling is for under a game
        pointer.at(x, y);
    }

    @Override
    protected void onStop() {
        Radio.get(this).hide();
        super.onStop();
    }

    private float speed(KeyEvent e) {
        if (e.getRepeatCount() == 0 || heldKey != e.getKeyCode()) {
            heldKey = e.getKeyCode();
            heldSince = SystemClock.uptimeMillis();
        }
        float d = getResources().getDisplayMetrics().density;
        long held = SystemClock.uptimeMillis() - heldSince;
        return d * (held < 250 ? 10 : held < 900 ? 22 : 40);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // In a text box, keys are for it; BACK leaves it, for the pointer again.
        if (web != null && web.hasFocus() && isTyping()) {
            if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
            if (!down) {
                web.evaluateJavascript("document.activeElement && document.activeElement.blur()", null);
                web.clearFocus();
            }
            return true;
        }
        int w = root.getWidth(), h = root.getHeight();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!down) return true;
                float s = speed(e);
                float dx = 0, dy = 0;
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: dx = -s; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: dx = s; break;
                    case KeyEvent.KEYCODE_DPAD_UP: dy = -s; break;
                    default: dy = s; break;
                }
                float edge = 24 * getResources().getDisplayMetrics().density;
                // At the top or bottom edge, the page scrolls under the pointer instead.
                if ((dy < 0 && y <= edge) || (dy > 0 && y >= h - edge)) {
                    scroll(dy < 0 ? 1f : -1f);
                    return true;
                }
                x = Math.max(0, Math.min(w - 1, x + dx));
                y = Math.max(0, Math.min(h - 1, y + dy));
                pointer.at(x, y);
                hover();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) tap(MotionEvent.ACTION_DOWN);
                else if (!down) tap(MotionEvent.ACTION_UP);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (web != null && web.canGoBack()) web.goBack();
                    else finish();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Menu: Untitled's start page.
                if (down && e.getRepeatCount() == 0 && web != null) web.loadUrl(Radio.HOME);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** Whether a text box on the page has the keyboard. */
    private boolean isTyping() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        return im != null && im.isAcceptingText() && im.isActive(web);
    }

    private long downAt;

    private void tap(int action) {
        if (web == null) return;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downAt = now;
        MotionEvent m = MotionEvent.obtain(downAt, now, action, x, y, 0);
        m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(m);
        m.recycle();
        if (action == MotionEvent.ACTION_DOWN) web.requestFocus();
    }

    private MotionEvent pointerEvent(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        return MotionEvent.obtain(now, now, action, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
    }

    /** The pointer's over something: the page's hover look (menus that open on hover). */
    private void hover() {
        if (web == null) return;
        MotionEvent m = pointerEvent(MotionEvent.ACTION_HOVER_MOVE);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** A mouse wheel turn under the pointer, so the part of the page under it scrolls. */
    private void scroll(float notches) {
        if (web == null) return;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, notches * 2);
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        long now = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** An arrow pointer drawn over the page. */
    private static final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float px, py;

        Pointer(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            fill.setColor(Color.WHITE);
            edge.setColor(0xCC000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * d);
            edge.setStrokeJoin(Paint.Join.ROUND);
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 20 * d);
            arrow.lineTo(5.5f * d, 15 * d);
            arrow.lineTo(9 * d, 23 * d);
            arrow.lineTo(12 * d, 21.5f * d);
            arrow.lineTo(8.5f * d, 14 * d);
            arrow.lineTo(15 * d, 14 * d);
            arrow.close();
            setElevation(10);
        }

        void at(float x, float y) {
            px = x;
            py = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.save();
            c.translate(px, py);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restore();
        }
    }
}
