package plugin.google.iap.billing.plus;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
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
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.ansca.corona.purchasing.StoreServices;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
// import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import plugin.google.iap.billing.plus.util.Security;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, CoronaRuntimeListener, PurchasesUpdatedListener, BillingClientStateListener {
    private int fLibRef;
    private int fListener;
    private CoronaRuntimeTaskDispatcher fDispatcher;
    private boolean fSetupSuccessful;
    private boolean fIsConnecting;
    private boolean fIsProductDetailSupported;
    private String fLicenseKey;
    private BillingClient fBillingClient;
    private static final HashMap<String, ProductDetails> fCachedProductDetails = new HashMap<String, ProductDetails>();

    private final HashSet<String> fConsumedPurchases = new HashSet<String>();
    private final HashSet<String> fAcknowledgedPurchases = new HashSet<String>();

    private final QueryPurchasesParams INAPP = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build();
    private final QueryPurchasesParams SUBS =  QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build();
    private HashSet<Purchase> cachePurchases = null; //Used for Async Events

    private static final HashMap<String, Number> prorationMode = new HashMap<String, Number>(){{
        put("unknownSubscriptionUpgrade", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.UNKNOWN_REPLACEMENT_MODE);
        put("deferred", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED);
        put("immediateAndChargeFullPrice", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE);
        put("immediateAndChargeProratedPrice", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE);
        put("immediateWithoutProration", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION);
        put("immediateWithTimeProration", BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION);
    }};

    // private static final HashMap<String, ProductDetails> fCachedSKUDetails = new HashMap<>();
    // private static int fNumReconnect = 0;
    // private static final int RECONNECT_LIMIT = 3;
    public static final int PLAY_SERVICES_RESOLUTION_REQUEST = 999;

    static String GetPurchaseType(String productId) {
        ProductDetails details = fCachedProductDetails.get(productId);
        if (details != null) {
            return details.getProductType();
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
     * Creates a new Lua interface to this plugin.
     * <p>
     * Note that a new LuaLoader instance will not be created for every CoronaActivity instance.
     * That is, only one instance of this class will be created for the lifetime of the application process.
     * This gives a plugin the option to do operations in the background while the CoronaActivity is destroyed.
     */
    @SuppressWarnings("unused")
    public LuaLoader() {
        Log.d("Corona", "Google billing plugin LuaLoader, fListener = " + fListener + " fBillingClient = " + fBillingClient);
        // Initialize member variables.
        fListener = CoronaLua.REFNIL;

        // Set up this plugin to listen for Corona runtime events to be received by methods
        // onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().
        // CoronaEnvironment.addRuntimeListener(this);
    }

    /**
     * Warning! This method is not called on the main UI thread.
     */
    @Override
    public int invoke(LuaState L) {
        Log.d("Corona", "Google billing plugin invoke, fListener = " + fListener + ", fBillingClient = " + fBillingClient);
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
                new CheckIfProductDetailSupportedWrapper(),
                new GetCachedProductDetailsWrapper(),
                new GetCurrentEntitlementsWrapper(),
                new OpenSubscriptionManagerWrapper(),
                new TestWrapper()
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
        // connect(false);
        Log.d("Corona", "Billing client disconnected.");
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
        if (list != null && !list.isEmpty()) {
            Log.d("Corona", "onPurchasesUpdated(1)" + billingResult);
            for (Purchase purchase : list) {
                if (Security.verifyPurchase(fLicenseKey, purchase.getOriginalJson(), purchase.getSignature())) {
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener, "unknown"));
                } else {
                    Log.e("Corona", "Signature verification failed!");
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "verificationFailed", fListener, "unknown"));
                }
            }
        } else {
            Log.d("Corona", "onPurchasesUpdated(2)" + billingResult);
            fDispatcher.send(new StoreTransactionRuntimeTask(null, billingResult, fListener, "unknown"));
        }
    }

    public void onRestoreUpdated(BillingResult billingResult, List<Purchase> list, String productType) {
        if (list != null && !list.isEmpty()) {
            for (Purchase purchase : list) {
                if (Security.verifyPurchase(fLicenseKey, purchase.getOriginalJson(), purchase.getSignature())) {
                    if (purchase.isAcknowledged()){
                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "restoreFinished", fListener, productType));
                    } else {
                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener, productType));
                    }
                } else {
                    Log.e("Corona", "Signature verification failed!");
                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "verificationFailed", fListener, productType));
                }
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

                    if (list != null) {
                        L.pushInteger(list.size());
                    } else {
                        L.pushInteger(0);
                    }
                    L.setField(-2, "numPurchases");

                    L.pushString(productType);
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
        connect(true, fListener);

        return 0;
    }

    private void connect(boolean dispatchEventWhenIsAlreadyConnected, int listener) {
        Log.d("Corona", "connect, isConnected:" + initSuccessful() + ", isConnecting:" + fIsConnecting);
        if (!initSuccessful() && !fIsConnecting) {
            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fIsConnecting = true;
                if (fBillingClient == null) {
                    // Updated for PBL 8: Use PendingPurchasesParams
                    PendingPurchasesParams pendingPurchasesParams = PendingPurchasesParams.newBuilder()
                            .enableOneTimeProducts()
                            .enablePrepaidPlans()
                            .build();
                    fBillingClient = BillingClient.newBuilder(activity)
                            .enablePendingPurchases(pendingPurchasesParams)
                            .setListener(this)
                            .build();
                }
                fBillingClient.startConnection(this);
            } else {
                Log.d("Corona", "Connect to play service error, activity is null");
                fBillingClient = null;
            }
        } else {
            if (dispatchEventWhenIsAlreadyConnected && initSuccessful() && !fIsConnecting && listener != CoronaLua.REFNIL) {
                BillingResult billingResult = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
                InitRuntimeTask task = new InitRuntimeTask(billingResult, listener, fLibRef);
                fDispatcher.send(task);
            }
        }
    }

    private int loadProducts(LuaState L) {
        connect(false, fListener);
        // if (fIsProductDetailSupported) {
        return loadProductsNew((L));
        // } else {
        //     Log.d("Corona", "loadProductsOld");
        //     return loadProductsOld((L));
        // }
    }
    private int loadProductsNew(LuaState L) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to load products.");
            return 0;
        }

        int managedProductsTableIndex = 1;
        int listenerIndex = 2;

        final HashSet<String> managedProducts = new HashSet<String>();
        List<QueryProductDetailsParams.Product> inAppProductsList = new ArrayList<>();
        if (L.isTable(managedProductsTableIndex)) {
            int managedProductsLength = L.length(managedProductsTableIndex);
            for (int i = 1; i <= managedProductsLength; i++) {
                L.rawGet(managedProductsTableIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    managedProducts.add(L.toString(-1));
                    QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(L.toString(-1))
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build();
                    inAppProductsList.add(myProduct);
                }
                L.pop(1);
            }
        } else {
            Log.e("Corona", "Missing product table to store.loadProducts");
        }

        final HashSet<String> subscriptionProducts = new HashSet<String>();
        List<QueryProductDetailsParams.Product> subProductsList = new ArrayList<>();
        if (!CoronaLua.isListener(L, listenerIndex, "productList") && L.isTable(listenerIndex)) {
            int subscriptionProductsLength = L.length(listenerIndex);
            for (int i = 1; i <= subscriptionProductsLength; i++) {
                L.rawGet(listenerIndex, i);
                if (L.type(-1) == LuaType.STRING) {
                    subscriptionProducts.add(L.toString(-1));
                    QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(L.toString(-1))
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build();
                    subProductsList.add(myProduct);
                }
                L.pop(1);
            }
            listenerIndex++;
        }

        final int listener = CoronaLua.isListener(L, listenerIndex, "productList") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;
        final List<ProductDetails> allDetails = new ArrayList<ProductDetails>();
        final BillingResult.Builder result = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK);

        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        final ProductDetailsResponseListener responder = new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult billingResult, QueryProductDetailsResult queryProductDetailsResult) {
                if (billingResult.getResponseCode() != BillingResponseCode.OK && result.build().getResponseCode() == BillingResponseCode.OK) {
                    result.setResponseCode(billingResult.getResponseCode());
                    result.setDebugMessage(billingResult.getDebugMessage());
                }
                List<ProductDetails> list = queryProductDetailsResult.getProductDetailsList();
                if (list != null) {
                    allDetails.addAll(list);
                    for (ProductDetails details : list) {
                        fCachedProductDetails.put(details.getProductId(), details);
                    }
                }
                waiter.Hit();
            }
        };

        int tasks = 0;
        if(!inAppProductsList.isEmpty()){
            tasks++;
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder()
                    .setProductList(inAppProductsList)
                    .build();
            fBillingClient.queryProductDetailsAsync(detailsParams, responder);
        }
        if(!subProductsList.isEmpty()){
            tasks++;
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder()
                    .setProductList(subProductsList)
                    .build();
            fBillingClient.queryProductDetailsAsync(detailsParams, responder);
        }

        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                fDispatcher.send(new ProductListRuntimeTask(allDetails, managedProducts, subscriptionProducts, result.build(), listener));
            }
        });

        return 0;
    }

    private int restore(LuaState L) {
        connect(false, fListener);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to restore products.");
            return 0;
        }
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                onRestoreUpdated(billingResult, list, BillingClient.ProductType.INAPP);
            }
        });

        return 0;
    }

    private int restoreSubscription(LuaState L) {
        connect(false, fListener);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to restore products.");
            return 0;
        }
        fBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                onRestoreUpdated(billingResult, list, BillingClient.ProductType.SUBS);
            }
        });

        return 0;
    }

    private int purchaseType(LuaState L, final QueryPurchasesParams type) {
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to purchase products.");
            return 0;
        }

        final String productId;
        if (L.type(1) == LuaType.STRING) {
            productId = L.toString(1);
        } else {
            productId = null;
        }
        final int hashFlags = Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_CLOSE | Base64.NO_WRAP;
        String productIdType = "inapp";
        if (productId == null) return 0;

        final BillingFlowParams.Builder purchaseParams = BillingFlowParams.newBuilder();
        final BillingFlowParams.SubscriptionUpdateParams.Builder subscriptionUpdateBuilder = BillingFlowParams.SubscriptionUpdateParams.newBuilder();
        String previousSubscriptionPurchaseToken = null;

        final String offerToken;
        if(L.isTable(2)) {
            L.getField(2, "offerToken");
            if (L.type(-1) == LuaType.STRING) {
                offerToken = L.toString(-1);
                productIdType = BillingClient.ProductType.SUBS;
            } else {
                offerToken = null;
            }
            L.pop(1);

            L.getField(2, "accountId");
            if (L.type(-1) == LuaType.STRING) {
                try {
                    String s = L.toString(-1);
                    String hashed = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes()), hashFlags);
                    purchaseParams.setObfuscatedAccountId(hashed);
                } catch (Throwable err) {
                    Log.e("Corona", "Error while hashing accountId: " + err.toString());
                }
            }
            L.pop(1);

            L.getField(2, "profileId");
            if (L.type(-1) == LuaType.STRING) {
                try {
                    String s = L.toString(-1);
                    String hashed = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes()), hashFlags);
                    purchaseParams.setObfuscatedProfileId(hashed);
                } catch (Throwable err) {
                    Log.e("Corona", "Error while hashing profileId: " + err.toString());
                }
            }
            L.pop(1);

            L.getField(2, "obfuscatedAccountId");
            if (L.type(-1) == LuaType.STRING) {
                purchaseParams.setObfuscatedAccountId(L.toString(-1));
            }
            L.pop(1);

            L.getField(2, "obfuscatedProfileId");
            if (L.type(-1) == LuaType.STRING) {
                purchaseParams.setObfuscatedProfileId(L.toString(-1));
            }
            L.pop(1);

            L.getField(2, "offerPersonalized");
            if (L.type(-1) == LuaType.BOOLEAN) {
                purchaseParams.setIsOfferPersonalized(L.toBoolean(-1));
            }
            L.pop(1);

            L.getField(2, "subscriptionUpdate");
            if (L.type(-1) == LuaType.TABLE) {
                L.getField(-1, "purchaseToken");
                if (L.type(-1) == LuaType.STRING) {
                    previousSubscriptionPurchaseToken = L.toString(-1);
                    subscriptionUpdateBuilder.setOldPurchaseToken(previousSubscriptionPurchaseToken);
                }
                L.pop(1);

                // Updated for PBL 8: Use correct method name
                L.getField(-1, "prorationMode");
                if (L.type(-1) == LuaType.STRING) {
                    if (prorationMode.containsKey(L.toString(-1))) {
                        subscriptionUpdateBuilder.setSubscriptionReplacementMode((int) prorationMode.get(L.toString(-1)));
                    } else {
                        Log.e("Corona", "Error Invalid prorationMode type: " + L.toString(-1));
                    }
                }
                L.pop(1);

                L.getField(-1, "productType");
                if (L.type(-1) == LuaType.STRING) {
                    if (L.toString(-1).equals("subs") || L.toString(-1).equals("inapp")) {
                        productIdType = L.toString(-1);
                    } else {
                        Log.e("Corona", "Error Invalid productIdType type: " + L.toString(-1));
                    }
                }
                L.pop(1);
            }
            L.pop(1);
        } else if (L.isTable(2)) {
            offerToken = L.toString(2);
        } else {
            offerToken = null;
        }

        final String f_previousSubscriptionPurchaseToken = previousSubscriptionPurchaseToken;

        ProductDetails productDetails = fCachedProductDetails.get(productId);
        if (productDetails != null) {
            BillingFlowParams.ProductDetailsParams productDetailsParams = null;
            if(BillingClient.ProductType.SUBS.equals(productDetails.getProductType())){
                // For subscriptions, we need to get the offer token
                if (offerToken != null) {
                    productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build();
                } else {
                    if (productDetails.getSubscriptionOfferDetails() != null && !productDetails.getSubscriptionOfferDetails().isEmpty()) {
                        productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .setOfferToken(productDetails.getSubscriptionOfferDetails().get(0).getOfferToken())
                                .build();
                    } else {
                        Log.e("Corona", "No subscription offer details found for product: " + productId);
                        return 0;
                    }
                }
            } else {
                productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build();
            }

            List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
            productDetailsParamsList.add(productDetailsParams);

            purchaseParams.setProductDetailsParamsList(productDetailsParamsList);
            if (f_previousSubscriptionPurchaseToken != null) {
                purchaseParams.setSubscriptionUpdateParams(subscriptionUpdateBuilder.build());
            }

            CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
            if (activity != null) {
                fBillingClient.launchBillingFlow(activity, purchaseParams.build());
            }
        } else {
            // Product details not cached, need to query first
            List<QueryProductDetailsParams.Product> myProductList = new ArrayList<>();
            QueryProductDetailsParams.Product myProduct = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(productIdType)
                    .build();
            myProductList.add(myProduct);
            QueryProductDetailsParams detailsParams = QueryProductDetailsParams.newBuilder()
                    .setProductList(myProductList)
                    .build();

            fBillingClient.queryProductDetailsAsync(detailsParams, new ProductDetailsResponseListener() {
                @Override
                public void onProductDetailsResponse(final BillingResult billingResult, QueryProductDetailsResult queryProductDetailsResult) {
                    boolean sent = false;
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        List<ProductDetails> list = queryProductDetailsResult.getProductDetailsList();
                        for (ProductDetails details : list) {
                            fCachedProductDetails.put(details.getProductId(), details);
                            if (details.getProductId().equals(productId)) {
                                BillingFlowParams.ProductDetailsParams productDetailsParams = null;

                                if(BillingClient.ProductType.SUBS.equals(details.getProductType())){
                                    if (details.getSubscriptionOfferDetails() != null && !details.getSubscriptionOfferDetails().isEmpty()) {
                                        productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                                                .setProductDetails(details)
                                                .setOfferToken(details.getSubscriptionOfferDetails().get(0).getOfferToken())
                                                .build();
                                    } else {
                                        Log.e("Corona", "No subscription offer details found for product: " + productId);
                                        return;
                                    }
                                } else {
                                    productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                                            .setProductDetails(details)
                                            .build();
                                }

                                List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
                                productDetailsParamsList.add(productDetailsParams);
                                purchaseParams.setProductDetailsParamsList(productDetailsParamsList);

                                if (f_previousSubscriptionPurchaseToken != null) {
                                    purchaseParams.setSubscriptionUpdateParams(subscriptionUpdateBuilder.build());
                                }

                                CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
                                if (activity != null) {
                                    fBillingClient.launchBillingFlow(activity, purchaseParams.build());
                                }
                                sent = true;
                                break;
                            }
                        }
                        if (!sent) {
                            Log.e("Corona", "Error while purchasing because Product Id was not found");
                        }
                    } else {
                        Log.e("Corona", "Error while purchasing: " + billingResult.getDebugMessage());
                    }
                }
            });
        }

        return 0;
    }

    private int purchaseSubscription(LuaState L) {
        connect(false, fListener);
        // if (fIsProductDetailSupported) {
            return purchaseType(L, SUBS);
            // return purchaseTypeNew(L, BillingClient.ProductType.SUBS);
        // } else {
        //     Log.d("Corona", "purchaseSubscriptionOld");
        //     return purchaseTypeOld(L, BillingClient.ProductType.SUBS);
        // }
    }

    private int purchase(LuaState L) {
        connect(false, fListener);
        // if (fIsProductDetailSupported) {
            return purchaseType(L, INAPP);
            // return purchaseTypeNew(L, BillingClient.ProductType.INAPP);
        // } else {
        //     Log.d("Corona", "purchaseOld");
        //     return purchaseTypeOld(L, BillingClient.ProductType.INAPP);
        // }
    }

    private int consumePurchase(LuaState L) {
        connect(false, fListener);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to consume products.");
            return 0;
        }

        Runnable processPurchases = new Runnable() {
            @Override
            public void run() {
                for (final Purchase purchase : cachePurchases) {
                    if (!fConsumedPurchases.contains(purchase.getPurchaseToken())) {
                        fConsumedPurchases.add(purchase.getPurchaseToken());
                        ConsumeParams params = ConsumeParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                        fBillingClient.consumeAsync(params, new ConsumeResponseListener() {
                            @Override
                            public void onConsumeResponse(BillingResult billingResult, String ignore) {
                                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "consumed", fListener, "unknown"));
                                } else {
                                    fConsumedPurchases.remove(purchase.getPurchaseToken());
                                    fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener, "unknown"));
                                }
                            }
                        });
                    } else {
                        Log.i("Corona", "Product already being consumed, skipping it: " + purchase.getOrderId() + ". It is safe to ignore this message");
                    }
                }
            }
        };
        getPurchasesFromTransaction(L, true, processPurchases);

        return 0;
    }

    private int finishTransaction(LuaState L) {
        connect(false, fListener);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to finishTransaction.");
            return 0;
        }

        Runnable processPurchases = new Runnable() {
            @Override
            public void run() {
                for (final Purchase purchase : cachePurchases) {
                    if (!fAcknowledgedPurchases.contains(purchase.getPurchaseToken())) {
                        if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            fAcknowledgedPurchases.add(purchase.getPurchaseToken());
                            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.getPurchaseToken())
                                    .build();
                            fBillingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                                @Override
                                public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, "finished", fListener, "unknown"));
                                    } else {
                                        fAcknowledgedPurchases.remove(purchase.getPurchaseToken());
                                        fDispatcher.send(new StoreTransactionRuntimeTask(purchase, billingResult, fListener, "unknown"));
                                    }
                                }
                            });
                        }
                    } else {
                        Log.i("Corona", "Purchase already being finished (acknowledged): " + purchase.getOrderId() + ". It is safe to ignore this message");
                    }
                }
            }
        };
        getPurchasesFromTransaction(L, false, processPurchases);

        return 0;
    }

    private void getPurchasesFromTransaction(LuaState L, final boolean IAPsOnly, final Runnable task) {
        final HashSet<String> productsIds = new HashSet<String>();
        final HashSet<String> tokens = new HashSet<String>();

        if (L.isTable(1)) {
            int tableLength = L.length(1);
            for (int i = 1; i <= tableLength; i++) {
                L.rawGet(1, i);
                if (L.type(-1) == LuaType.STRING) {
                    productsIds.add(L.toString(-1));
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
                    productsIds.add(L.toString(-1));
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
                productsIds.add(L.toString(-1));
            }
            L.pop(1);
        } else {
            if (L.type(1) == LuaType.STRING) {
                productsIds.add(L.toString(1));
            }
        }

        final List<Purchase> allPurchases = new ArrayList<Purchase>();
        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        int tasks = 0;

        if (!IAPsOnly) {
            tasks++;
            fBillingClient.queryPurchasesAsync(SUBS, new PurchasesResponseListener() {
                @Override
                public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                    if (list != null) {
                        allPurchases.addAll(list);
                    }
                    waiter.Hit();
                }
            });
        }

        tasks++;
        fBillingClient.queryPurchasesAsync(INAPP, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                if (list != null) {
                    allPurchases.addAll(list);
                }
                waiter.Hit();
            }
        });

        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                HashSet<Purchase> purchases = new HashSet<Purchase>();
                for (String productsId : productsIds) {
                    for (Purchase purchase : allPurchases) {
                        for (String purchasePID : purchase.getProducts()) {
                            if(productsId.equals(purchasePID)){
                                purchases.add(purchase);
                            }
                        }
                    }
                }
                for (String token : tokens) {
                    for (Purchase purchase : allPurchases) {
                        if (token.equals(purchase.getPurchaseToken())) {
                            purchases.add(purchase);
                        }
                    }
                }

                cachePurchases = purchases;
                task.run();
            }
        });
    }

    private void acknowledgePurchase(Purchase purchase) {
        if (!fAcknowledgedPurchases.contains(purchase.getPurchaseToken())) {
            if (!purchase.isAcknowledged() && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                fAcknowledgedPurchases.add(purchase.getPurchaseToken());
                AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();
                fBillingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                    @Override
                    public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                        if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                            fAcknowledgedPurchases.remove(purchase.getPurchaseToken());
                            Log.d("Corona", "Purchase acknowledge failed: " + purchase.getOrderId());
                        }
                    }
                });
            }
        } else {
            Log.d("Corona", "Purchase already being finished (acknowledged): " + purchase.getOrderId() + ". It is safe to ignore this message");
        }
    }

    private int getCurrentEntitlements(LuaState L) {
        connect(false, CoronaLua.REFNIL);
        if (!initSuccessful()) {
            Log.w("Corona", "Please call init before trying to getCurrentEntitlements.");
            return 0;
        }
        int listenerIndex = 1;
        final int listener = CoronaLua.isListener(L, listenerIndex, "entitlements") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;

        final List<Purchase> subscriptions = new ArrayList<>();
        final List<Purchase> inApps = new ArrayList<>();
        final List<BillingResult> billingResults = new ArrayList<>();
        final BillingUtils.SynchronizedWaiter waiter = new BillingUtils.SynchronizedWaiter();
        int tasks = 0;

        tasks++;
        fBillingClient.queryPurchasesAsync(SUBS, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                if (list != null) {
                    subscriptions.addAll(list);
                }
                billingResults.add(billingResult);
                waiter.Hit();
            }
        });

        tasks++;
        fBillingClient.queryPurchasesAsync(INAPP, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
                if (list != null) {
                    inApps.addAll(list);
                }
                billingResults.add(billingResult);
                waiter.Hit();
            }
        });

        waiter.Set(tasks, new Runnable() {
            @Override
            public void run() {
                for (Purchase purchase : subscriptions) {
                    acknowledgePurchase(purchase);
                }
                for (Purchase purchase : inApps) {
                    acknowledgePurchase(purchase);
                }
                fDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        if (listener == CoronaLua.REFNIL) {
                            return;
                        }
                        LuaState L = coronaRuntime.getLuaState();
                        try {
                            CoronaLua.newEvent(L, "entitlements");
                            L.newTable();

                            boolean isError = false;
                            for (BillingResult result : billingResults) {
                                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                                    isError = true;

                                    L.pushInteger(result.getResponseCode());
                                    L.setField(-2, "errorType");

                                    L.pushString(result.getDebugMessage());
                                    L.setField(-2, "errorString");

                                    break;
                                }
                            }

                            L.pushBoolean(isError);
                            L.setField(-2, "isError");

                            L.pushBoolean(!subscriptions.isEmpty());
                            L.setField(-2, "hasSubscriptions");

                            L.pushBoolean(!inApps.isEmpty());
                            L.setField(-2, "hasInapps");

                            L.pushInteger(subscriptions.size() + inApps.size());
                            L.setField(-2, "totalCount");

                            BillingUtils.PushPurchasesToLua(subscriptions, L, -1, "subscriptions", fLicenseKey);
                            BillingUtils.PushPurchasesToLua(inApps, L, -1, "inapps", fLicenseKey);

                            L.setField(-2, "entitlements");

                            CoronaLua.dispatchEvent(L, listener, 0);
                        } catch (Exception ex) {
                            Log.e("Corona", "getCurrentEntitlements: dispatching Google IAP entitlements event", ex);
                        }
                    }
                });
            }
        });

        return 0;
    }

    private int openSubscriptionManager(LuaState L) {
        String packageName = CoronaEnvironment.getApplicationContext().getPackageName();

        final String subscriptionProductId;
        if (L.type(1) == LuaType.STRING) {
            subscriptionProductId = L.toString(1);
        } else {
            subscriptionProductId = null;
        }

        String url = "https://play.google.com/store/account/subscriptions?" + "sku=" + subscriptionProductId + "&package=" + packageName;

        try {
            // 使用 Intent.ACTION_VIEW 打开 URL
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));

            // 添加 FLAG，确保 Play Store/浏览器在新的任务中打开
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            CoronaEnvironment.getApplicationContext().startActivity(intent);

        } catch (Exception e) {
            // 捕获异常，例如设备上没有可处理此 Intent 的应用（极少发生）
            e.printStackTrace();
        }

        return 0;
    }

    private int checkPlayService(LuaState L) {
        Context context = CoronaEnvironment.getApplicationContext();
        CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
        int result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if(GoogleApiAvailability.getInstance().isUserResolvableError(result)) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(activity, result, PLAY_SERVICES_RESOLUTION_REQUEST);
                    if (dialog != null) {
                        dialog.show();
                    }
                }
            });
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

    private int getCachedProductDetails(LuaState L) {
        int count = 1;
        L.newTable();
        for (Map.Entry <String, ProductDetails> entry: fCachedProductDetails.entrySet()) {
            L.newTable();
            BillingUtils.PushDetailsToLua(entry.getValue(), L, -1);
            // L.setField(-2, entry.getKey());
            L.rawSet(-2, count);
            count++;
        }

        return 1;
    }

    private int test (LuaState L) {
        // int managedProductsTableIndex = 1;
        // int listenerIndex = 2;

        // final List<QueryProductDetailsParams.Product> productList = new ArrayList<>();

        // final HashSet<String> managedProducts = new HashSet<String>();
        // if (L.isTable(managedProductsTableIndex)) {
        //     int managedProductsLength = L.length(managedProductsTableIndex);
        //     for (int i = 1; i <= managedProductsLength; i++) {
        //         L.rawGet(managedProductsTableIndex, i);
        //         if (L.type(-1) == LuaType.STRING) {
        //             String id = L.toString(-1);
        //             managedProducts.add(id);
        //             productList.add(QueryProductDetailsParams.Product.newBuilder()
        //                     .setProductType(BillingClient.ProductType.INAPP)
        //                     .setProductId(id).build());
        //         }
        //         L.pop(1);
        //     }
        // } else {
        //     Log.e("Corona", "Missing product table to store.loadProducts");
        // }

        // final HashSet<String> subscriptionProducts = new HashSet<String>();
        // if (!CoronaLua.isListener(L, listenerIndex, "productList") && L.isTable(listenerIndex)) {
        //     int subscriptionProductsLength = L.length(listenerIndex);
        //     for (int i = 1; i <= subscriptionProductsLength; i++) {
        //         L.rawGet(listenerIndex, i);
        //         if (L.type(-1) == LuaType.STRING) {
        //             String id = L.toString(-1);
        //             subscriptionProducts.add(id);
        //             productList.add(QueryProductDetailsParams.Product.newBuilder()
        //                     .setProductType(BillingClient.ProductType.SUBS)
        //                     .setProductId(id).build());
        //         }
        //         L.pop(1);
        //     }
        //     listenerIndex++;
        // }

        // final int listener = CoronaLua.isListener(L, listenerIndex, "productList") ? CoronaLua.newRef(L, listenerIndex) : CoronaLua.REFNIL;

        // QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
        //         .setProductList(productList)
        //         .build();
        // fBillingClient.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
        //     @Override
        //     public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetailsList) {
        //         if (productDetailsList != null) {
        //             // BillingUtils.PushProductDetailsListToLua(productDetailsList, L);
        //             // Process the result
        //             for (ProductDetails details : productDetailsList) {
        //                 fCachedSKUDetails.put(details.getProductId(), details);
        //             }
        //             fDispatcher.send(new ProductListRuntimeTask(productDetailsList, managedProducts, subscriptionProducts, billingResult, listener));
        //         }
        //     }
        // });

        return 0;
    }

    @Override
    public void onLoaded(CoronaRuntime runtime) {
        Log.d("Corona", "Google billing plugin onLoaded, fListener = " + fListener + ", fBillingClient = " + fBillingClient);
    }

    @Override
    public void onStarted(CoronaRuntime runtime) {
        Log.d("Corona", "Google billing plugin onStarted");
    }

    @Override
    public void onSuspended(CoronaRuntime runtime) {
        Log.d("Corona", "Google billing plugin onSuspended");
    }

    @Override
    public void onResumed(CoronaRuntime runtime) {
        Log.d("Corona", "Google billing plugin onResumed");
    }

    @Override
    public void onExiting(CoronaRuntime runtime) {
        CoronaLua.deleteRef( runtime.getLuaState(), fListener );
        fListener = CoronaLua.REFNIL;
        if (fBillingClient != null) {
            fBillingClient.endConnection();
            fBillingClient = null;
        }
        fIsConnecting = false;
        fSetupSuccessful = false;
        Log.d("Corona", "Google billing plugin onExiting");
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

    private class TestWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "test";
        }
        @Override
        public int invoke(LuaState L) {
            return test(L);
        }
    }

    private class GetCachedProductDetailsWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "getCachedProductDetails";
        }
        @Override
        public int invoke(LuaState L) {
            return getCachedProductDetails(L);
        }
    }

    private class GetCurrentEntitlementsWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "getCurrentEntitlements";
        }
        @Override
        public int invoke(LuaState L) {
            return getCurrentEntitlements(L);
        }
    }

    private class OpenSubscriptionManagerWrapper implements NamedJavaFunction {
        @Override
        public String getName() {
            return "openSubscriptionManager";
        }
        @Override
        public int invoke(LuaState L) {
            return openSubscriptionManager(L);
        }
    }
}
