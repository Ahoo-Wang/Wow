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

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.spi.json.WowJson
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The identity a CoSec gateway forwards in `CoSec-Identity` once it has verified a person's token and permitted the
 * request: what a local client or an end-to-end test sends a service behind one (`wow.cosec.mode: gateway`), which
 * believes the header as it comes. [subject] of [tenant] with [roles] in the scope [app] and [space] (empty for none
 * and the default space), its token valid for [ttl] from the moment the header is written. The credential is a
 * person's (`auth.kind` `person`, CoSec 8.0.0-beta.9's `IDN-44`), as a user's sign-in is: a header without the member
 * is read as a workload's.
 *
 * ```kotlin
 * val headers = CoSecIdentity("alice", listOf("buyer")).headers() // {"CoSec-Identity": "eyJ2Ijox…"}
 * ```
 *
 * Written as CoSec documents the header, base64url without padding of its JSON, format version 1
 * ([Forwarding the identity](https://cosec.ahoo.me/integration/web#forwarding-the-identity),
 * `schema/cosec-identity.schema.json`): CoSec's own codec is its internal API, which only CoSec's modules use. The
 * services' tests that run behind CoSec's gateway mode read it through CoSec's own pipeline, so a change of the format
 * fails them. It is a test's and a developer's tool, never a gateway's: a service believes whoever sends it.
 */
@ExperimentalWowApi
class CoSecIdentity(
    val subject: String,
    val roles: Collection<String> = emptyList(),
    val tenant: String = DEFAULT_TENANT,
    val app: String = "",
    val space: String = "",
    val ttl: Duration = Duration.ofHours(1),
) {
    /** The header's value, its token valid for [ttl] from now. */
    fun header(): String {
        val now = Instant.now()
        val identity = linkedMapOf(
            "v" to 1,
            "tenant" to tenant,
            "sub" to subject,
            "scope" to linkedMapOf("app" to app, "space" to space),
            "roles" to roles.distinct(),
            "auth" to linkedMapOf(
                "method" to "jwt",
                "kind" to "person",
                "jti" to UUID.randomUUID().toString(),
                "exp" to now.plus(ttl).epochSecond,
            ),
            "iat" to now.epochSecond,
            "rid" to UUID.randomUUID().toString(),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(WowJson.mapper.writeValueAsBytes(identity))
    }

    /** The header as a request's headers. */
    fun headers(): Map<String, String> = mapOf(HEADER to header())

    companion object {
        /** The header's name. */
        const val HEADER: String = "CoSec-Identity"

        /** CoSec's default tenant, `(0)`, which a principal of no tenant is in. */
        const val DEFAULT_TENANT: String = "(0)"
    }
}
