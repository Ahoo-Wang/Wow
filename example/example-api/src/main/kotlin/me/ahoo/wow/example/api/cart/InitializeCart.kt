package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

@Summary("初始化客户购物车")
@CreateAggregate
@CommandRoute(appendIdPath = CommandRoute.AppendIdPath.ALWAYS, path = "")
data class InitializeCart(
    @CommandRoute.PathVariable
    val id: String
)

object CartInitialized
