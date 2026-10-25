package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Every setting is a row: left/right or OK cycles it, and the change is saved at once. */
public final class Options extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Ui.PAGE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.PAGE));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);

        TextView title = Ui.title(this, 30);
        title.setText("Options");
        title.setPadding(dp(16), 0, 0, dp(20));
        list.addView(title);

        for (Config.Choice c : Config.CHOICES) list.addView(choice(c));
        list.addView(gap());
        list.addView(action("Apps on home screen", () -> pick(Catalog.APPS)));
        list.addView(action("Inputs on home screen", () -> pick(Catalog.INPUTS)));
        list.addView(action("Reset order, names and hidden covers", this::reset));
        list.addView(action("Open Amazon's home screen", () -> {
            Guard.amazon(this);
            finish();
        }));

        TextView about = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        about.setSingleLine(false);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(dp(16), dp(28), 0, 0);
        about.setText("Settings file: " + cfg.dir + "/" + Config.FILE
                + "\nOn Fire OS 8 adb cannot reach that folder; use /sdcard/Android/media/"
                + getPackageName() + "/ instead"
                + "\nThe Apps shelf also shows whenever there are no inputs to show"
                + "\nNo internet permission. No tracking, no ads, no accounts.");
        list.addView(about);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = label(c.title);
        TextView value = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            String text = c.name(cfg.get(c));
            value.setText(focused ? "‹  " + text + "  ›" : text);
            value.setTextColor(focused ? Ui.SURFACE : Ui.MUTED);
            name.setTextColor(focused ? Ui.SURFACE : Ui.INK);
            boolean off = c == Config.SWITCH && "off".equals(cfg.get(Config.INPUTS));
            row.setAlpha(off ? 0.4f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> show.run());
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        int n = c.values.length;
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        return true;
    }

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        row.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView name = (TextView) row.getChildAt(0);
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        row.setOnFocusChangeListener((v, has) -> {
            name.setTextColor(has ? Ui.SURFACE : Ui.INK);
            chevron.setTextColor(has ? Ui.SURFACE : Ui.MUTED);
        });
        refreshers.add(() -> style(row));
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(600), WRAP);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** Every row a white card, like Physeek's settings lists; the focused one filled with ink. */
    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(Ui.INK, dp(18)));
        bg.addState(new int[0], Ui.round(Ui.SURFACE, dp(18)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 16, Ui.INK, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        return v;
    }

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? "Apps on home screen" : "Inputs on home screen")
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton("Done", null)
                .show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Reset the home screen layout?")
                .setMessage("Order, names and hidden covers go back to default. Other options stay.")
                .setPositiveButton("Reset", (d, w) -> cfg.removePrefix("name.", "apps.", "inputs."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
