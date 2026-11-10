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

package me.ahoo.wow.tck.log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test

/**
 * The record cases of the [EventLog] contract: a stream reads back exactly as appended, its versions, payload, scope,
 * headers, operator, access attributes and schedule events, and one aggregate id under two types is two streams. They
 * read only [EventLogFixtures]' fixtures, hooks and constants; see [EventLogFixtures] for the chain of families this
 * class is part of.
 */
abstract class EventLogRecordCases internal constructor() : EventLogFixtures() {
    @Test
    fun `an aggregate's first stream is version 1 and each next one exactly one more`(): Unit = runBlocking {
        val log = createLog()
        log.append(stream("a", 2)).assert().isEqualTo(AppendOutcome.Conflict(0))
        log.append(stream("a", 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 3)).assert().isEqualTo(AppendOutcome.Conflict(1))
        log.append(stream("a", 1)).assert().isEqualTo(AppendOutcome.Conflict(1))
        log.append(stream("a", 2)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `a stream reads back exactly as appended`(): Unit = runBlocking {
        val log = createLog()
        val appended = stream("a", 1)
        log.append(appended)
        val read = log.read(type.ref("tenant", "a")).toList().single()
        read.assertSameAs(appended)
        read.events.map { it.name to it.revision }.assert().containsExactly(
            "$context.aggregate.Happened" to 1,
            "$context.aggregate.Noted" to 2,
        )
    }

    @Test
    fun `payload numbers read back exactly as appended`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        val appended = stream("a", 1).let {
            it.copyWith(events = listOf(EventRecord("$context.aggregate.Priced", 1, ExactJson.NUMBERS)) + it.events)
        }
        log.append(appended)
        log.read(appended.ref).toList().single().assertSameAs(appended)
        checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(1).toList() }.single().stream.assertSameAs(appended)
    }

    @Test
    fun `scope, causation, headers and operator read back as appended, absent ones too`(): Unit = runBlocking {
        val log = createLog()
        val full = stream(
            "a",
            1,
            scope = Scope("owner ü", "space"),
            causation = Causation("root", "$context.aggregate/tenant/b@3"),
            headers = mapOf("app" to "console", "quote" to "\" é"),
            operator = Operator("alice ü", "home", crossScope = true),
        )
        val ownerOnly = stream("b", 1, scope = Scope(ownerId = "owner"), operator = Operator("bob", "tenant"))
        val spaceOnly = stream("c", 1, scope = Scope(spaceId = "space"))
        val none = stream("d", 1, scope = Scope.NONE)
        listOf(full, ownerOnly, spaceOnly, none).forEach { log.append(it) }
        listOf(full, ownerOnly, spaceOnly, none).forEach { appended ->
            log.read(appended.ref).toList().single().assertSameAs(appended)
            checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        }
    }

    @Test
    fun `the access attributes read back as appended, none and not stamped told apart`(): Unit = runBlocking {
        val log = createLog()
        val stamped = stream(
            "a",
            1,
            access = linkedMapOf(
                "owner" to "owner",
                "space" to null,
                "region" to "north ü",
                "level" to 3L,
                "public" to true,
                "teams" to listOf("eng", "ops"),
                "grades" to listOf(1L, 2L),
                "none" to emptyList<String>(),
            ),
        )
        val none = stream("b", 1, access = emptyMap())
        val unstamped = stream("c", 1, access = null)
        val streams = listOf(stamped, none, unstamped)
        streams.forEach { log.append(it) }
        streams.forEach { appended ->
            log.read(appended.ref).toList().single().assertSameAs(appended)
            checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        }
        streams.forEach { appended ->
            log.readEntries(appended.ref, 1, 1).toList().single().stream.access.assert().isEqualTo(appended.access)
        }
    }

    @Test
    fun `the schedule system events read back as appended, alone or with the events`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        val set = EventRecord(
            "wow.ScheduleSet",
            1,
            """{"key":"pay-timeout","rule":{"type":"after","millis":600000},"target":{"type":{"context":"other",""" +
                """"name":"order"},"tenantId":"tenant","id":"o-1"},"commandType":"other.order.CancelOrder",""" +
                """"command":"{\"reason\":\"unpaid ü\",\"amount\":2.50}"}""",
        )
        val cancelled = EventRecord("wow.ScheduleCancelled", 1, """{"key":"remind"}""")
        val withEvents = stream("a", 1).let { it.copyWith(events = it.events + set + cancelled) }
        val alone = stream("a", 2)
            .copyWith(events = listOf(EventRecord("wow.ScheduleCancelled", 1, """{"key":"pay"}""")))
        log.append(withEvents)
        log.append(alone)
        val read = log.read(withEvents.ref).toList()
        read.map { stream -> stream.events.map { it.name to ExactJson.of(it.payload) } }.assert().containsExactly(
            withEvents.events.map { it.name to ExactJson.of(it.payload) },
            alone.events.map { it.name to ExactJson.of(it.payload) },
        )
        val tailed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }
        tailed.map { it.stream.events.map { event -> event.name } }.assert()
            .containsExactly(withEvents.events.map { it.name }, listOf("wow.ScheduleCancelled"))
    }

    @Test
    fun `a payload with a NUL character reads back as appended, or is rejected, never unknown`(): Unit = runBlocking {
        // PostgreSQL's jsonb refuses it, and the append was answered as unknown, retried for ever.
        val log = createLog(partitions = 1)
        val appended = stream("a", 1).copyWith(
            events = listOf(EventRecord("$context.aggregate.Named", 1, """{"name":"a\u0000b","n\u0000":1}""")),
        )
        when (val outcome = log.append(appended)) {
            is AppendOutcome.Committed -> log.read(appended.ref).toList().single().assertSameAs(appended)
            is AppendOutcome.Rejected -> {
                outcome.cause.assert().isInstanceOf(IllegalArgumentException::class.java)
                log.read(appended.ref).toList().assert().isEmpty()
            }

            else -> throw AssertionError("A payload with a NUL character is committed or rejected, not $outcome.")
        }
    }

    @Test
    fun `one aggregate id under two aggregate types is two streams`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val first = (log.append(stream("same", 1)) as AppendOutcome.Committed).position
        val second = (log.append(stream("same", 1, aggregate = other)) as AppendOutcome.Committed).position
        log.append(stream("same", 1)).assert().isEqualTo(AppendOutcome.Conflict(1))
        log.append(stream("same", 3, aggregate = other)).assert().isEqualTo(AppendOutcome.Conflict(1))
        log.append(stream("same", 2, aggregate = other)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(type.ref("tenant", "same")).toList().map { it.version }.assert().containsExactly(1)
        log.read(other.ref("tenant", "same")).toList().map { it.version }.assert().containsExactly(1, 2)
        log.positionOf(type.ref("tenant", "same"), 1).assert().isEqualTo(first)
        log.positionOf(other.ref("tenant", "same"), 1).assert().isEqualTo(second)
        log.positionOf(type.ref("tenant", "same"), 2).assert().isNull()
    }

    @Test
    fun `the lookup by aggregate type returns facts only, as appended`(): Unit = runBlocking {
        val log = createLog()
        val appended = stream("a", 1, causation = Causation("root", "$context.aggregate/tenant/b@3"))
        log.append(appended, TransportCodec.encode(Transport(trace = mapOf("traceparent" to "t"))))
        checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        log.findByRequestId(AggregateType(context, "untouched"), "tenant", appended.requestId).assert().isNull()
    }
}
