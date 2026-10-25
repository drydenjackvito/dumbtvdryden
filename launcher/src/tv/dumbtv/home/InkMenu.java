package tv.dumbtv.home;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Physeek's pop-up menu: an ink panel with white rows, opened under the button or cover that
 * asked for it. A null label is a divider.
 */
final class InkMenu {
    private InkMenu() {}

    /** Opens under {@code anchor}, lined up with its left edge. */
    static void show(Context c, View anchor, String[] labels, Runnable[] actions) {
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        show(c, at[0], at[1] + anchor.getHeight() + Ui.dp(c, 8), labels, actions);
    }

    /** Opens with its top left corner at screen position x, y. */
    static void show(Context c, int x, int y, String[] labels, Runnable[] actions) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 6);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(Ui.round(Ui.INK, Ui.dp(c, 18)));
        panel.setElevation(Ui.dp(c, 12));
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) {
                View line = new View(c);
                line.setBackgroundColor(0x24FFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
                panel.addView(line, lp);
                continue;
            }
            TextView row = Ui.text(c, 17, Ui.SURFACE, Ui.MEDIUM);
            row.setText(labels[i]);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 24), 0);
            row.setFocusable(true);
            row.setClickable(true);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(0x2EFFFFFF, Ui.dp(c, 12)));
            bg.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
            row.setBackground(bg);
            Runnable run = actions[i];
            row.setOnClickListener(v -> {
                d.dismiss();
                run.run();
            });
            panel.addView(row, new LinearLayout.LayoutParams(Ui.dp(c, 240), Ui.dp(c, 50)));
            if (first == null) first = row;
        }
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x;
            lp.y = y;
            w.setAttributes(lp);
        }
        if (first != null) first.requestFocus();
        d.show();
    }
}
