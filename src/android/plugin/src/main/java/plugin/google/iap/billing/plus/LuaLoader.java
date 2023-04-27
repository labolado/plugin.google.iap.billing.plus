package plugin.google.iap.billing.plus;

import android.app.Dialog;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.ansca.corona.purchasing.StoreServices;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;

import plugin.google.iap.billing.plus.util.Security;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, PurchasesUpdatedListener, BillingClientStateListener {
    private int fLibRef;
    private int fListener;
    private CoronaRuntimeTaskDispatcher fDispatcher;
    private boolean fSetupSuccessful;
    private boolean fIsConnecting;
    private boolean fIsProductDetailSupported;
    private String fLicenseKey;
    private BillingClient fBillingClient;
    private static final HashMap<String, ProductDetails> fCachedSKUDetails = new HashMap<>();
    private static final HashMap<String, SkuDetails> fCachedSKUDetailsOld = new HashMap<>();
    private static int fNumReconnect = 0;
    private static final int RECONNECT_LIMIT = 3;
    public static final int PLAY_SERVICES_RESOLUTION_REQUEST = 999;

    private final HashSet<String> fConsumedPurchases = new HashSet<String>();
    private final HashSet<String> fAcknowledgedPurchases = new HashSet<String>();

    static String GetPurchaseType(String sku) {
        ProductDetails details = fCachedSKUDetails.get(sku);
        if (details != null) {
            return details.getProductType();
        }
        SkuDetails skuDetails = fCachedSKUDetailsOld.get(sku);
        if (skuDetails != null) {
            return skuDetails.getType();
        }
        return "unknown";
    }

    // public static boolean isPlayStoreInstalled(android.content.Context context){
    //     GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        // try {
        //     context.getPackageManager()
        //             .getPackageInfo(GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE, 0);
        //     return true;
        // } catch (PackageManager.NameNotFoundException e) {
        //     return false;
        // }
    // }

    private boolean initSuccessful() {
        return fBillingClient != null && fSetupSuccessful;
    }

    /**
     * Warning! This method is not called on the main UI thread.
     */
    @Override
    public int invoke(LuaState L) {
        fDispatcher = new CoronaRuntimeTaskDispatcher(L);

        fSetupSuccessful = false;
        fIsConnecting = false;
        fIsProductDetailSupported = false;

        // Add functions to library
        NamedJavaFunction[] luaFunctions = new NamedJavaFunction[]{
                new InitWrapper(),
                new LoadProductsWrapper(),
                new PurchaseWrapper(),
                new ConsumePurchaseWrapper(),
                new PurchaseSubscriptionWrapper(),
                new FinishTransactionWrapper(),
                new RestoreWrapper(),
                new RestoreSubscriptionWrapper(),
                new CheckPlayServiceWrapper(),
                new CheckIfProductDetailSupportedWrapper()
        };

        String libName = L.toString(1);
        L.register(libName, luaFunctions);

        L.pushValue(-1);
        fLibRef = L.ref(LuaState.REGISTRYINDEX);

        L.pushBoolean(true);
        L.setField(-2, "canLoadProducts");

        L.pushBoolean(true);
        L.setField(-2, "canMakePurchases");

        L.pushBoolean(false);
        L.setField(-2, "isActive");

        L.pushBoolean(false);
        L.setField(-2, "canPurchaseSubscriptions");

        L.pushString(StoreServices.getTargetedAppStoreName());
        L.setField(-2, "target");

        return 1;
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        if (billingResult.getResponseCode() == BillingResponseCode.OK) {
            fSetupSuccessful = true;
        }
        fIsConnecting = false;
        BillingResult testResult = fBillingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS);
        fIsProductDetailSupported = testResult.getResponseCode() == BillingResponseCode.OK;
        Log.d("Corona", "isFeatureSupported: code = " + testResult.getResponseCode() + ", msg = " + testResult.getDebugMessage() );

        if (fListener != CoronaLua.REFNIL) {
            InitRuntimeTask task = new InitRuntimeTask(billingResult, fListener, fLibRef);
            fDispatcher.send(task);
        }
    }

    @Override
    public void onBillingServiceDisconnected() {
        fSetupSuccessful = false;
        fIsConnecting = false;
        connect(false);
        Log.d("Corona", "Billing client disconnected.");
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
        if (list != null && !list.isEmpty()) {
            Log.d("Corona", "onPurchasesUpdated(1)" + billingResult);
            for (Purchase purchase : list) {
                if (Security.verifyPurchase(fLicenseKey, purchase.getOriginalJson(), purchase.getSignature())) {
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                } else {
                    Log.e("Corona", "Signature verification failed!");
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "verificationFailed", fListener));
                }
            }
        } else {
            Log.d("Corona", "onPurchasesUpdated(2)" + billingResult);
            fDispatcher.send(new StoreTransactionRuntimeTask(null, billingResult, fListener));
        }
    }

    public void onRestoreUpdated(BillingResult billingResult, List<Purchase> list) {
        if (list != null && !list.isEmpty()) {
            for (Purchase purchase : list) {
                if (Security.verifyPurchase(fLicenseKey, purchase.getOriginalJson(), purchase.getSignature())) {
                    if (purchase.isAcknowledged()){
                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "restoreFinished", fListener));
                    } else {
                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                    }
                } else {
                    Log.e("Corona", "Signature verification failed!");
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "verificationFailed", fListener));
                }
            }

            fDispatcher.send(new CoronaRuntimeTask() {
                @Override
                public void executeUsing(CoronaRuntime coronaRuntime) {
                    if (fListener == CoronaLua.REFNIL) {
                        return;
                    }
                    LuaState L = coronaRuntime.getLuaState();
                    try {
                        CoronaLua.newEvent(L, "storeTransaction");
                        L.newTable();

                        L.pushString("restore");
                        L.setField(-2, "type");

                        L.pushString("restoreCompleted");
                        L.setField(-2, "state");

                        L.setField(-2, "transaction");

                        CoronaLua.dispatchEvent(L, fListener, 0);
                    } catch (Exception ex) {
                        Log.e("Corona", "StoreTransactionRuntimeTask: dispatching Google IAP storeTransaction event", ex);
                    }
                }
            });
        } else {
            fDispatcher.send(new StoreTransactionRuntimeTask(null, billingResult, fListener));
        }
    }

    private int init(LuaState L) {
        int listenerIndex = 1;

        L.getGlobal("require");
        L.pushString("config");
        L.call(1, LuaState.MULTRET);

        //gets the application table
        L.getGlobal("application");
        if (L.type(-1) == LuaType.TABLE) {
            //push the license table to the top of the stack
            L.getField(-1, "license");
            if (L.type(-1) == LuaType.TABLE) {
                //push the google table to the top of the stack
                L.getField(-1, "google");
                if (L.type(-1) == LuaType.TABLE) {
                    //gets the key field from the google table
                    L.getField(-1, "key");
                    if (L.type(-1) == LuaType.STRING) {
                        fLicenseKey = L.toString(-1);
                    }
                    L.pop(1);
                }
                L.pop(1);
            }
            L.pop(1);
        }
        L.pop(1);

        // Skip an initial string parameter if present to be compatible with old store.init() API
        if (L.type(listenerIndex) == LuaType.STRING) {
            listenerIndex++;
        }

        fListener = CoronaLua.REFNIL;
        if (CoronaLua.isListener(L, listenerIndex, "storeTransaction")) {
            fListener = CoronaLua.newRef(L, listenerIndex);
        }

        connect(true);

        return 0;
    }

    private void connect(boolean dispatchEventWhenIsAlreadyConnected) {
        Log.d("Corona", "connect, isConnected:" + initSuccessful() + ", isConnecting:" + fIsConnecting);
        if (!initSuccessful() && !fIsConnecting) {
            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fIsConnecting = true;
                if (fBillingClient == null) {
                    fBillingClient = BillingClient.newBuilder(activity).enablePendingPurchases().setListener(this).build();
                }
                fBillingClient.startConnection(this);
            } else {
                Log.d("Corona", "Connect to play service error, activity is null");
                fBillingClient = null;
            }
        } else {
            if (dispatchEventWhenIsAlreadyConnected && initSuccessful() && !fIsConnecting && fListener != CoronaLua.REFNIL) {
                BillingResult billingResult = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
                InitRuntimeTask task = new InitRuntimeTask(billingResult, fListener, fLibRef);
                fDispatcher.send(task);
            }
        }
    }

    private int loadProducts(LuaState L) {
        connect(false);
        if (fIsProductDetailSupported) {
            return loadProductsNew((L));
        } else {
            Log.d("Corona", "loadProductsOld");
            return loadProductsOld((L));
        }
    }
    private int loadProductsNew(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to load products.");
            return 0;
        }

        int managedProductsTableIndex = 1;
        int listenerIndex = 2;

        final List<QueryProductDetailsParams.Product> productList = new ArrayList<>();

        final HashSet<String> managedProducts = new HashSet<String>();
        if (L.isTable(managedProductsTableIndex)) {
            int managedProductsLength = L.length(managedProductsTableIndex);
            for (int i = 1; i <= managedProductsLength; i++) {
                L.rawGet(managedProductsTableIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    String id = L.toString(-1);
                    managedProducts.add(id);
                    productList.add(QueryProductDetailsParams.Product.newBuilder()
                            .setProductType(BillingClient.ProductType.INAPP)
                            .setProductId(id).build());
                }
                L.pop(1);
            }
        } else {
            Log.e("Corona", "Missing product table to store.loadProducts");
        }

        final HashSet<String> subscriptionProducts = new HashSet<String>();
        if (!CoronaLua.isListener(L, listenerIndex, "productList") && L.isTable(listenerIndex)) {
            int subscriptionProductsLength = L.length(listenerIndex);
            for (int i = 1; i <= subscriptionProductsLength; i++) {
                L.rawGet(listenerIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    String id = L.toString(-1);
                    subscriptionProducts.add(id);
                    productList.add(QueryProductDetailsParams.Product.newBuilder()
                            .setProductType(BillingClient.ProductType.SUBS)
                            .setProductId(id).build());
                }
                L.pop(1);
            }
            listenerIndex++;
        }

        final int listener = CoronaLua.isListener(L, listenerIndex, "productList") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();
        fBillingClient.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
            public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetailsList) {
                if (productDetailsList != null) {
                    // Process the result
                    for (ProductDetails details : productDetailsList) {
                        fCachedSKUDetails.put(details.getProductId(), details);
                    }
                    fDispatcher.send(new ProductListRuntimeTask(productDetailsList, managedProducts, subscriptionProducts, billingResult, listener));
                }
            }
        });

        return 0;
    }

    private int loadProductsOld(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to load products.");
            return 0;
        }

        int managedProductsTableIndex = 1;
        int listenerIndex = 2;

        final HashSet<String> managedProducts = new HashSet<String>();
        if (L.isTable(managedProductsTableIndex)) {
            int managedProductsLength = L.length(managedProductsTableIndex);
            for (int i = 1; i <= managedProductsLength; i++) {
                L.rawGet(managedProductsTableIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    managedProducts.add(L.toString(-1));
                }
                L.pop(1);
            }
        } else {
            Log.e("Corona", "Missing product table to store.loadProducts");
        }

        final HashSet<String> subscriptionProducts = new HashSet<String>();
        if (!CoronaLua.isListener(L, listenerIndex, "productList") && L.isTable(listenerIndex)) {
            int subscriptionProductsLength = L.length(listenerIndex);
            for (int i = 1; i <= subscriptionProductsLength; i++) {
                L.rawGet(listenerIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    subscriptionProducts.add(L.toString(-1));
                }
                L.pop(1);
            }
            listenerIndex++;
        }

        final int listener = CoronaLua.isListener(L, listenerIndex, "productList") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;
        final List<SkuDetails> allSkus = new ArrayList<SkuDetails>();
        final BillingResult.Builder result = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK);

        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        final SkuDetailsResponseListener responder = new SkuDetailsResponseListener() {
            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> list) {
                if (billingResult.getResponseCode() != BillingResponseCode.OK && result.build().getResponseCode() == BillingResponseCode.OK) {
                    result.setResponseCode(billingResult.getResponseCode());
                    result.setDebugMessage(billingResult.getDebugMessage());
                }
                if (list != null) {
                    allSkus.addAll(list);
                    for (SkuDetails details : list) {
                        fCachedSKUDetailsOld.put(details.getSku(), details);
                    }
                }
                waiter.Hit();
            }
        };

        int tasks = 0;
        if (!managedProducts.isEmpty()) {
            tasks++;
            List<String> l = new ArrayList<String>(managedProducts);
            SkuDetailsParams params = SkuDetailsParams.newBuilder().setSkusList(l).setType(BillingClient.SkuType.INAPP).build();
            fBillingClient.querySkuDetailsAsync(params, responder);
        }
        if (!subscriptionProducts.isEmpty()) {
            tasks++;
            List<String> l = new ArrayList<String>(subscriptionProducts);
            SkuDetailsParams params = SkuDetailsParams.newBuilder().setSkusList(l).setType(BillingClient.SkuType.SUBS).build();
            fBillingClient.querySkuDetailsAsync(params, responder);
        }

        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                fDispatcher.send(new ProductListRuntimeTaskOld(allSkus, managedProducts, subscriptionProducts, result.build(), listener));
            }
        });

        return 0;
    }

    private int restore(LuaState L) {
        connect(false);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to restore products.");
            return 0;
        }
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                onRestoreUpdated(billingResult, list);
            }
        });

        // final List<Purchase> allPurchases = new ArrayList<>();
        // final BillingResult.Builder result = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).setDebugMessage("OK");
        // final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        // PurchasesResponseListener purchasesResponseListener = new PurchasesResponseListener() {
        //     @Override
        //     public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
        //         if (billingResult.getResponseCode() != BillingResponseCode.OK && result.build().getResponseCode() == BillingResponseCode.OK) {
        //             result.setResponseCode(billingResult.getResponseCode());
        //             result.setDebugMessage(billingResult.getDebugMessage());
        //         }
        //         if (list != null) {
        //             allPurchases.addAll(list);
        //         }
        //         waiter.Hit();
        //     }
        // };
        // fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), purchasesResponseListener);
        // fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), purchasesResponseListener);
        //
        // int tasks = 2;
        // waiter.Set(tasks, new Runnable() {
        //     @Override
        //     public void run() {
        //         onRestoreUpdated(result.build(), allPurchases);
        //     }
        // });

        // BillingResult.Builder res = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK);
        // Purchase.PurchasesResult r;
        // ArrayList<Purchase> purchases = new ArrayList<Purchase>();

        // r = fBillingClient.queryPurchases(BillingClient.SkuType.SUBS);
        // if (r.getResponseCode() != BillingResponseCode.OK && res.build().getResponseCode() == BillingResponseCode.OK) {
        //     res.setResponseCode(r.getResponseCode());
        //     res.setDebugMessage(r.getBillingResult().getDebugMessage());
        // }
        // if (r.getPurchasesList() != null) {
        //     purchases.addAll(r.getPurchasesList());
        // }

        // r = fBillingClient.queryPurchases(BillingClient.SkuType.INAPP);
        // if (r.getResponseCode() != BillingResponseCode.OK && res.build().getResponseCode() == BillingResponseCode.OK) {
        //     res.setResponseCode(r.getResponseCode());
        //     res.setDebugMessage(r.getBillingResult().getDebugMessage());
        // }
        // if (r.getPurchasesList() != null) {
        //     purchases.addAll(r.getPurchasesList());
        // }

        // onRestoreUpdated(res.build(), res.build().getResponseCode() == BillingResponseCode.OK ? purchases : null);
        // fDispatcher.send(new CoronaRuntimeTask() {
        //     @Override
        //     public void executeUsing(CoronaRuntime coronaRuntime) {
        //         if (fListener == CoronaLua.REFNIL) {
        //             return;
        //         }
        //         LuaState L = coronaRuntime.getLuaState();
        //         try {
        //             CoronaLua.newEvent(L, "storeTransaction");
        //             L.newTable();
        //
        //             L.pushString("restore");
        //             L.setField(-2, "type");
        //
        //             L.pushString("restoreCompleted");
        //             L.setField(-2, "state");
        //
        //             L.setField(-2, "transaction");
        //
        //             CoronaLua.dispatchEvent(L, fListener, 0);
        //         } catch (Exception ex) {
        //             Log.e("Corona", "StoreTransactionRuntimeTask: dispatching Google IAP storeTransaction event", ex);
        //         }
        //     }
        // });

        return 0;
    }

    private int restoreSubscription(LuaState L) {
        connect(false);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to restore products.");
            return 0;
        }
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                onRestoreUpdated(billingResult, list);
            }
        });

        return 0;
    }

    private int purchaseTypeNew(final LuaState L, final String type) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to purchase products.");
            return 0;
        }

        final String sku;
        if (L.type(1) == LuaType.STRING) {
            sku = L.toString(1);
        } else {
            sku = null;
        }

        if (sku == null) {
            Log.w("Corona", "Purchase type: " + type + " ,productId can not be nil!");
            return 0;
        }

        ProductDetails skuDetails = fCachedSKUDetails.get(sku);

        final String offerToken;
        if (L.type(2) == LuaType.STRING) {
            offerToken = L.toString(2);
        } else {
            offerToken = null;
        }

        if (offerToken == null && type.equals(BillingClient.ProductType.SUBS)) {
            Log.w("Corona", "Subscription needs to pass 'offer token' as second param!");
            return 0;
        }

        Log.d("Corona", "productId: " + sku + ", offerToken: " + offerToken) ;

        if (skuDetails != null) {
            Log.d("Corona", "productDetails: " + skuDetails) ;
            // BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setSkuDetails(skuDetails);

            final List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
            if (type.equals(BillingClient.ProductType.INAPP)) {
                productDetailsParamsList.add(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(skuDetails).build());
            } else {
                if (offerToken == null) return 0;
                productDetailsParamsList.add(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(skuDetails).setOfferToken(offerToken).build());
            }
            BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParamsList);
            BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParamsList);
            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fBillingClient.launchBillingFlow(activity, purchaseParams.build());
            }
        } else {
            final List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
            productList.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductType(type)
                    .setProductId(sku).build());
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                    .setProductList(productList)
                    .build();
            fBillingClient.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
                @Override
                public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> list) {
                    boolean sent = false;
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        for (ProductDetails details : list) {
                            fCachedSKUDetails.put(details.getProductId(), details);
                            if (details.getProductId().equals(sku)) {
                                final List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
                                if (details.getProductType().equals(BillingClient.ProductType.INAPP)) {
                                    productDetailsParamsList.add(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build());
                                } else {
                                    productDetailsParamsList.add(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offerToken).build());
                                }
                                BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParamsList);
                                CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
                                if (activity != null) {
                                    fBillingClient.launchBillingFlow(activity, purchaseParams.build());
                                }
                                sent = true;
                                break;
                            }
                        }
                        if (!sent) {
                            Log.e("Corona", "Error while purchasing because SKU was not found");
                        }
                    } else {
                        Log.e("Corona", "Error while purchasing" + billingResult.getDebugMessage());
                    }
                }
            });
        }

        return 0;
    }

    private int purchaseTypeOld(final LuaState L, final String type) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to purchase products.");
            return 0;
        }

        final String sku;
        if (L.type(1) == LuaType.STRING) {
            sku = L.toString(1);
        } else {
            sku = null;
        }
        L.pop(1);

        if (sku == null) return 0;

        SkuDetails skuDetails = fCachedSKUDetailsOld.get(sku);

        if (skuDetails != null) {
            Log.d("Corona", "skuDetails: " + skuDetails) ;
            BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setSkuDetails(skuDetails);
            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fBillingClient.launchBillingFlow(activity, purchaseParams.build());
            }
        } else {
            List<String> list = new ArrayList<>();
            list.add(sku);
            SkuDetailsParams skuDetailsParams = SkuDetailsParams.newBuilder().setSkusList(list).setType(type).build();
            fBillingClient.querySkuDetailsAsync(skuDetailsParams, new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> list) {
                    boolean sent = false;
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        for (SkuDetails details : list) {
                            fCachedSKUDetailsOld.put(details.getSku(), details);
                            if (details.getSku().equals(sku)) {
                                BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder().setSkuDetails(details);
                                CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
                                if (activity != null) {
                                    fBillingClient.launchBillingFlow(activity, purchaseParams.build());
                                }
                                sent = true;
                                break;
                            }
                        }
                        if (!sent) {
                            Log.e("Corona", "Error while purchasing because SKU was not found");
                        }
                    } else {
                        Log.e("Corona", "Error while purchasing" + billingResult.getDebugMessage());
                    }
                }
            });
        }

        return 0;
    }

    private int purchaseSubscription(LuaState L) {
        connect(false);
        if (fIsProductDetailSupported) {
            return purchaseTypeNew(L, BillingClient.ProductType.SUBS);
        } else {
            Log.d("Corona", "purchaseSubscriptionOld");
            return purchaseTypeOld(L, BillingClient.ProductType.SUBS);
        }
    }

    private int purchase(LuaState L) {
        connect(false);
        if (fIsProductDetailSupported) {
            return purchaseTypeNew(L, BillingClient.ProductType.INAPP);
        } else {
            Log.d("Corona", "purchaseOld");
            return purchaseTypeOld(L, BillingClient.ProductType.INAPP);
        }
    }

    private int consumePurchase(LuaState L) {
        connect(false);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to consume products.");
            return 0;
        }

        final Hashtable<String, HashSet<String>> t = getPurchasesFromTransaction(L);
        PurchasesResponseListener purchasesResponseListener = new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(@NonNull BillingResult result, @NonNull List<Purchase> purchases) {
                if (result.getResponseCode() == BillingResponseCode.OK) {
                    for (final Purchase purchase : purchases) {
                        if (checkContainsPurchase(t, purchase)) {
                            if (!fConsumedPurchases.contains(purchase.getPurchaseToken())) {
                                fConsumedPurchases.add(purchase.getPurchaseToken());
                                ConsumeParams params = ConsumeParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build();
                                fBillingClient.consumeAsync(params, new ConsumeResponseListener() {
                                    @Override
                                    public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String ignore) {
                                        if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                            fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "consumed", fListener));
                                        } else {
                                            fConsumedPurchases.remove(purchase.getPurchaseToken());
                                            fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                                        }
                                    }
                                });
                            } else {
                                Log.i("Corona", "Product already being consumed, skipping it: " + purchase.getProducts() + ". It is safe to ignore this message");
                            }
                        } else {
                            Log.d("Corona", "It's not current product, skipping it: " + purchase.getProducts() + ". It is safe to ignore this message");
                        }
                    }
                } else {
                    Log.d("Corona", "consumePurchase error: " + result.getDebugMessage());
                }
            }
        };
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), purchasesResponseListener);

        return 0;
    }

    private int finishTransaction(LuaState L) {
        connect(false);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to finishTransaction.");
            return 0;
        }

        final Hashtable<String, HashSet<String>> t = getPurchasesFromTransaction(L);

        PurchasesResponseListener purchasesResponseListener = new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(@NonNull BillingResult result, @NonNull List<Purchase> purchases) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (final Purchase purchase : purchases) {
                        if (checkContainsPurchase(t, purchase)) {
                            Log.d("Corona", "finishTransaction productIds:" + purchase.getProducts() + ", token:" + purchase.getPurchaseToken());
                            if (!fAcknowledgedPurchases.contains(purchase.getPurchaseToken())) {
                                if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    fAcknowledgedPurchases.add(purchase.getPurchaseToken());
                                    AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build();
                                    fBillingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                                        @Override
                                        public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                                            if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                                fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "finished", fListener));
                                            } else {
                                                fAcknowledgedPurchases.remove(purchase.getPurchaseToken());
                                                fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener));
                                            }
                                        }
                                    });
                                }
                            } else {
                                Log.i("Corona", "Purchase already being finished (acknowledged)" + purchase.getProducts() + ". It is safe to ignore this message");
                            }
                        } else {
                            Log.d("Corona", "Purchase already being finished (2)" + purchase + ". It is safe to ignore this message");
                        }
                    }
                } else {
                    Log.d("Corona", "finishTransaction error: " + result.getDebugMessage());
                }
            }
        };
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), purchasesResponseListener);
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), purchasesResponseListener);

        return 0;
    }

    private boolean checkContainsPurchase(Hashtable<String, HashSet<String>> t, Purchase purchase) {
        boolean result = false;
        HashSet<String> productIds = t.get("productIds");
        if (productIds != null) {
            for (String sku : productIds) {
                // if (sku.equals(purchase.getSkus())) {
                if (purchase.getProducts().contains(sku)) {
                    result = true;
                    break;
                }
            }
        }
        HashSet<String> tokens = t.get("tokens");
        if (!result && tokens != null) {
            result = tokens.contains(purchase.getPurchaseToken());
        }
        return result;
    }

    private Hashtable<String, HashSet<String>> getPurchasesFromTransaction(LuaState L) {
        // if (!IAPsOnly) {
        //     fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), purchasesResponseListener);
        // }
        // fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), purchasesResponseListener);
        final HashSet<String> SKUs = new HashSet<>();
        final HashSet<String> tokens = new HashSet<>();
        if (L.isTable(1)) {
            int tableLength = L.length(1);
            for (int i = 1; i <= tableLength; i++) {
                L.rawGet(1, i);
                if (L.type(-1) == LuaType.STRING) {
                    SKUs.add(L.toString(-1));
                }
                L.pop(1);
            }

            L.getField(1, "transaction");
            if (L.isTable(-1)) {
                L.getField(-1, "token");
                if (L.type(-1) == LuaType.STRING) {
                    tokens.add(L.toString(-1));
                }
                L.pop(1);

                L.getField(-1, "productIdentifier");
                if (L.type(-1) == LuaType.STRING) {
                    SKUs.add(L.toString(-1));
                }
                L.pop(1);
            }
            L.pop(1);

            L.getField(1, "token");
            if (L.type(-1) == LuaType.STRING) {
                tokens.add(L.toString(-1));
            }
            L.pop(1);

            L.getField(1, "productIdentifier");
            if (L.type(-1) == LuaType.STRING) {
                SKUs.add(L.toString(-1));
            }
            L.pop(1);
        } else {
            if (L.type(1) == LuaType.STRING) {
                SKUs.add(L.toString(1));
            }
        }

        Hashtable<String, HashSet<String>> t = new Hashtable<>();
        t.put("productIds", SKUs);
        t.put("tokens", tokens);
        return t;

        // List<Purchase> allPurchases = new ArrayList<Purchase>();
        // if (!IAPsOnly) {
        //     List<Purchase> subs = fBillingClient.queryPurchases(BillingClient.SkuType.SUBS).getPurchasesList();
        //     if (subs != null) {
        //         allPurchases.addAll(subs);
        //     }
        // }
        // List<Purchase> IAPs = fBillingClient.queryPurchases(BillingClient.SkuType.INAPP).getPurchasesList();
        // if (IAPs != null) {
        //     allPurchases.addAll(IAPs);
        // }

        // HashSet<Purchase> purchases = new HashSet<Purchase>();
        // for (String sku : SKUs) {
        //     for (Purchase purchase : allPurchases) {
        //         // if (sku.equals(purchase.getSkus())) {
        //         if (purchase.getSkus().contains(sku)) {
        //             purchases.add(purchase);
        //         }
        //     }
        // }
        // for (String token : tokens) {
        //     for (Purchase purchase : allPurchases) {
        //         if (token.equals(purchase.getPurchaseToken())) {
        //             purchases.add(purchase);
        //         }
        //     }
        // }

        // return purchases;
    }

    // private int checkPlayService(LuaState L) {
    //     Context context = CoronaEnvironment.getApplicationContext();
    //     int result =  GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
    //     GoogleApiAvailability.getInstance().showErrorNotification(context, result);
    //     L.pushBoolean(result == ConnectionResult.SUCCESS);
    //     return 1;
    // }

    private int checkPlayService(LuaState L) {
        Context context = CoronaEnvironment.getApplicationContext();
        CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
        int result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        // if(result != ConnectionResult.SUCCESS) {
        // }
        if(GoogleApiAvailability.getInstance().isUserResolvableError(result)) {
            Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(activity, result, PLAY_SERVICES_RESOLUTION_REQUEST);
            if (dialog != null) {
                dialog.show();
            }
        }
        L.pushBoolean(result == ConnectionResult.SUCCESS);
        return 1;
    }

    private int checkIfProductDetailSupported(LuaState L) {
        boolean result = false;
        String errMsg = "";
        if (initSuccessful()) {
            BillingResult r =  fBillingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS);
            result = r.getResponseCode() == BillingResponseCode.OK;
            errMsg = r.getDebugMessage();
        }
        L.pushBoolean(result);
        L.pushString(errMsg);

        return 2;
    }

    private class InitWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "init";
        }

        @Override
        public int invoke(LuaState L) {
            return init(L);
        }
    }

    private class LoadProductsWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "loadProducts";
        }

        @Override
        public int invoke(LuaState L) {
            return loadProducts(L);
        }
    }

    private class PurchaseWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "purchase";
        }

        @Override
        public int invoke(LuaState L) {
            return purchase(L);
        }
    }

    private class PurchaseSubscriptionWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "purchaseSubscription";
        }

        @Override
        public int invoke(LuaState L) {
            return purchaseSubscription(L);
        }
    }

    private class ConsumePurchaseWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "consumePurchase";
        }

        @Override
        public int invoke(LuaState L) {
            return consumePurchase(L);
        }
    }

    private class FinishTransactionWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "finishTransaction";
        }

        @Override
        public int invoke(LuaState L) {
            return finishTransaction(L);
        }
    }

    private class RestoreWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "restore";
        }

        @Override
        public int invoke(LuaState L) {
            return restore(L);
        }
    }

    private class RestoreSubscriptionWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "restoreSubscription";
        }

        @Override
        public int invoke(LuaState L) {
            return restoreSubscription(L);
        }
    }

    private class CheckPlayServiceWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "checkPlayService";
        }

        @Override
        public int invoke(LuaState L) {
            return checkPlayService(L);
        }
    }

    private class CheckIfProductDetailSupportedWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "checkIfProductDetailSupported";
        }
        @Override
        public int invoke(LuaState L) {
            return checkIfProductDetailSupported(L);
        }
    }
}
