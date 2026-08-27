package me.ahoo.wow.redis.bus

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.tck.command.CommandBusSpec
import me.ahoo.wow.tck.container.RedisTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.reactivestreams.Subscription
import org.springframework.data.redis.connection.stream.ReadOffset
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RedisCommandBusTest : CommandBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): CommandBus {
        return RedisCommandBus(redis.redisTemplate)
    }

    @Test
    fun `receiver does not consume through operator prefetch before processing opens`() {
        val receiverGroup = generateGlobalId()
        val subscription = MessageSubscription(namedAggregate, receiverGroup)
        val topic = DefaultCommandTopicConverter.convert(namedAggregate)
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            pollTimeout = Duration.ofMillis(20),
        )
        val receivedMessageId = CompletableFuture<String>()
        val receiver = bus.receiver(subscription).mapMessages { messages ->
            Flux.merge(
                Flux.never(),
                messages.filterWhen { Mono.just(true) },
            )
        }
        val receiverSubscription = object : BaseSubscriber<ServerCommandExchange<*>>() {
            fun requestOne() = request(1)

            override fun hookOnSubscribe(subscription: Subscription) = Unit

            override fun hookOnNext(value: ServerCommandExchange<*>) {
                receivedMessageId.complete(value.message.id)
            }
        }
        receiver.messages.subscribe(receiverSubscription)

        try {
            receiver.readiness.block(Duration.ofSeconds(5))
            receiverSubscription.requestOne()
            val message = createMessage()
            bus.send(message).block(Duration.ofSeconds(5))
            Mono.delay(Duration.ofMillis(100)).block()

            receivedMessageId.isDone.assert().isFalse()
            streamOps.pending(topic, receiverGroup)
                .block(Duration.ofSeconds(5))!!
                .totalPendingMessages
                .assert()
                .isZero()

            receiver.openProcessing()
            receivedMessageId.get(5, TimeUnit.SECONDS)
                .assert()
                .isEqualTo(message.id)
        } finally {
            receiverSubscription.dispose()
        }
    }

    @Test
    fun `receiver readiness creates the latest group before demand`() {
        val receiverGroup = generateGlobalId()
        val subscription = MessageSubscription(namedAggregate, receiverGroup)
        val topic = DefaultCommandTopicConverter.convert(namedAggregate)
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        val bus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            pollTimeout = Duration.ofMillis(20),
        )
        val oldMessage = createMessage()
        bus.send(oldMessage).block(Duration.ofSeconds(5))

        val firstReceiver = bus.receiver(subscription)
        val firstSubscription = object : BaseSubscriber<ServerCommandExchange<*>>() {
            override fun hookOnSubscribe(subscription: Subscription) = Unit
        }
        firstReceiver.messages.subscribe(firstSubscription)
        firstReceiver.readiness.block(Duration.ofSeconds(5))
        firstSubscription.dispose()

        val receivedMessageId = CompletableFuture<String>()
        val secondReceiver = bus.receiver(subscription)
        val secondSubscription = object : BaseSubscriber<ServerCommandExchange<*>>() {
            override fun hookOnSubscribe(subscription: Subscription) = Unit

            override fun hookOnNext(value: ServerCommandExchange<*>) {
                receivedMessageId.complete(value.message.id)
            }
        }
        secondReceiver.messages.subscribe(secondSubscription)

        try {
            secondReceiver.readiness.block(Duration.ofSeconds(5))
            val newMessage = createMessage()
            bus.send(newMessage).block(Duration.ofSeconds(5))
            Mono.delay(Duration.ofMillis(100)).block()
            receivedMessageId.isDone.assert().isFalse()
            streamOps.pending(topic, receiverGroup)
                .block(Duration.ofSeconds(5))!!
                .totalPendingMessages
                .assert()
                .isZero()
            secondSubscription.dispose()

            val thirdReceiver = bus.receiver(subscription)
            val thirdSubscription = object : BaseSubscriber<ServerCommandExchange<*>>() {
                fun requestOne() = request(1)

                override fun hookOnSubscribe(subscription: Subscription) = Unit

                override fun hookOnNext(value: ServerCommandExchange<*>) {
                    receivedMessageId.complete(value.message.id)
                }
            }
            thirdReceiver.messages.subscribe(thirdSubscription)
            try {
                thirdReceiver.readiness.block(Duration.ofSeconds(5))
                thirdReceiver.openProcessing()
                thirdSubscription.requestOne()
                receivedMessageId.get(5, TimeUnit.SECONDS)
                    .assert()
                    .isEqualTo(newMessage.id)
            } finally {
                thirdSubscription.dispose()
            }
        } finally {
            secondSubscription.dispose()
        }
    }

    @Test
    fun `receiver readiness creates every group when an earlier group exists`() {
        val receiverGroup = generateGlobalId()
        val firstAggregate = namedAggregate
        val secondAggregate = MaterializedNamedAggregate(
            firstAggregate.contextName,
            "${firstAggregate.aggregateName}-${generateGlobalId()}",
        )
        val firstTopic = DefaultCommandTopicConverter.convert(firstAggregate)
        val secondTopic = DefaultCommandTopicConverter.convert(secondAggregate)
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        streamOps.add(firstTopic, mapOf("bootstrap" to "group-anchor"))
            .then(streamOps.createGroup(firstTopic, ReadOffset.latest(), receiverGroup))
            .block(Duration.ofSeconds(5))
        val bus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            pollTimeout = Duration.ofMillis(20),
        )
        val receiver = bus.receiver(
            MessageSubscription(
                namedAggregates = linkedSetOf(firstAggregate, secondAggregate),
                receiverGroup = receiverGroup,
            ),
        )
        val subscription = object : BaseSubscriber<ServerCommandExchange<*>>() {
            override fun hookOnSubscribe(subscription: Subscription) = Unit
        }
        receiver.messages.subscribe(subscription)

        try {
            receiver.readiness.block(Duration.ofSeconds(5))
            streamOps.groups(secondTopic)
                .map { group -> group.groupName() }
                .collectList()
                .block(Duration.ofSeconds(5))
                .assert()
                .contains(receiverGroup)
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `should isolate an undecodable record and continue consuming valid messages`() {
        val receiverGroup = generateGlobalId()
        val subscription = MessageSubscription(namedAggregate, receiverGroup)
        val topic = DefaultCommandTopicConverter.convert(namedAggregate)
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        streamOps.add(topic, mapOf("bootstrap" to "group-anchor")).block(Duration.ofSeconds(5))
        streamOps.createGroup(topic, ReadOffset.latest(), receiverGroup).block(Duration.ofSeconds(5))
        streamOps.add(topic, mapOf("unexpected" to "missing-message-field")).block(Duration.ofSeconds(5))
        streamOps.add(topic, mapOf(MESSAGE_FIELD to "not-json")).block(Duration.ofSeconds(5))
        val validMessage = createMessage()
        val observations = mutableListOf<RedisMessageBusObservation>()
        val bus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            messageBusObserver = RedisMessageBusObserver { observation ->
                observations += observation
                throw IllegalStateException("observer failed")
            },
            pollTimeout = Duration.ofMillis(50),
        )
        bus.send(validMessage).block(Duration.ofSeconds(5))

        bus.receive(subscription)
            .take(1)
            .concatMap { exchange -> exchange.acknowledge().thenReturn(exchange) }
            .test()
            .assertNext { exchange -> exchange.message.id.assert().isEqualTo(validMessage.id) }
            .expectComplete()
            .verify(Duration.ofSeconds(5))

        observations.filterIsInstance<RedisMessageBusObservation.RecordDecodeFailed>()
            .map { observation -> observation.reason }
            .assert()
            .containsExactly(
                RedisRecordDecodeFailureReason.MISSING_MESSAGE_FIELD,
                RedisRecordDecodeFailureReason.DESERIALIZATION_FAILED,
            )
        streamOps.pending(topic, receiverGroup)
            .block(Duration.ofSeconds(5))!!
            .totalPendingMessages
            .assert()
            .isEqualTo(2)
    }

    @Test
    fun `should recover and acknowledge idle messages from an old consumer`() {
        val receiverGroup = generateGlobalId()
        val subscription = MessageSubscription(namedAggregate, receiverGroup)
        val messages = List(3) { createMessage() }
        val oldBus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            pollTimeout = Duration.ofMillis(20),
        )
        oldBus.receive(subscription)
            .doOnSubscribe {
                Mono.delay(Duration.ofMillis(100))
                    .thenMany(Flux.fromIterable(messages).concatMap(oldBus::send))
                    .then()
                    .subscribe()
            }
            .take(messages.size.toLong())
            .test()
            .expectNextCount(messages.size.toLong())
            .expectComplete()
            .verify(Duration.ofSeconds(5))

        val topic = DefaultCommandTopicConverter.convert(namedAggregate)
        val streamOps = redis.redisTemplate.opsForStream<String, String>()
        streamOps.pending(topic, receiverGroup)
            .block(Duration.ofSeconds(5))!!
            .totalPendingMessages
            .assert()
            .isEqualTo(messages.size.toLong())
        Mono.delay(Duration.ofMillis(100)).block()

        val recoveringBus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions(
                minIdleTime = Duration.ofMillis(50),
                interval = Duration.ofMillis(20),
                batchSize = 1,
            ),
            pollTimeout = Duration.ofMillis(20),
        )
        recoveringBus.receive(subscription)
            .take(messages.size.toLong())
            .concatMap { exchange -> exchange.acknowledge().thenReturn(exchange) }
            .test()
            .recordWith(::mutableListOf)
            .expectNextCount(messages.size.toLong())
            .consumeRecordedWith { exchanges ->
                exchanges.map { exchange -> exchange.message.id }
                    .assert()
                    .containsExactlyElementsOf(messages.map { message -> message.id })
            }
            .expectComplete()
            .verify(Duration.ofSeconds(5))

        streamOps.pending(topic, receiverGroup)
            .block(Duration.ofSeconds(5))!!
            .totalPendingMessages
            .assert()
            .isEqualTo(0)
    }

    @Test
    fun `should not recover from an active consumer lease`() {
        val receiverGroup = generateGlobalId()
        val subscription = MessageSubscription(namedAggregate, receiverGroup)
        val message = createMessage()
        val recoveryOptions = RedisStreamRecoveryOptions(
            minIdleTime = Duration.ofMillis(200),
            interval = Duration.ofMillis(500),
            batchSize = 1,
        )
        val activeBus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = recoveryOptions,
            pollTimeout = Duration.ofSeconds(2),
        )
        val received = CountDownLatch(1)
        val activeSubscription = activeBus.receive(subscription)
            .doOnNext { received.countDown() }
            .subscribe()
        val recoveringBus = RedisCommandBus(
            redisTemplate = redis.redisTemplate,
            recoveryOptions = recoveryOptions,
            pollTimeout = Duration.ofMillis(20),
        )
        try {
            activeBus.send(message).block(Duration.ofSeconds(5))
            received.await(5, TimeUnit.SECONDS).assert().isTrue()

            recoveringBus.receive(subscription)
                .take(1)
                .concatMap { exchange -> exchange.acknowledge().thenReturn(exchange) }
                .test()
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .then(activeSubscription::dispose)
                .assertNext { exchange -> exchange.message.id.assert().isEqualTo(message.id) }
                .expectComplete()
                .verify(Duration.ofSeconds(10))
        } finally {
            activeSubscription.dispose()
        }
    }
}
