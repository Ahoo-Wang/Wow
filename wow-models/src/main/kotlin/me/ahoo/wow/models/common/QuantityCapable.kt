package me.ahoo.wow.models.common

interface NullableQuantityCapable {
    val qty: Int?
}

interface QuantityCapable : NullableQuantityCapable {
    override val qty: Int
}
