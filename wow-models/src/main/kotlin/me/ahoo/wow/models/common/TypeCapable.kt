package me.ahoo.wow.models.common

import jakarta.validation.constraints.NotBlank

interface TypeCapable {
    val type: String

    companion object {
        const val TYPE = "type"
    }
}

interface NotBlankTypeCapable : TypeCapable {
    @get:NotBlank
    override val type: String
}
