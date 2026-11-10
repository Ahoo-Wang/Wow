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
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The batch cases of the [EventLog] contract: what `appendAll` commits, sequences and refuses, stream by stream and
 * partition by partition, against what `append` would. They read only [EventLogFixtures]' fixtures, hooks and
 * constants; see [EventLogFixtures] for the chain of families this class is part of.
 */
abstract class EventLogBatchCases internal constructor() : EventLogReadCases() {
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
        // The refused stream failed the whole group.
        val outcomes = log.appendAll(listOf(stream(before, 1) to null, refused to null, stream(after, 1) to null))
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(refused.ref).toList().assert().isEmpty()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }.map { it.stream.ref.id }
            .assert().containsExactlyInAnyOrder(before, after)
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
        // A backend may leave the conflicting streams' sequences unused: they only increase.
        val committed = listOf(outcomes[0], outcomes[3]).map { (it as AppendOutcome.Committed).position.sequence }
        committed.assertIncreasingAfter(1L)
        log.head(0).assert().isEqualTo(committed.last())
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }.map { it.stream.ref.id }
            .assert().isEqualTo(listOf(taken, first, last))
    }

    @Test
    fun `appendAll fails alone a stream whose version another writer took since, and commits the others in order`(): Unit = runBlocking {
        // Another node committed a command without the partition's lease: this log still remembers the version
        // before it, so only the store's version key finds the conflict.
        Assumptions.assumeTrue(opensOneStorePerContext, "This backend's logs are stores of their own.")
        val log = createLog(partitions = 4)
        val ids = idsInPartitionZero(4, 4)
        val firsts = ids.map { stream(it, 1) }
        log.appendAll(firsts.map { it to null })
        createLog(partitions = 4).append(stream(ids[1], 2, requestId = "elsewhere"))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val outcomes = log.appendAll(ids.map { stream(it, 2) to null })
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(2))
        val committed = listOf(outcomes[0], outcomes[2], outcomes[3])
            .map { (it as AppendOutcome.Committed).position.sequence }
        committed.assertIncreasingAfter(ids.size.toLong())
        log.read(type.ref("tenant", ids[1])).toList().map { it.requestId }.assert()
            .containsExactly(firsts[1].requestId, "elsewhere")
        // Where the other writer's stream lies among them is the backend's sequencing.
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(8).toList() }.map { it.stream.ref.id to it.stream.version }
            .assert().containsExactlyInAnyOrderElementsOf(ids.map { it to 1 } + ids.map { it to 2 })
    }

    @Test
    fun `appendAll commits an aggregate's consecutive streams together, in order`(): Unit = runBlocking {
        // A kernel's batch of one aggregate's commands, beside another aggregate's append.
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
        // A log that takes several partitions a call is handed several partitions' groups at once.
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
}
