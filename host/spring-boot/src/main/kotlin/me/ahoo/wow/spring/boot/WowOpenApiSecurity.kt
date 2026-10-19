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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.openapi.OpenApiSecurityScheme

/**
 * How a caller of the public routes presents its identity, as the springdoc document declares it (decision D233 ③):
 * the document's security schemes, by name, and its global `security`, any one of them, so that Swagger UI's
 * "Try it out" sends the identity.
 *
 * The host fills it from its [CallerResolver] ([of]): CoSec's resolver takes a bearer token ([COSEC]); the anonymous
 * caller, and an application's own resolver, declare none. An application with a resolver of its own declares how it
 * authenticates by a bean of this type, which replaces the host's.
 */
class WowOpenApiSecurity(val schemes: Map<String, OpenApiSecurityScheme>) {
    override fun equals(
        other: Any?
    ): Boolean = this === other || other is WowOpenApiSecurity && schemes == other.schemes

    override fun hashCode(): Int = schemes.hashCode()

    override fun toString(): String = "WowOpenApiSecurity(schemes=$schemes)"

    companion object {
        /** No security scheme: the anonymous caller's routes. */
        val NONE: WowOpenApiSecurity = WowOpenApiSecurity(emptyMap())

        /** The scheme name of CoSec's bearer token. */
        const val BEARER: String = "bearer"

        /** CoSec's: its access token, a JWT, as `Authorization: Bearer`. */
        val COSEC: WowOpenApiSecurity = WowOpenApiSecurity(
            mapOf(BEARER to OpenApiSecurityScheme.bearer("JWT", "CoSec's access token.")),
        )

        /** wow-cosec's resolver, by name: the host does not depend on wow-cosec. */
        internal const val COSEC_CALLER_RESOLVER = "me.ahoo.wow.cosec.CoSecCallerResolver"

        /** The schemes of [callers]: [COSEC] for CoSec's resolver, [NONE] for any other. */
        fun of(callers: CallerResolver): WowOpenApiSecurity = ofType(callers.javaClass.name)

        /** The schemes of the resolver class [type]. */
        internal fun ofType(type: String): WowOpenApiSecurity = if (type == COSEC_CALLER_RESOLVER) COSEC else NONE
    }
}
