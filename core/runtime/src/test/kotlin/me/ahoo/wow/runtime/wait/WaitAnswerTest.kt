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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a wait answers with in one service: the results the decide function put, at every hop after the commit
 * (decision D49), and a timeout at its deadline, whatever its caller's coroutine context (decision D64).
 */
class WaitAnswerTest {
    data class Place(val items: Int)
    data class PlaceSlowly(val items: Int)
    data class Placed(val items: Int)

    class Order(val id: String) {
        var items = 0
    }

    /** A [PlaceSlowly] is decided once it completes. */
    private val decideGate = CompletableDeferred<Unit>()

    private val order = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<Place>(Creation.ALLOWED) { _, body ->
            putResult(ITEMS, body.items)
            Decision.events(Placed(body.items))
        }
        decideSuspending<PlaceSlowly>(Creation.ALLOWED) { _, body ->
            decideGate.await()
            Decision.events(Placed(body.items))
        }
        evolve<Placed> { items = it.items }
    }

    private val log = InMemoryEventLog(partitions = 2)
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val signals = LocalSignalSender(origin.node, registry)
    private val sequence = AtomicInteger()

    /** While not null, the snapshot subscription holds every entry until it completes. */
    @Volatile
    private var snapshotGate: CompletableDeferred<Unit>? = null

    private val snapshot = Subscription("snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SNAPSHOT) {
        snapshotGate?.await()
    }
    private val runtime = SubscriptionRuntime(
        "shop",
        log,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(snapshot),
        testOptions(),
        signals = signals,
    )
    private val coordinator = WaitCoordinator(Executor(Kernel(log)), registry, runtime, signals, WaitOptions(origin))

    private fun <C : Any> command(body: C, wait: WaitPlan = WaitPlan.DEFAULT): Command<C> {
        val id = "o-${sequence.incrementAndGet()}"
        return Command("c-$id", "r-$id", order.type.ref("t", id), body, wait = wait)
    }

    @BeforeEach
    fun start(): Unit = runBlocking {
        runtime.start(setOf(0, 1))
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        snapshotGate?.complete(Unit)
        decideGate.complete(Unit)
        runtime.stop()
    }

    @Test
    fun `the default wait answers the results the decide function put, from the commit`(): Unit = runBlocking {
        val result = coordinator.execute(order, command(Place(3)))
        result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        result.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        result.results.assert().isEqualTo(mapOf(ITEMS to 3))
    }

    @Test
    fun `each streamed hop after the commit carries its results, and a COMMITTED wait answers them`(): Unit =
        runBlocking {
            val plan = WaitPlan.of(Hop.accepted(), Hop.committed(), Hop.snapshot())
            val hops = coordinator.stream(order, command(Place(2), plan)).toList()
            hops.map { it.results }.assert().containsExactly(emptyMap(), mapOf(ITEMS to 2), mapOf(ITEMS to 2))
            coordinator.execute(order, command(Place(4), WaitPlan.COMMITTED)).results
                .assert().isEqualTo(mapOf(ITEMS to 4))
        }

    @Test
    fun `a duplicate request answers no results, which are not persisted`(): Unit = runBlocking {
        val first = command(Place(6))
        coordinator.execute(order, first).results.assert().isEqualTo(mapOf(ITEMS to 6))
        val retry = coordinator.execute(order, first.copy(id = "c-retry"))
        retry.duplicate.assert().isTrue()
        retry.results.assert().isEmpty()
    }

    @Test
    fun `a timeout after the commit answers its results`(): Unit = runBlocking {
        snapshotGate = CompletableDeferred()
        val result = coordinator.execute(order, command(Place(5), WaitPlan.SNAPSHOT.within(200.milliseconds)))
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.error!!.message.assert().isEqualTo("The wait timed out after reaching committed(shop).")
        result.results.assert().isEqualTo(mapOf(ITEMS to 5))
    }

    @Test
    fun `a wait ends at its deadline even when its caller's timers never fire`(): Unit = runBlocking {
        snapshotGate = CompletableDeferred()
        // The caller's own timers never fire, as for a WebFlux handler (Dispatchers.Unconfined) whose timers run on
        // kotlinx's single DefaultExecutor thread when that thread is held up: the deadline is the wait's, not theirs.
        val result = withTimeout(5.seconds) {
            withContext(StalledTimers) {
                coordinator.execute(order, command(Place(2), WaitPlan.SNAPSHOT.within(300.milliseconds)))
            }
        }
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.results.assert().isEqualTo(mapOf(ITEMS to 2))
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `an execution that outlasts the deadline ends the wait at ACCEPTED even when the caller's timers never fire`():
        Unit = runBlocking {
        val result = withTimeout(5.seconds) {
            withContext(StalledTimers) {
                coordinator.execute(order, command(PlaceSlowly(1), WaitPlan.COMMITTED.within(300.milliseconds)))
            }
        }
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.assert().isEqualTo(Hop.accepted("shop"))
        registry.size.assert().isEqualTo(0)
    }

    private companion object {
        const val ITEMS = "items"
    }
}

/**
 * Runs on [Dispatchers.Default], but never fires a timer: what a caller sees when the thread its timers run on is held
 * up.
 */
@OptIn(InternalCoroutinesApi::class)
private object StalledTimers : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Dispatchers.Default.dispatch(context, block)

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) = Unit

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        DisposableHandle { }
}
