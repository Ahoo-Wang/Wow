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

package me.ahoo.wow.benchmark.e2e

import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.DiscardingDistributedCommandBus
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.messaging.shouldLocalFirst
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Command send through the gateway with a started command dispatcher, on a plain in-memory bus versus a
 * [LocalFirstCommandBus] in front of the same in-memory bus (its distributed bus drops messages, so no broker cost is
 * included). `sendAndWaitSent` shows the sender-side cost of local-first: the distributed copy is sent only after the
 * local dispatcher admits the message. `sendAndWaitProcessed` is the full write path on the ceiling scenario.
 *
 * `sendAndWaitSent` does not wait for processing, so a sender faster than the dispatcher only grows the backlog of
 * sent but unprocessed commands. The auxiliary counters make that visible: `processed` is the processing rate (the
 * event store appends), `backlog` the sent-but-unprocessed commands at the end of the iteration, `oldGenAfterGcMb` the
 * old generation after the last collection. `sendAndWaitSentBounded` is the sustainable sent rate: the sender waits
 * while the backlog exceeds [BOUNDED_BACKLOG].
 *
 * Audit 9.3.0 B §F6 (design WP G2).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class LocalFirstCommandSendE2EBenchmark {
    @Param("in-memory", "local-first")
    lateinit var bus: String

    private lateinit var scenario: CommandDispatcherScenario
    private var discardingBus: DiscardingDistributedCommandBus? = null
    private val failures = AtomicInteger()
    private val sent = AtomicLong()
    private val processed = AtomicLong()

    /** The no-op event store, counting appends: one per processed command. */
    private val countingEventStore = object : EventStore by NoopEventStore {
        override fun append(eventStream: DomainEventStream): Mono<Void> {
            processed.incrementAndGet()
            return NoopEventStore.append(eventStream)
        }
    }

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        sent.set(0)
        processed.set(0)
        val commandBus: CommandBus = when (bus) {
            "in-memory" -> InMemoryCommandBus()
            "local-first" -> LocalFirstCommandBus(
                distributedBus = DiscardingDistributedCommandBus().also { discardingBus = it },
                localBus = InMemoryCommandBus(),
            )

            else -> error("Unsupported bus: $bus")
        }
        scenario = CommandDispatcherScenario.create(
            commandBus = commandBus,
            eventStore = countingEventStore,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            // Idempotency is off here, so the processing node does not check request IDs either (as in Spring).
            processingRequestIdChecker = null,
            validator = NoOpValidator,
        )
        if (bus == "local-first") {
            check(BenchmarkCommands.commandPathAddCartItem().shouldLocalFirst()) {
                "Benchmark aggregate must be local for local-first routing."
            }
            scenario.commandGateway.sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem()).block()
            // Since 9.3 (X5) the distributed copy is sent asynchronously after the local hand-off: wait for it.
            val copySent = System.nanoTime() + COPY_TIMEOUT_NANOS
            while (checkNotNull(discardingBus).sent.sum() < 1L && System.nanoTime() < copySent) {
                Thread.onSpinWait()
            }
            check(checkNotNull(discardingBus).sent.sum() == 1L) { "Local-first must still send the distributed copy." }
            processed.set(0)
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Local-first command send [$bus] recorded $failureCount failure(s).",
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Benchmark
    fun sendAndWaitSent(blackhole: Blackhole, rate: ProcessedRate, end: EndOfIteration) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForSent(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
        sent.incrementAndGet()
        rate.record(processed.get())
        end.record(sent.get(), processed.get())
    }

    /** The sustainable sent rate: the sender waits while more than [BOUNDED_BACKLOG] commands are unprocessed. */
    @Benchmark
    fun sendAndWaitSentBounded(blackhole: Blackhole, rate: ProcessedRate, end: EndOfIteration) {
        while (sent.get() - processed.get() > BOUNDED_BACKLOG) {
            Thread.onSpinWait()
        }
        sendAndWaitSent(blackhole, rate, end)
    }

    /** `processed`: this thread's share of the commands processed in the iteration, reported as a rate. */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    open class ProcessedRate {
        @JvmField
        var processed: Long = 0

        private var threads: Int = 1
        private var processedAtStart: Long = -1

        @Setup(Level.Iteration)
        fun setup(params: BenchmarkParams) {
            threads = params.threads
            processed = 0
            processedAtStart = -1
        }

        internal fun record(processedNow: Long) {
            if (processedAtStart < 0) {
                processedAtStart = processedNow
            }
            processed = (processedNow - processedAtStart) / threads
        }
    }

    /**
     * End-of-iteration totals (this thread's share; JMH sums the threads): `backlog`, the commands sent but not yet
     * processed, and `oldGenAfterGcMb`, the old generation after the last collection (sampled every 4096 sends).
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    open class EndOfIteration {
        @JvmField
        var backlog: Long = 0

        @JvmField
        var oldGenAfterGcMb: Long = 0

        private var threads: Int = 1
        private var calls: Long = 0

        @Setup(Level.Iteration)
        fun setup(params: BenchmarkParams) {
            threads = params.threads
            backlog = 0
            oldGenAfterGcMb = 0
        }

        internal fun record(sent: Long, processed: Long) {
            backlog = (sent - processed) / threads
            if (calls++ % OLD_GEN_SAMPLE_EVERY == 0L) {
                oldGenAfterGcMb = ManagementFactory.getMemoryPoolMXBeans()
                    .filter { it.name.contains("Old Gen") }
                    .sumOf { it.collectionUsage?.used ?: 0L } / MB / threads
            }
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
    }

    private companion object {
        /** The unprocessed commands `sendAndWaitSentBounded` allows before its sender waits. */
        const val BOUNDED_BACKLOG = 1024L

        /** How long setup waits for the asynchronous distributed copy of its warm-up command. */
        const val COPY_TIMEOUT_NANOS = 5_000_000_000L
    }
}

// Outside the counter classes: JMH treats every public field of an @AuxCounters state as a counter.
private const val MB = 1024L * 1024L
private const val OLD_GEN_SAMPLE_EVERY = 4096L
