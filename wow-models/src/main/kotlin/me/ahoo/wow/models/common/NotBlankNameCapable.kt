package me.ahoo.wow.models.common

import jakarta.validation.constraints.NotBlank
import me.ahoo.wow.api.naming.Named

interface NotBlankNameCapable : Named {
    @get:NotBlank
    override val name: String
}
