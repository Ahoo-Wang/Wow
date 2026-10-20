package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary

@Order(1)
@AllowCreate
@CommandRoute(
    method = CommandRoute.Method.POST,
)
@Summary("加入购物车")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

@Summary("商品已加入购物车")
data class CartItemAdded(
    val added: CartItem
)
