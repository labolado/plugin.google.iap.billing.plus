package plugin.google.iap.billing.plus;

import com.android.billingclient.api.SkuDetails;
import com.naef.jnlua.LuaState;

public class BillingUtils {
    public static class SynchronizedWaiter {
        private boolean isSet = false;
        private int tasks = 0;
        private Runnable exec;

        public void Set(int tasks, Runnable exec) {
            if (!isSet) {
                this.tasks += tasks;
                this.exec = exec;
                isSet = true;
                checkAndRun();
            }
        }

        public synchronized void Hit() {
            tasks--;
            checkAndRun();
        }

        private void checkAndRun() {
            if (this.tasks == 0 && exec != null) {
                exec.run();
                exec = null;
            }
        }
    }


    public static int normalizeIndex(LuaState L, int tableIndex) {
        if (tableIndex < 0) {
            tableIndex = L.getTop() + tableIndex + 1;
        }
        return tableIndex;
    }

    public static void PushSKUToLua(SkuDetails sku, LuaState L, int tableIndex) {
        tableIndex = normalizeIndex(L, tableIndex);
        L.pushString(sku.getTitle());
        L.setField(tableIndex, "title");
        L.pushString(sku.getDescription());
        L.setField(tableIndex, "description");
        L.pushString(sku.getPrice());
        L.setField(tableIndex, "localizedPrice");
        L.pushString(sku.getSku());
        L.setField(tableIndex, "productIdentifier");
        L.pushString(sku.getType());
        L.setField(tableIndex, "type");
        L.pushString(String.valueOf(sku.getPriceAmountMicros()));
        L.setField(tableIndex, "priceAmountMicros");
        L.pushString(sku.getPriceCurrencyCode());
        L.setField(tableIndex, "priceCurrencyCode");
        L.pushString(sku.getOriginalJson());
        L.setField(tableIndex, "originalJson");
    }
}
