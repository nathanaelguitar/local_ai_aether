package com.nathanaelguitar.canopychat.core

import android.app.Activity
import android.content.Context
import com.nathanaelguitar.canopychat.BuildConfig
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingClient.ProductType.SUBS
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android counterpart of CanopySubscriptionManager. Products remain optional until Play Console setup. */
class CanopySubscriptionManager(context: Context) :
    BillingClientStateListener,
    PurchasesUpdatedListener {

    companion object {
        const val MONTHLY_PRODUCT_ID = "com.nathanaelguitar.canopychat.monthly"
        const val YEARLY_PRODUCT_ID = "com.nathanaelguitar.canopychat.yearly"
        const val TEST_ACCESS_CODE = "CANOPY-TEST"
        private const val TEST_ACCESS_KEY = "testAccessUnlocked"
    }

    private val preferences = context.getSharedPreferences("canopychat", Context.MODE_PRIVATE)
    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products: StateFlow<List<ProductDetails>> = _products.asStateFlow()
    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    val testAccessUnlocked = MutableStateFlow(preferences.getBoolean(TEST_ACCESS_KEY, false))
    private val _hasPremiumAccess = MutableStateFlow(testAccessUnlocked.value)
    val hasPremiumAccess: StateFlow<Boolean> = _hasPremiumAccess.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        refresh()
    }

    fun refresh() {
        _isLoading.value = true
        if (!billingClient.isReady) {
            billingClient.startConnection(this)
        } else {
            queryProducts()
            queryPurchases()
        }
    }

    fun purchase(activity: Activity, product: ProductDetails) {
        val offer = product.subscriptionOfferDetails?.firstOrNull() ?: run {
            _errorMessage.value = "This subscription is not currently available."
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .setOfferToken(offer.offerToken)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build()
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _errorMessage.value = result.debugMessage.ifBlank { "Purchase could not be started." }
        }
    }

    fun restorePurchases() = refresh()

    fun redeemTestAccessCode(code: String): Boolean {
        if (!BuildConfig.DEBUG) {
            _errorMessage.value = "Test access is only available in debug builds."
            return false
        }
        if (!code.trim().equals(TEST_ACCESS_CODE, ignoreCase = true)) {
            _errorMessage.value = "That test access code was not recognized."
            return false
        }
        testAccessUnlocked.value = true
        _hasPremiumAccess.value = true
        preferences.edit().putBoolean(TEST_ACCESS_KEY, true).apply()
        _errorMessage.value = null
        return true
    }

    fun resetTestAccess() {
        testAccessUnlocked.value = false
        _hasPremiumAccess.value = _isSubscribed.value
        preferences.edit().remove(TEST_ACCESS_KEY).apply()
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _errorMessage.value = "Google Play subscriptions are not configured yet."
            _isLoading.value = false
            return
        }
        queryProducts()
        queryPurchases()
    }

    override fun onBillingServiceDisconnected() {
        _isLoading.value = false
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            applyPurchases(purchases)
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _errorMessage.value = result.debugMessage.ifBlank { "Purchase could not be completed." }
        }
    }

    private fun queryProducts() {
        val products = listOf(MONTHLY_PRODUCT_ID, YEARLY_PRODUCT_ID).map { id ->
            QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(SUBS).build()
        }
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build()
        ) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = details
                _errorMessage.value = null
            }
            _isLoading.value = false
        }
    }

    private fun queryPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) applyPurchases(purchases)
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                billingClient.acknowledgePurchase(
                    com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                ) { }
            }
        _isSubscribed.value = purchases.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        _hasPremiumAccess.value = _isSubscribed.value || testAccessUnlocked.value
        _isLoading.value = false
    }
}
