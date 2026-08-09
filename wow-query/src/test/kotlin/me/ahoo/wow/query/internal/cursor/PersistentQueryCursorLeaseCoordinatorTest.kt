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

package me.ahoo.wow.query.internal.cursor

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)
class PersistentQueryCursorLeaseCoordinatorTest {
    @Test
    fun `reaper should report only successfully closed leases and expose cleanup failure`() {
        val manager = mockk<PersistentQueryCursorLeaseManager>()
        every { manager.reapExpired(NOW, limit = 10) } returns Flux.just(envelope())
        val failures = CopyOnWriteArrayList<Throwable>()
        val coordinator = PersistentQueryCursorLeaseCoordinator(
            manager,
            listOf(PersistentQueryCursorBackendLeaseRegistration(TARGET, BACKEND) { Mono.error(FAILURE) }),
            QueryCursorLeaseObserver { _, _, error -> failures += error },
        )

        coordinator.reapExpired(NOW, 10).block().assert().isZero()
        failures.assert().containsExactly(FAILURE)
    }

    private fun envelope() = QueryCursorEnvelope(
        TARGET,
        PlanFingerprint("1".repeat(64)),
        QueryCursorMappingDigest("a".repeat(64)),
        QueryCursorSecurityContextDigest("c".repeat(64)),
        QueryCursorPosition.Analytics(
            listOf(AnalyticsAlias("status")),
            listOf(NormalizedValue.Text("PAID")),
        ),
        NOW.plusSeconds(60),
        QueryCursorBackendState(BACKEND, "pit".toByteArray()),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        val TARGET = QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT)
        val BACKEND = BackendId("elasticsearch")
        val FAILURE = IllegalStateException("close failed")
    }
}
