package com.rosiva.app;

import android.util.Log;
import androidx.annotation.NonNull;

import com.android.billingclient.api.*;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;

import java.util.*;

@CapacitorPlugin(name = "BillingPlugin")
public class BillingPlugin extends Plugin implements PurchasesUpdatedListener {

    private static final String TAG = "RosivaBilling";
    private BillingClient billingClient;
    private PluginCall pendingCall;

    @Override
    public void load() {
        try {
            billingClient = BillingClient.newBuilder(getContext())
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .build();
            connect();
        } catch (Exception e) {
            Log.e(TAG, "load() error: " + e.getMessage());
        }
    }

    private void connect() {
        if (billingClient == null || billingClient.isReady()) return;
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult r) {
                Log.d(TAG, "connected: " + r.getResponseCode());
            }
            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "disconnected, retrying...");
                getActivity().runOnUiThread(() ->
                    new android.os.Handler().postDelayed(() -> connect(), 3000));
            }
        });
    }

    // ── isReady ──────────────────────────────────────────────────
    @PluginMethod
    public void isReady(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ready", billingClient != null && billingClient.isReady());
        call.resolve(r);
    }

    // ── getProducts ──────────────────────────────────────────────
    @PluginMethod
    public void getProducts(PluginCall call) {
        if (billingClient == null || !billingClient.isReady()) {
            call.reject("Billing not ready");
            return;
        }
        JSArray ids = call.getArray("productIdentifiers");
        if (ids == null) { call.reject("Missing ids"); return; }

        List<QueryProductDetailsParams.Product> list = new ArrayList<>();
        try {
            for (int i = 0; i < ids.length(); i++) {
                list.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(ids.getString(i))
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build());
            }
        } catch (Exception e) { call.reject(e.getMessage()); return; }

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(list).build(),
            (result, details) -> {
                try {
                    JSArray arr = new JSArray();
                    for (ProductDetails pd : details) {
                        JSObject o = new JSObject();
                        o.put("productIdentifier", pd.getProductId());
                        List<ProductDetails.SubscriptionOfferDetails> offers =
                            pd.getSubscriptionOfferDetails();
                        if (offers != null && !offers.isEmpty()) {
                            List<ProductDetails.PricingPhase> phases =
                                offers.get(0).getPricingPhases().getPricingPhaseList();
                            if (!phases.isEmpty())
                                o.put("localizedPrice", phases.get(0).getFormattedPrice());
                        }
                        arr.put(o);
                    }
                    JSObject res = new JSObject();
                    res.put("products", arr);
                    call.resolve(res);
                } catch (Exception e) { call.reject(e.getMessage()); }
            });
    }

    // ── purchaseProduct ──────────────────────────────────────────
    @PluginMethod
    public void purchaseProduct(PluginCall call) {
        if (billingClient == null || !billingClient.isReady()) {
            call.reject("Billing not ready");
            return;
        }
        call.setKeepAlive(true);
        String productId = call.getString("productIdentifier");
        if (productId == null) { call.reject("Missing productIdentifier"); return; }

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()))
                .build(),
            (result, details) -> {
                if (details == null || details.isEmpty()) {
                    call.reject("Product not found: " + result.getDebugMessage());
                    return;
                }
                ProductDetails pd = details.get(0);
                List<ProductDetails.SubscriptionOfferDetails> offers =
                    pd.getSubscriptionOfferDetails();
                if (offers == null || offers.isEmpty()) {
                    call.reject("No subscription offers found");
                    return;
                }
                BillingFlowParams params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .setOfferToken(offers.get(0).getOfferToken())
                            .build()))
                    .build();

                pendingCall = call;
                getActivity().runOnUiThread(() -> {
                    try {
                        BillingResult lr = billingClient.launchBillingFlow(getActivity(), params);
                        if (lr.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            pendingCall = null;
                            call.reject("Launch failed: " + lr.getDebugMessage());
                        }
                    } catch (Exception e) {
                        pendingCall = null;
                        call.reject("Launch exception: " + e.getMessage());
                    }
                });
            });
    }

    // ── restoreTransactions ──────────────────────────────────────
    @PluginMethod
    public void restoreTransactions(PluginCall call) {
        if (billingClient == null || !billingClient.isReady()) {
            call.reject("Billing not ready"); return;
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            (result, purchases) -> {
                try {
                    JSArray arr = new JSArray();
                    for (Purchase p : purchases) {
                        if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            JSObject o = new JSObject();
                            o.put("productIdentifier", p.getProducts().get(0));
                            o.put("purchaseToken", p.getPurchaseToken());
                            arr.put(o);
                        }
                    }
                    JSObject res = new JSObject();
                    res.put("purchases", arr);
                    call.resolve(res);
                } catch (Exception e) { call.reject(e.getMessage()); }
            });
    }

    // ── onPurchasesUpdated ───────────────────────────────────────
    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                    List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null && !purchases.isEmpty()) {
            Purchase p = purchases.get(0);
            if (!p.isAcknowledged()) {
                billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(p.getPurchaseToken()).build(),
                    r -> Log.d(TAG, "ack: " + r.getResponseCode()));
            }
            try {
                JSObject obj = new JSObject();
                obj.put("productIdentifier", p.getProducts().get(0));
                obj.put("purchaseToken", p.getPurchaseToken());
                JSArray arr = new JSArray();
                arr.put(obj);
                JSObject res = new JSObject();
                res.put("purchases", arr);
                if (pendingCall != null) {
                    pendingCall.resolve(res);
                    pendingCall = null;
                }
                notifyListeners("purchasesUpdate", res);
            } catch (Exception e) {
                Log.e(TAG, "onPurchasesUpdated: " + e.getMessage());
            }
        } else if (result.getResponseCode() ==
                   BillingClient.BillingResponseCode.USER_CANCELED) {
            if (pendingCall != null) {
                pendingCall.reject("USER_CANCELLED");
                pendingCall = null;
            }
        } else {
            if (pendingCall != null) {
                pendingCall.reject("Error " + result.getResponseCode()
                    + ": " + result.getDebugMessage());
                pendingCall = null;
            }
        }
    }
}
