package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary

@Summary("加入购物车")
@AllowCreate
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

data class CartItemAdded(
    val added: CartItem
)
