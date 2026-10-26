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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.log.readSnapshotTailApart
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * The [EventLog] contract (documentation/designs/03-log-and-snapshot.md §1) every backend passes. A backend's
 * test extends it and supplies a fresh, empty log from [createLog].
 */
@Suppress("LargeClass") // One contract, one test per rule.
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

    /**
     * A new, empty snapshot store of bounded context [context] that this backend's log reads with its tails
     * (decision D268): one in the log's database where the backend has one. Null, the default, skips those cases. A
     * log with a [SnapshotTailReader] must read this store's snapshots through it (finding R5.B-9).
     */
    protected open fun createSnapshots(context: String): SnapshotStore? = null

    /**
     * What a cache miss reads: through the log's [EventLog.snapshotTails] when it has one, which must serve the store of
     * [createSnapshots], else apart, as the kernel does.
     */
    private suspend fun EventLog.snapshotTail(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail {
        val reader = snapshotTails ?: return readSnapshotTailApart(ref, snapshots, earlier)
        return checkNotNull(reader.readWithSnapshot(ref, snapshots, earlier)) {
            "The log's snapshot tail reader does not serve the snapshot store of its own backend."
        }
    }

    private fun snapshot(id: String, version: Int) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = version,
        schemaFingerprint = "fingerprint",
        state = """{"id":"$id","version":$version}""",
        scope = Scope("owner", "space"),
        deleted = false,
        firstEventTime = 1_700_000_000_001L,
        lastEventTime = 1_700_000_000_000L + version,
        tags = mapOf("tier" to listOf("gold")),
        operator = Operator("bob", "tenant"),
        schedules = mapOf("remind" to ScheduleRule.After(60_000)),
    )

    /**
     * These sequences increase from after [floor]: a partition's sequences only increase, and a backend may leave some
     * unused (decision D271).
     */
    protected fun List<Long>.assertIncreasingAfter(floor: Long) {
        zipWithNext().all { (earlier, later) -> later > earlier }.assert().describedAs("$this increase").isTrue()
        (first() > floor).assert().describedAs("$this start after $floor").isTrue()
    }

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
        tags: Map<String, List<String>>? = null,
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
        tags = tags,
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
    fun `the tags read back as appended, none and not recorded told apart (D259)`(): Unit = runBlocking {
        val log = createLog()
        val tagged = stream("a", 1, tags = mapOf("region" to listOf("east", "north ü"), "dept" to listOf("eng")))
        val none = stream("b", 1, tags = emptyMap())
        val unrecorded = stream("c", 1, tags = null)
        val streams = listOf(tagged, none, unrecorded)
        streams.forEach { log.append(it) }
        streams.forEach { appended ->
            log.read(appended.ref).toList().single().assertSameAs(appended)
            checkNotNull(log.findByRequestId("tenant", appended.requestId)).assertSameAs(appended)
        }
        streams.forEach { appended ->
            log.readEntries(appended.ref, 1, 1).toList().single().stream.tags.assert().isEqualTo(appended.tags)
        }
    }

    @Test
    fun `a log that backfills tags lists the untagged aggregates and records only the missing tags (D259)`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is TagsBackfill, "This log records no tags to backfill.")
            val backfill = log as TagsBackfill
            val east = mapOf("region" to listOf("east"))
            listOf(
                stream("a", 1),
                stream("a", 2),
                stream("b", 1, tags = east),
                stream("c", 1),
                stream("c", 1, tenant = "other"),
            ).forEach { (log.append(it) is AppendOutcome.Committed).assert().isTrue() }
            val a = type.ref("tenant", "a")
            val c = type.ref("tenant", "c")
            val otherC = type.ref("other", "c")
            // The log's key order: aggregate id, then tenant.
            // In the log's own order (MongoDB's leads with the partition, decision D277); a page resumes after the last.
            val walk = backfill.untagged(type, null, 10)
            walk.assert().containsExactlyInAnyOrder(a, otherC, c)
            backfill.untagged(type, limit = 1).assert().containsExactly(walk[0])
            backfill.untagged(type, walk[0], 10).assert().containsExactlyElementsOf(walk.drop(1))
            backfill.untagged(type, walk[1], 10).assert().containsExactlyElementsOf(walk.drop(2))
            backfill.recordTags(a, mapOf(1 to east, 2 to emptyMap(), 9 to east)).assert().isEqualTo(2)
            log.read(a).toList().map { it.tags }.assert().containsExactly(east, emptyMap())
            // Recorded tags are never changed.
            backfill.recordTags(a, mapOf(1 to emptyMap())).assert().isEqualTo(0)
            backfill.recordTags(type.ref("tenant", "b"), mapOf(1 to emptyMap())).assert().isEqualTo(0)
            backfill.recordTags(c, emptyMap()).assert().isEqualTo(0)
            log.read(a).toList().first().tags.assert().isEqualTo(east)
            backfill.untagged(type, null, 10).assert().containsExactlyElementsOf(walk.filter { it != a })
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
        // One partition: a request key may be unique within a partition only (decision D277).
        val log = createLog(partitions = 1)
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
        log.append(carried, TransportCodec.encode(transport))
        log.append(stream("a", 2))
        log.append(stream("a", 3), TransportCodec.encode(Transport(trace = mapOf("traceparent" to "t"))))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }
        entries[0].decodedTransport.assert().isEqualTo(transport)
        entries[0].stream.assertSameAs(carried)
        entries[1].transport.assert().isNull()
        entries[2].decodedTransport.assert().isEqualTo(Transport(trace = mapOf("traceparent" to "t")))
        log.readEntries(carried.ref).toList().map { it.decodedTransport }
            .assert().isEqualTo(entries.map { it.decodedTransport })
        // read and findByRequestId return facts only: an EventStreamRecord has no place for the transport.
        log.read(carried.ref, 1, 1).toList().single().assertSameAs(carried)
        checkNotNull(log.findByRequestId("tenant", carried.requestId)).assertSameAs(carried)
    }

    /**
     * The transport is the runtime's opaque bytes (finding R5.B-3): a backend gives them back unchanged, so a field a
     * newer version adds survives a backend that does not know it, and the runtime of that version reads it.
     */
    @Test
    fun `an unknown future transport field round-trips unchanged through tail and readEntries`(): Unit = runBlocking {
        val log = createLog(partitions = 1)
        val known = TransportCodec.encode(Transport(trace = mapOf("traceparent" to "t")))
        val future = (
            known.decodeToString().removeSuffix("}") +
                ",\"future\":{\"priority\":7,\"note\":\"ü\\u0000\",\"amount\":2.50}}"
            ).encodeToByteArray()
        val ref = stream("a", 1).ref
        log.append(stream("a", 1), future)
        log.appendAll(listOf(stream("a", 2) to future))
        val tailed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }
        tailed.forEach { entry ->
            entry.transport.assert().isEqualTo(future)
            entry.decodedTransport.assert().isEqualTo(Transport(trace = mapOf("traceparent" to "t")))
        }
        log.readEntries(ref).toList().forEach { it.transport.assert().isEqualTo(future) }
    }

    @Test
    fun `readEntries returns a version range in version order, each entry with its position and transport`(): Unit =
        runBlocking {
            val log = createLog(partitions = 4)
            val transport = Transport(reservations = listOf(ReservationKey("user-name", "ahoo ü")))
            val appended = listOf(stream("a", 1), stream("a", 2), stream("a", 3), stream("a", 4))
            val positions = appended.mapIndexed { index, stream ->
                log.append(stream("b", index + 1))
                val outcome = log.append(stream, TransportCodec.encode(transport).takeIf { index % 2 == 1 })
                (outcome as AppendOutcome.Committed).position
            }
            val entries = log.readEntries(type.ref("tenant", "a"), 2, 3).toList()
            entries.map { it.stream.version }.assert().containsExactly(2, 3)
            entries.map { it.position }.assert().containsExactly(positions[1], positions[2])
            entries.map { it.decodedTransport }.assert().containsExactly(transport, null)
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
        log.appendAll(listOf(stream(first, 1) to TransportCodec.encode(transport), stream(second, 1) to null))
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val entries = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }
        entries.associate { it.stream.ref.id to it.decodedTransport }
            .assert().isEqualTo(mapOf(first to transport, second to null))
    }

    /**
     * What the runtime hands its tails in memory (decision D249) is the stream and transport as appended at the
     * position the outcome names: a backend's tail must read back exactly that, the events' payloads as JSON.
     */
    @Test
    fun `the entry an append's outcome names is what tail reads back`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (first, second, third) = idsInPartitionZero(3, 4)
        val transport = Transport(
            wait = WaitContext(
                waitId = "w-1",
                origin = Origin("node-1", "10.0.0.7:8081", "order-service"),
                remaining = listOf(Hop.snapshot()),
                deadline = 1_700_000_010_000L,
            ),
            reservations = listOf(ReservationKey("user-name", "ahoo ü")),
            trace = mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
        )
        val single = stream(
            first,
            1,
            causation = Causation("cause-1", "corr-1"),
            headers = mapOf("h" to "v"),
            operator = Operator("user-1", "user"),
        ).let {
            it.copyWith(events = listOf(EventRecord("$context.aggregate.Priced", 1, ExactJson.NUMBERS)) + it.events)
        }
        val encoded = TransportCodec.encode(transport)
        val grouped = listOf(stream(second, 1) to null, stream(third, 1) to encoded)
        val handed = buildList {
            add(single to encoded to log.append(single, encoded))
            grouped.zip(log.appendAll(grouped)).forEach { (appended, outcome) -> add(appended to outcome) }
        }.map { (appended, outcome) ->
            Triple((outcome as AppendOutcome.Committed).position, appended.first, appended.second)
        }
        val read = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }
        read.map { it.position }.assert().isEqualTo(handed.map { it.first })
        read.map { it.decodedTransport }.assert().isEqualTo(handed.map { it.third?.let(TransportCodec::decode) })
        read.zip(handed).forEach { (entry, expected) -> entry.stream.assertSameAs(expected.second) }
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
    fun `every partition is created with no parent at epoch 0, and keeps that lineage as entries land`(): Unit =
        runBlocking {
            val log = createLog(partitions = 4)
            (0 until 4).forEach { log.lineage(it).assert().isEqualTo(PartitionLineage.ROOT) }
            idsInPartitionZero(2, 4).forEach { log.append(stream(it, 1)) }
            (0 until 4).forEach { log.lineage(it).assert().isEqualTo(PartitionLineage.ROOT) }
            PartitionLineage.ROOT.parent.assert().isNull()
            PartitionLineage.ROOT.epoch.assert().isZero()
            assertThrows<IllegalArgumentException> { log.lineage(4) }
            assertThrows<IllegalArgumentException> { log.lineage(-1) }
        }

    @Test
    fun `a partition count that is not a power of two dividing 4096 is refused`() {
        listOf(0, 3, 6, 100, 8192).forEach { partitions ->
            assertThrows<IllegalArgumentException> { createLog(context, partitions) }
                .message.assert().contains("a power of two that divides 4096")
        }
    }

    @Test
    fun `read returns a version range in version order`(): Unit = runBlocking {
        val log = createLog()
        (1..5).forEach { log.append(stream("a", it)) }
        log.read(type.ref("tenant", "a"), 2, 4).toList().map { it.version }.assert().containsExactly(2, 3, 4)
        log.read(type.ref("tenant", "missing")).toList().assert().isEmpty()
    }

    @Test
    fun `readTail gives the tail from a version and the latest earlier request keys, newest first`(): Unit =
        runBlocking {
            val log = createLog()
            val appended = (1..6).map { stream("a", it, commandType = "$context.aggregate.Act$it") }
            appended.forEach { log.append(it) }
            log.append(stream("a", 1, tenant = "other"))
            log.append(stream("b", 1))
            val tail = log.readTail(type.ref("tenant", "a"), 4, 2)
            tail.streams.map { it.version }.assert().containsExactly(4, 5, 6)
            tail.streams.first().assertSameAs(appended[3])
            tail.earlier.assert().containsExactly(
                CommittedRequest.of(appended[2]),
                CommittedRequest.of(appended[1]),
            )
        }

    @Test
    fun `readTail gives fewer earlier request keys only when none is left before them`(): Unit = runBlocking {
        val log = createLog()
        val appended = (1..3).map { stream("a", it) }
        appended.forEach { log.append(it) }
        val ref = type.ref("tenant", "a")
        val whole = log.readTail(ref, 3, 10)
        whole.streams.map { it.version }.assert().containsExactly(3)
        whole.earlier.map { it.version }.assert().containsExactly(2, 1)
        whole.earlier.map { it.requestId }.assert().containsExactly(appended[1].requestId, appended[0].requestId)
        log.readTail(ref, 3, 0).earlier.assert().isEmpty()
        log.readTail(ref, 1, 5).earlier.assert().isEmpty()
        log.readTail(ref, 4, 2).let {
            it.streams.assert().isEmpty()
            it.earlier.map { key -> key.version }.assert().containsExactly(3, 2)
        }
        log.readTail(type.ref("tenant", "missing"), 1, 5).assert().isEqualTo(AggregateTail.of(emptyList(), emptyList()))
    }

    @Test
    fun `a snapshot tail read gives the snapshot and the tail from its version with the earlier request keys`(): Unit =
        runBlocking {
            val snapshots = createSnapshots(context)
            Assumptions.assumeTrue(snapshots != null, "This backend has no snapshot store to read with the log.")
            checkNotNull(snapshots)
            val log = createLog()
            val appended = (1..6).map { stream("a", it) }
            appended.forEach { log.append(it) }
            log.append(stream("b", 1))
            val saved = snapshot("a", 4)
            snapshots.save(saved).assert().isTrue()
            snapshots.save(snapshot("a", 2).copyWith(ref = type.ref("other", "a"))).assert().isTrue()
            val read = log.snapshotTail(type.ref("tenant", "a"), snapshots, 2)
            val loaded = checkNotNull(read.snapshot)
            ExactJson.of(loaded.state).assert().isEqualTo(ExactJson.of(saved.state))
            loaded.copyWith(state = saved.state).assert().isEqualTo(saved)
            read.tail.streams.map { it.version }.assert().containsExactly(4, 5, 6)
            read.tail.streams.first().assertSameAs(appended[3])
            read.tail.earlier.assert().containsExactly(
                CommittedRequest.of(appended[2]),
                CommittedRequest.of(appended[1])
            )
            log.snapshotTail(type.ref("tenant", "a"), snapshots, 0).tail.earlier.assert().isEmpty()
        }

    @Test
    fun `a snapshot tail read without a snapshot gives every stream and no earlier request keys`(): Unit = runBlocking {
        val snapshots = createSnapshots(context)
        Assumptions.assumeTrue(snapshots != null, "This backend has no snapshot store to read with the log.")
        checkNotNull(snapshots)
        val log = createLog()
        val appended = (1..3).map { stream("a", it) }
        appended.forEach { log.append(it) }
        val read = log.snapshotTail(type.ref("tenant", "a"), snapshots, 5)
        read.snapshot.assert().isNull()
        read.tail.streams.map { it.version }.assert().containsExactly(1, 2, 3)
        read.tail.streams.last().assertSameAs(appended[2])
        read.tail.earlier.assert().isEmpty()
        log.snapshotTail(type.ref("tenant", "missing"), snapshots, 5)
            .assert().isEqualTo(SnapshotTail.of(null, AggregateTail.of(emptyList(), emptyList())))
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
            // One partition: a request key may be unique within a partition only (decision D277).
            val log = createLog(partitions = 1)
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
            // The lookup by an aggregate finds what an append of it meets: here the first commit.
            log.findByRequestId(type.ref("tenant", "b"), "request")?.ref.assert().isEqualTo(first.ref)
            log.findByRequestId(type.ref("tenant", "b"), "missing").assert().isNull()
            log.findByRequestId(type.ref("other", "b"), "request")?.ref.assert().isEqualTo(type.ref("other", "a"))
        }

    @Test
    fun `a request id reused by aggregates of two partitions is answered by the first commit or commits again`(): Unit =
        runBlocking {
            // Decision D277: a request key unique within a partition, as MongoDB's, does not meet the other partition's.
            val log = createLog(partitions = 4)
            val first = stream("a", 1, requestId = "request")
            log.append(first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            val elsewhere = generateSequence(1) { it + 1 }.map { "b-$it" }
                .first { Partitioner.partitionOf(type.ref("tenant", it), 4) != Partitioner.partitionOf(first.ref, 4) }
            when (val again = log.append(stream(elsewhere, 1, requestId = "request"))) {
                is AppendOutcome.AlreadyCommitted -> again.existing.ref.assert().isEqualTo(first.ref)
                is AppendOutcome.Committed ->
                    log.findByRequestId(type.ref("tenant", elsewhere), "request")?.ref.assert()
                        .isEqualTo(type.ref("tenant", elsewhere))
                else -> throw AssertionError("Neither a duplicate nor a commit: $again")
            }
            log.findByRequestId(first.ref, "request")?.ref.assert().isEqualTo(first.ref)
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

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @Test
    fun `appendAll sequences the streams around a conflict in order`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (first, gap, taken, last) = idsInPartitionZero(4, 4)
        log.append(stream(taken, 1))
        val outcomes = log.appendAll(
            listOf(stream(first, 1) to null, stream(gap, 2) to null, stream(taken, 1) to null, stream(last, 1) to null),
        )
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(0))
        outcomes[2].assert().isEqualTo(AppendOutcome.Conflict(1))
        // A backend may leave the conflicting streams' sequences unused (decision D271): they only increase.
        val committed = listOf(outcomes[0], outcomes[3]).map { (it as AppendOutcome.Committed).position.sequence }
        committed.assertIncreasingAfter(1L)
        log.head(0).assert().isEqualTo(committed.last())
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }.map { it.stream.ref.id }
            .assert().isEqualTo(listOf(taken, first, last))
    }

    @Test
    fun `appendAll commits an aggregate's consecutive streams together, in order`(): Unit = runBlocking {
        // Roadmap §6 item 10: a kernel's batch of one aggregate's commands, beside another aggregate's append.
        val log = createLog(partitions = 4)
        val (hot, other) = idsInPartitionZero(2, 4)
        log.append(stream(hot, 1))
        val chain = (2..5).map { stream(hot, it) }
        val outcomes = log.appendAll(
            listOf(chain[0] to null, stream(other, 1) to null, chain[1] to null, chain[2] to null, chain[3] to null),
        )
        outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        outcomes.map { (it as AppendOutcome.Committed).position.sequence }.assertIncreasingAfter(1L)
        log.read(type.ref("tenant", hot)).toList().map { it.version }.assert().containsExactly(1, 2, 3, 4, 5)
        (log.append(stream(hot, 6)) as AppendOutcome.Committed).position.sequence
            .assert().isGreaterThan((outcomes.last() as AppendOutcome.Committed).position.sequence)
    }

    /** One aggregate id in each of [partitions] partitions, in partition order, the [nth] one found in each. */
    private fun idsByPartition(partitions: Int, nth: Int = 0): List<String> = (0 until partitions).map { partition ->
        generateSequence(1) { it + 1 }.map { "p-$it" }
            .filter { Partitioner.partitionOf(type.ref("tenant", it), partitions) == partition }
            .drop(nth)
            .first()
    }

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @Test
    fun `appendAll across partitions sequences each partition on its own, and one's conflict stops none of the others`():
        Unit = runBlocking {
        // Decision D275: a log that takes several partitions a call is handed several partitions' groups at once.
        val log = createLog(partitions = 4)
        Assumptions.assumeTrue(log.appendsAcrossPartitions, "This log takes one partition a call.")
        val (zero, one, two, three) = idsByPartition(4)
        val repeated = idsByPartition(4, nth = 1)[1]
        log.append(stream(one, 1))
        log.append(stream(idsByPartition(4, nth = 2)[1], 1, requestId = "taken"))
        val outcomes = log.appendAll(
            listOf(
                stream(zero, 1) to null,
                stream(one, 1) to null,
                stream(two, 1) to null,
                stream(zero, 2) to null,
                stream(repeated, 1, requestId = "taken") to null,
                stream(three, 1) to null,
                stream(two, 2) to null,
            ),
        )
        // A conflict and a duplicate in partition 1: the other partitions' streams commit all the same.
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(1))
        outcomes[4].assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
        listOf(0, 2, 3, 5, 6).forEach { outcomes[it].assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val positions = outcomes.filterIsInstance<AppendOutcome.Committed>().map { it.position }
        positions.groupBy { it.partition }.keys.assert().containsExactlyInAnyOrder(0, 2, 3)
        // Each partition's streams take its sequences in the order they came, after what it held.
        (outcomes[3] as AppendOutcome.Committed).position.sequence
            .assert().isGreaterThan((outcomes[0] as AppendOutcome.Committed).position.sequence)
        (outcomes[6] as AppendOutcome.Committed).position.sequence
            .assert().isGreaterThan((outcomes[2] as AppendOutcome.Committed).position.sequence)
        log.read(type.ref("tenant", zero)).toList().map { it.version }.assert().containsExactly(1, 2)
        log.read(type.ref("tenant", two)).toList().map { it.version }.assert().containsExactly(1, 2)
    }

    @Test
    fun `appendAll appends none of an aggregate's streams after its first conflicts`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (hot, other) = idsInPartitionZero(2, 4)
        log.append(stream(hot, 1))
        log.append(stream(hot, 2))
        val outcomes = log.appendAll(
            listOf(stream(hot, 2) to null, stream(hot, 3) to null, stream(other, 1) to null, stream(hot, 4) to null),
        )
        outcomes[0].assert().isEqualTo(AppendOutcome.Conflict(2))
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(2))
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[3].assert().isEqualTo(AppendOutcome.Conflict(2))
        log.read(type.ref("tenant", hot)).toList().map { it.version }.assert().containsExactly(1, 2)
        log.append(stream(hot, 3)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `appendAll commits an aggregate's streams up to one that is a duplicate, and none after it`(): Unit =
        runBlocking {
            val log = createLog(partitions = 4)
            val (hot, other, elsewhere) = idsInPartitionZero(3, 4)
            log.append(stream(elsewhere, 1, requestId = "taken"))
            val outcomes = log.appendAll(
                listOf(
                    stream(hot, 1) to null,
                    stream(hot, 2, requestId = "taken") to null,
                    stream(other, 1) to null,
                    stream(hot, 3) to null,
                ),
            )
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            (outcomes[1] as AppendOutcome.AlreadyCommitted).existing.ref.id.assert().isEqualTo(elsewhere)
            outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            outcomes[3].assert().isEqualTo(AppendOutcome.Conflict(1))
            log.read(type.ref("tenant", hot)).toList().map { it.version }.assert().containsExactly(1)
        }

    @Test
    fun `appendAll commits a request id repeated in a group once, the repeat is a duplicate`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (first, repeat, other) = idsInPartitionZero(3, 4)
        val outcomes = log.appendAll(
            listOf(
                stream(first, 1, requestId = "request") to null,
                stream(repeat, 1, requestId = "request") to null,
                stream(other, 1) to null,
            ),
        )
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (outcomes[1] as AppendOutcome.AlreadyCommitted).existing.ref.id.assert().isEqualTo(first)
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(type.ref("tenant", repeat)).toList().assert().isEmpty()
        val tailed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }
        tailed.map { it.stream.ref.id }.assert().isEqualTo(listOf(first, other))
        tailed.map { it.position.sequence }.assertIncreasingAfter(0L)
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
            entries.map { it.position.sequence }.assertIncreasingAfter(0L)
            writers.forEach { (aggregate, id) ->
                entries.filter { it.stream.ref == aggregate.ref("tenant", id) }.map { it.stream.version }
                    .assert().isEqualTo((1..perWriter).toList())
            }
            log.head(0).assert().isEqualTo(entries.last().position.sequence)
            val after = entries[6].position.sequence
            val resumed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0, after = after).take(total - 7).toList() }
            resumed.assert().isEqualTo(entries.drop(7))
            log.read(other.ref("tenant", "w1")).toList().map { it.ref.type }.distinct().assert().containsExactly(other)
            log.readEntries(type.ref("tenant", "w1")).toList().map { it.position }
                .assert().isEqualTo(entries.filter { it.stream.ref == type.ref("tenant", "w1") }.map { it.position })
        }

    @Test
    fun `appendAll sequences a group of two aggregate types in order`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val ids = idsInPartitionZero(3, 4).zip(idsInPartitionZero(3, 4, other))
        val group = ids.flatMap { (first, second) ->
            listOf(stream(first, 1) to null, stream(second, 1, aggregate = other) to null)
        }
        log.appendAll(group).map { (it as AppendOutcome.Committed).position.sequence }.assertIncreasingAfter(0L)
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
        // One partition: a request key may be unique within a partition only (decision D277).
        val log = createLog(partitions = 1)
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
        log.append(appended, TransportCodec.encode(Transport(trace = mapOf("traceparent" to "t"))))
        checkNotNull(log.findByRequestId(type, "tenant", appended.requestId)).assertSameAs(appended)
        log.findByRequestId(AggregateType(context, "untouched"), "tenant", appended.requestId).assert().isNull()
    }

    protected companion object {
        const val DEFAULT_PARTITIONS = 8
        const val CONTEXT_SUFFIX = 12
        const val TAIL_TIMEOUT_MS = 30_000L
    }
}
