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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.VersionPages
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The read cases of the [EventLog] contract: an aggregate's streams read by version, whole across pages, and with its
 * tail, its earlier request keys and its snapshot. They read only [EventLogFixtures]' fixtures, hooks and constants;
 * see [EventLogFixtures] for the chain of families this class is part of.
 */
abstract class EventLogReadCases internal constructor() : EventLogEntryCases() {
    @Test
    fun `read returns a version range in version order`(): Unit = runBlocking {
        val log = createLog()
        (1..5).forEach { log.append(stream("a", it)) }
        log.read(type.ref("tenant", "a"), 2, 4).toList().map { it.version }.assert().containsExactly(2, 3, 4)
        log.read(type.ref("tenant", "missing")).toList().assert().isEmpty()
    }

    @Test
    fun `a long read is whole and in order across its pages, read and readEntries alike`(): Unit = runBlocking {
        // A backend reads a page of VersionPages.SIZE streams at a time; its pages join with no gap nor repeat.
        val log = createLog()
        val ref = type.ref("tenant", "long")
        val count = 2 * VersionPages.SIZE + 3
        val appended = (1..count).map { stream("long", it) }
        appended.chunked(APPEND_GROUP).forEachIndexed { index, group ->
            log.appendAll(group.map { it to null }).assert().allMatch { it is AppendOutcome.Committed }
            // Another aggregate's streams between, which no page of this one takes.
            log.append(stream("between", index + 1))
        }
        fun versions(
            from: Int,
            to: Int = Int.MAX_VALUE
        ) = runBlocking { log.read(ref, from, to).toList().map { it.version } }

        val read = log.read(ref).toList()
        read.map { it.version }.assert().isEqualTo((1..count).toList())
        read[VersionPages.SIZE].assertSameAs(appended[VersionPages.SIZE])
        versions(1, VersionPages.SIZE).assert().isEqualTo((1..VersionPages.SIZE).toList())
        versions(
            VersionPages.SIZE,
            VersionPages.SIZE + 1
        ).assert().containsExactly(VersionPages.SIZE, VersionPages.SIZE + 1)
        versions(VersionPages.SIZE + 1).assert().isEqualTo((VersionPages.SIZE + 1..count).toList())
        versions(count + 1).assert().isEmpty()
        val entries = log.readEntries(ref).toList()
        entries.map { it.stream.version }.assert().isEqualTo((1..count).toList())
        entries.map { it.position.sequence }.assertIncreasingAfter(0)
        log.readEntries(ref, 2 * VersionPages.SIZE).toList().map { it.stream.version }
            .assert().isEqualTo((2 * VersionPages.SIZE..count).toList())
        // A read stopped after its first stream reads no more.
        log.read(ref).first().version.assert().isEqualTo(1)
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
}
