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

package me.ahoo.wow.runtime.wait

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A subscription on a wait chain that parks the aggregate fails the wait with the code and where it parked, never the
 * handler's exception (review A5-7): its class and message stay in the failure ledger and the logs.
 */
class ParkedSignalTest {
    data class Place(val items: Int = 1)
    data class Placed(val items: Int)

    private val order = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, body -> Decision.events(Placed(body.items)) }
        evolve<Placed> { }
    }
    private val log = InMemoryEventLog(partitions = 2)
    private val ledger = InMemoryFailureLedger()
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val signals = LocalSignalSender(origin.node, registry)
    private val failing = Subscription("order.failing", SubscriptionFilter("shop", setOf("order"))) {
        error("jdbc:postgresql://10.0.3.5:5432 refused ${it.ref}")
    }
    private val runtime = SubscriptionRuntime(
        "shop",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(failing),
        testOptions(),
        signals = signals,
    )
    private val coordinator = WaitCoordinator(Executor(Kernel(log)), registry, runtime, signals, WaitOptions(origin))

    @AfterEach
    fun stop(): Unit = runBlocking {
        runtime.stop()
    }

    @Test
    fun `a parked subscription's signal carries a generic message, the cause staying in the ledger`(): Unit =
        runBlocking {
            runtime.start(setOf(0, 1))
            val command = Command(
                "c-1",
                "r-1",
                order.type.ref("t", "o-1"),
                Place(),
                wait = WaitPlan.of(Hop.handled("order.failing")),
            )
            val result = withTimeout(5.seconds) { coordinator.execute(order, command) }
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            result.error!!.message.assert()
                .isEqualTo("${command.ref}@1 is parked by order.failing; the failure ledger has the cause.")
            result.error!!.message.assert().doesNotContain("10.0.3.5")
            val parked = ledger.parked("order.failing", command.ref)!!
            parked.reason.assert().contains("IllegalStateException").contains("10.0.3.5")
        }
}
