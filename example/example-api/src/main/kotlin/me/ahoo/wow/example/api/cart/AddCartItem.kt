package me.ahoo.wow.example.api.cart

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute

@AllowCreate
@CommandRoute(
    method = CommandRoute.Method.POST,
    summary = "加入购物车",
    description = "加入购物车"
)
data class AddCartItem(
    @field:CommandRoute.PathVariable
    val id: String,
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

data class CartItemAdded(
    val added: CartItem
)
