package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

@Summary("初始化客户购物车")
@CreateAggregate
object InitializeCart

object CartInitialized
