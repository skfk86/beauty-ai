package com.rosiva.app;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.*;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

@CapacitorPlugin(name = "BillingPlugin")
public class BillingPlugin extends Plugin implements PurchasesUpdatedListener {

    private static final String TAG = "BillingPlugin";
    private BillingClient billingClient;
    private PluginCall pendingPurchaseCall;
    private boolean isConnected = false;

    @Override
    public void load() {
        billingClient = BillingClient.newBuilder(getContext())
            .setListener(this)
            .enablePendingPurchases()
            .build();
        connectBilling();
    }

    private void connectBilling() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    isConnected = true;
                    Log.d(TAG, "Billing connected");
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                isConnected = false;
                Log.d(TAG, "Billing disconnected — will retry");
                connectBilling();
            }
        });
    }

    // ── getProducts ──────────────────────────────────────────────
    @PluginMethod
    public void getProducts(PluginCall call) {
        JSArray ids = call.getArray("productIdentifiers");
        if (ids == null) { call.reject("Missing productIdentifiers"); return; }

        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        try {
            for (int i = 0; i < ids.length(); i++) {
                products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(ids.getString(i))
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build());
            }
        } catch (Exception e) { call.reject("Bad product list: " + e.getMessage()); return; }

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build();

        billingClient.queryProductDetailsAsync(params, (result, productDetailsList) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                call.reject("queryProductDetails failed: " + result.getDebugMessage());
                return;
            }
            try {
                JSArray arr = new JSArray();
                for (ProductDetails pd : productDetailsList) {
                    JSObject obj = new JSObject();
                    obj.put("productIdentifier", pd.getProductId());
                    obj.put("localizedTitle", pd.getTitle());
                    // جلب السعر من أول Base Plan
                    List<ProductDetails.SubscriptionOfferDetails> offers = pd.getSubscriptionOfferDetails();
                    if (offers != null && !offers.isEmpty()) {
                        List<ProductDetails.PricingPhase> phases =
                            offers.get(0).getPricingPhases().getPricingPhaseList();
                        if (!phases.isEmpty()) {
                            obj.put("localizedPrice", phases.get(0).getFormattedPrice());
                            obj.put("priceAmountMicros", phases.get(0).getPriceAmountMicros());
                            obj.put("priceCurrencyCode", phases.get(0).getPriceCurrencyCode());
                        }
                    }
                    arr.put(obj);
                }
                JSObject res = new JSObject();
                res.put("products", arr);
                call.resolve(res);
            } catch (Exception e) {
                call.reject("Parse error: " + e.getMessage());
            }
        });
    }

    // ── purchaseProduct ──────────────────────────────────────────
    @PluginMethod
    public void purchaseProduct(PluginCall call) {
        call.setKeepAlive(true);
        String productId = call.getString("productIdentifier");
        if (productId == null) { call.reject("Missing productIdentifier"); return; }

        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS)
            .build());

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
            (result, productDetailsList) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK
                        || productDetailsList.isEmpty()) {
                    call.reject("Product not found: " + result.getDebugMessage());
                    return;
                }
                ProductDetails pd = productDetailsList.get(0);
                List<ProductDetails.SubscriptionOfferDetails> offers = pd.getSubscriptionOfferDetails();
                if (offers == null || offers.isEmpty()) {
                    call.reject("No offers available");
                    return;
                }
                String offerToken = offers.get(0).getOfferToken();
                List<BillingFlowParams.ProductDetailsParams> detailsParams =
                    Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .setOfferToken(offerToken)
                            .build());

                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(detailsParams)
                    .build();

                pendingPurchaseCall = call;
                Activity activity = getActivity();
                BillingResult launchResult = billingClient.launchBillingFlow(activity, flowParams);
                if (launchResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    pendingPurchaseCall = null;
                    call.reject("launchBillingFlow failed: " + launchResult.getDebugMessage());
                }
            });
    }

    // ── restoreTransactions ──────────────────────────────────────
    @PluginMethod
    public void restoreTransactions(PluginCall call) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            (result, purchases) -> {
                try {
                    JSArray arr = new JSArray();
                    for (Purchase p : purchases) {
                        if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            JSObject obj = new JSObject();
                            obj.put("productIdentifier", p.getProducts().get(0));
                            obj.put("purchaseToken", p.getPurchaseToken());
                            obj.put("orderId", p.getOrderId());
                            arr.put(obj);
                        }
                    }
                    JSObject res = new JSObject();
                    res.put("purchases", arr);
                    call.resolve(res);
                } catch (Exception e) {
                    call.reject("restoreTransactions error: " + e.getMessage());
                }
            });
    }

    // ── onPurchasesUpdated (callback من Google Play) ─────────────
    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                   List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null && !purchases.isEmpty()) {
            Purchase purchase = purchases.get(0);
            // تأكيد الشراء مع Google
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
                billingClient.acknowledgePurchase(ackParams, ackResult -> {
                    Log.d(TAG, "Purchase acknowledged: " + ackResult.getResponseCode());
                });
            }
            // إرجاع النتيجة لـ JavaScript
            try {
                JSObject obj = new JSObject();
                obj.put("productIdentifier", purchase.getProducts().get(0));
                obj.put("purchaseToken", purchase.getPurchaseToken());
                obj.put("orderId", purchase.getOrderId());
                JSArray arr = new JSArray();
                arr.put(obj);
                JSObject res = new JSObject();
                res.put("purchases", arr);
                if (pendingPurchaseCall != null) {
                    pendingPurchaseCall.resolve(res);
                    pendingPurchaseCall = null;
                }
                notifyListeners("purchasesUpdate", res);
            } catch (Exception e) {
                Log.e(TAG, "onPurchasesUpdated parse error", e);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (pendingPurchaseCall != null) {
                pendingPurchaseCall.reject("USER_CANCELLED");
                pendingPurchaseCall = null;
            }
        } else {
            if (pendingPurchaseCall != null) {
                pendingPurchaseCall.reject("Purchase failed: " + result.getDebugMessage());
                pendingPurchaseCall = null;
            }
        }
    }
}
