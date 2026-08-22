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
import java.util.Arrays;
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
    // Last mood/genre search recipe, persisted across service/app restarts so the
    // Android Auto "Resume" tile works even after a cold start (mirrors app.html's
    // jmLastMood/jmLastGenre, but stores the already-resolved tag/genre lists).
    private static final String PREF_RESUME_TAGS     = "resume_tags";
    private static final String PREF_RESUME_GENRE    = "resume_genre_constraint";
    private static final String PREF_RESUME_FALLBACK = "resume_fallback_genres";

    private static final String AUTO_TL     = "auto_tl";
    private static final String AUTO_TR     = "auto_tr";
    private static final String AUTO_BL     = "auto_bl";
    private static final String AUTO_BR     = "auto_br";
    private static final String AUTO_RESUME = "auto_resume";

    public interface OnTransitionListener { void onTransition(); }
    public interface OnPrevListener       { void onPrev(); }
    public interface OnPlayStateChanged   { void onPlayStateChanged(boolean playing); }

    private ExoPlayer          mPlayer;
    private MediaSessionCompat mSession;
    private String mTitle  = "";
    private String mArtist = "";

    private final List<String[]> mQueue = Collections.synchronizedList(new ArrayList<>()); // {id, title, artist}
    private volatile int mCurrentIndex = 0;

    private volatile Bitmap mCurrentArt = null;
    private volatile String mCurrentArtId = null;
    private volatile int mArtGeneration = 0;

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
                    setNowPlaying(id, mTitle, mArtist);
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
            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                // Without this, a stream that fails to load (bad URL, network blip,
                // unsupported container) leaves mSession stuck at STATE_BUFFERING
                // forever — Android Auto and the in-app spinner then never resolve.
                if (mPlayer.hasNextMediaItem()) {
                    mPlayer.seekToNextMediaItem();
                    mPlayer.prepare();
                    mPlayer.play();
                } else {
                    setAutoError("Playback error");
                }
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
                if (AUTO_RESUME.equals(mediaId)) fetchAndPlayForResume();
                else fetchAndPlayForAuto(mediaId);
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                // Defense in depth: onGetRoot already gates the connection, but that
                // check only runs once at connect time — re-check here too in case
                // premium status changed (refund/lapse) during a long-lived session.
                if (!BillingManager.isPremiumStatic(MusicService.this)) {
                    setAutoError("Musian Premium required for voice playback");
                    return;
                }
                fetchAndPlayForSearch(query == null ? "" : query);
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
        if (isVoiceOrAutoPackage(clientPackageName)) {
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
        if (getSharedPreferences(PREFS, MODE_PRIVATE).contains(PREF_RESUME_TAGS)) {
            items.add(buildAutoItem(AUTO_RESUME, "Resume", R.drawable.musian_logo));
        }
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

    // Android Auto/driving-mode callers, plus Assistant/Gemini (for voice playback via
    // onPlayFromSearch below). Exact Assistant caller package isn't documented by Google;
    // both candidates are allow-listed until confirmed on-device (see MUSIAN-DEV notes).
    private boolean isVoiceOrAutoPackage(String pkg) {
        return "com.google.android.projection.gearhead".equals(pkg)
            || "com.google.android.carassistant".equals(pkg)
            || "com.google.android.autosimulator".equals(pkg)
            || "com.google.android.googlequicksearchbox".equals(pkg)
            || "com.google.android.apps.bard".equals(pkg);
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

        List<String> tags = Arrays.asList(tagsForQuadrant(quadrantId));
        new Thread(() -> {
            List<String[]> tracks = new ArrayList<>();
            try {
                tracks = fetchTracksByTags(server, userId, token, tags, null, 40);
            } catch (Exception ignored) {}
            finishAutoFetch(tracks);
        }).start();
    }

    // Reads the last mood/genre search recipe (persisted by setRefetchSpec whenever
    // the in-app mood wheel or genre bar is used) and replays it, same as tapping
    // Resume in the app.
    private void fetchAndPlayForResume() {
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build());
        startForeground(NOTIF_ID, buildNotification("Loading…", "", true));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String server = prefs.getString(PREF_SERVER, null);
        String userId = prefs.getString(PREF_USER_ID, null);
        String token  = prefs.getString(PREF_TOKEN, null);
        if (server == null || token == null || userId == null) {
            setAutoError("Not logged in");
            return;
        }
        String tagsJson     = prefs.getString(PREF_RESUME_TAGS, null);
        String genreJson    = prefs.getString(PREF_RESUME_GENRE, null);
        String fallbackJson = prefs.getString(PREF_RESUME_FALLBACK, null);
        if (tagsJson == null) {
            setAutoError("No tracks found");
            return;
        }

        new Thread(() -> {
            List<String[]> tracks = new ArrayList<>();
            try {
                List<String> tags     = jsonArrayToList(tagsJson);
                List<String> genres   = genreJson == null ? null : jsonArrayToList(genreJson);
                List<String> fallback = fallbackJson == null ? new ArrayList<>() : jsonArrayToList(fallbackJson);
                tracks = fetchTracksByTags(server, userId, token, tags, genres, 40);
                if (tracks.isEmpty() && genres != null) {
                    tracks = fetchTracksByTags(server, userId, token, tags, null, 40);
                }
                if (tracks.isEmpty()) {
                    tracks = fetchTracksByGenres(server, userId, token, fallback, 40);
                }
            } catch (Exception ignored) {}
            finishAutoFetch(tracks);
        }).start();
    }

    // Google Assistant/Gemini voice playback ("play happy music on Musian"). Matches
    // the free-text query against the same mood/genre vocabulary as the in-app mood
    // wheel (see MOODS/GENRE_ZONES below), then reuses the same fetch/playback pipeline
    // as fetchAndPlayForAuto/fetchAndPlayForResume.
    private void fetchAndPlayForSearch(String query) {
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build());
        startForeground(NOTIF_ID, buildNotification("Loading…", "", true));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String server = prefs.getString(PREF_SERVER, null);
        String userId = prefs.getString(PREF_USER_ID, null);
        String token  = prefs.getString(PREF_TOKEN, null);
        if (server == null || token == null || userId == null) {
            setAutoError("Not logged in");
            return;
        }

        QueryMatch match = matchQueryToMood(query);
        if (match == null) {
            setAutoError("Couldn't match that to a mood or genre");
            return;
        }
        // Persist as the resume/refill spec too, same as every other mood-selection
        // path (wheel tap, genre bar, Auto quadrants) — a voice-triggered session then
        // behaves identically for background queue refill and the Auto Resume tile.
        // Only for mood matches: mSpecGenreConstraint is meant to narrow a tag search,
        // not stand alone, and fetchByTags/fetchTracksByTags don't special-case an
        // empty tag list the way app.html's JS fetchTracksByTags does — so a genre-only
        // voice match (e.g. "play jazz") is played but not persisted as a refill spec.
        if (match.tags != null) {
            setRefetchSpec(toJsonArray(match.tags), toJsonArray(match.genres), toJsonArray(new ArrayList<>()));
        }

        new Thread(() -> {
            List<String[]> tracks = new ArrayList<>();
            try {
                if (match.tags != null) {
                    tracks = fetchTracksByTags(server, userId, token, match.tags, match.genres, 40);
                    if (tracks.isEmpty() && match.genres != null) {
                        tracks = fetchTracksByTags(server, userId, token, match.tags, null, 40);
                    }
                } else {
                    tracks = fetchTracksByGenres(server, userId, token, match.genres, 40);
                }
            } catch (Exception ignored) {}
            finishAutoFetch(tracks);
        }).start();
    }

    private void finishAutoFetch(List<String[]> tracks) {
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
    }

    // Mirrors app.html's fetchTracksByTags(): expand each mood word into
    // lowercase/Capitalized and Mood:-prefixed variants, OR'd in one request,
    // since libraries commonly tag moods as e.g. "Mood:Aggressive". An optional
    // genre list is AND'd in via Jellyfin's separate Genres= filter.
    private List<String[]> fetchTracksByTags(String server, String userId, String token,
                                              List<String> tags, List<String> genres, int limit) throws Exception {
        List<String> expanded = new ArrayList<>();
        for (String tag : tags) {
            String cap = Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
            expanded.add(tag);
            expanded.add(cap);
            expanded.add("Mood:" + tag);
            expanded.add("Mood:" + cap);
        }
        String urlStr = server + "/Users/" + userId + "/Items"
            + "?IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=" + limit
            + "&Fields=MediaSources"
            + "&Tags=" + URLEncoder.encode(String.join("|", expanded), "UTF-8");
        if (genres != null && !genres.isEmpty()) {
            urlStr += "&Genres=" + URLEncoder.encode(String.join("|", genres), "UTF-8");
        }
        urlStr += "&api_key=" + token;
        return parseTrackItems(httpGet(urlStr, token), server, userId, token);
    }

    private List<String[]> fetchTracksByGenres(String server, String userId, String token,
                                                List<String> genres, int limit) throws Exception {
        if (genres == null || genres.isEmpty()) return new ArrayList<>();
        String urlStr = server + "/Users/" + userId + "/Items"
            + "?IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=" + limit
            + "&Fields=MediaSources"
            + "&Genres=" + URLEncoder.encode(String.join("|", genres), "UTF-8")
            + "&api_key=" + token;
        return parseTrackItems(httpGet(urlStr, token), server, userId, token);
    }

    private List<String[]> parseTrackItems(String json, String server, String userId, String token) throws Exception {
        List<String[]> tracks = new ArrayList<>();
        JSONArray items = new JSONObject(json).getJSONArray("Items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id     = item.getString("Id");
            String name   = item.optString("Name", "");
            String artist = item.optString("AlbumArtist", "");
            tracks.add(new String[]{buildStreamUrl(server, id, userId, token), id, name, artist});
        }
        return tracks;
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

    // ── Voice search vocabulary (Assistant/Gemini) ───────────────────────────────
    // Mirrors app.html's MOODS (Configuration/app.html:382-449) and GENRES
    // (Configuration/app.html:547-558) — keep in sync by hand when moods/genres change
    // there. Kept separate from tagsForQuadrant() above: that's a deliberate 4-tile
    // Auto UI mapping, not meant to be derived from this fuller vocabulary.

    private static final class Mood {
        final String name; final String[] tags; final String[] genres;
        Mood(String name, String[] tags, String[] genres) { this.name = name; this.tags = tags; this.genres = genres; }
    }

    private static final Mood[] MOODS = {
        new Mood("Aggressive", new String[]{"aggressive","aggression"}, new String[]{"Metal","Punk","Hard Rock","Hardcore"}),
        new Mood("Angry", new String[]{"angry","anger","choleric","fury","outraged","rage","angry music"}, new String[]{"Metal","Hard Rock","Punk"}),
        new Mood("Anxious", new String[]{"anxious","angst","anxiety","jumpy","nervous","angsty"}, new String[]{"Punk","Alternative Rock","Grunge","Indie Rock"}),
        new Mood("Pessimistic", new String[]{"pessimistic","pessimism","cynical","weltschmerz","cynical/sarcastic"}, new String[]{"Blues","Alternative Rock","Grunge"}),
        new Mood("Brooding", new String[]{"brooding","contemplative","meditative","reflective","broody","pensive","pondering","wistful"}, new String[]{"Alternative Rock","Grunge","Blues","Psychedelic Rock"}),
        new Mood("Grief", new String[]{"grief","heartbreak","mournful","sorrow","sorry","doleful","heartache","heartbreaking","heartsick","lachrymose","mourning","plaintive","regret","sorrowful"}, new String[]{"Blues","Soul","Folk"}),
        new Mood("Depressed", new String[]{"depressed","blue","dark","depressive","dreary","gloom","darkness","depress","depression","depressing","gloomy"}, new String[]{"Blues","Metal","Grunge","Alternative Rock"}),
        new Mood("Sad", new String[]{"sad","sadness","unhappy","melancholic","melancholy","feeling sad","sad song"}, new String[]{"Blues","Soul","Folk","Country"}),
        new Mood("Earnest", new String[]{"earnest","heartfelt"}, new String[]{"Folk","Soul","Country","Indie Rock"}),
        new Mood("Dreamy", new String[]{"dreamy"}, new String[]{"Psychedelic Rock","Space Rock","Jazz","Folk"}),
        new Mood("Romantic", new String[]{"romantic","romantic music"}, new String[]{"Jazz","Soul","Ballad","Folk"}),
        new Mood("Calm", new String[]{"calm","comfort","quiet","serene","mellow","relaxed","chill out","calm down","calming","chillout","comforting","content","cool down","mellow music","mellow rock","peace of mind","quietness","relaxation","serenity","solace","soothe","soothing","still","tranquil","tranquility"}, new String[]{"Jazz","Folk","Country","Soul","Reggae"}),
        new Mood("Hopeful", new String[]{"hopeful","desire","hope"}, new String[]{"Folk","Pop","Soul","Indie Rock","Country"}),
        new Mood("Confident", new String[]{"confident","encouraging","encouragement","optimism","optimistic"}, new String[]{"Rock","Hard Rock","Soul","Rap"}),
        new Mood("Happy", new String[]{"happy","happiness","happy songs","happy music","glad"}, new String[]{"Pop","Soul","Funk","Reggae","Ska"}),
        new Mood("Cheerful", new String[]{"cheerful","cheer up","festive","jolly","jovial","merry","party","cheer","cheering","cheery","get happy","rejoice","songs that are cheerful","sunny"}, new String[]{"Pop","Folk","Soul","Funk"}),
        new Mood("Upbeat", new String[]{"upbeat","gleeful","high spirits","zest","enthusiastic","buoyancy","elation"}, new String[]{"Pop","Ska","Funk","Reggae"}),
        new Mood("Excited", new String[]{"excitement","exciting","exhilarating","thrill","ardor","stimulating","thrilling","titillating"}, new String[]{"Pop","Punk","Alternative Rock","Rap"}),
        new Mood("Restless", new String[]{"restless","tense","unsettled","charged","edgy","alert","agitated","stirred"}, new String[]{"Rock","Alternative Rock","Electronic","Indie Rock"}),
        new Mood("Frustrated", new String[]{"frustrated","irritable","impatient","discontent","disgruntled","bitter","sullen"}, new String[]{"Rock","Grunge","Alternative Rock","Punk"}),
        new Mood("Tender", new String[]{"tender","warm","affectionate","sweet","gentle","loving","nurturing","intimate"}, new String[]{"Jazz","Soul","Folk","Pop","Ballad"}),
        new Mood("Nostalgic", new String[]{"nostalgic","bittersweet","longing","yearning","sentimental","reminiscent","poignant","aching"}, new String[]{"Folk","Country","Soul","Indie Rock","Singer-Songwriter"}),
    };

    private static final class GenreZone {
        final String name; final String[] genres;
        GenreZone(String name, String[] genres) { this.name = name; this.genres = genres; }
    }

    private static final GenreZone[] GENRE_ZONES = {
        new GenreZone("Classical", new String[]{"Classical","Opera","Orchestral","Baroque","Symphony"}),
        new GenreZone("Folk & Country", new String[]{"Folk","Country","Ballad"}),
        new GenreZone("Jazz & Blues", new String[]{"Jazz","Blues","Rhythm And Blues"}),
        new GenreZone("Latin & Reggae", new String[]{"Latin","Reggae","Ska","Dub","Dancehall","Salsa","Bossa Nova","Latin Rock"}),
        new GenreZone("Soul & Pop", new String[]{"Soul","Pop","Funk","R&B"}),
        new GenreZone("Rock", new String[]{"Rock","Grunge","Rockabilly"}),
        new GenreZone("Hard Rock & Punk", new String[]{"Hard Rock","Punk","Progressive Rock","Hardcore"}),
        new GenreZone("Metal", new String[]{"Metal"}),
        new GenreZone("Rap", new String[]{"Rap","Hip Hop"}),
        new GenreZone("Dance & Electronic", new String[]{"Dance","Electronic","House","Techno","Disco","Trance","Electronica","EDM","Electro","Eurodance","Dance Pop","Electronic Dance Music","Club"}),
    };

    private static final Set<String> SEARCH_STOPWORDS = new HashSet<>(Arrays.asList(
        "play", "some", "music", "songs", "song", "on", "musian", "the", "a", "please", "me"));

    private static final class QueryMatch {
        final List<String> tags;   // null => genre-only match
        final List<String> genres;
        QueryMatch(List<String> tags, List<String> genres) { this.tags = tags; this.genres = genres; }
    }

    // Word-boundary containment, adapted from app.html's genreWordMatch (Configuration/app.html:1310-1314).
    private boolean wordMatch(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return false;
        String h = haystack.toLowerCase();
        String n = java.util.regex.Pattern.quote(needle.toLowerCase());
        return java.util.regex.Pattern.compile("(?:^| )" + n + "(?= |$)").matcher(h).find();
    }

    // Scores a free-text voice query against MOODS first, then GENRE_ZONES as a
    // fallback (for queries that name a genre rather than a mood, e.g. "play jazz").
    // Returns null if nothing scores at all, so the caller can report an error
    // instead of guessing.
    private QueryMatch matchQueryToMood(String query) {
        List<String> words = new ArrayList<>();
        for (String w : query.toLowerCase().trim().split("\\s+")) {
            if (!w.isEmpty() && !SEARCH_STOPWORDS.contains(w)) words.add(w);
        }
        if (words.isEmpty()) return null;

        Mood best = null; int bestScore = 0;
        for (Mood mood : MOODS) {
            int score = 0;
            for (String tag : mood.tags) {
                for (String w : words) if (wordMatch(tag, w) || wordMatch(w, tag)) score++;
            }
            if (score > bestScore) { bestScore = score; best = mood; }
        }
        if (best != null) return new QueryMatch(Arrays.asList(best.tags), Arrays.asList(best.genres));

        for (GenreZone zone : GENRE_ZONES) {
            for (String g : zone.genres) {
                for (String w : words) {
                    if (wordMatch(g, w)) return new QueryMatch(null, Arrays.asList(zone.genres));
                }
            }
        }
        return null;
    }

    private String toJsonArray(List<String> items) {
        JSONArray arr = new JSONArray();
        for (String s : items) arr.put(s);
        return arr.toString();
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
        setNowPlaying(id, title, artist);
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
            if (!mSpecTags.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_RESUME_TAGS, tagsJson)
                    .putString(PREF_RESUME_GENRE, genreConstraintJsonOrNull)
                    .putString(PREF_RESUME_FALLBACK, fallbackGenresJson)
                    .apply();
            }
        } catch (Exception ignored) {}
    }

    public void clearRefetchSpec() {
        mSpecTags = null;
        mSpecGenreConstraint = null;
        mSpecFallbackGenres = null;
    }

    // Only called from the explicit Stop button — natural playlist end and other
    // internal stops should NOT forget the last mood, same as jmLastMood in app.html.
    public void clearResumeSpec() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(PREF_RESUME_TAGS)
            .remove(PREF_RESUME_GENRE)
            .remove(PREF_RESUME_FALLBACK)
            .apply();
    }

    // ── MediaSession ──────────────────────────────────────────────────────────

    private void setMetadata(String title, String artist) {
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        if (mCurrentArt != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, mCurrentArt);
        mSession.setMetadata(b.build());
    }

    // Sets title/artist immediately, then fetches cover art in the background and
    // re-pushes metadata + notification once it lands (Android Auto and the lock
    // screen both read album art off the session, not just the notification).
    private void setNowPlaying(String id, String title, String artist) {
        if (!id.equals(mCurrentArtId)) {
            mCurrentArtId = id;
            mCurrentArt = null;
            loadArt(id, title, artist);
        }
        setMetadata(title, artist);
    }

    private void loadArt(String id, String title, String artist) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String server = prefs.getString(PREF_SERVER, null);
        String token  = prefs.getString(PREF_TOKEN, null);
        if (server == null || token == null) return;
        final int gen = ++mArtGeneration;
        new Thread(() -> {
            try {
                String url = server + "/Items/" + id + "/Images/Primary?maxHeight=256&quality=90&api_key=" + token;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("X-Emby-Token", token);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                if (bmp == null || gen != mArtGeneration || !id.equals(mCurrentArtId)) return;
                mCurrentArt = bmp;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (gen != mArtGeneration) return;
                    setMetadata(title, artist);
                    postNotification(title, artist, mPlayer.isPlaying());
                });
            } catch (Exception ignored) {}
        }).start();
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
            .setLargeIcon(mCurrentArt)
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
