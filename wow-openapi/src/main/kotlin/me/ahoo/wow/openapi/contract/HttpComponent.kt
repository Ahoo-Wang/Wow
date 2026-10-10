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

package me.ahoo.wow.openapi.contract

import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.openapi.context.HttpComponentContext

/**
 * A reusable OpenAPI component (parameter, header, request body or response) that route contracts reference by
 * [key]. A contract only holds the reference: nothing is built until the document is rendered. Create one with
 * [parameter], [header], [requestBody] or [response].
 *
 * The renderer builds each component once, with the builder given here, and registers it under [key]; every
 * reference in the document is then a `$ref` to [key] (or the component itself when the context inlines schemas).
 *
 * Keys are unique within a document: two components are equal when their keys are equal, and the renderer rejects
 * two different components that share a key.
 */
class HttpComponent<T : Any> private constructor(
    val key: String,
    internal val kind: Kind,
    private val register: HttpComponentContext.() -> T
) {
    /** Which components section the component goes in. */
    internal enum class Kind {
        PARAMETER,
        HEADER,
        REQUEST_BODY,
        RESPONSE
    }

    init {
        require(key.isNotBlank()) {
            "key must not be blank"
        }
    }

    /**
     * Builds the component with [context] and registers it under [key]. Returns a `$ref` to it, or the component
     * itself when the context inlines schemas. Called by the renderer.
     */
    fun build(context: HttpComponentContext): T = context.register()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpComponent<*>) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "HttpComponent(key=$key)"

    companion object {
        fun parameter(key: String, builder: Parameter.(HttpComponentContext) -> Unit): HttpComponent<Parameter> =
            HttpComponent(key, Kind.PARAMETER) {
                val context = this
                context.parameter(key) { builder(context) }
            }

        fun header(key: String, builder: Header.(HttpComponentContext) -> Unit): HttpComponent<Header> =
            HttpComponent(key, Kind.HEADER) {
                val context = this
                context.header(key) { builder(context) }
            }

        fun requestBody(
            key: String,
            builder: RequestBodyBuilder.(HttpComponentContext) -> Unit
        ): HttpComponent<RequestBody> = HttpComponent(key, Kind.REQUEST_BODY) {
            val context = this
            context.requestBody(key) { builder(context) }
        }

        fun response(
            key: String,
            builder: ApiResponseBuilder.(HttpComponentContext) -> Unit
        ): HttpComponent<ApiResponse> = HttpComponent(key, Kind.RESPONSE) {
            val context = this
            context.response(key) { builder(context) }
        }
    }
}
