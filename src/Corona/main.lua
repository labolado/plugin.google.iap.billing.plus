display.setStatusBar( display.DefaultStatusBar )
local json = require "json"

local store = require("plugin.google.iap.v3")

local products = {
	"com.solar2d.permanent",
	"com.solar2d.comsumable",
	"nope-product",
}

local subscriptionProducts = {
	"com.solar2d.subscription",
	"nope-subscription",
}

local listenerTable = {}


local function productListener(event)
	print("Product list event", json.prettify(event),"\n----------------")
end

local function storeTransaction(event)
	print("Transaction event", json.prettify(event),"\n----------------")
	store.finishTransaction( event )
end


local y = display.safeScreenOriginY + 10

local text = display.newText( "init", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.init(storeTransaction)
end )
y=y+40

local text = display.newText( "load", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.loadProducts( products, subscriptionProducts, productListener )
end )
y=y+40

local text = display.newText( "restore", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.restore( )
end )
y=y+40

local text = display.newText( "purchase consumable", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.purchase("com.solar2d.comsumable")
end )
y=y+40

local text = display.newText( "consume", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.consumePurchase("com.solar2d.comsumable")
end )
y=y+40

local text = display.newText( "purchase permanent", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.purchase("com.solar2d.permanent")
end )
y=y+40


local text = display.newText( "purchase subscription", display.contentCenterX, y, native.systemFont, 25 )
text:addEventListener( "tap", function( )
	store.purchaseSubscription("com.solar2d.subscription")
end )
y=y+40

