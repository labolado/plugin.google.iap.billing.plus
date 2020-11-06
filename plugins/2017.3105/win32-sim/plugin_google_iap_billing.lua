local Library = require "CoronaLibrary"

-- Create library
local lib = Library:new{ name='plugin.google.iap.billing', publisherId='com.coronalabs' }

-------------------------------------------------------------------------------
-- BEGIN (Insert your implementation starting here)
-------------------------------------------------------------------------------
local function placeholder()
	print( "WARNING: This library is not available on this platform")
end

lib.init = placeholder
lib.finishTransaction = placeholder
lib.loadProducts = placeholder
lib.purchase = placeholder
lib.restore = placeholder
lib.consumePurchase = placeholder
lib.purchaseSubscription = placeholder
lib.canLoadProducts = false
lib.canMakePurchases = false
lib.canPurchaseSubscriptions = false
lib.isActive = false
lib.target = "google"

return lib