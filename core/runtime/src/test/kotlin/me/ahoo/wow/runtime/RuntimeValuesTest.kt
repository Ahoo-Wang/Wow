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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.admission.TargetScope
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The runtime's options and its command request are plain classes (decision D216): they keep a data class's
 * equality, hash and string; a request is built through its factories.
 */
class RuntimeValuesTest {
    private val origin = Origin("node-1", "10.0.0.1:8081", "sales-service")
    private val body = CommandBody.of("""{"quantity":1}""")

    @Test
    fun `an option equals another with the same fields, and prints them`() {
        valueSemantics(SnapshotOptions(), SnapshotOptions(), SnapshotOptions(batch = 1))
        valueSemantics(ClusterOptions(), ClusterOptions(), ClusterOptions(leaseParallelism = 1))
        valueSemantics(JobOptions(), JobOptions(), JobOptions(enabled = false))
        valueSemantics(WaitOptions(origin), WaitOptions(origin), WaitOptions(origin, maxHops = 1))
        valueSemantics(TimerOptions(), TimerOptions(), TimerOptions(batch = 1))
        valueSemantics(StateViewOptions(), StateViewOptions(), StateViewOptions(capacity = 1))
        valueSemantics(SubscriptionOptions(), SubscriptionOptions(), SubscriptionOptions(maxInFlight = 1))
        valueSemantics(ParkedRetryOptions(), ParkedRetryOptions(), ParkedRetryOptions(scanInterval = 1.seconds))
        valueSemantics(AppendQueueOptions(), AppendQueueOptions(), AppendQueueOptions(enabled = false))
        valueSemantics(ServiceNodeOptions(), ServiceNodeOptions(), ServiceNodeOptions(laneCapacity = 1))
        val request = CommandRequest.of("sales", "order", "Place", body)
        valueSemantics(request, CommandRequest.of("sales", "order", "Place", body), request.copyWith(command = "Pay"))
    }

    @Test
    fun `Wow's own copy of an option replaces the fields it is given`() {
        WaitOptions(origin).copyWith(maxHops = 1).assert().isEqualTo(WaitOptions(origin, maxHops = 1))
        TimerOptions().copyWith(batch = 1).assert().isEqualTo(TimerOptions(batch = 1))
        SubscriptionOptions().copyWith(maxInFlight = 1).assert().isEqualTo(SubscriptionOptions(maxInFlight = 1))
        ServiceNodeOptions().copyWith(laneCapacity = 1).assert().isEqualTo(ServiceNodeOptions(laneCapacity = 1))
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

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
