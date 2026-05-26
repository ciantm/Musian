package com.musian.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;
import com.musian.app.BillingManager;

public class MainActivity extends BridgeActivity {

    private MusicService   mService;
    private boolean        mBound = false;
    private BillingManager mBilling;

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
                    "if(typeof jmSetNativePlayState==='function')jmSetNativePlayState(" + playing + ");", null)));
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
        bridge.getWebView().addJavascriptInterface(new NativeBillingBridge(), "NativeBilling");
        Intent svc = new Intent(this, MusicService.class);
        bindService(svc, mConn, BIND_AUTO_CREATE);
        mBilling = new BillingManager(this);
        mBilling.setListener(isPremium ->
            runOnUiThread(() -> bridge.getWebView().evaluateJavascript(
                "if(typeof jmOnPremiumChanged==='function')jmOnPremiumChanged(" + isPremium + ");", null)));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mConn);
            mBound = false;
        }
        if (mBilling != null) mBilling.destroy();
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

    @Override
    public void onResume() {
        super.onResume();
    }

    // ── Billing JS bridge ──────────────────────────────────────────────────────

    class NativeBillingBridge {
        @JavascriptInterface
        public boolean isPremium() {
            return mBilling != null && mBilling.isPremium();
        }

        @JavascriptInterface
        public void purchase() {
            if (mBilling != null) mBilling.launchPurchaseFlow(MainActivity.this);
        }
    }
}
