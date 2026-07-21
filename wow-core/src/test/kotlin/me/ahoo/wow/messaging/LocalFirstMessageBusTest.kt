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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.handler.MessageExchange
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap

class LocalFirstMessageBusTest {

    @Test
    fun `send sends local message first and distributed copy when local subscriber exists`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(id = "message-id")

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.single().assert().isSameAs(message)
        distributedBus.sent.single().assert().isNotSameAs(message)
        message.isLocalFirst().assert().isTrue()
        distributedBus.sent.single().isLocalFirst().assert().isTrue()
    }

    @Test
    fun `send disables local first on distributed copy when local send fails`() {
        val localBus = RecordingLocalBus(subscribers = 1).apply {
            sendResult = { Mono.error(IllegalStateException("local failed")) }
        }
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)

        StepVerifier.create(bus.send(LocalFirstTestMessage(id = "message-id")))
            .verifyComplete()

        localBus.sent.assert().hasSize(1)
        distributedBus.sent.single().isLocalFirst().assert().isFalse()
    }

    @Test
    fun `send skips local bus when there are no local subscribers`() {
        val localBus = RecordingLocalBus(subscribers = 0)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(id = "message-id")

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.assert().isEmpty()
        distributedBus.sent.single().assert().isSameAs(message)
        message.isLocalFirst().assert().isFalse()
    }

    @Test
    fun `send skips local bus when local first is disabled case insensitively`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(
            id = "message-id",
            header = DefaultHeader.empty().with(LOCAL_FIRST_HEADER, "FALSE"),
        )

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.assert().isEmpty()
        distributedBus.sent.single().assert().isSameAs(message)
        message.isLocalFirst().assert().isFalse()
    }

    @Test
    fun `receive filters distributed messages already handled locally and acknowledges them`() {
        val localExchange = LocalFirstTestExchange(LocalFirstTestMessage(id = "local"))
        val filteredDistributedExchange = LocalFirstTestExchange(
            LocalFirstTestMessage(id = "filtered").withLocalFirst()
        )
        val remoteDistributedExchange = LocalFirstTestExchange(LocalFirstTestMessage(id = "remote"))
        val localBus = RecordingLocalBus(receiveFlux = Flux.just(localExchange))
        val distributedBus = RecordingDistributedBus(
            receiveFlux = Flux.just(filteredDistributedExchange, remoteDistributedExchange)
        )
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group")

        StepVerifier.create(bus.receive(subscription).collectList())
            .assertNext { exchanges ->
                exchanges.map { it.message.id }.toSet().assert().isEqualTo(setOf("local", "remote"))
                filteredDistributedExchange.acknowledged.assert().isTrue()
                remoteDistributedExchange.acknowledged.assert().isFalse()
            }
            .verifyComplete()

        localBus.received.single().receiverGroup.assert().isEqualTo(subscription.receiverGroup)
        distributedBus.received.single().assert().isEqualTo(subscription)
    }
}

private class RecordingLocalFirstMessageBus(
    override val localBus: LocalMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>,
    override val distributedBus: DistributedMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>,
) : LocalFirstMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>

private class RecordingLocalBus(
    private val subscribers: Int = 0,
    private val receiveFlux: Flux<LocalFirstTestExchange> = Flux.empty(),
) : LocalMessageBus<LocalFirstTestMessage, LocalFirstTestExchange> {
    val sent: MutableList<LocalFirstTestMessage> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()
    var sendResult: (LocalFirstTestMessage) -> Mono<Void> = { Mono.empty() }

    override fun send(message: LocalFirstTestMessage): Mono<Void> =
        Mono.defer {
            sent += message
            sendResult(message)
        }

    override fun receive(subscription: MessageSubscription): Flux<LocalFirstTestExchange> {
        received += subscription
        return receiveFlux
    }

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = subscribers
}

private class RecordingDistributedBus(
    private val receiveFlux: Flux<LocalFirstTestExchange> = Flux.empty(),
) : DistributedMessageBus<LocalFirstTestMessage, LocalFirstTestExchange> {
    val sent: MutableList<LocalFirstTestMessage> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()

    override fun send(message: LocalFirstTestMessage): Mono<Void> =
        Mono.fromRunnable {
            sent += message
        }

    override fun receive(subscription: MessageSubscription): Flux<LocalFirstTestExchange> {
        received += subscription
        return receiveFlux
    }
}

private class LocalFirstTestMessage(
    override val id: String = "message-id",
    override val header: Header = DefaultHeader.empty(),
    override val body: String = "body",
    override val createTime: Long = 1,
) : Message<LocalFirstTestMessage, String>,
    NamedAggregate,
    Copyable<LocalFirstTestMessage> {
    override val contextName: String = "wow-core-test"
    override val aggregateName: String = "modeling_command_aggregate"

    override fun copy(): LocalFirstTestMessage =
        LocalFirstTestMessage(
            id = id,
            header = header.copy(),
            body = body,
            createTime = createTime,
        )
}

private class LocalFirstTestExchange(
    override val message: LocalFirstTestMessage
) : MessageExchange<LocalFirstTestExchange, LocalFirstTestMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    var acknowledged: Boolean = false
        private set

    override fun acknowledge(): Mono<Void> =
        Mono.fromRunnable {
            acknowledged = true
        }
}
