/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.models.common

import jakarta.validation.constraints.NotBlank

/** A type that has a [type], such as the discriminator of a polymorphic value. */
interface TypeCapable {
    val type: String

    companion object {
        /** The property's name, [TypeCapable.type]. */
        const val TYPE = "type"
    }
}

/** A [TypeCapable] whose type is not blank. */
interface NotBlankTypeCapable : TypeCapable {
    @get:NotBlank
    override val type: String
}

/**
 * The type of a polymorphic value, which a client both reads and writes.
 *
 * 9.x marked it read-write for OpenAPI with Swagger's `@Schema(accessMode = READ_WRITE)`; in 10.x the schemas come from
 * the Manifest and only wow-openapi depends on Swagger, so this carries no Swagger annotation.
 */
interface PolymorphicTypeCapable : NotBlankTypeCapable {
    override val type: String
}
