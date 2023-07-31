package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

@Summary("删除商品")
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class RemoveCartItem(
    @CommandRoute.PathVariable
    val id: String,
    val productIds: Set<String>
)

data class CartItemRemoved(
    val productIds: Set<String>
)
