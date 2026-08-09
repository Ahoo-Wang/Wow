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

package me.ahoo.wow.query.internal.execution

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.gateway.QueryShadowConfiguration
import me.ahoo.wow.query.gateway.QueryShadowObservation
import me.ahoo.wow.query.gateway.QueryShadowObserver
import me.ahoo.wow.query.gateway.QueryShadowOutcome
import me.ahoo.wow.query.internal.plan.PlanFingerprint
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)
class BoundedQueryShadowSupervisorTest {
    @Test
    fun `saturated probes should be rejected and capacity should be released after cancellation`() {
        val observations = CopyOnWriteArrayList<QueryShadowObservation>()
        val supervisor = BoundedQueryShadowSupervisor(
            QueryShadowConfiguration(
                maxConcurrentProbes = 1,
                maxComparedRecords = 10,
                probeTimeout = Duration.ofSeconds(1),
            ),
            QueryShadowObserver(observations::add),
        )
        val first = supervisor.submit(countTask(Mono.never())) as QueryShadowSubmission.Accepted

        val saturated = supervisor.submit(countTask(Mono.just(1))) as QueryShadowSubmission.Rejected
        saturated.issue.code.assert().isEqualTo(QueryRejectionCode.SHADOW_SUPERVISOR_SATURATED)
        observations.single().outcome.assert().isEqualTo(QueryShadowOutcome.SATURATED)

        first.handle.onPrimary(QueryShadowPrimarySignal.Cancelled)
        first.handle.cancelProbe()
        val accepted = supervisor.submit(countTask(Mono.just(3))) as QueryShadowSubmission.Accepted
        accepted.handle.onPrimary(QueryShadowPrimarySignal.CountValue(3))
        accepted.handle.onPrimary(QueryShadowPrimarySignal.Complete)

        observations.map(QueryShadowObservation::outcome).assert().containsExactly(
            QueryShadowOutcome.SATURATED,
            QueryShadowOutcome.CANCELLED,
            QueryShadowOutcome.MATCH,
        )
    }

    @Test
    fun `observer failure should remain isolated from comparison lifecycle`() {
        val supervisor = BoundedQueryShadowSupervisor(
            QueryShadowConfiguration(maxConcurrentProbes = 1),
            QueryShadowObserver { error("observer unavailable") },
        )

        val accepted = supervisor.submit(countTask(Mono.just(2))) as QueryShadowSubmission.Accepted
        accepted.handle.onPrimary(QueryShadowPrimarySignal.CountValue(2))
        accepted.handle.onPrimary(QueryShadowPrimarySignal.Complete)

        val next = supervisor.submit(countTask(Mono.just(2)))
        (next is QueryShadowSubmission.Accepted).assert().isTrue()
    }

    @Test
    fun `primary comparison should be bounded and cancel a slow probe exactly once`() {
        val observations = CopyOnWriteArrayList<QueryShadowObservation>()
        val supervisor = BoundedQueryShadowSupervisor(
            QueryShadowConfiguration(maxConcurrentProbes = 1, maxComparedRecords = 2),
            QueryShadowObserver(observations::add),
        )
        val accepted = supervisor.submit(streamTask(Flux.never())) as QueryShadowSubmission.Accepted

        repeat(100) { index ->
            accepted.handle.onPrimary(QueryShadowPrimarySignal.RecordValue(record("order-$index")))
        }
        accepted.handle.onPrimary(QueryShadowPrimarySignal.Complete)

        observations.assert().hasSize(1)
        observations.single().outcome.assert().isEqualTo(QueryShadowOutcome.PROBE_ERROR)
        observations.single().reasonCode.assert().isEqualTo(QueryRejectionCode.RESULT_LIMIT_EXCEEDED.name)
        (supervisor.submit(countTask(Mono.just(1))) is QueryShadowSubmission.Accepted).assert().isTrue()
    }

    private fun countTask(result: Mono<Long>): QueryShadowTask.Count = QueryShadowTask.Count(
        target = PlanningFixtures.target,
        fingerprint = PlanFingerprint("f".repeat(64)),
        semanticTier = SemanticTier.PORTABLE,
        publisher = result,
    )

    private fun streamTask(result: Flux<BackendRecord>): QueryShadowTask.Stream = QueryShadowTask.Stream(
        target = PlanningFixtures.target,
        fingerprint = PlanFingerprint("f".repeat(64)),
        semanticTier = SemanticTier.PORTABLE,
        publisher = result,
    )

    private fun record(identity: String): BackendRecord = BackendRecord(
        identity,
        me.ahoo.wow.query.backend.NormalizedValue.ObjectValue(emptyMap()),
        BackendRecordCompleteness.COMPLETE,
    )
}
