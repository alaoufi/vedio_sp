package com.myvideolibrary.app.security

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play subscription entitlement. When the app is installed from Play and
 * the account has an active subscription, this unlocks the app automatically —
 * no codes, no manual step. Sideloaded builds simply get "not entitled" and the
 * app falls back to the offline licence/trial.
 *
 * The last known entitlement is cached so the launcher can decide instantly,
 * then a fresh query updates it in the background each session.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    private val prefs by lazy { context.getSharedPreferences("billing", Context.MODE_PRIVATE) }

    private val _entitled = MutableStateFlow(isEntitledCached())
    /** Emits true whenever an active Play subscription is detected. */
    val entitled: StateFlow<Boolean> = _entitled.asStateFlow()

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
    }

    /** Last known entitlement, readable synchronously by the launcher gate. */
    fun isEntitledCached(): Boolean = prefs.getBoolean(KEY_ENTITLED, false)

    /** Connects (if needed) and re-queries active subscriptions in the background. */
    fun refresh() {
        if (client.isReady) {
            queryEntitlement()
            return
        }
        runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) queryEntitlement()
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    private fun queryEntitlement() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val active = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            purchases.forEach(::acknowledgeIfNeeded)
            setEntitled(active)
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { }
        }
    }

    /** Opens Play's subscribe sheet for the monthly plan. */
    fun launchSubscribe(activity: Activity) {
        val ensureThen: () -> Unit = {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUB_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            client.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
                val pd = details.firstOrNull() ?: return@queryProductDetailsAsync
                val offerToken = pd.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return@queryProductDetailsAsync
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(pd)
                                .setOfferToken(offerToken)
                                .build()
                        )
                    )
                    .build()
                client.launchBillingFlow(activity, flowParams)
            }
        }
        if (client.isReady) ensureThen()
        else runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) ensureThen()
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach(::acknowledgeIfNeeded)
            if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) setEntitled(true)
        }
    }

    private fun setEntitled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENTITLED, value).apply()
        _entitled.value = value
    }

    companion object {
        /** Subscription product id — must match the one created in Play Console. */
        const val SUB_PRODUCT_ID = "mvl_monthly"
        private const val KEY_ENTITLED = "entitled"
    }
}
