package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotEmpty
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.query.annotation.QueryReference
import me.ahoo.wow.example.api.ExampleService.PRODUCT_AGGREGATE_NAME

@Order(3)
@Summary("删除商品")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)

data class CartItemRemoved(
    @field:QueryReference(PRODUCT_AGGREGATE_NAME)
    val productIds: Set<String>
)
