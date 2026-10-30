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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The analytics catalog as it is deployed: outside development, behind the CoSec gateway (the `cosec` profile), without
 * Kafka (decision D322). Every aggregate of it is published by default (D156), which needs no topic transport, so it
 * starts and publishes none of its streams.
 */
class AnalyticsCatalogDeployedTest {
    @Test
    fun `the analytics catalog should start outside development without a topic transport`() {
        startCatalog(*DEPLOYED, profiles = listOf("cosec")).use { node ->
            node.context.isActive.assert().isTrue()
        }
    }

    @Test
    fun `a principal with none of the catalog's roles should be refused its reads as forbidden (UI-35)`() {
        startCatalog(*DEPLOYED, profiles = listOf("cosec")).use { node ->
            listOf(signedIn("nobody"), signedIn("stranger", "someone.else")).forEach { reader ->
                node.sources(reader).assertProblem(FORBIDDEN, "wow.auth.forbidden").json["detail"].asString().assert()
                    .contains("reads no source")
            }
            node.sources(signedIn("vic", AnalyticsCatalogRoles.VIEWER)).assertOk()
        }
    }

    /**
     * [id] of CoSec's default tenant with [roles], as the CoSec gateway forwards it after verifying its credential: the
     * service holds no secret and verifies nothing, it believes the header.
     */
    private fun signedIn(id: String, vararg roles: String): Who {
        val now = Instant.now()
        val identity = Identity(
            tenant = "(0)",
            subject = id,
            app = "",
            space = "",
            roles = roles.toSet(),
            credential = Identity.Credential(Identity.Method.JWT, "token-of-$id", now + Duration.ofHours(1)),
            issuedAt = now,
            requestId = "request-of-$id",
        )
        return Who(mapOf(IdentityCodec.HEADER to IdentityCodec.encode(identity)))
    }

    private companion object {
        const val FORBIDDEN = 403

        /** Bound to every interface, as a pod is; in memory, so the backend is not what it checks. */
        val DEPLOYED = arrayOf<Pair<String, Any>>(
            "wow.internal.host" to "0.0.0.0",
            "wow.backend" to "memory",
        )
    }
}
