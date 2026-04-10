package com.musian.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private MusicService mService;
    private boolean      mBound = false;

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((MusicService.MusicBinder) binder).getService();
            mBound   = true;

            mService.setOnTransitionListener(() -> { /* JS polls via getNativeIndex() */ });

            mService.setOnPrevListener(() ->
                runOnUiThread(() -> bridge.getWebView().evaluateJavascript(
                    "jmPlayIdx=Math.max(0,jmPlayIdx-1);jmPlayTrack(jmPlayIdx,jmGeneration);", null)));

            mService.setOnPlayStateChangedListener(playing ->
                runOnUiThread(() -> bridge.getWebView().evaluateJavascript(
                    "document.getElementById('jmPauseBtn').innerHTML='"
                        + (playing ? "&#9646;&#9646;" : "&#9654;") + "';"
                        + "jmUpdatePersistentBar(null);", null)));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge.getWebView().addJavascriptInterface(new NativeMediaBridge(), "NativeMedia");
        Intent svc = new Intent(this, MusicService.class);
        bindService(svc, mConn, BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mConn);
            mBound = false;
        }
    }

    // ── JS bridge ──────────────────────────────────────────────────────────────

    class NativeMediaBridge {
        @JavascriptInterface
        public void playTrack(final String url, final String title, final String artist) {
            runOnUiThread(new Runnable() { public void run() {
                if (!mBound) {
                    Intent svc = new Intent(MainActivity.this, MusicService.class);
                    startService(svc);
                    bindService(svc, mConn, BIND_AUTO_CREATE);
                    return;
                }
                mService.playTrack(url, title, artist);
            }});
        }

        @JavascriptInterface
        public void pauseTrack() {
            runOnUiThread(new Runnable() { public void run() {
                if (mBound) mService.pauseTrack();
            }});
        }

        @JavascriptInterface
        public void resumeTrack() {
            runOnUiThread(new Runnable() { public void run() {
                if (mBound) mService.resumeTrack();
            }});
        }

        @JavascriptInterface
        public void queueNextTrack(final String url, final String title, final String artist) {
            runOnUiThread(new Runnable() { public void run() {
                if (mBound) mService.queueNextTrack(url, title, artist);
            }});
        }

        @JavascriptInterface
        public void clearSession() {
            runOnUiThread(new Runnable() { public void run() {
                if (mBound) mService.stopPlayback();
            }});
        }

        @JavascriptInterface
        public int getNativeIndex() {
            return mBound ? mService.getCurrentIndex() : -1;
        }
    }

    // ── Keep WebView alive in background ───────────────────────────────────────

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

    @Override
    public void onResume() {
        super.onResume();
        // JS polls native index via getNativeIndex() — no evaluateJavascript needed
    }
}
