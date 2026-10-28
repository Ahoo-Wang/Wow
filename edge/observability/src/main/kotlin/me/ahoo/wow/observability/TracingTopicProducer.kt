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

package me.ahoo.wow.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.observability.WowAttributes.MESSAGING_BATCH_COUNT
import me.ahoo.wow.observability.WowAttributes.MESSAGING_DESTINATION
import me.ahoo.wow.observability.WowAttributes.MESSAGING_OPERATION
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import kotlin.coroutines.cancellation.CancellationException

/**
 * A [TopicProducer] that sends through [delegate] in a span of its own, `send <topic>` of kind PRODUCER, the child
 * of the span the sender runs in: a Kafka produce, its `messaging.system` [messagingSystem]. The span ends with its
 * `wow.outcome`, and `error.type` after a failure. The messages themselves carry the sender's trace
 * already ([me.ahoo.wow.spi.log.Transport.trace]), so their consumers continue the sender's trace, beside this span.
 *
 * Closing it closes [delegate] when that is [AutoCloseable], as a host that replaced its producer bean by this one
 * closes the bean.
 */
@InternalWowApi
class TracingTopicProducer(
    val delegate: TopicProducer,
    openTelemetry: OpenTelemetry,
    private val messagingSystem: String = OtelObserver.MEMORY_SYSTEM,
) :
    TopicProducer,
    AutoCloseable {
    private val tracer = openTelemetry.wowTracer()

    override suspend fun send(topic: String, messages: List<TopicMessage>) {
        val span = tracer.spanBuilder("$SEND $topic")
            .setSpanKind(SpanKind.PRODUCER)
            .setParent(Context.current())
            .setAttribute(WowAttributes.MESSAGING_SYSTEM, messagingSystem)
            .setAttribute(MESSAGING_DESTINATION, topic)
            .setAttribute(WowAttributes.MESSAGING_OPERATION_NAME, SEND)
            .setAttribute(MESSAGING_OPERATION, SEND)
            .setAttribute(MESSAGING_BATCH_COUNT, messages.size.toLong())
            .startSpan()
        var outcome = ObservedOutcome.OK
        try {
            delegate.send(topic, messages)
        } catch (cancelled: CancellationException) {
            outcome = ObservedOutcome.CANCELLED
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Recorded on the span, then rethrown unchanged.
            failure: Exception,
        ) {
            outcome = ObservedOutcome.ERROR
            span.setAttribute(WowAttributes.ERROR_TYPE, failure.javaClass.name)
            span.recordException(failure)
            span.setStatus(StatusCode.ERROR, failure.message.orEmpty())
            throw failure
        } finally {
            span.setAttribute(WowAttributes.OUTCOME, outcome.value)
            span.end()
        }
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }

    override fun toString(): String = "TracingTopicProducer($delegate)"

    private companion object {
        /** OpenTelemetry's operation of a produce: the span name's verb and `messaging.operation.name`. */
        const val SEND = "send"
    }
}
