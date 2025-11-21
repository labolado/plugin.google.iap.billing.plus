package plugin.google.iap.billing.plus;

import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntimeTask;
import com.naef.jnlua.LuaState;


public class StoreTransactionRuntimeTask implements CoronaRuntimeTask {

    private final Purchase fPurchase;
    private final int fListener;
    private final String fState;
    private final BillingResult fError;
    private final String fProductType;

    public StoreTransactionRuntimeTask(Purchase purchase, BillingResult result, int listener, String productType) {
        fPurchase = purchase;
        fListener = listener;
        fProductType = productType;
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            if (purchase != null) {
                fError = null;
                switch (purchase.getPurchaseState()) {
                    case Purchase.PurchaseState.PURCHASED:
                        fState = "purchased";
                        break;
                    case Purchase.PurchaseState.PENDING:
                        fState = "pending";
                        break;
                    default:
                        fState = "unknown";
                        break;
                }
            } else {
                fError = result;
                fState = "unknown";
            }
        } else {
            fError = result;
            switch (result.getResponseCode()) {
                case BillingClient.BillingResponseCode.USER_CANCELED:
                    fState = "cancelled";
                    break;
                default:
                    fState = "failed";
                    break;
            }
        }
    }

    public StoreTransactionRuntimeTask(Purchase purchase, String result, int listener, String productType) {
        fPurchase = purchase;
        fListener = listener;
        fState = result;
        fError = null;
        fProductType = productType;
    }

    @Override
    public void executeUsing(com.ansca.corona.CoronaRuntime runtime) {
        if (fListener == CoronaLua.REFNIL) {
            return;
        }

        // *** We are now running on the Corona runtime thread. ***
        LuaState L = runtime.getLuaState();
        try {
            CoronaLua.newEvent(L, "storeTransaction");

            L.newTable();
            if (fError != null) {
                L.pushBoolean(true);
                L.setField(-2, "isError");

                L.pushNumber(fError.getResponseCode());
                L.setField(-2, "errorType");

                L.pushString(fError.getDebugMessage());
                L.setField(-2, "errorString");
            } else {
                String sku = fPurchase.getProducts().isEmpty() ? "" : fPurchase.getProducts().get(0);
                String productType = LuaLoader.GetPurchaseType((sku));
                if (productType.equals("unknown")) {
                    productType = fProductType;
                }
                L.pushString(productType);
                L.setField(-2, "type");

                int count2 = 1;
                L.newTable();
                for (String product : fPurchase.getProducts()) {
                    L.pushString(product);
                    L.rawSet(-2, count2);
                    count2++;
                }
                L.setField(-2, "products");

                String orderId = fPurchase.getOrderId();
                if (orderId == null) orderId = "";
                L.pushString(orderId);
                L.setField(-2, "identifier");

                L.pushInteger(fPurchase.getPurchaseState());
                L.setField(-2, "purchaseState");

                L.pushString(fPurchase.getPackageName());
                L.setField(-2, "packageName");

                L.pushString(sku);
                L.setField(-2, "productIdentifier");

                L.pushNumber(fPurchase.getPurchaseTime());
                L.setField(-2, "date");

                L.pushString(fPurchase.getPurchaseToken());
                L.setField(-2, "token");

                L.pushString(fPurchase.getOriginalJson());
                L.setField(-2, "originalJson");

                L.pushString(fPurchase.getOriginalJson());
                L.setField(-2, "receipt");

                L.pushString(fPurchase.getSignature());
                L.setField(-2, "signature");

                L.pushBoolean(fPurchase.isAutoRenewing());
                L.setField(-2, "isAutoRenewing");

                L.pushBoolean(fPurchase.isAcknowledged());
                L.setField(-2, "isAcknowledged");

                L.pushBoolean(fState.equals("verificationFailed"));
                L.setField(-2, "isVerified");
            }

            L.pushString(fState);
            L.setField(-2, "state");

            L.setField(-2, "transaction");

            // Dispatch event table at top of stack
            CoronaLua.dispatchEvent(L, fListener, 0);
        } catch (Exception ex) {
            Log.e("Corona", "StoreTransactionRuntimeTask: dispatching Google IAP storeTransaction event", ex);
        }
    }
}
