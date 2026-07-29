package plugin.google.iap.billing.plus;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.ansca.corona.CoronaEnvironment;
import com.naef.jnlua.LuaState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import plugin.google.iap.billing.plus.util.Security;

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

    /*
        ToDo: Double-check data fields. Google Billing documentation does not explain the field very well
        ToDo: Support multiple pricingPlans
    */
    public static String getOriginalJson(ProductDetails details) {

        JSONObject originalJson = new JSONObject();
        try {
            if(BillingClient.ProductType.SUBS.equals(details.getProductType()) || BillingClient.ProductType.INAPP.equals(details.getProductType())){
                originalJson.put("title", details.getTitle());
                originalJson.put("description", details.getDescription());
                originalJson.put("productId", details.getProductId());
                originalJson.put("type", details.getProductType());
                originalJson.put("packageName", CoronaEnvironment.getApplicationContext().getPackageName());
            }

            if(BillingClient.ProductType.SUBS.equals(details.getProductType()))
            {
                List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = details.getSubscriptionOfferDetails();
                ProductDetails.SubscriptionOfferDetails pricingPlan = subscriptionPlans.get(0);

                int phaseIndex = 0;
                ProductDetails.PricingPhase firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                originalJson.put("skuDetailsToken", pricingPlan.getOfferToken());


                // String trialDays = null; // null for no days?
                if(firstPricingPhase.getPriceAmountMicros() == 0) // free trial is always first
                {
                    originalJson.put("trialDays", firstPricingPhase.getBillingPeriod());
                    phaseIndex++;
                    firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                }
                originalJson.put("introductoryPricePeriod", firstPricingPhase.getBillingPeriod());
                originalJson.put("subscriptionPeriod", firstPricingPhase.getBillingPeriod()); //Same things?
                originalJson.put("introductoryPriceCycles", firstPricingPhase.getBillingCycleCount());
                originalJson.put("introductoryPriceAmountMicros", firstPricingPhase.getPriceAmountMicros());
                originalJson.put("introductoryPrice", firstPricingPhase.getFormattedPrice());

                originalJson.put("localizedPrice", firstPricingPhase.getFormattedPrice());
                originalJson.put("price_amount_micros", firstPricingPhase.getPriceAmountMicros());
                originalJson.put("price_currency_code", firstPricingPhase.getPriceCurrencyCode());
                try {
                    ProductDetails.PricingPhase secondPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex+1);
                    //Second Price
                    originalJson.put("original_price", secondPricingPhase.getFormattedPrice());
                    originalJson.put("original_price_micros", secondPricingPhase.getPriceAmountMicros());
                } catch ( IndexOutOfBoundsException e ) {
                    //Do nothing for now
                }

            }else if(BillingClient.ProductType.INAPP.equals(details.getProductType())){
                originalJson.put("localizedPrice", details.getOneTimePurchaseOfferDetails().getFormattedPrice());
                originalJson.put("price_amount_micros", details.getOneTimePurchaseOfferDetails().getPriceAmountMicros());
                originalJson.put("price_currency_code", details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return originalJson.toString();
    }

    // public static void PushDetailsToLua2(ProductDetails details, LuaState L, int tableIndex) {
    //     tableIndex = normalizeIndex(L, tableIndex);
    //     L.pushString(details.getTitle());
    //     L.setField(tableIndex, "title");
    //     L.pushString(details.getDescription());
    //     L.setField(tableIndex, "description");
    //
    //     if(BillingClient.ProductType.INAPP.equals(details.getProductType())){
    //         L.pushString(details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
    //         L.setField(tableIndex, "priceCurrencyCode");
    //         L.pushString(String.valueOf(details.getOneTimePurchaseOfferDetails().getPriceAmountMicros()));
    //         L.setField(tableIndex, "priceAmountMicros");
    //         L.pushString(details.getOneTimePurchaseOfferDetails().getFormattedPrice());
    //         L.setField(tableIndex, "localizedPrice");
    //         L.pushString(getOriginalJson(details));
    //         L.setField(tableIndex, "originalJson");
    //     }else if(BillingClient.ProductType.SUBS.equals(details.getProductType())){
    //
    //         List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = details.getSubscriptionOfferDetails();
    //         ProductDetails.SubscriptionOfferDetails pricingPlan = subscriptionPlans.get(0);
    //
    //         ProductDetails.PricingPhase pricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(0);
    //         if (pricingPhase.getPriceAmountMicros() == 0) // check to make sure phase is not free trial
    //         {
    //             pricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(1);
    //         }
    //         L.pushString(pricingPhase.getPriceCurrencyCode());
    //         L.setField(tableIndex, "priceCurrencyCode");
    //         L.pushString(String.valueOf(pricingPhase.getPriceAmountMicros()));
    //         L.setField(tableIndex, "priceAmountMicros");
    //         L.pushString(pricingPhase.getFormattedPrice());
    //         L.setField(tableIndex, "localizedPrice");
    //
    //         L.pushString(getOriginalJson(details));
    //         L.setField(tableIndex, "originalJson");
    //
    //
    //     }
    //     L.pushString(details.getProductId());
    //     L.setField(tableIndex, "productIdentifier");
    //     L.pushString(details.getProductType());
    //     L.setField(tableIndex, "type");
    // }

    public static void PushDetailsToLua(ProductDetails sku, LuaState L, int tableIndex) {
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
            List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = sku.getSubscriptionOfferDetails();
            int count = 1;
            L.newTable();
            if (subscriptionPlans != null) {
                for (ProductDetails.SubscriptionOfferDetails details : subscriptionPlans) {
                    L.newTable();
                    int nestIndex = normalizeIndex(L, -1);
                    L.pushString(details.getOfferToken());
                    L.setField(nestIndex, "offerToken");

                    L.pushString(details.getBasePlanId());
                    L.setField(nestIndex, "basePlanId");

                    String offerId = details.getOfferId();
                    if (offerId != null) {
                        L.pushString(offerId);
                        L.setField(nestIndex, "offerId");
                    }

                    int count2 = 1;
                    L.newTable();
                    for (String tag : details.getOfferTags()) {
                        L.pushString(tag);
                        L.rawSet(-2, count2);
                        count2++;
                    }
                    L.setField(nestIndex, "offerTags");

                    count2 = 1;
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
            }
            L.setField(tableIndex, "subscriptionOfferDetails");
        }

        // L.pushString(getOriginalJson(sku));
        // L.setField(tableIndex, "originalJson");
    }

    public  static void PushPurchasesToLua(List<Purchase> list, LuaState L, int tableIndex, String field, String publicKey) {
        tableIndex = normalizeIndex(L, tableIndex);
        int count = 1;
        L.newTable();
        for (Purchase purchase : list) {
            L.newTable();
            int nestIndex = normalizeIndex(L, -1);

            String productId = purchase.getProducts().isEmpty() ? "" : purchase.getProducts().get(0);
            String productType = LuaLoader.GetPurchaseType((productId));
            L.pushString(productType);
            L.setField(nestIndex, "type");

            int count2 = 1;
            L.newTable();
            for (String product : purchase.getProducts()) {
                L.pushString(product);
                L.rawSet(-2, count2);
                count2++;
            }
            L.setField(nestIndex, "products");

            String orderId = purchase.getOrderId();
            if (orderId == null) orderId = "";
            L.pushString(orderId);
            L.setField(nestIndex, "identifier");

            L.pushInteger(purchase.getPurchaseState());
            L.setField(nestIndex, "purchaseState");

            String state = "unknown";
            switch (purchase.getPurchaseState()) {
                case Purchase.PurchaseState.PURCHASED:
                    state = "purchased";
                    break;
                case Purchase.PurchaseState.PENDING:
                    state = "pending";
                    break;
                default:
                    state = "unknown";
                    break;
            }
            L.pushString(state);
            L.setField(nestIndex, "state");

            L.pushString(purchase.getPackageName());
            L.setField(nestIndex, "packageName");

            L.pushString(productId);
            L.setField(nestIndex, "productIdentifier");

            L.pushNumber(purchase.getPurchaseTime());
            L.setField(nestIndex, "date");

            L.pushString(purchase.getPurchaseToken());
            L.setField(nestIndex, "token");

            L.pushString(purchase.getOriginalJson());
            L.setField(nestIndex, "originalJson");

            L.pushString(purchase.getOriginalJson());
            L.setField(nestIndex, "receipt");

            L.pushString(purchase.getSignature());
            L.setField(nestIndex, "signature");

            L.pushBoolean(purchase.isAutoRenewing());
            L.setField(nestIndex, "isAutoRenewing");

            L.pushBoolean(purchase.isAcknowledged());
            L.setField(nestIndex, "isAcknowledged");

            L.pushBoolean(Security.verifyPurchase(publicKey, purchase.getOriginalJson(), purchase.getSignature()));
            L.setField(nestIndex, "isVerified");

            L.rawSet(-2, count);
            count++;
        }
        L.setField(tableIndex, field);
    }

}
