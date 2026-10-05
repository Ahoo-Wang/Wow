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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.reactivestreams.Subscription
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class RedisStreamRetentionTest {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    private val namedAggregate: NamedAggregate = requiredNamedAggregate<MockCreateAggregate>()

    @Test
    fun `streams are not trimmed by default`() {
        val topic = redis.key("default")
        val bus = bus(topic, RedisStreamRetentionOptions.DEFAULT)

        send(bus, 30)

        streamLength(topic).assert().isEqualTo(30L)
    }

    @Test
    fun `max length trims the stream on every send`() {
        val topic = redis.key("max-length")
        val bus = bus(topic, RedisStreamRetentionOptions(maxLength = 10, approximate = false))

        send(bus, 30)

        streamLength(topic).assert().isEqualTo(10L)
    }

    @Test
    fun `approximate max length keeps at least the limit and trims whole nodes`() {
        val topic = redis.key("max-length-approximate")
        val bus = bus(topic, RedisStreamRetentionOptions(maxLength = 10))

        send(bus, 500)

        streamLength(topic).assert().isBetween(10L, 499L)
    }

    @Test
    fun `max age trims entries older than the age on every send`() {
        val topic = redis.key("max-age")
        val untrimmed = bus(topic, RedisStreamRetentionOptions.DEFAULT)
        send(untrimmed, 20)
        Mono.delay(Duration.ofMillis(300)).block()
        val bus = bus(topic, RedisStreamRetentionOptions(maxAge = Duration.ofMillis(200), approximate = false))

        send(bus, 1)

        streamLength(topic).assert().isEqualTo(1L)
    }

    @Test
    fun `a starting receiver deletes idle consumers that have nothing pending`() {
        val topic = redis.key("consumers")
        val group = generateGlobalId()
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = bus(topic, RedisStreamRetentionOptions(consumerIdleTimeout = Duration.ofMillis(100)))
        send(bus, 1)
        streamOps.createGroup(topic, ReadOffset.from("0"), group).block()
        // "pending" reads the entry without acknowledging it; "idle" reads nothing.
        read(topic, group, "pending")
        read(topic, group, "idle")
        consumerNames(topic, group).assert().containsExactlyInAnyOrder("pending", "idle")
        Mono.delay(Duration.ofMillis(200)).block()

        startReceiver(bus, group) {
            consumerNames(topic, group).assert().containsExactly("pending")
            streamOps.pending(topic, group).block()!!.totalPendingMessages.assert().isEqualTo(1L)
        }
    }

    @Test
    fun `a starting receiver keeps consumers that are not idle long enough`() {
        val topic = redis.key("recent-consumers")
        val group = generateGlobalId()
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = bus(topic, RedisStreamRetentionOptions(consumerIdleTimeout = Duration.ofMinutes(10)))
        send(bus, 1)
        streamOps.createGroup(topic, ReadOffset.latest(), group).block()
        read(topic, group, "recent")

        startReceiver(bus, group) {
            consumerNames(topic, group).assert().contains("recent")
        }
    }

    @Test
    fun `consumer reaping can be turned off`() {
        val topic = redis.key("keep-consumers")
        val group = generateGlobalId()
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = bus(topic, RedisStreamRetentionOptions(consumerIdleTimeout = null))
        send(bus, 1)
        streamOps.createGroup(topic, ReadOffset.latest(), group).block()
        read(topic, group, "idle")
        Mono.delay(Duration.ofMillis(50)).block()

        startReceiver(bus, group) {
            consumerNames(topic, group).assert().contains("idle")
        }
    }

    private fun bus(topic: String, retention: RedisStreamRetentionOptions) = RedisCommandBus(
        redisTemplate = redis.redisTemplate,
        topicConverter = object : CommandTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String = topic
        },
        pollTimeout = Duration.ofMillis(20),
        recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
        retentionOptions = retention,
    )

    private fun send(bus: RedisCommandBus, count: Int) {
        Flux.range(0, count).concatMap { bus.send(createMessage()) }.blockLast(Duration.ofSeconds(10))
    }

    private fun createMessage(): CommandMessage<*> = MockCreateAggregate(
        id = GlobalIdGenerator.generateAsString(),
        data = GlobalIdGenerator.generateAsString(),
    ).toCommandMessage()

    private fun streamLength(topic: String): Long =
        redis.redisTemplate.opsForStream<String, String>().info(topic).block()!!.streamLength()

    private fun read(topic: String, group: String, consumer: String) {
        redis.redisTemplate.opsForStream<String, String>()
            .read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(topic, ReadOffset.lastConsumed()),
            ).collectList().block()
    }

    private fun consumerNames(topic: String, group: String): List<String> =
        redis.redisTemplate.opsForStream<String, String>().consumers(topic, group)
            .map { it.consumerName() }
            .collectList()
            .block()!!

    /** Starts a receiver of [group]; its readiness completes after the idle consumers are deleted. */
    private fun startReceiver(bus: RedisCommandBus, group: String, assertion: () -> Unit) {
        val receiver = bus.receiver(MessageSubscription(namedAggregate, group))
        val subscriber = object : BaseSubscriber<Any>() {
            override fun hookOnSubscribe(subscription: Subscription) = Unit
        }
        receiver.messages.subscribe(subscriber)
        try {
            receiver.readiness.block(Duration.ofSeconds(5))
            assertion()
        } finally {
            subscriber.dispose()
        }
    }
}
