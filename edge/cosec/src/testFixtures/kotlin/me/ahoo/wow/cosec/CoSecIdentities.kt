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

package me.ahoo.wow.cosec

import me.ahoo.wow.spi.json.WowJson
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The `CoSec-Identity` header a CoSec gateway forwards once it has verified a principal's token and permitted the
 * request, for the tests of a service behind one (`wow.cosec.mode: gateway`), which believes the header as it comes.
 *
 * Written as CoSec documents the header, base64url without padding of its JSON, format version 1
 * ([Forwarding the identity](https://cosec.ahoo.me/integration/web#forwarding-the-identity),
 * `schema/cosec-identity.schema.json`): CoSec's own codec is its internal API, which only CoSec's modules use.
 */
object CoSecIdentities {
    /** The header's name. */
    const val HEADER: String = "CoSec-Identity"

    /** CoSec's default tenant, `(0)`, which a principal of no tenant is in. */
    const val DEFAULT_TENANT: String = "(0)"

    /**
     * The header of [subject] of [tenant] with [roles] in the scope [app] and [space] (empty for none and the default
     * space), its token valid for [ttl] from now.
     */
    @Suppress("LongParameterList")
    fun header(
        subject: String,
        roles: Collection<String> = emptyList(),
        tenant: String = DEFAULT_TENANT,
        app: String = "",
        space: String = "",
        ttl: Duration = Duration.ofHours(1),
    ): String {
        val now = Instant.now()
        val identity = linkedMapOf(
            "v" to 1,
            "tenant" to tenant,
            "sub" to subject,
            "scope" to linkedMapOf("app" to app, "space" to space),
            "roles" to roles.distinct(),
            "auth" to linkedMapOf(
                "method" to "jwt",
                "jti" to UUID.randomUUID().toString(),
                "exp" to now.plus(ttl).epochSecond,
            ),
            "iat" to now.epochSecond,
            "rid" to UUID.randomUUID().toString(),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(WowJson.mapper.writeValueAsBytes(identity))
    }

    /** The header of [header] as a request's headers. */
    @Suppress("LongParameterList")
    fun headers(
        subject: String,
        roles: Collection<String> = emptyList(),
        tenant: String = DEFAULT_TENANT,
        app: String = "",
        space: String = "",
        ttl: Duration = Duration.ofHours(1),
    ): Map<String, String> = mapOf(HEADER to header(subject, roles, tenant, app, space, ttl))
}
