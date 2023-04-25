package plugin.google.iap.billing.plus;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.SkuDetails;
import com.naef.jnlua.LuaState;

import java.util.List;

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

    public static void PushSKUToLua(ProductDetails sku, LuaState L, int tableIndex) {
        tableIndex = normalizeIndex(L, tableIndex);
        L.pushString(sku.getTitle());
        L.setField(tableIndex, "title");
        L.pushString(sku.getDescription());
        L.setField(tableIndex, "description");
        L.pushString(sku.getProductId());
        L.setField(tableIndex, "productIdentifier");
        L.pushString(sku.getProductType());
        L.setField(tableIndex, "type");
        if (sku.getProductType().equals(BillingClient.ProductType.INAPP)) {
            ProductDetails.OneTimePurchaseOfferDetails details = sku.getOneTimePurchaseOfferDetails();
            if (details != null) {
                L.pushString(details.getFormattedPrice());
                L.setField(tableIndex, "localizedPrice");
                L.pushString(String.valueOf(details.getPriceAmountMicros()));
                L.setField(tableIndex, "priceAmountMicros");
                L.pushString(details.getPriceCurrencyCode());
                L.setField(tableIndex, "priceCurrencyCode");
            }
        } else {
            List<ProductDetails.SubscriptionOfferDetails> detailsList = sku.getSubscriptionOfferDetails();
            int count = 1;
            L.newTable();
            for (ProductDetails.SubscriptionOfferDetails details : detailsList) {
                L.newTable();
                int nestIndex = normalizeIndex(L, -1);
                L.pushString(details.getOfferToken());
                L.setField(nestIndex, "offerToken");
                L.pushString(details.getBasePlanId());
                L.setField(nestIndex, "basePanId");
                L.pushString(details.getOfferId());
                L.setField(nestIndex, "offerId");

                int count2 = 1;
                L.newTable();
                for (ProductDetails.PricingPhase pricingPhase : details.getPricingPhases().getPricingPhaseList()) {
                    L.newTable();
                    int nestIndex2 = normalizeIndex(L, -1);
                    L.pushString(pricingPhase.getFormattedPrice());
                    L.setField(nestIndex2, "localizedPrice");
                    L.pushString(String.valueOf(pricingPhase.getPriceAmountMicros()));
                    L.setField(nestIndex2, "priceAmountMicros");
                    L.pushString(pricingPhase.getPriceCurrencyCode());
                    L.setField(nestIndex2, "priceCurrencyCode");
                    L.pushString(pricingPhase.getBillingPeriod());
                    L.setField(nestIndex2, "billingPeriod");
                    L.pushInteger(pricingPhase.getBillingCycleCount());
                    L.setField(nestIndex2, "billingCycleCount");
                    L.pushInteger(pricingPhase.getRecurrenceMode());
                    L.setField(nestIndex2, "recurrenceMode");

                    L.rawSet(-2, count2);
                    count2++;
                }
                L.setField(nestIndex, "pricingPhases");

                L.rawSet(-2, count);
                count++;
            }
            L.setField(tableIndex, "subscriptionOfferDetails");
        }
        // L.pushString(sku.getOriginalJson());
        // L.setField(tableIndex, "originalJson");
    }

    public static void PushSKUToLuaOld(SkuDetails sku, LuaState L, int tableIndex) {
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
