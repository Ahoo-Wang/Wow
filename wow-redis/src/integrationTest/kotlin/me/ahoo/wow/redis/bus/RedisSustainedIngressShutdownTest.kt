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

package me.ahoo.wow.redis.bus

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.transport.TransportServerCommandExchange
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.domain.Range
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B8 on Redis Streams: a graceful stop while commands keep arriving. The runtime stops reading, drains what it read,
 * acknowledges exactly the processed entries and leaves the rest unread or pending for the group.
 */
class RedisSustainedIngressShutdownTest {
    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val QUIET_PERIOD: Duration = Duration.ofMillis(500)
        private const val AGGREGATES = 8
    }

    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    @Test
    fun `graceful stop under sustained ingress acknowledges only processed entries`() {
        val topicConverter = object : CommandTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String =
                redis.key(DefaultCommandTopicConverter.convert(namedAggregate))
        }
        val topic = topicConverter.convert(MOCK_AGGREGATE_METADATA)
        val group = "b8-${generateGlobalId()}"
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            topicConverter = topicConverter,
            pollTimeout = Duration.ofMillis(100),
        )
        val processed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val processCounts = ConcurrentHashMap<String, AtomicInteger>()
        val dispatcher = CommandDispatcher(
            name = group,
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = bus,
            commandHandler = object : CommandHandler {
                override fun handle(
                    exchange: ServerCommandExchange<*>,
                    aggregateMetadata: AggregateMetadata<*, *>,
                ): Mono<Void> = Mono.delay(Duration.ofMillis(2))
                    .then(
                        Mono.fromRunnable<Void> {
                            processed += (exchange as TransportServerCommandExchange<*>).record.id
                            processCounts.computeIfAbsent(exchange.message.id) { AtomicInteger() }.incrementAndGet()
                        },
                    )
                    .then(exchange.acknowledge())
            },
        )
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            shutdownQuietPeriod = QUIET_PERIOD,
        )
        runtime.start().block(Duration.ofSeconds(30))
        val sequence = AtomicInteger()
        val sent = AtomicInteger()
        val producer = Flux.interval(Duration.ofMillis(2), Schedulers.single())
            .onBackpressureDrop()
            .concatMap {
                val id = sequence.getAndIncrement()
                val aggregateId = "aggregate-${id % AGGREGATES}"
                bus.send(MockCreateAggregate(id = aggregateId, data = "$id").toCommandMessage(aggregateId = aggregateId))
                    .doOnSuccess { sent.incrementAndGet() }
            }
            .subscribe()
        try {
            awaitUntil(Duration.ofSeconds(30)) { processCounts.size >= 300 }

            val stopStarted = System.nanoTime()
            val stopFailure = runCatching { runtime.stopGracefully().block(SHUTDOWN_TIMEOUT.multipliedBy(2)) }
                .exceptionOrNull()
            val stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)
            println("Redis graceful stop under sustained ingress took $stopMillis ms.")

            stopFailure.assert().isNull()
            stopMillis.assert().isLessThan(SHUTDOWN_TIMEOUT.toMillis() / 4)
            val sentAtStop = sent.get()
            awaitUntil(Duration.ofSeconds(10)) { sent.get() > sentAtStop + 100 }
        } finally {
            producer.dispose()
            runtime.forceStop()
        }

        val entries = streamOps.range(topic, Range.unbounded()).map { it.id }.collectList().block()!!
        val lastDelivered = streamOps.groups(topic).filter { it.groupName() == group }.blockFirst()!!.lastDeliveredId()
        val pending = streamOps.pending(topic, group, Range.unbounded<Any>(), Long.MAX_VALUE).block()!!
            .map { it.id.value }
            .toSet()
        val delivered = entries.map { it.value }.filter { it.entryOrder() <= lastDelivered.entryOrder() }
        val acknowledged = delivered.toSet() - pending

        // Processed at most once.
        processCounts.values.forEach { it.get().assert().isEqualTo(1) }
        // Exactly the processed entries are acknowledged: nothing acknowledged without processing, and nothing
        // processed stays pending for another member to process again.
        acknowledged.assert().isEqualTo(processed.toSet())
        // Ingress went on: entries were left unread or pending for the group.
        (entries.size - acknowledged.size).assert().isGreaterThan(0)
        bus.close()
    }

    /** A stream entry ID `<ms>-<seq>` as one comparable number pair. */
    private fun String.entryOrder(): Pair<Long, Long> =
        split('-').let { it[0].toLong() to it[1].toLong() }

    private operator fun Pair<Long, Long>.compareTo(other: Pair<Long, Long>): Int =
        compareValuesBy(this, other, { it.first }, { it.second })

    private fun awaitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not reached within $timeout." }
            Thread.sleep(20)
        }
    }
}
