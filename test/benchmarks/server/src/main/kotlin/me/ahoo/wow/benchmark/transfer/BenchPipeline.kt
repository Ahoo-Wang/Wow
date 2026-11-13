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

package me.ahoo.wow.benchmark.transfer

import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandSucceededEvent
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoPoolGauge
import me.ahoo.wow.runtime.CallClock
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.snapshot.SnapshotWriteCounts
import me.ahoo.wow.spi.store.HeldStoreCallMarks
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The saga pipeline's depths and the append queue's calls, logged once a second as `bench-pipeline {json}` at WARN,
 * only with `bench.pipeline.enabled=true` (off by default, so every other run measures a node that counts none of
 * it): where a command's chain queues between one commit and the next, which the executor's in-flight count does not
 * see. One line a second for the node, the shape of `bench-signals`' and `bench-query-limits`':
 * `{"t": <seconds since start>, "<section>": {"<field>": <number>, …}, …}`, every value a number, so a reader takes a
 * section it knows and passes over one it does not. A section is left out while the node has nothing for it.
 *
 * - `appendDepth`, now, summed over the contexts: `queued`, the appends not taken into a batch yet (the
 *   `wow.log.append_queue.depth` gauge's read); `handedGroups` and `handedStreams`, the lanes' groups handed to the
 *   committers and not yet taken into a call.
 * - `appendCalls`, cumulative from this line's first read: `calls` to the log, the `streams` they carried, and the calls
 *   `full` at their cap (`mergeMax` merged). Streams over calls between two lines is the mean call of that second.
 * - `appendClock`, cumulative from this line's first read, in microseconds summed over the calls (the queue's
 *   `CallClock`): the `calls` clocked, each one's `call` from its group taken to the log's call returned and its
 *   `complete` from there to its outcomes handed back and its committer free; over the `marked` calls (those whose
 *   log marks the instants only it sees, PostgreSQL's and MongoDB's groups), `enter` to the log's call, `hop` to the
 *   thread of its blocking work, `acquire` to its connection (MongoDB's driver checks one out within each command, so
 *   none there), `bind` to its first statement or command sent (its tables found, its statement prepared, its streams'
 *   parameters bound and encoded; on MongoDB its documents built and sequenced), `statement` to its last result read,
 *   `release` to its connection given back, and `hopBack` to the call returned, which sum to their `call`; and, over
 *   the `commanded` calls (MongoDB's, whose driver reports each command's time to this line's listener), `command`,
 *   the driver's own time of the commands within `statement`, so `statement` less `command` is the driver's work
 *   around them (a command's encoding, a connection's check-out). Each between two lines, over that second's calls, is
 *   the mean of a call's step.
 * - `snapshotClock`, cumulative from this line's first read, the same for the snapshot subscriptions' writes (each
 *   subscription's `SnapshotWrites`, a write one `saveAll` of a leader's batch): the `saves` the leaders took and the
 *   `snapshots` they wrote (an aggregate's highest version a write), `writing` now (the leaders in a store's call), and
 *   the clock's fields. A write's `call` runs from its batch taken to the store's call returned (a failed batch's saves
 *   again alone among it), `complete` to its saves ended; on PostgreSQL the transaction's commit is in `release`.
 * - `handoff`, cumulative from the node's start: the entries the tails `delivered` from memory and their `storeReads`.
 * - `window` and `windowMax`, now, per subscription: the entries its workers took above their watermarks, summed over
 *   the partitions and the largest one partition's, against its in-flight bound (`wow.subscription.max-in-flight`).
 * - `pool`, now, the store's connection pool: on PostgreSQL, HikariCP's `active` and `idle` connections, the threads
 *   `waiting` for one and its `max` (its public pool MXBean; the server sets no size, so HikariCP's default of 10 is
 *   every append, snapshot write, checkpoint, tail read and load of the node); on MongoDB, the check-outs `waiting`
 *   and a server pool's `max` ([MongoPoolGauge], which the client is built with). `pool.<bean>` for each, when there
 *   are several.
 *
 * Every read is of an atomic or volatile field, with no lock taken on the path a command or an entry runs. The append
 * queue counts its calls and handed groups only once read (`PartitionAppendQueue.counts`): an atomic add per call and
 * per group, none per append; while read, each call also carries its marks (`StoreCallMarks`, nine clock reads) in its
 * coroutine context, which the log marks and the queue sums, thirteen adds a call. The snapshot writes count the same
 * way, once read (`SnapshotSubscription.writeCounts`): three adds and a clock a write, none per save. With the line on,
 * the server's MongoDB client is built with the command listener ([commandClock]), which the
 * driver then calls for every command of the node; it adds to a call's marks only while the call holds them. The
 * hand-off's channels have no depth
 * to read without a count on every entry, and the throttle's waiters are behind its monitor, so neither is here; the
 * throttled partitions are the `partitionThrottled` less `partitionUnthrottled` of `bench-signals`.
 *
 * The node's queues are Wow's internal API, which an application does not use (the architecture's internal opt-in
 * rule): this class is the benchmark server's one exception, a reader of the node it measures that calls nothing on
 * its write path. No other class of the server names those types, so a change to them breaks this file alone.
 */
@OptIn(InternalWowApi::class)
class BenchPipeline(private val sections: () -> Map<String, Map<String, Number>>?) : AutoCloseable {
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bench-pipeline").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate({ snapshot()?.let { log.warn("bench-pipeline {}", it) } }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
    }

    /** The sections as one JSON object, in their order; null when there are none. */
    fun snapshot(): String? {
        val read = sections()?.filterValues { it.isNotEmpty() }
        if (read.isNullOrEmpty()) return null
        return buildString {
            append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
            read.forEach { (section, fields) ->
                append(",\"").append(section).append("\":{")
                append(fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
                append('}')
            }
            append('}')
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BenchPipeline::class.java)
        private const val MILLIS_PER_SECOND = 1_000

        /** The line of the node among [beans]. */
        fun of(beans: BeanFactory): BenchPipeline {
            val node = beans.getBean(ServiceNode::class.java)
            val listed = beans as? ListableBeanFactory
            val hikari = listed?.getBeansOfType(HikariDataSource::class.java).orEmpty()
            val gauges = listed?.getBeansOfType(MongoPoolGauge::class.java).orEmpty()
            return BenchPipeline { sectionsOf(node) + poolsOf(hikari, gauges) }
        }

        /**
         * The pools of [hikari] and [gauges] now, as `pool`, or `pool.<bean>` each when there are several: a pool not
         * started yet says nothing.
         */
        internal fun poolsOf(
            hikari: Map<String, HikariDataSource>,
            gauges: Map<String, MongoPoolGauge>,
        ): Map<String, Map<String, Number>> {
            val pools = hikari.mapValues { (_, source) ->
                source.hikariPoolMXBean?.let { pool ->
                    linkedMapOf(
                        "active" to pool.activeConnections,
                        "idle" to pool.idleConnections,
                        "waiting" to pool.threadsAwaitingConnection,
                        "max" to source.maximumPoolSize,
                    )
                }.orEmpty()
            } + gauges.mapValues { (_, gauge) ->
                gauge.waiting()?.let { waiting ->
                    linkedMapOf("waiting" to waiting, "max" to (gauge.maxConnections ?: 0))
                }.orEmpty()
            }
            return if (pools.size == 1) mapOf("pool" to pools.values.single()) else pools.mapKeys { "pool.${it.key}" }
        }

        /** The sections of [node] now: its contexts' append queues, their log groups' hand-off logs and positions. */
        private fun sectionsOf(node: ServiceNode): Map<String, Map<String, Number>> {
            val queues = node.contexts.values.map { it.log }
            val counts = queues.map { it.counts() }
            val groups = node.contexts.values.flatMap { it.logGroups }
            // A group's log is its hand-off log (ContextAssembly hands each group's commits to its tails).
            val handoffs = groups.mapNotNull { it.log as? HandoffLog }
            val positions = groups.flatMap { it.subscriptions.positions() }.groupBy { it.subscription }.toSortedMap()
            return linkedMapOf(
                "appendDepth" to linkedMapOf(
                    "queued" to queues.sumOf { it.queued },
                    "handedGroups" to counts.sumOf { it.handedGroups },
                    "handedStreams" to counts.sumOf { it.handedStreams },
                ),
                "appendCalls" to linkedMapOf(
                    "calls" to counts.sumOf { it.calls },
                    "streams" to counts.sumOf { it.streams },
                    "full" to counts.sumOf { it.full },
                ),
                "appendClock" to clockOf(counts.map { it.clock }),
                "snapshotClock" to snapshotClockOf(
                    node.contexts.values.flatMap { it.snapshotSubscriptions }.map { it.writeCounts() },
                ),
                "handoff" to if (handoffs.isEmpty()) {
                    emptyMap()
                } else {
                    linkedMapOf(
                        "delivered" to handoffs.sumOf { it.delivered },
                        "storeReads" to handoffs.sumOf { it.storeReads },
                    )
                },
                "window" to positions.mapValues { (_, each) -> each.sumOf { it.inFlight } },
                "windowMax" to positions.mapValues { (_, each) -> each.maxOf { it.inFlight } },
            )
        }

        /** The [clocks]' calls, summed over the contexts; none while no call was clocked. */
        internal fun clockOf(clocks: List<CallClock>): Map<String, Number> =
            if (clocks.sumOf { it.calls } == 0L) {
                emptyMap()
            } else {
                linkedMapOf(
                    "calls" to clocks.sumOf { it.calls },
                    "call" to clocks.sumOf { it.call },
                    "complete" to clocks.sumOf { it.complete },
                    "marked" to clocks.sumOf { it.marked },
                    "enter" to clocks.sumOf { it.enter },
                    "hop" to clocks.sumOf { it.hop },
                    "acquire" to clocks.sumOf { it.acquire },
                    "bind" to clocks.sumOf { it.bind },
                    "statement" to clocks.sumOf { it.statement },
                    "release" to clocks.sumOf { it.release },
                    "hopBack" to clocks.sumOf { it.hopBack },
                    "commanded" to clocks.sumOf { it.commanded },
                    "command" to clocks.sumOf { it.command },
                )
            }

        /** The snapshot writes' [counts], summed over the subscriptions; none while no write was clocked. */
        internal fun snapshotClockOf(counts: List<SnapshotWriteCounts>): Map<String, Number> =
            clockOf(counts.map { it.clock }).takeIf { it.isNotEmpty() }?.let { clock ->
                linkedMapOf<String, Number>(
                    "saves" to counts.sumOf { it.saves },
                    "snapshots" to counts.sumOf { it.snapshots },
                    "writing" to counts.sumOf { it.writing },
                ) + clock
            }.orEmpty()

        /**
         * The driver listener of the MongoDB stores' clocked calls, which the server's client is built with: each
         * command's own time, added to the marks of the call its thread holds ([HeldStoreCallMarks]), if any.
         */
        fun commandClock(): CommandListener = object : CommandListener {
            override fun commandSucceeded(event: CommandSucceededEvent) {
                HeldStoreCallMarks.held()?.commanded(event.getElapsedTime(TimeUnit.NANOSECONDS))
            }

            override fun commandFailed(event: CommandFailedEvent) {
                HeldStoreCallMarks.held()?.commanded(event.getElapsedTime(TimeUnit.NANOSECONDS))
            }
        }
    }
}

/** [BenchPipeline] on the node, with `bench.pipeline.enabled=true` only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.pipeline", name = ["enabled"], havingValue = "true")
class BenchPipelineConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchPipeline(beans: BeanFactory): BenchPipeline = BenchPipeline.of(beans)

    /** The MongoDB client's command listener (`MongoBackend` adds every one): the driver's time of a clocked call. */
    @Bean
    fun benchCommandClock(): CommandListener = BenchPipeline.commandClock()
}
