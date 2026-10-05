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

package me.ahoo.wow.messaging.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.runtime.RuntimeResource
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

/**
 * A distributed message bus over a [Transport]: the one place that names topics, encodes and decodes messages,
 * validates received records and builds exchanges, whatever the broker.
 *
 * The wire format is the v9 one on every backend: the topic is [topicNaming]'s (memoised per aggregate), the key is
 * the aggregate ID, the timestamp is the message's creation time and the payload is the message's JSON. A received
 * record must decode to a [messageType] whose aggregate ID equals the record key (when the backend has keys) and whose
 * topic is the record's; anything else goes to [decodeFailureHandler]. Decoded messages are read-only.
 */
abstract class TransportMessageBus<M, E>(
    val transport: Transport,
    topicNaming: TopicNaming,
    private val decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
) : DistributedMessageBus<M, E>
    where M : Message<*, *>, M : AggregateIdCapable, M : NamedAggregate, E : MessageExchange<*, M> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val topicNaming: TopicNaming = topicNaming.memoized()

    abstract val messageType: Class<M>

    /**
     * The exchange for a decoded [message]; acknowledging it acknowledges [record].
     */
    protected abstract fun createExchange(message: M, record: TransportRecord): E

    /** The transport's resources for the runtime to close ([Transport.runtimeResource]). */
    val runtimeResource: RuntimeResource
        get() = transport.runtimeResource

    fun topicOf(namedAggregate: NamedAggregate): String = topicNaming.topicOf(namedAggregate)

    override fun send(message: M): Mono<Void> =
        Mono.defer {
            log.debug { "Send $message." }
            transport.send(encode(message))
        }

    /**
     * [message] as it goes on the wire. Makes [message] read-only.
     */
    fun encode(message: M): TransportMessage {
        message.withReadOnly()
        return TransportMessage(
            topic = topicOf(message),
            key = message.aggregateId.id,
            payload = message.toJsonString(),
            timestamp = message.createTime,
        )
    }

    /**
     * The message [record] carries, read-only.
     *
     * @throws IllegalArgumentException when the record has no payload
     * @throws TransportRecordMismatchException when its key (on a [keyed][TransportRecord.keyed] record) or topic
     * does not match the decoded message
     */
    fun decode(record: TransportRecord): M {
        val payload = requireNotNull(record.payload) {
            "Transport record has no payload."
        }
        val message = payload.toObject(messageType)
        if (record.keyed && record.key != message.aggregateId.id) {
            throw TransportRecordMismatchException("Transport record key does not match the decoded aggregate id.")
        }
        if (record.topic != topicOf(message)) {
            throw TransportRecordMismatchException("Transport record topic does not match the decoded aggregate.")
        }
        message.withReadOnly()
        return message
    }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<E> {
        val group = subscription.receiverGroup
        val transportReceiver = transport.open(
            group = group,
            topics = subscription.namedAggregates.mapTo(linkedSetOf(), topicNaming::topicOf),
        )
        // A decode failure reaches readiness from inside the inner publisher, before concatMap cancels the transport,
        // whose readiness would otherwise fail first with its own cancellation error.
        val decodeFailure = Sinks.empty<Void>()
        val messages = transportReceiver.records
            .concatMap { record ->
                decodeRecord(group, record).doOnError { decodeFailure.tryEmitError(it) }
            }
        return MessageReceiver(
            messages = messages,
            readiness = Mono.firstWithSignal(decodeFailure.asMono(), transportReceiver.readiness),
            processingAdmission = transportReceiver::openProcessing,
            processingQuiescence = transportReceiver::close,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeRecord(group: String, record: TransportRecord): Mono<E> {
        val message = try {
            decode(record)
        } catch (cause: Exception) {
            return handleDecodeFailure(TransportDecodeFailure(record, group, messageType, cause))
        }
        return Mono.just(createExchange(message, record))
    }

    private fun handleDecodeFailure(failure: TransportDecodeFailure): Mono<E> =
        Mono.defer { decodeFailureHandler.handle(failure) }
            .defaultIfEmpty(TransportDecodeFailureAction.FAIL)
            .flatMap { action ->
                when (action) {
                    TransportDecodeFailureAction.FAIL -> Mono.error(TransportDecodeException(failure))
                    TransportDecodeFailureAction.ACKNOWLEDGE -> failure.record.ack().then(Mono.empty())
                    TransportDecodeFailureAction.LEAVE_PENDING -> Mono.empty()
                }
            }

    override fun close() {
        log.info { "[${this.javaClass.simpleName}] Close transport." }
        transport.close()
    }
}
