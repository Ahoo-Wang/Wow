package me.ahoo.wow.models.common

import io.swagger.v3.oas.annotations.media.Schema
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

interface PolymorphicTypeCapable : NotBlankTypeCapable {
    @get:Schema(accessMode = Schema.AccessMode.READ_WRITE)
    override val type: String
}
