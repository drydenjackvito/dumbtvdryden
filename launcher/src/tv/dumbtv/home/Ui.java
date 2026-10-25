package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Colours, type and sizes, taken from the Physeek app's stylesheet: a light grey page, white
 * cards, near-black ink for text and focus, grey for secondary text.
 */
final class Ui {
    /** Physeek --bg. */
    static final int PAGE = 0xFFF5F5F7;
    /** Physeek --surface. */
    static final int SURFACE = 0xFFFFFFFF;
    /** Physeek --text: text, focus and the inverted menus. */
    static final int INK = 0xFF111114;
    /** Physeek --text-muted. */
    static final int MUTED = 0xFF8A8A93;
    /** Physeek --up: an input with something connected. */
    static final int CONNECTED = 0xFF1F9D55;

    /** For the built-in input screen, which sits over video and stays dark. */
    static final int TEXT = 0xFFF2F2F3;
    static final int DIM_TEXT = 0x99FFFFFF;

    static final int DIALOG = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** Physeek's titles are weight 600; Android's closest stock face is medium. */
    static final Typeface SEMIBOLD = MEDIUM;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        v.setSingleLine();
        return v;
    }

    /** Large semibold title with Physeek's tight tracking (-0.02em). */
    static TextView title(Context c, float sp) {
        TextView v = text(c, sp, INK, SEMIBOLD);
        v.setLetterSpacing(-0.02f);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
