package me.ahoo.wow.example.api.cart

data class CartItem(
    val productId: String,
    val quantity: Int = 1
)
