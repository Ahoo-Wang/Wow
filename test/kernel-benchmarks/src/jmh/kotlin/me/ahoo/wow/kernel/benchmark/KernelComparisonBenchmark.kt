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

package me.ahoo.wow.kernel.benchmark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Spike H3 (documentation/designs/v10/decisions.md): does the coroutine kernel cost more than the 9.3 Reactor
 * kernel? Both process one AddCartItem on a new cart with the same decide logic, against a store that accepts every
 * append and holds nothing, so only the kernel itself is measured.
 *
 * The 9.3 side is wow-benchmarks' `AggregateHandleComponentBenchmark.processCommandAggregate`. The V10 side also
 * deduplicates by request id and serializes the events to the log's format, which 9.3 leaves to the store and the
 * bus; [v10SerializationFreeShare] isolates that cost.
 */
@State(Scope.Thread)
open class KernelComparisonBenchmark {
    private val commandAggregateFactory = SimpleCommandAggregateFactory(NoopEventStore)
    private val kernel = Kernel(NoopLog)
    private val ids = AtomicLong()

    @Benchmark
    fun v93ProcessCommand(blackhole: Blackhole) {
        val commandMessage = BenchmarkCommands.commandPathAddCartItem()
        val stateAggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            commandMessage.aggregateId,
        )
        val commandAggregate = commandAggregateFactory.create(BenchmarkAggregates.cartMetadata, stateAggregate)
        blackhole.consume(commandAggregate.process(SimpleServerCommandExchange(commandMessage)).block())
    }

    /** What [v93ProcessCommand] spends building its command message, to compare the kernels alone. */
    @Benchmark
    fun v93BuildCommandOnly(blackhole: Blackhole) {
        blackhole.consume(BenchmarkCommands.commandPathAddCartItem())
    }

    @Benchmark
    fun v10ExecuteCommand(blackhole: Blackhole) {
        val id = ids.incrementAndGet().toString()
        val command = Command(id = id, requestId = id, ref = cart.type.ref("tenant", id), body = AddCartItem("productId"))
        blackhole.consume(runWithoutSuspending { kernel.execute(cart, command) })
    }

    @Benchmark
    fun v10SerializationFreeShare(blackhole: Blackhole) {
        blackhole.consume(serializer.encode(cart, CartItemAdded(CartItem("productId", 1))))
    }

    private val serializer = me.ahoo.wow.kernel.EventCodec()

    class CartState(val id: String) {
        var items: List<CartItem> = listOf()
    }

    private companion object {
        const val MAX_CART_ITEM_SIZE = 100

        /** The example cart's AddCartItem logic (example-domain `Cart.onCommand`), in the V10 DSL. */
        val cart = aggregate<CartState>("example-service", "cart") {
            initial { CartState(it.id) }
            decide<AddCartItem>(Creation.REQUIRED) { state, command ->
                require(state.items.size < MAX_CART_ITEM_SIZE) { "At most $MAX_CART_ITEM_SIZE items." }
                val existing = state.items.firstOrNull { it.productId == command.productId }
                if (existing != null) {
                    Decision.events(CartQuantityChanged(existing.copy(quantity = existing.quantity + command.quantity)))
                } else {
                    Decision.events(CartItemAdded(CartItem(command.productId, command.quantity)))
                }
            }
            evolve<CartItemAdded> { items = items + it.added }
            evolve<CartQuantityChanged> { changed -> items = items.map { if (it.productId == changed.changed.productId) changed.changed else it } }
        }

        /**
         * Runs [block] on the calling thread. With [NoopLog] nothing suspends, so the coroutine completes before
         * `startCoroutine` returns: the measurement holds the kernel's own continuations and nothing else.
         */
        fun <T> runWithoutSuspending(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            return checkNotNull(outcome) { "The kernel suspended." }.getOrThrow()
        }
    }

    /** A log that holds nothing and accepts every append. */
    private object NoopLog : EventLog {
        private val position = LogPosition(0, 1)
        override val partitions: Int = 1

        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
            AppendOutcome.Committed(position)

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = emptyFlow()

        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = emptyFlow()

        override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = null

        override suspend fun findByRequestId(
            type: AggregateType,
            tenantId: String,
            requestId: String,
        ): EventStreamRecord? = null

        override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? = null

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = emptyFlow()

        override suspend fun head(partition: Int): Long? = null
    }
}
