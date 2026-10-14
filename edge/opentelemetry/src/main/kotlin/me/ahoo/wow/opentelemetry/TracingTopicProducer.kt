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

package me.ahoo.wow.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import me.ahoo.wow.opentelemetry.WowAttributes.MESSAGING_BATCH_COUNT
import me.ahoo.wow.opentelemetry.WowAttributes.MESSAGING_DESTINATION
import me.ahoo.wow.opentelemetry.WowAttributes.MESSAGING_OPERATION
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import kotlin.coroutines.cancellation.CancellationException

/**
 * A [TopicProducer] that sends through [delegate] in a span of its own, `<topic> publish` of kind PRODUCER, the child
 * of the span the sender runs in (decision D174): a Kafka produce. The messages themselves carry the sender's trace
 * already ([me.ahoo.wow.spi.log.Transport.trace]), so their consumers continue the sender's trace, beside this span.
 *
 * Closing it closes [delegate] when that is [AutoCloseable], as a host that replaced its producer bean by this one
 * closes the bean.
 */
class TracingTopicProducer(val delegate: TopicProducer, openTelemetry: OpenTelemetry) :
    TopicProducer,
    AutoCloseable {
    private val tracer = openTelemetry.getTracer(WowAttributes.INSTRUMENTATION)

    override suspend fun send(topic: String, messages: List<TopicMessage>) {
        val span = tracer.spanBuilder("$topic publish")
            .setSpanKind(SpanKind.PRODUCER)
            .setParent(Context.current())
            .setAttribute(MESSAGING_DESTINATION, topic)
            .setAttribute(MESSAGING_OPERATION, "send")
            .setAttribute(MESSAGING_BATCH_COUNT, messages.size.toLong())
            .startSpan()
        try {
            delegate.send(topic, messages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Recorded on the span, then rethrown unchanged.
            failure: Exception,
        ) {
            span.recordException(failure)
            span.setStatus(StatusCode.ERROR, failure.message.orEmpty())
            throw failure
        } finally {
            span.end()
        }
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }

    override fun toString(): String = "TracingTopicProducer($delegate)"
}
