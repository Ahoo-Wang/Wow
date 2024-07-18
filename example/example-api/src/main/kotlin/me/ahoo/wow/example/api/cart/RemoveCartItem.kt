package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotEmpty
import me.ahoo.wow.api.annotation.Summary

@Summary("删除商品")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)

data class CartItemRemoved(
    val productIds: Set<String>
)
