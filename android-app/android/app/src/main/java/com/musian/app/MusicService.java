package com.musian.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicService extends Service {

    private static final String CHANNEL_ID = "musian_playback";
    private static final int    NOTIF_ID   = 42;
    private static final String ACT_PLAY   = "musian.PLAY";
    private static final String ACT_PAUSE  = "musian.PAUSE";
    private static final String ACT_NEXT   = "musian.NEXT";
    private static final String ACT_PREV   = "musian.PREV";

    public interface OnTransitionListener { void onTransition(); }
    public interface OnPrevListener       { void onPrev(); }
    public interface OnPlayStateChanged   { void onPlayStateChanged(boolean playing); }

    private ExoPlayer          mPlayer;
    private MediaSessionCompat mSession;
    private String mTitle  = "";
    private String mArtist = "";

    // Metadata list mirrors ExoPlayer's queue for notification updates on transition
    private final List<String[]> mQueue = Collections.synchronizedList(new ArrayList<>());

    private OnTransitionListener mTransitionListener;
    private OnPrevListener       mPrevListener;
    private OnPlayStateChanged   mPlayStateListener;

    // ── Binder ────────────────────────────────────────────────────────────────

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }
    private final MusicBinder mBinder = new MusicBinder();

    @Nullable @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                int idx = mPlayer.getCurrentMediaItemIndex();
                if (idx >= 0 && idx < mQueue.size()) {
                    mTitle  = mQueue.get(idx)[0];
                    mArtist = mQueue.get(idx)[1];
                    setMetadata(mTitle, mArtist);
                    postNotification(mTitle, mArtist, true);
                }
                // Only notify JS for real advances, not playlist setup
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                        && mTransitionListener != null) {
                    mTransitionListener.onTransition();
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                postNotification(mTitle, mArtist, isPlaying);
                updateSession(isPlaying);
                if (mPlayStateListener != null) mPlayStateListener.onPlayStateChanged(isPlaying);
            }
        });

        mSession = new MediaSessionCompat(this, "Musian");
        mSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()     { mPlayer.play(); }
            @Override public void onPause()    { mPlayer.pause(); }
            @Override public void onSkipToNext()     { mPlayer.seekToNextMediaItem(); }
            @Override public void onSkipToPrevious() { if (mPrevListener != null) mPrevListener.onPrev(); }
            @Override public void onStop()           { stopPlayback(); }
        });
        mSession.setActive(true);

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
        super.onDestroy();
        unregisterReceiver(mReceiver);
        mPlayer.release();
        mSession.release();
        stopForeground(true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int getCurrentIndex() { return mPlayer.getCurrentMediaItemIndex(); }

    public void setOnTransitionListener(OnTransitionListener l)     { mTransitionListener = l; }
    public void setOnPrevListener(OnPrevListener l)                  { mPrevListener = l; }
    public void setOnPlayStateChangedListener(OnPlayStateChanged l)  { mPlayStateListener = l; }

    public void playTrack(String url, String title, String artist) {
        mQueue.clear();
        mQueue.add(new String[]{title, artist});
        mTitle  = title;
        mArtist = artist;
        mPlayer.clearMediaItems();
        mPlayer.setMediaItem(MediaItem.fromUri(url));
        mPlayer.prepare();
        mPlayer.play();
        setMetadata(title, artist);
        startForeground(NOTIF_ID, buildNotification(title, artist, true));
    }

    public void queueNextTrack(String url, String title, String artist) {
        mQueue.add(new String[]{title, artist});
        mPlayer.addMediaItem(MediaItem.fromUri(url));
    }

    public void pauseTrack()  { mPlayer.pause(); }
    public void resumeTrack() { mPlayer.play(); }

    public void stopPlayback() {
        mPlayer.stop();
        mSession.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    // ── MediaSession ──────────────────────────────────────────────────────────

    private void setMetadata(String title, String artist) {
        mSession.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build());
    }

    private void updateSession(boolean playing) {
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(playing ? PlaybackStateCompat.STATE_PLAYING
                              : PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build());
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Now Playing", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm().createNotificationChannel(ch);
        }
    }

    private void postNotification(String title, String artist, boolean playing) {
        nm().notify(NOTIF_ID, buildNotification(title, artist, playing));
    }

    private Notification buildNotification(String title, String artist, boolean playing) {
        Intent launch = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launchPi = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(launchPi)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Prev",  pending(ACT_PREV))
            .addAction(playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", pending(playing ? ACT_PAUSE : ACT_PLAY))
            .addAction(android.R.drawable.ic_media_next, "Next", pending(ACT_NEXT))
            .setStyle(new MediaStyle()
                .setMediaSession(mSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .build();
    }

    private PendingIntent pending(String action) {
        Intent i = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    // ── Notification button receiver ──────────────────────────────────────────

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if      (ACT_PAUSE.equals(a)) { mPlayer.pause(); }
            else if (ACT_PLAY.equals(a))  { mPlayer.play(); }
            else if (ACT_NEXT.equals(a))  { mPlayer.seekToNextMediaItem(); }
            else if (ACT_PREV.equals(a))  { if (mPrevListener != null) mPrevListener.onPrev(); }
        }
    };
}
