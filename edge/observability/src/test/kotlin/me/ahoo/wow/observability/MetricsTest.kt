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

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The metrics and spans of wow-observability beside the core's observer: queries, Kafka produces, parked entries. */
class MetricsTest {
    private val telemetry = Telemetry()

    @Test
    fun `a query should be a span and a timer by its outcome`(): Unit = runBlocking {
        val queries = OtelQueryObserver(telemetry.sdk, telemetry.meters)
        queries.start(ORDER, QueryType.LIST, QueryModel.SNAPSHOT).observe { }
        assertThrows<IllegalArgumentException> {
            queries.start(
                ORDER,
                QueryType.PAGED,
                QueryModel.EVENTS
            ).observe { throw IllegalArgumentException("A bad filter.") }
        }
        assertThrows<CancellationException> {
            queries.start(
                ORDER,
                QueryType.LIST,
                QueryModel.SNAPSHOT
            ).observe { throw CancellationException("The collector left.") }
        }
        val (list, paged) = telemetry.named("query $ORDER")
        list.attr(WowAttributes.QUERY_TYPE).assert().isEqualTo("list")
        list.attr(WowAttributes.AGGREGATE).assert().isEqualTo("order")
        paged.status.statusCode.assert().isEqualTo(StatusCode.ERROR)
        fun count(type: QueryType, outcome: String) = telemetry.meters.find(WowMetrics.QUERY_DURATION.name)
            .tags(WowMetrics.QUERY_DURATION.tagsOf(SALES, "order", type.name.lowercase(), outcome))
            .timer()?.count()
        count(QueryType.LIST, ObservedOutcome.OK.value).assert().isEqualTo(1L)
        count(QueryType.PAGED, ObservedOutcome.ERROR.value).assert().isEqualTo(1L)
        count(QueryType.LIST, ObservedOutcome.CANCELLED.value).assert().isEqualTo(1L)
    }

    @Test
    fun `a dataset's query should be tagged with the dataset, not an aggregate type`(): Unit = runBlocking {
        val queries = OtelQueryObserver(telemetry.sdk, telemetry.meters)
        val dataset = AggregateType(SALES, "revenue")
        queries.start(dataset, QueryType.COUNT, QueryModel.DATASET).observe { }
        val span = telemetry.named("query $dataset").single()
        span.attr(WowAttributes.DATASET).assert().isEqualTo("revenue")
        span.attributes.asMap().keys.map { it.key }.assert().doesNotContain(WowAttributes.AGGREGATE)
        telemetry.meters.find(WowMetrics.DATASET_QUERY_DURATION.name)
            .tags(WowMetrics.DATASET_QUERY_DURATION.tagsOf(SALES, "revenue", "count", ObservedOutcome.OK.value))
            .timer()?.count().assert().isEqualTo(1L)
        telemetry.meters.find(WowMetrics.QUERY_DURATION.name).timer().assert().isNull()
    }

    @Test
    fun `a produce should be a producer span, and close what it wraps`(): Unit = runBlocking {
        val topics = InMemoryTopics(1)
        val producer = TracingTopicProducer(topics, telemetry.sdk)
        producer.send("orders", TopicMessage.of("o-1", "{}".toByteArray()))
        val publish = telemetry.single("orders publish")
        publish.kind.assert().isEqualTo(SpanKind.PRODUCER)
        publish.attr(WowAttributes.MESSAGING_DESTINATION).assert().isEqualTo("orders")
        publish.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey(WowAttributes.MESSAGING_BATCH_COUNT))
            .assert().isEqualTo(1L)
        producer.toString().assert().isEqualTo("TracingTopicProducer($topics)")
        producer.close()
    }

    @Test
    fun `a produce that fails should be recorded on its span, and a cancelled one only ended`(): Unit = runBlocking {
        val topics = InMemoryTopics(1).apply { failure = IllegalStateException("The broker is down.") }
        assertThrows<IllegalStateException> {
            TracingTopicProducer(topics, telemetry.sdk).send("orders", TopicMessage.of("o-1", "{}".toByteArray()))
        }
        val leaving = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) =
                throw CancellationException("The sender left.")
        }
        val cancelled = TracingTopicProducer(leaving, telemetry.sdk)
        assertThrows<CancellationException> { cancelled.send("orders", TopicMessage.of("o-1", "{}".toByteArray())) }
        // A producer that holds nothing to close is closed as a no-op.
        cancelled.close()
        val (failed, left) = telemetry.named("orders publish")
        failed.status.statusCode.assert().isEqualTo(StatusCode.ERROR)
        failed.status.description.assert().isEqualTo("The broker is down.")
        left.status.statusCode.assert().isEqualTo(StatusCode.UNSET)
    }

    @Test
    fun `the aggregates a pinned events query checked should be counted by how they were read`() {
        val queries = OtelQueryObserver(telemetry.sdk, telemetry.meters)
        queries.onEventsPinned(ORDER, 3, 1)
        queries.onEventsPinned(ORDER, 2, 0)
        fun count(read: String) = telemetry.meters.find(WowMetrics.EVENTS_PINNED.name)
            .tags(WowMetrics.CONTEXT, SALES, WowMetrics.AGGREGATE, "order", WowMetrics.READ, read).counter()?.count()
        count(WowMetrics.BATCH_READ).assert().isEqualTo(5.0)
        count(WowMetrics.POINT_READ).assert().isEqualTo(1.0)
    }

    @Test
    fun `each parked notice should be counted by subscription and kind`(): Unit = runBlocking {
        val parked = ParkedMetrics(telemetry.meters)
        val entry = ParkedAggregate("Board.onOrder", ORDER.ref("t", "o-1"), 1, "boom", 0)
        parked.notify(ParkedNotice(ParkedNotice.Kind.PARKED, "g", entry, ParkedRetrySpec(), 0))
        parked.notify(ParkedNotice(ParkedNotice.Kind.PARKED, "g", entry, ParkedRetrySpec(), 0))
        parked.notify(ParkedNotice(ParkedNotice.Kind.EXHAUSTED, "g", entry, ParkedRetrySpec(), 0))
        fun count(kind: String) = telemetry.meters.find(WowMetrics.PARKED_NOTICES.name)
            .tags(WowMetrics.SUBSCRIPTION, "Board.onOrder", WowMetrics.KIND, kind).counter()!!.count()
        count("parked").assert().isEqualTo(2.0)
        count("exhausted").assert().isEqualTo(1.0)
    }

    @Test
    fun `each undecodable message should be counted by topic and whether it parked its aggregate`() {
        // Review A3-14.
        telemetry.observer.observed(ObservedEvent.Undecodable("orders", 0, 7, parked = true))
        telemetry.observer.observed(ObservedEvent.Undecodable("orders", 1, 8, parked = false))
        telemetry.observer.observed(ObservedEvent.Undecodable("orders", 1, 9, parked = false))
        fun count(outcome: String) = telemetry.meters.find(WowMetrics.UNDECODABLE_MESSAGES.name)
            .tags(WowMetrics.TOPIC, "orders", WowMetrics.OUTCOME, outcome).counter()!!.count()
        count(WowMetrics.PARKED_OUTCOME).assert().isEqualTo(1.0)
        count(WowMetrics.SKIPPED_OUTCOME).assert().isEqualTo(2.0)
    }
}
