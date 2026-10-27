package tv.dumbtv.home;

import android.service.notification.NotificationListenerService;

/**
 * Android shows which app is playing music, and lets another app skip its songs, only to an app
 * the user allowed to read notifications. This service is that allowance and nothing more: it
 * reads no notifications. install.sh switches it on (cmd notification allow_listener).
 */
public final class Listener extends NotificationListenerService {}
