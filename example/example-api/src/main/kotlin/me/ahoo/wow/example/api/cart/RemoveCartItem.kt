package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary

@Summary("删除商品")
@AllowCreate
data class RemoveCartItem(
    val productIds: Set<String>
)

data class CartItemRemoved(
    val productIds: Set<String>
)
