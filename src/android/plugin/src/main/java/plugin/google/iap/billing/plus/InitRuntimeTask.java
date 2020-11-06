package plugin.google.iap.billing.plus;

import android.util.Log;

import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingResult;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeTask;
import com.naef.jnlua.LuaState;

public class InitRuntimeTask implements CoronaRuntimeTask {

    private final int fListener;
    private final int fLibRef;
    private final BillingResult fResult;

    public InitRuntimeTask(BillingResult result, int listener, int libRef) {
        fResult = result;
        fListener = listener;
        fLibRef = libRef;
    }

    @Override
    public void executeUsing(CoronaRuntime runtime) {
        if (fListener == CoronaLua.REFNIL) {
            return;
        }
        // *** We are now running on the Corona runtime thread. ***
        LuaState L = runtime.getLuaState();
        try {
            // Set the store attributes
            L.rawGet(LuaState.REGISTRYINDEX, fLibRef);

            L.pushBoolean(fResult.getResponseCode() == BillingResponseCode.OK);
            L.setField(-2, "isActive");

            L.pushBoolean(true);
            L.setField(-2, "canPurchaseSubscriptions");

            L.pop(1);

            CoronaLua.newEvent(L, "init");

            L.newTable();
            if (fResult.getResponseCode() != BillingResponseCode.OK) {
                L.pushBoolean(true);
                L.setField(-2, "isError");

                L.pushInteger(fResult.getResponseCode());
                L.setField(-2, "errorType");

                L.pushString(fResult.getDebugMessage());
                L.setField(-2, "errorString");
            } else {
                L.pushBoolean(false);
                L.setField(-2, "isError");
            }

            L.pushString("initialized");
            L.setField(-2, "state");

            L.setField(-2, "transaction");

            CoronaLua.dispatchEvent(L, fListener, 0);
        } catch (Exception ex) {
            Log.e("Corona", "InitRuntimeTask: dispatching Google IAP init event", ex);
        }
    }
}
