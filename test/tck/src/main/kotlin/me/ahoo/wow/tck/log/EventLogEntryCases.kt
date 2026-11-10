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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The entry cases of the [EventLog] contract: where a stream lands as an entry of a partition, with its position and
 * transport, and how a tail reads the entries back in sequence. They read only [EventLogFixtures]' fixtures, hooks and
 * constants; see [EventLogFixtures] for the chain of families this class is part of.
 */
abstract class EventLogEntryCases internal constructor() : EventLogAccessCases() {
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
        checkNotNull(log.findByRequestId(type, "tenant", carried.requestId)).assertSameAs(carried)
    }

    /**
     * The transport is the runtime's opaque bytes: a backend gives them back unchanged, so a field a
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
     * What the runtime hands its tails in memory is the stream and transport as appended at the
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
}
