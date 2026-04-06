package com.musian.app;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onPause() {
        super.onPause();
        // BridgeActivity.onPause() calls webView.onPause() which suspends JS and audio.
        // Resume immediately so audio keeps playing when the screen locks or app backgrounds.
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // bridge.onStop() calls webView.onPause() again — resume to keep audio alive.
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
        }
    }
}
