package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

@CommandRoute("customer/{customerId}/cart", ignoreAggregateNamePrefix = true)
@Summary("初始化客户购物车")
@CreateAggregate
data class InitializeCart(
    @CommandRoute.PathVariable
    @AggregateId
    val customerId: String
)

object CartInitialized
