package com.musian.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.webkit.JavascriptInterface;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final String CHANNEL_ID  = "musian_playback";
    private static final int    NOTIF_ID    = 42;
    private static final String ACT_PLAY    = "musian.PLAY";
    private static final String ACT_PAUSE   = "musian.PAUSE";
    private static final String ACT_NEXT    = "musian.NEXT";
    private static final String ACT_PREV    = "musian.PREV";

    private MediaSessionCompat   mSession;
    private PowerManager.WakeLock mWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createChannel();
        mSession = new MediaSessionCompat(this, "Musian");
        mSession.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Musian::audio");
        bridge.getWebView().addJavascriptInterface(new NativeMediaBridge(), "NativeMedia");
        IntentFilter f = new IntentFilter();
        f.addAction(ACT_PLAY); f.addAction(ACT_PAUSE);
        f.addAction(ACT_NEXT); f.addAction(ACT_PREV);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, f);
        }
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        super.onDestroy();
        unregisterReceiver(mReceiver);
        mSession.release();
        cancelNotification();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Now Playing", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm().createNotificationChannel(ch);
        }
    }

    // ── JS bridge ──────────────────────────────────────────────────────────────

    class NativeMediaBridge {
        @JavascriptInterface
        public void updateSession(final String title, final String artist, final boolean playing) {
            runOnUiThread(new Runnable() { public void run() {
                mSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .build());
                mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(playing
                        ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .build());
                if (playing) {
                    if (!mWakeLock.isHeld()) mWakeLock.acquire();
                } else {
                    if (mWakeLock.isHeld()) mWakeLock.release();
                }
                postNotification(title, artist, playing);
            }});
        }

        @JavascriptInterface
        public void clearSession() {
            runOnUiThread(new Runnable() { public void run() {
                if (mWakeLock.isHeld()) mWakeLock.release();
                cancelNotification();
            }});
        }
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private void postNotification(String title, String artist, boolean playing) {
        Intent launch = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launchPi = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(launchPi)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Prev",  pending(ACT_PREV))
            .addAction(playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", pending(playing ? ACT_PAUSE : ACT_PLAY))
            .addAction(android.R.drawable.ic_media_next,     "Next",  pending(ACT_NEXT))
            .setStyle(new MediaStyle()
                .setMediaSession(mSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .build();
        nm().notify(NOTIF_ID, n);
    }

    private void cancelNotification() {
        nm().cancel(NOTIF_ID);
    }

    private PendingIntent pending(String action) {
        Intent i = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    // ── Notification button receiver ───────────────────────────────────────────

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            final String js;
            if      (ACT_PAUSE.equals(a)) js = "jmAudio.pause();";
            else if (ACT_PLAY.equals(a))  js = "jmAudio.play();";
            else if (ACT_NEXT.equals(a))  js = "jmPlayIdx++; jmPlayTrack(jmPlayIdx, jmGeneration);";
            else if (ACT_PREV.equals(a))  js = "jmPlayIdx=Math.max(0,jmPlayIdx-1); jmPlayTrack(jmPlayIdx,jmGeneration);";
            else return;
            runOnUiThread(new Runnable() { public void run() {
                bridge.getWebView().evaluateJavascript(js, null);
            }});
        }
    };

    // ── Keep WebView alive in background ───────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().evaluateJavascript(
                "if(window._pendingPlay){window._pendingPlay=false;jmAudio&&jmAudio.play();}", null);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bridge != null && bridge.getWebView() != null) bridge.getWebView().onResume();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (bridge != null && bridge.getWebView() != null) bridge.getWebView().onResume();
    }
}
