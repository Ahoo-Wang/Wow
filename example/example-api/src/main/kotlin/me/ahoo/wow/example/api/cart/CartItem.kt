package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.query.annotation.QueryReference
import me.ahoo.wow.example.api.ExampleService.PRODUCT_AGGREGATE_NAME

data class CartItem(
    /** The product's id. Products live outside this example service (see `PricingService`, `InventoryService`). */
    @field:QueryReference(PRODUCT_AGGREGATE_NAME)
    val productId: String,
    val quantity: Int = 1
)
