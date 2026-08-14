package com.streamza.loop.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wraps Play Billing for Streamza Loop's two subscription tiers (single/multi destination — mirrors
 *  server.js's existing tier model). Price and free-trial length are never hardcoded here: they come
 *  entirely from each subscription's base plan / offer as configured in Play Console, surfaced through
 *  [ProductDetails.subscriptionOfferDetails] and read by the UI at display time. Product ids themselves
 *  come from GET /billing/config (see AppRepository.billingConfig), not literals in this file.
 *
 *  Purchase acknowledgement happens server-side (see googlePlay.js / POST /billing/verify-purchase)
 *  once the purchase token is verified against the Play Developer API, so this class only needs to
 *  hand that token off — it never calls acknowledgePurchase itself. */
class BillingManager(
    context: Context,
    private val onPurchaseToken: (productId: String, purchaseToken: String) -> Unit,
) : PurchasesUpdatedListener {

    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products: StateFlow<List<ProductDetails>> = _products.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // enableOneTimeProducts() is required for PendingPurchasesParams to build even though this app
    // only sells subscriptions — Billing Library 6/7 reject an empty params object.
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun startConnection(onReady: () -> Unit = {}) {
        if (client.isReady) { onReady(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                _connected.value = result.responseCode == BillingClient.BillingResponseCode.OK
                if (_connected.value) onReady()
            }
            override fun onBillingServiceDisconnected() {
                _connected.value = false
            }
        })
    }

    suspend fun queryProducts(productIds: List<String>) {
        if (productIds.isEmpty() || !client.isReady) return
        val productList = productIds.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        val result = client.queryProductDetails(params)
        _products.value = result.productDetailsList ?: emptyList()
    }

    /** Launches the purchase flow for a product's first available offer (the base plan Play Console
     *  is configured with — including any free trial phase). Result arrives via [onPurchasesUpdated]. */
    fun launchPurchase(activity: Activity, productDetails: ProductDetails): Boolean {
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        return client.launchBillingFlow(activity, flowParams).responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val productId = purchase.products.firstOrNull() ?: continue
            onPurchaseToken(productId, purchase.purchaseToken)
        }
    }

    fun endConnection() {
        client.endConnection()
    }
}

/** First offer's pricing phases turned into "7-day free trial, then $4.99/month" style text — or just
 *  "$4.99/month" when there's no trial phase. Entirely derived from Play Console's configured offer;
 *  no price or trial length is assumed here. */
fun ProductDetails.pricingSummary(): String {
    val phases = subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList ?: return ""
    if (phases.isEmpty()) return ""
    val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L }
    val paidPhase = phases.firstOrNull { it.priceAmountMicros > 0L } ?: phases.last()
    val period = billingPeriodText(paidPhase.billingPeriod)
    val paidText = "${paidPhase.formattedPrice}/$period"
    return if (trialPhase != null) {
        "${billingPeriodText(trialPhase.billingPeriod, plain = true)} free trial, then $paidText"
    } else {
        paidText
    }
}

private fun billingPeriodText(isoPeriod: String, plain: Boolean = false): String = when (isoPeriod) {
    "P1W" -> if (plain) "7-day" else "week"
    "P1M" -> if (plain) "1-month" else "month"
    "P3M" -> if (plain) "3-month" else "3 months"
    "P6M" -> if (plain) "6-month" else "6 months"
    "P1Y" -> if (plain) "1-year" else "year"
    else -> isoPeriod
}
