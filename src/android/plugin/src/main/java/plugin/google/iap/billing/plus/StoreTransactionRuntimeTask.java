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

    public StoreTransactionRuntimeTask(Purchase purchase, BillingResult result, int listener) {
        fPurchase = purchase;
        fListener = listener;
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

    public StoreTransactionRuntimeTask(Purchase purchase, String result, int listener) {
        fPurchase = purchase;
        fListener = listener;
        fState = result;
        fError = null;
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
                L.pushString(LuaLoader.GetPurchaseType(sku));
                L.setField(-2, "type");

                L.pushString(fPurchase.getOrderId());
                L.setField(-2, "identifier");

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
