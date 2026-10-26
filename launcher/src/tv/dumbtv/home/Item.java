package tv.dumbtv.home;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;

/** One tile: an installed app (id = package name) or a TV input (id = input id). */
final class Item {
    /** The AirPlay panel at the end of the inputs: opens an AirPlay receiver app. */
    static final String AIRPLAY = "airplay";

    final String id;
    final CharSequence label;
    final Intent intent;
    ActivityInfo info;
    boolean leanback;
    int state = -1;
    /** For the AirPlay panel: the package it opens, or null when it opens the TV's settings. */
    String receiver;
    volatile boolean artRequested;
    volatile Drawable banner, icon;

    Item(String id, CharSequence label, Intent intent) {
        this.id = id;
        this.label = label;
        this.intent = intent;
    }
}
