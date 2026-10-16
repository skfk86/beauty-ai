package com.rosiva.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;

import com.android.billingclient.api.*;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;

import java.util.*;

/**
 * Rosiva — Google Play Billing (اشتراكات VIP)
 *
 * المبادئ:
 *  - لا يُعتبر الشراء ناجحاً إلا إذا كانت حالته PURCHASED (وليس PENDING).
 *  - كل شراء PURCHASED غير مُؤكَّد يُؤكَّد (acknowledge) — سواء جاء من الشراء
 *    أو من الاستعادة أو من المزامنة عند فتح التطبيق، وإلا تُعيد Google المبلغ بعد 3 أيام.
 *  - getActiveSubscriptions تُرجع الاشتراكات الفعّالة فقط حسب Google Play
 *    (Play لا يُرجع الاشتراك بعد انتهائه أو إلغائه واسترداده) — هذا مصدر الحقيقة.
 */
@CapacitorPlugin(name = "BillingPlugin")
public class BillingPlugin extends Plugin implements PurchasesUpdatedListener {

    private static final String TAG = "RosivaBilling";
    private BillingClient billingClient;
    private PluginCall pendingCall;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int retryDelayMs = 2000;

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
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    retryDelayMs = 2000;
                    JSObject ev = new JSObject();
                    ev.put("ready", true);
                    notifyListeners("billingReady", ev);
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                // إعادة محاولة بتأخير متزايد (حد أقصى 60 ثانية)
                int delay = retryDelayMs;
                retryDelayMs = Math.min(retryDelayMs * 2, 60000);
                mainHandler.postDelayed(() -> connect(), delay);
            }
        });
    }

    private boolean ensureReady(PluginCall call) {
        if (billingClient == null || !billingClient.isReady()) {
            connect();
            call.reject("Billing not ready", "NOT_READY");
            return false;
        }
        return true;
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
        if (!ensureReady(call)) return;
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
                    if (details != null) {
                        for (ProductDetails pd : details) {
                            JSObject o = new JSObject();
                            o.put("productIdentifier", pd.getProductId());
                            ProductDetails.SubscriptionOfferDetails base = pickBaseOffer(pd);
                            if (base != null) {
                                List<ProductDetails.PricingPhase> phases =
                                    base.getPricingPhases().getPricingPhaseList();
                                // آخر مرحلة = السعر المتكرر الفعلي (بعد أي تجربة مجانية/خصم)
                                ProductDetails.PricingPhase p = phases.get(phases.size() - 1);
                                o.put("localizedPrice", p.getFormattedPrice());
                                o.put("priceMicros", p.getPriceAmountMicros());
                                o.put("currency", p.getPriceCurrencyCode());
                                o.put("billingPeriod", p.getBillingPeriod());
                            }
                            arr.put(o);
                        }
                    }
                    JSObject res = new JSObject();
                    res.put("products", arr);
                    call.resolve(res);
                } catch (Exception e) { call.reject(e.getMessage()); }
            });
    }

    /** يختار العرض الأساسي (Base plan) بدلاً من أول عرض عشوائي */
    private ProductDetails.SubscriptionOfferDetails pickBaseOffer(ProductDetails pd) {
        List<ProductDetails.SubscriptionOfferDetails> offers = pd.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) return null;
        for (ProductDetails.SubscriptionOfferDetails o : offers) {
            if (o.getOfferId() == null) return o; // العرض بدون offerId = الخطة الأساسية
        }
        return offers.get(0);
    }

    // ── purchaseProduct ──────────────────────────────────────────
    @PluginMethod
    public void purchaseProduct(PluginCall call) {
        if (!ensureReady(call)) return;
        String productId = call.getString("productIdentifier");
        if (productId == null) { call.reject("Missing productIdentifier"); return; }
        if (pendingCall != null) { call.reject("Purchase already in progress", "IN_PROGRESS"); return; }

        String accountId = call.getString("obfuscatedAccountId"); // uid المستخدم (مُشفّر/مُجزّأ)

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
                    call.reject("Product not found: " + result.getDebugMessage(), "NOT_FOUND");
                    return;
                }
                ProductDetails pd = details.get(0);
                ProductDetails.SubscriptionOfferDetails offer = pickBaseOffer(pd);
                if (offer == null) {
                    call.reject("No subscription offers found", "NOT_FOUND");
                    return;
                }
                BillingFlowParams.Builder b = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .setOfferToken(offer.getOfferToken())
                            .build()));
                if (accountId != null && !accountId.isEmpty()) b.setObfuscatedAccountId(accountId);
                BillingFlowParams params = b.build();

                call.setKeepAlive(true);
                pendingCall = call;
                getActivity().runOnUiThread(() -> {
                    try {
                        BillingResult lr = billingClient.launchBillingFlow(getActivity(), params);
                        int code = lr.getResponseCode();
                        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                            finishPending(null, "ALREADY_OWNED", "Already subscribed");
                        } else if (code != BillingClient.BillingResponseCode.OK) {
                            finishPending(null, "LAUNCH_FAILED", "Launch failed: " + lr.getDebugMessage());
                        }
                    } catch (Exception e) {
                        finishPending(null, "LAUNCH_FAILED", "Launch exception: " + e.getMessage());
                    }
                });
            });
    }

    // ── getActiveSubscriptions (مزامنة صامتة عند فتح التطبيق) ──────
    @PluginMethod
    public void getActiveSubscriptions(PluginCall call) {
        queryOwned(call);
    }

    // ── restoreTransactions (زر "استعادة المشتريات") ──────────────
    @PluginMethod
    public void restoreTransactions(PluginCall call) {
        queryOwned(call);
    }

    private void queryOwned(PluginCall call) {
        if (!ensureReady(call)) return;
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            (result, purchases) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    call.reject("Query failed: " + result.getDebugMessage(), "QUERY_FAILED");
                    return;
                }
                try {
                    JSArray arr = new JSArray();
                    boolean hasPending = false;
                    if (purchases != null) {
                        for (Purchase p : purchases) {
                            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                acknowledgeIfNeeded(p);
                                arr.put(toJs(p));
                            } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                                hasPending = true;
                            }
                        }
                    }
                    JSObject res = new JSObject();
                    res.put("purchases", arr);
                    res.put("hasPending", hasPending);
                    call.resolve(res);
                } catch (Exception e) { call.reject(e.getMessage()); }
            });
    }

    // ── onPurchasesUpdated ───────────────────────────────────────
    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            JSArray done = new JSArray();
            boolean pending = false;
            for (Purchase p : purchases) {
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    acknowledgeIfNeeded(p);
                    done.put(toJs(p));
                } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    pending = true;
                }
            }
            JSObject res = new JSObject();
            res.put("purchases", done);
            res.put("hasPending", pending);
            notifyListeners("purchasesUpdate", res);

            if (done.length() > 0) finishPending(res, null, null);
            else if (pending) finishPending(null, "PENDING", "Payment pending");
            else finishPending(null, "UNKNOWN", "No completed purchase");
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            finishPending(null, "USER_CANCELLED", "USER_CANCELLED");
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            finishPending(null, "ALREADY_OWNED", "Already subscribed");
        } else {
            finishPending(null, "ERROR_" + code, "Error " + code + ": " + result.getDebugMessage());
        }
    }

    private void finishPending(JSObject res, String errCode, String errMsg) {
        PluginCall c = pendingCall;
        pendingCall = null;
        if (c == null) return;
        if (res != null) c.resolve(res); else c.reject(errMsg, errCode);
        c.setKeepAlive(false);
        bridge.releaseCall(c);
    }

    private void acknowledgeIfNeeded(Purchase p) {
        if (p.isAcknowledged()) return;
        billingClient.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
            r -> Log.d(TAG, "ack " + p.getOrderId() + ": " + r.getResponseCode()));
    }

    private JSObject toJs(Purchase p) {
        JSObject o = new JSObject();
        o.put("productIdentifier", p.getProducts().isEmpty() ? "" : p.getProducts().get(0));
        o.put("purchaseToken", p.getPurchaseToken());
        o.put("orderId", p.getOrderId());
        o.put("purchaseTime", p.getPurchaseTime());
        o.put("isAutoRenewing", p.isAutoRenewing());
        AccountIdentifiers ai = p.getAccountIdentifiers();
        o.put("obfuscatedAccountId", ai != null ? ai.getObfuscatedAccountId() : null);
        return o;
    }
}
