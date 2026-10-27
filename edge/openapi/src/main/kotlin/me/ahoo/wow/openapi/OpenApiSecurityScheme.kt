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

package me.ahoo.wow.openapi

/**
 * How a caller presents its identity to the public routes, as an OpenAPI security scheme: the host
 * declares the schemes of its caller resolver, CoSec's a [bearer] token, so that a client, Swagger UI's "Try it out"
 * among them, sends the identity.
 *
 * - [bearer]: `Authorization: Bearer <token>`, the token's [format][bearerFormat] (`JWT`) a hint only;
 * - [header]: an API key in the request header [name].
 */
class OpenApiSecurityScheme private constructor(
    /** The OpenAPI `type`: `http` or `apiKey`. */
    val type: String,
    /** The HTTP authorization scheme of an `http` scheme: `bearer`. */
    val scheme: String? = null,
    /** The format of a bearer token, a hint only: `JWT`. */
    val bearerFormat: String? = null,
    /** The request header of an `apiKey` scheme. */
    val name: String? = null,
    val description: String? = null,
) {
    /** The scheme as the document's `components.securitySchemes` holds it. */
    internal fun write(): Map<String, Any?> = Json.obj(
        "type" to type,
        "description" to description,
        "scheme" to scheme,
        "bearerFormat" to bearerFormat,
        "name" to name,
        "in" to name?.let { HEADER },
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is OpenApiSecurityScheme && write() == other.write()

    override fun hashCode(): Int = write().hashCode()

    override fun toString(): String =
        "OpenApiSecurityScheme(type=$type, scheme=$scheme, bearerFormat=$bearerFormat, name=$name, " +
            "description=$description)"

    companion object {
        private const val HEADER = "header"

        /** `Authorization: Bearer <token>`, the token in [format] (`JWT`) when given. */
        fun bearer(format: String? = null, description: String? = null): OpenApiSecurityScheme =
            OpenApiSecurityScheme("http", scheme = "bearer", bearerFormat = format, description = description)

        /** An API key in the request header [name]. */
        fun header(name: String, description: String? = null): OpenApiSecurityScheme {
            require(name.isNotBlank()) { "A header security scheme names its header." }
            return OpenApiSecurityScheme("apiKey", name = name, description = description)
        }
    }
}
