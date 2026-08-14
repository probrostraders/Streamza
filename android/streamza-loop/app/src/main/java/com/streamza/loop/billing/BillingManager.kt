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
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One purchasable offer: a specific (slot count, billing period) pair within a Play Console product.
 *  Each Streamza Loop product ("streamza_loop_1slot" etc, see server.js PLAY_PRODUCTS) carries weekly/
 *  monthly/yearly base plans; Play returns them all as [ProductDetails.subscriptionOfferDetails] and
 *  this is what turns one of those into something the UI can show a price/trial and button for. Price
 *  and trial length are never hardcoded — both come straight from whatever's configured in Play
 *  Console for that offer. */
data class PeriodOffer(
    val period: String,        // ISO 8601 duration of the recurring phase, e.g. "P1W"
    val periodLabel: String,   // "week" / "month" / "year"
    val offerToken: String,
    val priceText: String,     // "Rs 750.00/week"
    val trialText: String?,    // "7-day free trial" or null
)

data class SlotPlan(val slots: Int, val productDetails: ProductDetails, val offers: List<PeriodOffer>)

/** Wraps Play Billing for Streamza Loop's slot-count subscription tiers (1/2/3 destinations at once —
 *  mirrors the competitor model the app is matching). Purchase acknowledgement happens server-side
 *  (see googlePlay.js / POST /billing/verify-purchase) once the purchase token is verified against the
 *  Play Developer API, so this class only needs to hand that token off. */
class BillingManager(
    context: Context,
    private val onPurchaseToken: (productId: String, purchaseToken: String) -> Unit,
) : PurchasesUpdatedListener {

    private val _plans = MutableStateFlow<List<SlotPlan>>(emptyList())
    val plans: StateFlow<List<SlotPlan>> = _plans.asStateFlow()

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

    /** [productIdsBySlots] maps slot count -> Play product id (from GET /billing/config). */
    suspend fun queryProducts(productIdsBySlots: Map<Int, String>) {
        if (productIdsBySlots.isEmpty() || !client.isReady) return
        val productList = productIdsBySlots.values.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        val result = client.queryProductDetails(params)
        val byProductId = (result.productDetailsList ?: emptyList()).associateBy { it.productId }
        _plans.value = productIdsBySlots.entries
            .sortedBy { it.key }
            .mapNotNull { (slots, productId) ->
                val details = byProductId[productId] ?: return@mapNotNull null
                SlotPlan(slots, details, details.periodOffers())
            }
    }

    /** Launches the purchase flow for one specific (slot-tier, period) offer. Result arrives via
     *  [onPurchasesUpdated]. */
    fun launchPurchase(activity: Activity, plan: SlotPlan, offer: PeriodOffer) {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(plan.productDetails)
            .setOfferToken(offer.offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        client.launchBillingFlow(activity, flowParams)
    }

    /** Re-submits any active subscription this Play account already owns for server verification —
     *  covers reinstalling the app or signing in on a new device without buying again. */
    suspend fun restorePurchases() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
            client.queryPurchasesAsync(params) { _, list -> cont.resume(list) }
        }
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val productId = purchase.products.firstOrNull() ?: continue
            onPurchaseToken(productId, purchase.purchaseToken)
        }
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

private fun ProductDetails.periodOffers(): List<PeriodOffer> {
    val offers = subscriptionOfferDetails ?: return emptyList()
    return offers.mapNotNull { offer ->
        val phases = offer.pricingPhases.pricingPhaseList
        if (phases.isEmpty()) return@mapNotNull null
        val recurring = phases.lastOrNull { it.priceAmountMicros > 0L } ?: phases.last()
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
        PeriodOffer(
            period = recurring.billingPeriod,
            periodLabel = periodLabel(recurring.billingPeriod),
            offerToken = offer.offerToken,
            priceText = "${recurring.formattedPrice}/${periodLabel(recurring.billingPeriod)}",
            trialText = trial?.let { "${periodLabel(it.billingPeriod, plain = true)} free trial" },
        )
    }.sortedBy { periodSortKey(it.period) }
}

private fun periodLabel(isoPeriod: String, plain: Boolean = false): String = when (isoPeriod) {
    "P1W" -> if (plain) "7-day" else "week"
    "P1M" -> if (plain) "1-month" else "month"
    "P3M" -> if (plain) "3-month" else "3 months"
    "P6M" -> if (plain) "6-month" else "6 months"
    "P1Y" -> if (plain) "1-year" else "year"
    else -> isoPeriod
}

private fun periodSortKey(isoPeriod: String): Int = when (isoPeriod) {
    "P1W" -> 0; "P1M" -> 1; "P3M" -> 2; "P6M" -> 3; "P1Y" -> 4; else -> 99
}
