package com.musian.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import com.android.billingclient.api.*;
import java.util.List;

public class BillingManager implements PurchasesUpdatedListener, BillingClientStateListener {

    public static final String PRODUCT_PREMIUM = "musian_premium";

    public interface PremiumStatusListener {
        void onPremiumStatusChanged(boolean isPremium);
    }

    private final BillingClient      mBilling;
    private final SharedPreferences  mPrefs;
    private       PremiumStatusListener mListener;
    private       boolean            mConnected = false;

    public BillingManager(Context ctx) {
        mPrefs   = ctx.getSharedPreferences("musian_billing", Context.MODE_PRIVATE);
        mBilling = BillingClient.newBuilder(ctx)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build();
        mBilling.startConnection(this);
    }

    public void setListener(PremiumStatusListener l) { mListener = l; }

    // Cached check — works offline after first successful purchase validation.
    // Debug builds always return true so all features are testable without Play Store.
    public boolean isPremium() {
        if (BuildConfig.DEBUG) return true;
        return mPrefs.getBoolean("is_premium", false);
    }

    // ── BillingClientStateListener ────────────────────────────────────────────

    @Override
    public void onBillingSetupFinished(BillingResult result) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            mConnected = true;
            queryExistingPurchases();
        }
    }

    @Override
    public void onBillingServiceDisconnected() {
        mConnected = false;
    }

    // ── PurchasesUpdatedListener ──────────────────────────────────────────────

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) handlePurchase(p);
        }
    }

    // ── Launch purchase flow ──────────────────────────────────────────────────

    public void launchPurchaseFlow(Activity activity) {
        if (!mConnected) return;
        List<QueryProductDetailsParams.Product> products = List.of(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PREMIUM)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        );
        mBilling.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
            (res, detailsList) -> {
                if (res.getResponseCode() != BillingClient.BillingResponseCode.OK
                        || detailsList == null || detailsList.isEmpty()) return;
                BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(List.of(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(detailsList.get(0))
                            .build()
                    ))
                    .build();
                activity.runOnUiThread(() -> mBilling.launchBillingFlow(activity, flow));
            }
        );
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void queryExistingPurchases() {
        mBilling.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            (result, purchases) -> {
                boolean premium = false;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                        && purchases != null) {
                    for (Purchase p : purchases) {
                        if (p.getProducts().contains(PRODUCT_PREMIUM)
                                && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            premium = true;
                            acknowledgePurchaseIfNeeded(p);
                        }
                    }
                }
                setPremium(premium);
            }
        );
    }

    private void handlePurchase(Purchase purchase) {
        if (!purchase.getProducts().contains(PRODUCT_PREMIUM)) return;
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            setPremium(true);
            acknowledgePurchaseIfNeeded(purchase);
        }
    }

    private void acknowledgePurchaseIfNeeded(Purchase purchase) {
        if (purchase.isAcknowledged()) return;
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build();
        mBilling.acknowledgePurchase(params, r -> {});
    }

    private void setPremium(boolean value) {
        mPrefs.edit().putBoolean("is_premium", value).apply();
        if (mListener != null) mListener.onPremiumStatusChanged(value);
    }

    public void destroy() {
        if (mBilling != null) mBilling.endConnection();
    }
}
