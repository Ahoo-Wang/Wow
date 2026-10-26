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

package me.ahoo.wow.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.LedgerAggregate
import me.ahoo.wow.spi.ledger.LedgerResolution
import me.ahoo.wow.spi.ledger.LedgerRetrySpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The runtime's options and its command request are plain classes (decision D216): they keep a data class's
 * equality, hash and string; a request is built through its factories.
 */
class RuntimeValuesTest {
    private val body = CommandBody.of("""{"quantity":1}""")

    @Test
    fun `an option equals another with the same fields, and prints them`() {
        valueSemantics(SnapshotOptions(), SnapshotOptions(), SnapshotOptions(batch = 1))
        valueSemantics(ClusterOptions(), ClusterOptions(), ClusterOptions(leaseParallelism = 1))
        valueSemantics(JobOptions(), JobOptions(), JobOptions(enabled = false))
        valueSemantics(WaitOptions(), WaitOptions(), WaitOptions(maxHops = 1))
        valueSemantics(TimerOptions(), TimerOptions(), TimerOptions(batch = 1))
        valueSemantics(StateViewOptions(), StateViewOptions(), StateViewOptions(capacity = 1))
        valueSemantics(SubscriptionOptions(), SubscriptionOptions(), SubscriptionOptions(maxInFlight = 1))
        valueSemantics(ParkedRetryOptions(), ParkedRetryOptions(), ParkedRetryOptions(scanInterval = 1.seconds))
        valueSemantics(AppendQueueOptions(), AppendQueueOptions(), AppendQueueOptions(maxBatch = 1))
        valueSemantics(ExecutorOptions(), ExecutorOptions(), ExecutorOptions(laneCapacity = 1))
        valueSemantics(StateCacheOptions(), StateCacheOptions(), StateCacheOptions(capacity = 1))
        valueSemantics(
            ServiceNodeOptions(),
            ServiceNodeOptions(),
            ServiceNodeOptions(executor = ExecutorOptions(maxBatch = 1)),
        )
        val request = CommandRequest.of("sales", "order", "Place", body)
        valueSemantics(request, CommandRequest.of("sales", "order", "Place", body), request.copyWith(command = "Pay"))
    }

    @Test
    fun `Wow's own copy of an option replaces the fields it is given`() {
        WaitOptions().copyWith(maxHops = 1).assert().isEqualTo(WaitOptions(maxHops = 1))
        TimerOptions().copyWith(batch = 1).assert().isEqualTo(TimerOptions(batch = 1))
        SubscriptionOptions().copyWith(maxInFlight = 1).assert().isEqualTo(SubscriptionOptions(maxInFlight = 1))
        val executor = ExecutorOptions(laneCapacity = 1)
        ServiceNodeOptions().copyWith(executor = executor).assert().isEqualTo(ServiceNodeOptions(executor = executor))
        val stateCache = StateCacheOptions(recentRequests = 0)
        ServiceNodeOptions().copyWith(stateCache = stateCache).assert()
            .isEqualTo(ServiceNodeOptions(stateCache = stateCache))
        ExecutorOptions().copyWith(maxBatch = 1).assert().isEqualTo(ExecutorOptions(maxBatch = 1))
        StateCacheOptions().copyWith(capacity = 0).assert().isEqualTo(StateCacheOptions(capacity = 0))
        StateCacheOptions().recentRequests.assert().isEqualTo(Kernel.DEFAULT_RECENT_REQUESTS)
        ExecutorOptions().maxBatch.assert().isEqualTo(Executor.DEFAULT_MAX_BATCH)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { StateCacheOptions(recentRequests = -1) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { StateCacheOptions(capacity = -1) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ExecutorOptions(maxBatch = 0) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ExecutorOptions(laneCapacity = 0) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ExecutorOptions(maxInFlight = 0) }
    }

    @Test
    fun `a request is built with the facts it is given, the others left at their defaults`() {
        val plain = CommandRequest.builder("sales", "order", "Place", body).build()
        plain.assert().isEqualTo(CommandRequest.of("sales", "order", "Place", body))
        plain.caller.assert().isEqualTo(Caller.ANONYMOUS)
        plain.wait.assert().isNull()
        val caller = Caller(subject = "alice")
        val target = TargetScope(tenantId = "t-2")
        val request = CommandRequest.builder("sales", "order", "Place", body)
            .caller(caller)
            .aggregateId("o-1")
            .target(target)
            .requestId("r-1")
            .expectedVersion(3)
            .headers(mapOf("app" to "web"))
            .wait(WaitPlan.COMMITTED)
            .trace(mapOf("traceparent" to "00-1-2-01"))
            .build()
        request.assert().isEqualTo(
            CommandRequest(
                context = "sales",
                aggregate = "order",
                command = "Place",
                body = body,
                caller = caller,
                aggregateId = "o-1",
                target = target,
                requestId = "r-1",
                expectedVersion = 3,
                headers = mapOf("app" to "web"),
                wait = WaitPlan.COMMITTED,
                trace = mapOf("traceparent" to "00-1-2-01"),
            ),
        )
        request.call.assert().isNull()
    }

    @Test
    fun `a call context is a value built from its factory`() {
        val causation = Causation("root-1", "OrderSaga.onPlaced")
        val call = CallContext.of("call-1", "t-1", Scope("alice"), causation)
        valueSemantics(
            call,
            CallContext("call-1", "t-1", Scope("alice"), causation),
            CallContext.of("call-1", "t-1", Scope("alice"), causation, Operator("alice", "t-1")),
        )
        call.toString().assert().isEqualTo(
            "CallContext(commandId=call-1, tenantId=t-1, scope=${Scope("alice")}, causation=$causation, " +
                "operator=null, wait=null, facts={})",
        )
        call.wait.assert().isNull()
        // Only the facts that propagate travel with a call (decision D236).
        CallContext.of(
            "call-1",
            "t-1",
            Scope("alice"),
            causation,
            facts = mapOf("app_id" to "console", "remote_ip" to "10.0.0.1", "user_agent" to "curl", "X-Trace" to "t"),
        ).facts.assert().isEqualTo(mapOf("app_id" to "console"))
        valueSemantics(
            CallContext.of("call-1", "t-1", Scope("alice"), causation, facts = mapOf("app_id" to "console")),
            CallContext.of("call-1", "t-1", Scope("alice"), causation, facts = mapOf("app_id" to "console")),
            call,
        )
    }

    @Test
    fun `a context's backends are values built from their factory, and copied by Wow`() {
        val log = InMemoryEventLog(2)
        val checkpoints = InMemoryCheckpointStore()
        val leases = InMemoryLeaseStore()
        val ledger = InMemoryFailureLedger()
        val backends = ContextBackends.of(log, checkpoints, leases, ledger)
        backends.store.assert().isEqualTo(ContextBackends.DEFAULT_STORE)
        val invoices = setOf(AggregateType("sales", "invoice"))
        val group = LogGroupBackends("other", invoices, InMemoryEventLog(2), checkpoints, ledger)
        valueSemantics(
            backends,
            ContextBackends(log, checkpoints, leases, ledger),
            backends.copyWith(groups = listOf(group)),
        )
        backends.toString().assert().isEqualTo(
            "ContextBackends(log=$log, checkpoints=$checkpoints, leases=$leases, ledger=$ledger, snapshots=null, " +
                "reservations=null, timers=null, store=default, groups=[])",
        )
        backends.copyWith(store = "main").store.assert().isEqualTo("main")
        valueSemantics(
            group,
            LogGroupBackends("other", invoices, group.log, checkpoints, ledger),
            group.copyWith(store = "third"),
        )
        group.toString().assert().isEqualTo(
            "LogGroupBackends(store=other, types=${group.types}, log=${group.log}, checkpoints=$checkpoints, " +
                "ledger=$ledger, timers=null)",
        )
    }

    /** The JSON of the records that were data classes before decision D216, as the data classes wrote it. */
    private data class RetrySpecBefore(
        val maxRetries: Int,
        val minBackoff: String,
        val executionTimeout: String,
        val applied: Boolean,
    )

    private data class ResolutionBefore(
        val service: String,
        val context: String,
        val subscription: String,
        val aggregate: LedgerAggregate,
        val failedAt: Long?,
        val outcome: String,
        val resolvedAt: Long,
    )

    private data class TransformedFromBefore(val command: String, val aggregateId: String?)

    @Test
    fun `the records keep their JSON`() {
        val spec = LedgerRetrySpec.of(3, "PT1M", "PT2M", true)
        sameJson(spec, RetrySpecBefore(3, "PT1M", "PT2M", true))
        val aggregate = LedgerAggregate("sales", "order", "t-1", "o-1")
        val resolution = LedgerResolution.of("svc", "sales", "s", aggregate, null, LedgerResolution.SKIPPED, 9)
        sameJson(resolution, ResolutionBefore("svc", "sales", "s", aggregate, null, LedgerResolution.SKIPPED, 9))
        val transformed = TransformedFrom.of("sales.order.Import", "o-1")
        sameJson(transformed, TransformedFromBefore("sales.order.Import", "o-1"))
    }

    private fun sameJson(value: Any, before: Any) {
        val json = WowJson.mapper.writeValueAsString(value)
        json.assert().isEqualTo(WowJson.mapper.writeValueAsString(before))
        WowJson.mapper.readValue(json, value.javaClass).assert().isEqualTo(value)
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
