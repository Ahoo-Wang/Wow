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
 * How a caller of the public routes presents its identity, as the springdoc document declares it:
 * the document's security schemes, by name, and its global `security`, any one of them, so that Swagger UI's
 * "Try it out" sends the identity.
 *
 * The host fills it from its [CallerResolver] ([of]): CoSec's resolver takes a bearer token ([COSEC]); the host's
 * development callers take the `Wow-*` identity headers ([DEVELOPMENT]), each its own scheme, so Swagger UI's Authorize
 * sets those a request needs; the anonymous caller, and an application's own resolver, declare none. An application with a resolver of its own declares how it
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

        /**
         * The host's development callers' ([DevelopmentCallerResolver]): the identity headers, by their names, any
         * of them a request sends.
         */
        val DEVELOPMENT: WowOpenApiSecurity = WowOpenApiSecurity(
            listOf(
                DevelopmentCallerResolver.USER to "The caller's subject; without it, the anonymous caller.",
                DevelopmentCallerResolver.OWNER to "The owner of the caller's scope; without it, a tenant-level caller.",
                DevelopmentCallerResolver.ROLES to "The caller's roles, comma-separated.",
                DevelopmentCallerResolver.TENANT to "The caller's tenant; wow.tenant.default without it.",
                DevelopmentCallerResolver.APP to "The application the caller calls in.",
            ).associate { (header, description) -> header to OpenApiSecurityScheme.header(header, description) },
        )

        /** wow-cosec's resolver, by name: the host does not depend on wow-cosec. */
        internal const val COSEC_CALLER_RESOLVER = "me.ahoo.wow.cosec.CoSecCallerResolver"

        /**
         * The schemes of [callers]: [COSEC] for CoSec's resolver, [DEVELOPMENT] for the host's development callers,
         * [NONE] for any other.
         */
        fun of(callers: CallerResolver): WowOpenApiSecurity =
            if (callers is DevelopmentCallerResolver) DEVELOPMENT else ofType(callers.javaClass.name)

        /** The schemes of the resolver class [type]. */
        internal fun ofType(type: String): WowOpenApiSecurity = if (type == COSEC_CALLER_RESOLVER) COSEC else NONE
    }
}
