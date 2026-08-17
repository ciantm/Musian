package com.musian.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MusicService extends MediaBrowserServiceCompat {

    private static final String CHANNEL_ID = "musian_playback";
    private static final int    NOTIF_ID   = 42;
    private static final String ACT_PLAY   = "musian.PLAY";
    private static final String ACT_PAUSE  = "musian.PAUSE";
    private static final String ACT_NEXT   = "musian.NEXT";
    private static final String ACT_PREV   = "musian.PREV";

    static final String PREFS      = "musian_prefs";
    static final String PREF_SERVER  = "server";
    static final String PREF_USER_ID = "user_id";
    static final String PREF_TOKEN   = "token";

    private static final String AUTO_TL = "auto_tl";
    private static final String AUTO_TR = "auto_tr";
    private static final String AUTO_BL = "auto_bl";
    private static final String AUTO_BR = "auto_br";

    public interface OnTransitionListener { void onTransition(); }
    public interface OnPrevListener       { void onPrev(); }
    public interface OnPlayStateChanged   { void onPlayStateChanged(boolean playing); }

    private ExoPlayer          mPlayer;
    private MediaSessionCompat mSession;
    private String mTitle  = "";
    private String mArtist = "";

    private final List<String[]> mQueue = Collections.synchronizedList(new ArrayList<>()); // {id, title, artist}
    private volatile int mCurrentIndex = 0;

    private OnTransitionListener mTransitionListener;
    private OnPrevListener       mPrevListener;
    private OnPlayStateChanged   mPlayStateListener;

    // ── Background queue refill (Premium, Jellyfin only) ────────────────────────
    // Lets the queue keep topping itself up while the app is backgrounded/screen
    // locked, when WebView JS timers and evaluateJavascript calls are throttled.
    // JS still decides *what* to search for (mood/genre matching stays in app.html);
    // native just replays the same kind of Jellyfin fetch using the last spec it was given.
    private final List<String> mPlayedIds = Collections.synchronizedList(new ArrayList<>());
    private volatile List<String> mSpecTags = null;
    private volatile List<String> mSpecGenreConstraint = null; // null = no genre lock, mirrors jmGenreFilter
    private volatile List<String> mSpecFallbackGenres = null;
    private volatile boolean mRefilling = false; // mirrors jmFetching
    private volatile int mGeneration = 0;        // mirrors jmGeneration

    // ── Binder ────────────────────────────────────────────────────────────────

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }
    private final MusicBinder mBinder = new MusicBinder();

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return mBinder;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayer.setAudioAttributes(
            new androidx.media3.common.AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build(),
            true
        );
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                int idx = mPlayer.getCurrentMediaItemIndex();
                mCurrentIndex = idx;
                if (idx >= 0 && idx < mQueue.size()) {
                    String id = mQueue.get(idx)[0];
                    mTitle  = mQueue.get(idx)[1];
                    mArtist = mQueue.get(idx)[2];
                    setMetadata(mTitle, mArtist);
                    postNotification(mTitle, mArtist, true);
                    mPlayedIds.add(id);
                    if (mPlayedIds.size() > 500) mPlayedIds.remove(0);
                }
                maybeRefillQueue();
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
            @Override public void onPlay()               { mPlayer.play(); }
            @Override public void onPause()              { mPlayer.pause(); }
            @Override public void onSkipToNext()         { mPlayer.seekToNextMediaItem(); }
            @Override public void onSkipToPrevious()     { if (mPrevListener != null) mPrevListener.onPrev(); }
            @Override public void onStop()               { stopPlayback(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                fetchAndPlayForAuto(mediaId);
            }
        });
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build());
        mSession.setActive(true);
        setSessionToken(mSession.getSessionToken());

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

    // ── MediaBrowserServiceCompat ─────────────────────────────────────────────

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
        extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        // Debug builds: allow any caller so Auto can discover the app regardless of package name
        if (BuildConfig.DEBUG) {
            return new BrowserRoot("root", extras);
        }
        if (getPackageName().equals(clientPackageName)) {
            return new BrowserRoot("root", extras);
        }
        if (isAutoPackage(clientPackageName)) {
            if (!BillingManager.isPremiumStatic(this)) return null;
            return new BrowserRoot("root", extras);
        }
        return null;
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (!"root".equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        items.add(buildAutoItem(AUTO_TL, "Angry · Tense",  R.drawable.wheel_tl));
        items.add(buildAutoItem(AUTO_TR, "Happy · Excited", R.drawable.wheel_tr));
        items.add(buildAutoItem(AUTO_BL, "Sad · Lonely",   R.drawable.wheel_bl));
        items.add(buildAutoItem(AUTO_BR, "Calm · Serene",  R.drawable.wheel_br));
        result.sendResult(items);
    }

    private MediaBrowserCompat.MediaItem buildAutoItem(String id, String title, int iconRes) {
        Bitmap icon = BitmapFactory.decodeResource(getResources(), iconRes);
        Bundle extras = new Bundle();
        extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setIconBitmap(icon)
            .setExtras(extras)
            .build();
        return new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private boolean isAutoPackage(String pkg) {
        return "com.google.android.projection.gearhead".equals(pkg)
            || "com.google.android.carassistant".equals(pkg)
            || "com.google.android.autosimulator".equals(pkg);
    }

    // ── Auto playback ─────────────────────────────────────────────────────────

    private void fetchAndPlayForAuto(String quadrantId) {
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build());
        // Must go foreground before the fetch, not after: if the phone screen is off
        // (the normal driving case), a plain background Thread can get frozen by the
        // OS's cached-app freezer before the network call finishes, hanging the spinner.
        startForeground(NOTIF_ID, buildNotification("Loading…", "", true));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String server = prefs.getString(PREF_SERVER, null);
        String userId = prefs.getString(PREF_USER_ID, null);
        String token  = prefs.getString(PREF_TOKEN, null);
        if (server == null || token == null || userId == null) {
            setAutoError("Not logged in");
            return;
        }

        String[] tags = tagsForQuadrant(quadrantId);
        new Thread(() -> {
            List<String[]> tracks = new ArrayList<>();
            try {
                // Mirror app.html's fetchTracksByTags(): expand each mood word into
                // lowercase/Capitalized and Mood:-prefixed variants, OR'd in one request,
                // since libraries commonly tag moods as e.g. "Mood:Aggressive".
                List<String> expanded = new ArrayList<>();
                for (String tag : tags) {
                    String cap = Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
                    expanded.add(tag);
                    expanded.add(cap);
                    expanded.add("Mood:" + tag);
                    expanded.add("Mood:" + cap);
                }
                String urlStr = server + "/Users/" + userId + "/Items"
                    + "?IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=40"
                    + "&Fields=MediaSources"
                    + "&Tags=" + URLEncoder.encode(String.join("|", expanded), "UTF-8")
                    + "&api_key=" + token;
                String json = httpGet(urlStr, token);
                JSONArray items = new JSONObject(json).getJSONArray("Items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String id     = item.getString("Id");
                    String name   = item.optString("Name", "");
                    String artist = item.optString("AlbumArtist", "");
                    String stream = server + "/Audio/" + id + "/universal"
                        + "?UserId=" + userId
                        + "&AudioCodec=aac&AudioBitRate=192000"
                        + "&api_key=" + token;
                    tracks.add(new String[]{stream, id, name, artist});
                }
            } catch (Exception ignored) {}
            if (tracks.isEmpty()) {
                new Handler(Looper.getMainLooper()).post(() -> setAutoError("No tracks found"));
                return;
            }
            Collections.shuffle(tracks);
            List<String[]> finalTracks = tracks;
            new Handler(Looper.getMainLooper()).post(() -> {
                String[] first = finalTracks.get(0);
                playTrack(first[0], first[1], first[2], first[3]);
                for (int i = 1; i < finalTracks.size(); i++) {
                    String[] t = finalTracks.get(i);
                    queueNextTrack(t[0], t[1], t[2], t[3]);
                }
            });
        }).start();
    }

    private void setAutoError(String message) {
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED, message)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
            .build());
        stopForeground(true);
    }

    // Mirrors the mood-group tag arrays in app.html so Android Auto's quadrant
    // taps match as many library tag conventions as the main app's mood wheel does.
    private String[] tagsForQuadrant(String id) {
        switch (id) {
            case AUTO_TL: return new String[]{
                "aggressive", "aggression",
                "angry", "anger", "choleric", "fury", "outraged", "rage", "angry music",
                "anxious", "angst", "anxiety", "jumpy", "nervous", "angsty"};
            case AUTO_TR: return new String[]{
                "cheerful", "cheer up", "festive", "jolly", "jovial", "merry", "party",
                "cheer", "cheering", "cheery", "get happy", "rejoice", "songs that are cheerful", "sunny",
                "upbeat", "gleeful", "high spirits", "zest", "enthusiastic", "buoyancy", "elation",
                "excitement", "exciting", "exhilarating", "thrill", "ardor", "stimulating", "thrilling", "titillating"};
            case AUTO_BL: return new String[]{
                "grief", "heartbreak", "mournful", "sorrow", "sorry", "doleful", "heartache",
                "heartbreaking", "heartsick", "lachrymose", "mourning", "plaintive", "regret", "sorrowful",
                "depressed", "blue", "dark", "depressive", "dreary", "gloom", "darkness", "depress",
                "depression", "depressing", "gloomy",
                "sad", "sadness", "unhappy", "melancholic", "melancholy", "feeling sad", "sad song"};
            case AUTO_BR: return new String[]{
                "dreamy", "romantic", "romantic music",
                "calm", "comfort", "quiet", "serene", "mellow", "relaxed", "chill out", "calm down",
                "calming", "chillout", "comforting", "content", "cool down", "mellow music", "mellow rock",
                "peace of mind", "quietness", "relaxation", "serenity", "solace", "soothe", "soothing",
                "still", "tranquil", "tranquility"};
            default: return new String[]{"happy"};
        }
    }

    private String httpGet(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("X-Emby-Token", token);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ── Background queue refill ─────────────────────────────────────────────────
    // Runs the same tag/genre search JS would have run, but purely in native code,
    // so it keeps working while the WebView is throttled (backgrounded/screen locked).
    // Intentionally skips the title-word and random-fallback tiers app.html falls back
    // to — those are the least genre-constrained tiers and this is a background top-up,
    // not the primary queue-building experience.

    private void maybeRefillQueue() {
        if (mRefilling || mSpecTags == null) return;
        if (mPlayer.getMediaItemCount() - mCurrentIndex > 2) return; // mirrors jmPlaylist.length - idx <= 2

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String server = prefs.getString(PREF_SERVER, null);
        final String userId = prefs.getString(PREF_USER_ID, null);
        final String token  = prefs.getString(PREF_TOKEN, null);
        if (server == null || userId == null || token == null) return;

        mRefilling = true;
        final int gen = mGeneration;
        final List<String> tags = mSpecTags;
        final List<String> genreConstraint = mSpecGenreConstraint;
        final List<String> fallbackGenres = mSpecFallbackGenres;
        final List<String> playedSnapshot = new ArrayList<>(mPlayedIds);

        new Thread(() -> {
            List<String[]> fetched = new ArrayList<>();
            try {
                fetched = fetchByTags(server, userId, token, tags, genreConstraint);
                if (fetched.size() < 10) {
                    List<String> gf = (genreConstraint != null && !genreConstraint.isEmpty()) ? genreConstraint : fallbackGenres;
                    fetched = mergeDedupe(fetched, fetchByGenres(server, userId, token, gf));
                }
            } catch (Exception ignored) {}

            List<String[]> filtered = new ArrayList<>();
            for (String[] t : fetched) if (!playedSnapshot.contains(t[1])) filtered.add(t); // t = {stream, id, name, artist}
            if (filtered.isEmpty() && !fetched.isEmpty()) filtered = fetched; // exhausted-history reset, mirrors JS

            List<String[]> results = filtered;
            new Handler(Looper.getMainLooper()).post(() -> {
                mRefilling = false;
                if (gen != mGeneration) return;
                for (String[] t : results) queueNextTrack(t[0], t[1], t[2], t[3]);
            });
        }).start();
    }

    private List<String[]> fetchByTags(String server, String userId, String token,
                                        List<String> tags, List<String> genres) throws Exception {
        List<String> expanded = new ArrayList<>();
        for (String t : tags) {
            String cap = t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
            expanded.add(t); expanded.add(cap); expanded.add("Mood:" + t); expanded.add("Mood:" + cap);
        }
        StringBuilder url = new StringBuilder(server + "/Users/" + userId + "/Items"
            + "?IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=500&Fields=Genres,MediaSources"
            + "&Tags=" + URLEncoder.encode(joinPipe(expanded), "UTF-8"));
        if (genres != null && !genres.isEmpty()) {
            url.append("&Genres=").append(URLEncoder.encode(joinPipe(genres), "UTF-8"));
        }
        url.append("&api_key=").append(token);
        return parseItems(httpGet(url.toString(), token), server, userId, token);
    }

    private List<String[]> fetchByGenres(String server, String userId, String token, List<String> genres) throws Exception {
        StringBuilder url = new StringBuilder(server + "/Users/" + userId + "/Items"
            + "?IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=500&Fields=Genres,MediaSources");
        if (genres != null && !genres.isEmpty()) {
            url.append("&Genres=").append(URLEncoder.encode(joinPipe(genres), "UTF-8"));
        }
        url.append("&api_key=").append(token);
        return parseItems(httpGet(url.toString(), token), server, userId, token);
    }

    private List<String[]> parseItems(String json, String server, String userId, String token) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONArray items = new JSONObject(json).getJSONArray("Items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id     = item.getString("Id");
            String name   = item.optString("Name", "");
            String artist = item.optString("AlbumArtist", "");
            out.add(new String[]{buildStreamUrl(server, id, userId, token), id, name, artist});
        }
        return out;
    }

    private String buildStreamUrl(String server, String id, String userId, String token) {
        return server + "/Audio/" + id + "/universal"
            + "?UserId=" + userId
            + "&MaxStreamingBitrate=140000000"
            + "&Container=mp3,aac,m4a,flac,ogg,opus,webma,webm,wav"
            + "&AudioCodec=aac,mp3,flac,opus,vorbis"
            + "&TranscodingContainer=mp3"
            + "&TranscodingProtocol=http"
            + "&api_key=" + token;
    }

    private String joinPipe(List<String> parts) {
        return String.join("|", parts);
    }

    private List<String[]> mergeDedupe(List<String[]> a, List<String[]> b) {
        Set<String> seen = new HashSet<>();
        for (String[] t : a) seen.add(t[1]);
        List<String[]> out = new ArrayList<>(a);
        for (String[] t : b) if (!seen.contains(t[1])) out.add(t);
        return out;
    }

    private List<String> jsonArrayToList(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        return out;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int getCurrentIndex() { return mCurrentIndex; }

    public void setOnTransitionListener(OnTransitionListener l)    { mTransitionListener = l; }
    public void setOnPrevListener(OnPrevListener l)                 { mPrevListener = l; }
    public void setOnPlayStateChangedListener(OnPlayStateChanged l) { mPlayStateListener = l; }

    public void playTrack(String url, String id, String title, String artist) {
        mCurrentIndex = 0;
        mQueue.clear();
        mQueue.add(new String[]{id, title, artist});
        mTitle  = title;
        mArtist = artist;
        mPlayedIds.clear();
        mGeneration++;
        mPlayer.clearMediaItems();
        mPlayer.setMediaItem(MediaItem.fromUri(url));
        mPlayer.prepare();
        mPlayer.play();
        setMetadata(title, artist);
        startForeground(NOTIF_ID, buildNotification(title, artist, true));
    }

    public void queueNextTrack(String url, String id, String title, String artist) {
        mQueue.add(new String[]{id, title, artist});
        mPlayer.addMediaItem(MediaItem.fromUri(url));
    }

    // Drop everything queued after the currently playing item, so a fresh
    // mood/genre selection can replace what plays next without interrupting playback.
    public void clearQueueAhead() {
        int from = mPlayer.getCurrentMediaItemIndex() + 1;
        int to = mPlayer.getMediaItemCount();
        if (from < to) mPlayer.removeMediaItems(from, to);
        while (mQueue.size() > from) mQueue.remove(mQueue.size() - 1);
    }

    public void pauseTrack()  { mPlayer.pause(); }
    public void resumeTrack() { mPlayer.play(); }

    public void stopPlayback() {
        mGeneration++;
        clearRefetchSpec();
        mPlayer.stop();
        mSession.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    public void setRefetchSpec(String tagsJson, String genreConstraintJsonOrNull, String fallbackGenresJson) {
        try {
            mSpecTags = jsonArrayToList(tagsJson);
            mSpecGenreConstraint = genreConstraintJsonOrNull == null ? null : jsonArrayToList(genreConstraintJsonOrNull);
            mSpecFallbackGenres = jsonArrayToList(fallbackGenresJson);
        } catch (Exception ignored) {}
    }

    public void clearRefetchSpec() {
        mSpecTags = null;
        mSpecGenreConstraint = null;
        mSpecFallbackGenres = null;
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
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
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
