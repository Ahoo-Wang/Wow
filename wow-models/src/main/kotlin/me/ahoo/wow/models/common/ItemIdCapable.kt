package me.ahoo.wow.models.common

import jakarta.validation.constraints.NotBlank

interface ItemIdCapable {
    val itemId: String
}

interface NotBlankItemIdCapable : ItemIdCapable {
    @get:NotBlank
    override val itemId: String
}
