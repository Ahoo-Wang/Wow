package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.Summary

@Summary("变更购买数量")
data class ChangeQuantity(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int
)

data class CartQuantityChanged(
    val changed: CartItem
)
