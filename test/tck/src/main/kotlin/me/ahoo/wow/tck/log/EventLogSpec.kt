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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The [EventLog] contract (documentation/designs/v10/03-log-and-snapshot.md §1) every backend passes. A backend's
 * test extends it and supplies a fresh, empty log from [createLog].
 */
abstract class EventLogSpec {
    /** A new, empty log of bounded context [context] with [partitions] partitions. */
    protected abstract fun createLog(context: String, partitions: Int): EventLog

    /** Each test gets its own context, so a backend that keeps a log per context isolates the tests. */
    private val context = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
    private val type = AggregateType(context, "aggregate")
    private val other = AggregateType(context, "other")

    private fun createLog(partitions: Int = DEFAULT_PARTITIONS): EventLog = createLog(context, partitions)

    /**
     * [stream] changed so that this backend refuses it ([AppendOutcome.Rejected]): a value it cannot store. Null, the
     * default, for a backend that stores every stream, which skips the cases of a refused stream.
     */
    protected open fun refused(stream: EventStreamRecord): EventStreamRecord? = null

    protected fun stream(
        id: String,
        version: Int,
        requestId: String = "$id-$version-${UUID.randomUUID()}",
        tenant: String = "tenant",
        commandType: String = "$context.aggregate.Act",
        scope: Scope = Scope("owner", "space"),
        causation: Causation? = null,
        headers: Map<String, String> = emptyMap(),
        aggregate: AggregateType = type,
        operator: Operator? = null,
    ) = EventStreamRecord(
        ref = aggregate.ref(tenant, id),
        version = version,
        commandId = "command-$requestId",
        requestId = requestId,
        commandType = commandType,
        commitTime = 1_700_000_000_000L + version,
        events = listOf(
            EventRecord("$context.aggregate.Happened", 1, """{"version":$version,"text":"ü \" é"}"""),
            EventRecord("$context.aggregate.Noted", 2, """{"note":"second"}"""),
        ),
        scope = scope,
        causation = causation,
        headers = headers,
        operator = operator,
    )

    /** Asserts this is [expected], comparing payloads as JSON: a backend may store them as JSON and reformat them. */
    private fun EventStreamRecord.assertSameAs(expected: EventStreamRecord) {
        events.map { ExactJson.of(it.payload) }.assert().isEqualTo(expected.events.map { ExactJson.of(it.payload) })
        copyWith(events = expected.events).assert().isEqualTo(expected)
    }

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
        checkNotNull(log.findByRequestId("tenant", appended.requestId)).assertSameAs(appended)
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
            checkNotNull(log.findByRequestId("tenant", appended.requestId)).assertSameAs(appended)
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
    fun `a request id reused by another command type finds the stream committed for it`(): Unit = runBlocking {
        val log = createLog()
        log.append(stream("a", 1, requestId = "request", commandType = "$context.aggregate.Open"))
        val again = log.append(stream("b", 1, requestId = "request", commandType = "$context.aggregate.Close"))
        (again as AppendOutcome.AlreadyCommitted).existing.commandType.assert().isEqualTo("$context.aggregate.Open")
    }

    @Test
    fun `transport is given back by tail and readEntries only`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        val transport = Transport(
            wait = WaitContext(
                waitId = "w-1",
                origin = Origin("node-1", "10.0.0.7:8081", "order-service"),
                remaining = listOf(
                    Hop.handled("payment-saga", "payment"),
                    Hop.committed("inventory"),
                    Hop.snapshot(),
                ),
                deadline = 1_700_000_010_000L,
            ),
            reservations = listOf(
                ReservationKey("user-name", "ahoo"),
                ReservationKey("email", "a@b.c ü", system = true)
            ),
            trace = mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
            releases = listOf(ReservationKey("user-name", "old"), ReservationKey("login", "\$old.name", system = true)),
        )
        val carried = stream("a", 1)
        log.append(carried, transport)
        log.append(stream("a", 2))
        log.append(stream("a", 3), Transport(trace = mapOf("traceparent" to "t")))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }
        entries[0].transport.assert().isEqualTo(transport)
        entries[0].stream.assertSameAs(carried)
        entries[1].transport.assert().isNull()
        entries[2].transport.assert().isEqualTo(Transport(trace = mapOf("traceparent" to "t")))
        log.readEntries(carried.ref).toList().map { it.transport }.assert().isEqualTo(entries.map { it.transport })
        // read and findByRequestId return facts only: an EventStreamRecord has no place for the transport.
        log.read(carried.ref, 1, 1).toList().single().assertSameAs(carried)
        checkNotNull(log.findByRequestId("tenant", carried.requestId)).assertSameAs(carried)
    }

    @Test
    fun `readEntries returns a version range in version order, each entry with its position and transport`(): Unit =
        runBlocking {
            val log = createLog(partitions = 4)
            val transport = Transport(reservations = listOf(ReservationKey("user-name", "ahoo ü")))
            val appended = listOf(stream("a", 1), stream("a", 2), stream("a", 3), stream("a", 4))
            val positions = appended.mapIndexed { index, stream ->
                log.append(stream("b", index + 1))
                val outcome = log.append(stream, transport.takeIf { index % 2 == 1 })
                (outcome as AppendOutcome.Committed).position
            }
            val entries = log.readEntries(type.ref("tenant", "a"), 2, 3).toList()
            entries.map { it.stream.version }.assert().containsExactly(2, 3)
            entries.map { it.position }.assert().containsExactly(positions[1], positions[2])
            entries.map { it.transport }.assert().containsExactly(transport, null)
            entries[0].stream.assertSameAs(appended[1])
            log.readEntries(type.ref("tenant", "a")).toList().map { it.position }.assert().isEqualTo(positions)
            log.readEntries(type.ref("tenant", "missing")).toList().assert().isEmpty()
            log.readEntries(type.ref("other", "a")).toList().assert().isEmpty()
        }

    @Test
    fun `positionOf finds where a version of an aggregate was committed`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val first = (log.append(stream("a", 1)) as AppendOutcome.Committed).position
        log.append(stream("b", 1))
        val second = (log.append(stream("a", 2)) as AppendOutcome.Committed).position
        log.positionOf(type.ref("tenant", "a"), 1).assert().isEqualTo(first)
        log.positionOf(type.ref("tenant", "a"), 2).assert().isEqualTo(second)
        log.positionOf(type.ref("tenant", "a"), 3).assert().isNull()
        log.positionOf(type.ref("other", "a"), 1).assert().isNull()
    }

    @Test
    fun `appendAll keeps each stream's transport`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (first, second) = idsInPartitionZero(2, 4)
        val transport = Transport(reservations = listOf(ReservationKey("ns", "k")))
        log.appendAll(listOf(stream(first, 1) to transport, stream(second, 1) to null))
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val entries = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }
        entries.associate { it.stream.ref.id to it.transport }
            .assert().isEqualTo(mapOf(first to transport, second to null))
    }

    @Test
    fun `head is the last committed sequence of a partition, null when it is empty`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        (0 until 4).forEach { log.head(it).assert().isNull() }
        val ids = idsInPartitionZero(3, 4)
        ids.forEach { log.append(stream(it, 1)) }
        log.append(stream(ids.first(), 2))
        log.head(0).assert().isEqualTo(4L)
        log.append(stream(ids.first(), 2)).assert().isEqualTo(AppendOutcome.Conflict(2))
        log.head(0).assert().isEqualTo(4L)
    }

    @Test
    fun `read returns a version range in version order`(): Unit = runBlocking {
        val log = createLog()
        (1..5).forEach { log.append(stream("a", it)) }
        log.read(type.ref("tenant", "a"), 2, 4).toList().map { it.version }.assert().containsExactly(2, 3, 4)
        log.read(type.ref("tenant", "missing")).toList().assert().isEmpty()
    }

    @Test
    fun `two tenants may use the same aggregate id`(): Unit = runBlocking {
        val log = createLog()
        log.append(stream("same", 1, tenant = "t1")).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("same", 1, tenant = "t2")).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(type.ref("t1", "same")).toList().size.assert().isEqualTo(1)
    }

    @Test
    fun `a request id reused in a tenant and aggregate type answers the first commit, in another tenant it commits`(): Unit =
        runBlocking {
            val log = createLog()
            val first = stream("a", 1, requestId = "request")
            log.append(first)
            val again = log.append(stream("b", 1, requestId = "request")) as AppendOutcome.AlreadyCommitted
            again.existing.ref.assert().isEqualTo(first.ref)
            again.existing.requestId.assert().isEqualTo("request")
            log.append(stream("a", 1, requestId = "request", tenant = "other"))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.findByRequestId("tenant", "request")?.ref.assert().isEqualTo(first.ref)
            log.findByRequestId("tenant", "missing").assert().isNull()
            log.findByRequestId("nobody", "request").assert().isNull()
            log.findByRequestId(type, "tenant", "request")?.ref.assert().isEqualTo(first.ref)
            log.findByRequestId(type, "tenant", "missing").assert().isNull()
            log.findByRequestId(type, "nobody", "request").assert().isNull()
            log.findByRequestId(other, "tenant", "request").assert().isNull()
        }

    @Test
    fun `concurrent appends to one version let exactly one win`(): Unit = runBlocking {
        val log = createLog()
        log.append(stream("a", 1))
        val outcomes = coroutineScope { (1..8).map { async { log.append(stream("a", 2)) } }.awaitAll() }
        outcomes.count { it is AppendOutcome.Committed }.assert().isEqualTo(1)
        outcomes.filterIsInstance<AppendOutcome.Conflict>().size.assert().isEqualTo(7)
    }

    @Test
    fun `a retry of a request at the version it committed is a duplicate, not a conflict`(): Unit = runBlocking {
        val log = createLog()
        log.append(stream("a", 1))
        val first = stream("a", 2, requestId = "request")
        log.append(first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        // Both keys collide: the request key decides (finding A2-1).
        val retry = log.append(first.copyWith(commandId = "retry", commitTime = first.commitTime + 1))
        (retry as AppendOutcome.AlreadyCommitted).existing.assertSameAs(first)
    }

    @Test
    fun `appendAll reports a retry of a request at the version it committed as a duplicate`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (retried, fresh) = idsInPartitionZero(2, 4)
        val first = stream(retried, 1, requestId = "request")
        log.append(first)
        val outcomes = log.appendAll(listOf(first.copyWith(commandId = "group") to null, stream(fresh, 1) to null))
        (outcomes[0] as AppendOutcome.AlreadyCommitted).existing.assertSameAs(first)
        outcomes[1].assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `concurrent appends of one request at one version commit once, the others are duplicates`(): Unit =
        runBlocking {
            val log = createLog()
            log.append(stream("a", 1))
            val outcomes = coroutineScope {
                (1..8).map { attempt ->
                    async { log.append(stream("a", 2, requestId = "request").copyWith(commandId = "attempt-$attempt")) }
                }.awaitAll()
            }
            outcomes.count { it is AppendOutcome.Committed }.assert().isEqualTo(1)
            val duplicates = outcomes.filterIsInstance<AppendOutcome.AlreadyCommitted>()
            duplicates.size.assert().isEqualTo(7)
            duplicates.map { it.existing.commandId }.toSet().size.assert().isEqualTo(1)
        }

    @Test
    fun `an entry lands in its ref's partition, sequenced from 1`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val positions = (1..20).map { (log.append(stream("a-$it", 1)) as AppendOutcome.Committed).position }
        (1..20).forEach { index ->
            positions[index - 1].partition.assert()
                .isEqualTo(Partitioner.partitionOf(type.ref("tenant", "a-$index"), 4))
        }
        positions.groupBy { it.partition }.values.forEach { inPartition ->
            inPartition.map { it.sequence }.assert().isEqualTo((1L..inPartition.size).toList())
        }
    }

    @Test
    fun `tail never skips a committed entry, however appends interleave`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        val total = 200
        val tailed = async { withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(total).toList() } }
        coroutineScope {
            (1..10).forEach { writer ->
                launch { (1..total / 10).forEach { log.append(stream("w$writer", it)) } }
            }
        }
        val entries = tailed.await()
        entries.map { it.position.sequence }.assert().isEqualTo((1L..total).toList())
        (1..10).forEach { writer ->
            entries.filter { it.stream.ref.id == "w$writer" }.map { it.stream.version }
                .assert().isEqualTo((1..total / 10).toList())
        }
    }

    /** Distinct aggregate ids that land in partition 0 of [partitions]. */
    private fun idsInPartitionZero(count: Int, partitions: Int, aggregate: AggregateType = type): List<String> =
        generateSequence(1) { it + 1 }.map { "g-$it" }
            .filter { Partitioner.partitionOf(aggregate.ref("tenant", it), partitions) == 0 }
            .take(count)
            .toList()

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @Test
    fun `appendAll gives each stream the outcome append would`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (fits, behind, ahead, duplicate) = idsInPartitionZero(4, 4)
        log.append(stream(behind, 1))
        log.append(stream(duplicate, 1, requestId = "taken"))
        val outcomes = log.appendAll(
            listOf(
                stream(fits, 1) to null,
                stream(behind, 1) to null,
                stream(ahead, 3) to null,
            ),
        )
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(1))
        outcomes[2].assert().isEqualTo(AppendOutcome.Conflict(0))
        val withDuplicate = log.appendAll(
            listOf(stream(ahead, 1) to null, stream(duplicate, 2, requestId = "taken") to null),
        )
        withDuplicate[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (withDuplicate[1] as AppendOutcome.AlreadyCommitted).existing.ref.id.assert().isEqualTo(duplicate)
    }

    @Test
    fun `a stream the backend refuses is rejected, and in appendAll fails alone`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (before, bad, after) = idsInPartitionZero(3, 4)
        val probe = refused(stream(bad, 1))
        Assumptions.assumeTrue(probe != null, "This backend stores every stream.")
        val refused = checkNotNull(probe)
        log.append(refused).assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        log.head(0).assert().isNull()
        // Finding A2-5: the refused stream failed the whole group.
        val outcomes = log.appendAll(listOf(stream(before, 1) to null, refused to null, stream(after, 1) to null))
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(refused.ref).toList().assert().isEmpty()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }.map { it.stream.ref.id }
            .assert().containsExactlyInAnyOrder(before, after)
    }

    @Test
    fun `a payload with a NUL character reads back as appended, or is rejected, never unknown`(): Unit = runBlocking {
        // Finding A2-7: PostgreSQL's jsonb refuses it, and the append was answered as unknown, retried for ever.
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
    fun `appendAll sequences a group without gaps`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val ids = idsInPartitionZero(10, 4)
        val outcomes = log.appendAll(ids.map { stream(it, 1) to null })
        outcomes.map { (it as AppendOutcome.Committed).position.sequence }.assert().isEqualTo((1L..10L).toList())
        val more = log.appendAll(ids.take(3).map { stream(it, 2) to null })
        more.map { (it as AppendOutcome.Committed).position.sequence }.assert().isEqualTo((11L..13L).toList())
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(13).toList() }.map { it.position.sequence }
            .assert().isEqualTo((1L..13L).toList())
    }

    @Test
    fun `tail resumes after a position with the same entries`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        (1..10).forEach { log.append(stream("a", it)) }
        val first = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(10).toList() }
        val resumed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0, after = 4).take(6).toList() }
        resumed.assert().isEqualTo(first.drop(4))
    }

    @Test
    fun `appends of two aggregate types interleaved in one partition tail back in sequence order`(): Unit =
        runBlocking {
            val log = createLog(partitions = 1)
            val perWriter = 10
            val writers = (1..4).flatMap { listOf(type to "w$it", other to "w$it") }
            val total = writers.size * perWriter
            val tailed = async { withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(total).toList() } }
            coroutineScope {
                writers.forEach { (aggregate, id) ->
                    launch {
                        (1..perWriter).forEach {
                            log.append(stream(id, it, aggregate = aggregate))
                                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
                        }
                    }
                }
            }
            val entries = tailed.await()
            entries.map { it.position.sequence }.assert().isEqualTo((1L..total).toList())
            writers.forEach { (aggregate, id) ->
                entries.filter { it.stream.ref == aggregate.ref("tenant", id) }.map { it.stream.version }
                    .assert().isEqualTo((1..perWriter).toList())
            }
            log.head(0).assert().isEqualTo(total.toLong())
            val resumed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0, after = 7).take(total - 7).toList() }
            resumed.assert().isEqualTo(entries.drop(7))
            log.read(other.ref("tenant", "w1")).toList().map { it.ref.type }.distinct().assert().containsExactly(other)
            log.readEntries(type.ref("tenant", "w1")).toList().map { it.position }
                .assert().isEqualTo(entries.filter { it.stream.ref == type.ref("tenant", "w1") }.map { it.position })
        }

    @Test
    fun `appendAll sequences a group of two aggregate types without gaps`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val ids = idsInPartitionZero(3, 4).zip(idsInPartitionZero(3, 4, other))
        val group = ids.flatMap { (first, second) ->
            listOf(stream(first, 1) to null, stream(second, 1, aggregate = other) to null)
        }
        log.appendAll(group).map { (it as AppendOutcome.Committed).position.sequence }
            .assert().isEqualTo((1L..6L).toList())
        val entries = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(6).toList() }
        entries.map { it.stream.ref }.assert().isEqualTo(group.map { it.first.ref })
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
    fun `a request id is unique within its tenant and aggregate type`(): Unit = runBlocking {
        val log = createLog()
        val first = stream("a", 1, requestId = "request")
        log.append(first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (log.append(stream("b", 1, requestId = "request")) as AppendOutcome.AlreadyCommitted)
            .existing.ref.assert().isEqualTo(first.ref)
        // Decision D162: another aggregate type of the context keeps its own request key.
        log.append(stream("c", 1, requestId = "request", aggregate = other))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (log.append(stream("d", 1, requestId = "request", aggregate = other)) as AppendOutcome.AlreadyCommitted)
            .existing.ref.assert().isEqualTo(other.ref("tenant", "c"))
        // The lookup by aggregate type finds that type's stream only.
        log.findByRequestId(type, "tenant", "request")?.ref.assert().isEqualTo(first.ref)
        log.findByRequestId(other, "tenant", "request")?.ref.assert().isEqualTo(other.ref("tenant", "c"))
        // The context-wide lookup finds the stream committed first, whichever aggregate type holds it.
        log.findByRequestId("tenant", "request")?.ref.assert().isEqualTo(first.ref)
    }

    @Test
    fun `the lookup by aggregate type returns facts only, as appended`(): Unit = runBlocking {
        val log = createLog()
        val appended = stream("a", 1, causation = Causation("root", "$context.aggregate/tenant/b@3"))
        log.append(appended, Transport(trace = mapOf("traceparent" to "t")))
        checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        log.findByRequestId(AggregateType(context, "untouched"), "tenant", appended.requestId).assert().isNull()
    }

    protected companion object {
        const val DEFAULT_PARTITIONS = 8
        const val CONTEXT_SUFFIX = 12
        const val TAIL_TIMEOUT_MS = 30_000L
    }
}
