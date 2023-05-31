package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Positive

@CommandRoute("customer/{customerId}/cart/add", ignoreAggregateNamePrefix = true)
@Summary("加入购物车")
@AllowCreate
data class AddCartItem(
    @CommandRoute.PathVariable
    @AggregateId
    val customerId: String,
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

data class CartItemAdded(
    val added: CartItem
)
