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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.ReactiveStreamOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class RedisPendingMessageRecovererTest {
    private val topic = "topic"
    private val group = "group"
    private val currentConsumer = Consumer.from(group, "current")
    private val streamOps = mockk<ReactiveStreamOperations<String, String, String>>()
    private val scanner = mockk<RedisPendingMessageScanner>()
    private val leaseRegistry = mockk<RedisConsumerLeaseRegistry>()

    init {
        every {
            leaseRegistry.findActiveConsumers(any(), any(), any())
        } returns Mono.just(emptySet())
    }

    @Test
    fun `should not scan pending messages when recovery is disabled`() {
        val recoverer = RedisPendingMessageRecoverer(
            streamOps = streamOps,
            scanner = scanner,
            leaseRegistry = leaseRegistry,
            options = RedisStreamRecoveryOptions.DISABLED,
        )

        StepVerifier.create(recoverer.recover(topic, currentConsumer)).verifyComplete()

        verify(exactly = 0) { scanner.scan(any(), any(), any()) }
    }

    @Test
    fun `should not claim messages owned by the current consumer`() {
        every {
            scanner.scan(topic, group, scanRequest())
        } returns Mono.just(page("1-0", listOf(pending("1-0", currentConsumer.name)), complete = true))

        StepVerifier.create(
            recoverer(options(interval = Duration.ofDays(1)))
                .recover(topic, currentConsumer)
                .takeUntilOther(Mono.delay(Duration.ofMillis(20))),
        ).verifyComplete()

        verify(exactly = 0) {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                any<Duration>(),
                *anyVararg<RecordId>(),
            )
        }
    }

    @Test
    fun `should not claim messages owned by an active peer`() {
        val activePeer = "active-peer"
        every {
            scanner.scan(topic, group, scanRequest())
        } returns Mono.just(page("1-0", listOf(pending("1-0", activePeer)), complete = true))
        every {
            leaseRegistry.findActiveConsumers(topic, group, setOf(activePeer))
        } returns Mono.just(setOf(activePeer))

        StepVerifier.create(
            recoverer(options(interval = Duration.ofDays(1)))
                .recover(topic, currentConsumer)
                .takeUntilOther(Mono.delay(Duration.ofMillis(20))),
        ).verifyComplete()

        verify(exactly = 1) {
            leaseRegistry.findActiveConsumers(topic, group, setOf(activePeer))
        }
        verify(exactly = 0) {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                any<Duration>(),
                *anyVararg<RecordId>(),
            )
        }
    }

    @Test
    fun `should claim only inactive peers and report the outcome`() {
        val activePending = pending("1-0", "active-peer")
        val inactivePending = pending("2-0", "inactive-peer", deliveryCount = 7)
        every {
            scanner.scan(topic, group, scanRequest())
        } returns Mono.just(page("2-0", listOf(activePending, inactivePending), complete = true))
        every {
            leaseRegistry.findActiveConsumers(topic, group, setOf("active-peer", "inactive-peer"))
        } returns Mono.just(setOf("active-peer"))
        val claimedRecord = mockk<MapRecord<String, String, String>>()
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                inactivePending.id,
            )
        } returns Flux.just(claimedRecord)
        val observations = mutableListOf<RedisMessageBusObservation>()

        StepVerifier.create(
            recoverer(options(interval = Duration.ofDays(1)), observations)
                .recover(topic, currentConsumer)
                .take(1),
        )
            .expectNext(claimedRecord)
            .verifyComplete()

        observations.assert().containsExactly(
            RedisMessageBusObservation.PendingClaimCompleted(
                topic = topic,
                consumerGroup = group,
                requestedMessages = 1,
                claimedMessages = 1,
            ),
        )
    }

    @Test
    fun `should retry a failed scan without terminating recovery`() {
        val requests = mutableListOf<RedisPendingScanRequest>()
        val inactivePending = pending("1-0", "inactive-peer")
        every {
            scanner.scan(topic, group, capture(requests))
        } returnsMany listOf(
            Mono.error(IllegalStateException("scan failed")),
            Mono.just(page("1-0", listOf(inactivePending), complete = true)),
        )
        val claimedRecord = mockk<MapRecord<String, String, String>>()
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                inactivePending.id,
            )
        } returns Flux.just(claimedRecord)
        val observations = mutableListOf<RedisMessageBusObservation>()

        StepVerifier.withVirtualTime {
            recoverer(options(), observations).recover(topic, currentConsumer).take(1)
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1))
            .thenAwait(Duration.ofMillis(1))
            .expectNext(claimedRecord)
            .verifyComplete()

        requests.map(RedisPendingScanRequest::cursor).assert().containsExactly(null, null)
        observations.first().assert().isEqualTo(
            RedisMessageBusObservation.PendingScanFailed(
                topic = topic,
                consumerGroup = group,
                failureType = IllegalStateException::class.java.name,
            ),
        )
    }

    @Test
    fun `should retry a failed claim without terminating recovery`() {
        val inactivePending = pending("1-0", "inactive-peer")
        every {
            scanner.scan(topic, group, scanRequest())
        } returns Mono.just(page("1-0", listOf(inactivePending), complete = true))
        val claimedRecord = mockk<MapRecord<String, String, String>>()
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                inactivePending.id,
            )
        } returnsMany listOf(
            Flux.error(IllegalStateException("claim failed")),
            Flux.just(claimedRecord),
        )
        val observations = mutableListOf<RedisMessageBusObservation>()

        StepVerifier.withVirtualTime {
            recoverer(options(), observations).recover(topic, currentConsumer).take(1)
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1))
            .thenAwait(Duration.ofMillis(1))
            .expectNext(claimedRecord)
            .verifyComplete()

        observations.filterIsInstance<RedisMessageBusObservation.PendingClaimFailed>()
            .assert()
            .hasSize(1)
        observations.last().assert().isEqualTo(
            RedisMessageBusObservation.PendingClaimCompleted(
                topic = topic,
                consumerGroup = group,
                requestedMessages = 1,
                claimedMessages = 1,
            ),
        )
    }

    @Test
    fun `should drain every page in a bounded sweep before waiting`() {
        val oldPending = pending("3-0", "old")
        val requests = mutableListOf<RedisPendingScanRequest>()
        every {
            scanner.scan(topic, group, capture(requests))
        } returnsMany listOf(
            Mono.just(
                page(
                    sweepEnd = "3-0",
                    messages = listOf(
                        pending("1-0", currentConsumer.name),
                        pending("2-0", currentConsumer.name),
                    ),
                    complete = false,
                ),
            ),
            Mono.just(page("3-0", listOf(oldPending), complete = true)),
        )
        val claimedRecord = mockk<MapRecord<String, String, String>>()
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                oldPending.id,
            )
        } returns Flux.just(claimedRecord)

        StepVerifier.create(
            recoverer(options(interval = Duration.ofDays(1), batchSize = 2))
                .recover(topic, currentConsumer)
                .take(1),
        )
            .expectNext(claimedRecord)
            .verifyComplete()

        requests.map(RedisPendingScanRequest::cursor).assert().containsExactly(null, "2-0")
        requests.map(RedisPendingScanRequest::sweepEnd).assert().containsExactly(null, "3-0")
    }

    @Test
    fun `should revisit a pending message that was too young in the previous sweep`() {
        val youngPending = pending("1-0", "old", elapsedTime = Duration.ZERO)
        val idlePending = pending("1-0", "old", elapsedTime = Duration.ofSeconds(2))
        every {
            scanner.scan(topic, group, scanRequest())
        } returnsMany listOf(
            Mono.just(page("1-0", listOf(youngPending), complete = true)),
            Mono.just(page("1-0", listOf(idlePending), complete = true)),
        )
        val claimedRecord = mockk<MapRecord<String, String, String>>()
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                idlePending.id,
            )
        } returns Flux.just(claimedRecord)

        StepVerifier.withVirtualTime {
            recoverer(options()).recover(topic, currentConsumer).take(1)
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1))
            .thenAwait(Duration.ofMillis(1))
            .expectNext(claimedRecord)
            .verifyComplete()
    }

    @Test
    fun `should report when a claim race returns no records`() {
        val inactivePending = pending("1-0", "inactive-peer")
        every {
            scanner.scan(topic, group, scanRequest())
        } returns Mono.just(page("1-0", listOf(inactivePending), complete = true))
        every {
            streamOps.claim(
                topic,
                group,
                currentConsumer.name,
                Duration.ofSeconds(1),
                inactivePending.id,
            )
        } returns Flux.empty()
        val observations = mutableListOf<RedisMessageBusObservation>()

        StepVerifier.create(
            recoverer(options(interval = Duration.ofDays(1)), observations)
                .recover(topic, currentConsumer)
                .takeUntilOther(Mono.delay(Duration.ofMillis(20))),
        ).verifyComplete()

        observations.assert().containsExactly(
            RedisMessageBusObservation.PendingClaimCompleted(
                topic = topic,
                consumerGroup = group,
                requestedMessages = 1,
                claimedMessages = 0,
            ),
        )
    }

    private fun recoverer(
        options: RedisStreamRecoveryOptions,
        observations: MutableList<RedisMessageBusObservation>? = null,
    ): RedisPendingMessageRecoverer {
        return RedisPendingMessageRecoverer(
            streamOps = streamOps,
            scanner = scanner,
            leaseRegistry = leaseRegistry,
            options = options,
            observer = observations?.let { RedisMessageBusObserver(it::add) } ?: RedisMessageBusObserver.NOOP,
        )
    }

    private fun pending(
        id: String,
        consumerName: String,
        elapsedTime: Duration = Duration.ofSeconds(2),
        deliveryCount: Long = 1,
    ): RedisPendingMessage {
        return RedisPendingMessage(
            id = RecordId.of(id),
            consumerName = consumerName,
            elapsedTimeSinceLastDelivery = elapsedTime,
            deliveryCount = deliveryCount,
        )
    }

    private fun page(
        sweepEnd: String,
        messages: List<RedisPendingMessage>,
        complete: Boolean,
    ): RedisPendingMessagePage {
        return RedisPendingMessagePage(sweepEnd, messages, complete)
    }

    private fun scanRequest(
        cursor: String? = null,
        sweepEnd: String? = null,
        count: Long = 10,
    ): RedisPendingScanRequest {
        return RedisPendingScanRequest(cursor, sweepEnd, count)
    }

    private fun options(
        minIdleTime: Duration = Duration.ofSeconds(1),
        interval: Duration = Duration.ofSeconds(1),
        batchSize: Long = 10,
    ): RedisStreamRecoveryOptions {
        return RedisStreamRecoveryOptions(
            minIdleTime = minIdleTime,
            interval = interval,
            batchSize = batchSize,
        )
    }
}
