package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

@CommandRoute("customer/{customerId}/cart/remove", ignoreAggregateNamePrefix = true)
@Summary("删除商品")
data class RemoveCartItem(
    @CommandRoute.PathVariable
    @AggregateId
    val customerId: String,
    val productIds: Set<String>,
)

data class CartItemRemoved(
    val productIds: Set<String>
)
