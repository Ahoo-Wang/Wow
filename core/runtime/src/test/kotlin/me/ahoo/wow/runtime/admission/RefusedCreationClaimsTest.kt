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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.CreationClaim
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Review r1 (cluster, routing and node): a creation that names no aggregate, under a generator that cannot aim (a
 * domain's own, CosId's), is claimed in the log ([CommittedRequests.claim], [me.ahoo.wow.spi.log.EventLog.claimCreation])
 * at step 2 of admission, before step 3 authorizes the caller and step 4 decodes the body. A claim is durable and never
 * released (its KDoc), so every request a caller is refused, or sends with a body that does not decode, leaves a
 * document in the store; a caller who may create nothing grows the claims without bound, one per request id.
 */
class RefusedCreationClaimsTest : CommandGatewayFixtures() {
    /** What admission asked of the log: the lookups by request id and the claims written. */
    private val lookups = CopyOnWriteArrayList<String>()
    private val claims = CopyOnWriteArrayList<String>()

    private val recording = object : CommittedRequests {
        override suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? {
            lookups += requestId
            return null
        }

        override suspend fun claim(
            type: AggregateType,
            tenantId: String,
            requestId: String,
            claim: CreationClaim,
        ): CreationClaim {
            claims += requestId
            return claim
        }

        override fun partitions(type: AggregateType): Int = 4
    }

    private var drawn = 0

    /** A generator that cannot aim at a partition, so a creation without an id is claimed. */
    private val drawing = IdGenerators(aggregates = AggregateIdGenerator { "${it.name}-${++drawn}" })

    private val denying = CommandAuthorizer { _, _ -> Authorization.Denied.of("Nobody opens a cart here.") }

    @Test
    fun `a creation its caller may not send claims nothing`(): Unit = runBlocking {
        val gateway = gateway(authorizer = denying, ids = drawing, committed = recording)
        repeat(3) { attempt ->
            gateway.execute(request("OpenCart", body = "{}", aggregateId = null, requestId = "denied-$attempt"))
                .assertRefused(FrameworkCodes.AUTH_FORBIDDEN)
        }
        // Refused before anything of it ran: the log holds nothing for these requests.
        claims.assert().isEmpty()
        lookups.assert().isEmpty()
    }

    @Test
    fun `a creation whose body does not decode claims nothing`(): Unit = runBlocking {
        val gateway = gateway(ids = drawing, committed = recording)
        gateway.execute(request("OpenCart", body = """{"note":""", aggregateId = null, requestId = "malformed"))
            .assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        claims.assert().isEmpty()
    }
}
