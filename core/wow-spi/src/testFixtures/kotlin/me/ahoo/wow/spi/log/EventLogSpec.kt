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

package me.ahoo.wow.spi.log

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
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
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

    private fun createLog(partitions: Int = DEFAULT_PARTITIONS): EventLog = createLog(context, partitions)

    protected fun stream(
        id: String,
        version: Int,
        requestId: String = "$id-$version-${UUID.randomUUID()}",
        tenant: String = "tenant",
        fingerprint: String = "fingerprint",
    ) = EventStreamRecord(
        ref = type.ref(tenant, id),
        version = version,
        commandId = "command-$requestId",
        requestId = requestId,
        fingerprint = fingerprint,
        commitTime = 1_700_000_000_000L + version,
        events = listOf(
            EventRecord("$context.aggregate.Happened", 1, """{"version":$version,"text":"ü \" é"}"""),
            EventRecord("$context.aggregate.Noted", 2, """{"note":"second"}"""),
        ),
    )

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
        read.ref.assert().isEqualTo(appended.ref)
        read.version.assert().isEqualTo(1)
        read.commandId.assert().isEqualTo(appended.commandId)
        read.requestId.assert().isEqualTo(appended.requestId)
        read.fingerprint.assert().isEqualTo(appended.fingerprint)
        read.commitTime.assert().isEqualTo(appended.commitTime)
        read.events.map { it.name to it.revision }.assert().containsExactly(
            "$context.aggregate.Happened" to 1,
            "$context.aggregate.Noted" to 2,
        )
        // A backend may store the payload as JSON and give it back reformatted: compare it as JSON.
        read.events.map { json.readTree(it.payload) }.assert()
            .isEqualTo(appended.events.map { json.readTree(it.payload) })
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
    fun `a request id is unique within its tenant`(): Unit = runBlocking {
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
    private fun idsInPartitionZero(count: Int, partitions: Int): List<String> =
        generateSequence(1) { it + 1 }.map { "g-$it" }
            .filter { Partitioner.partitionOf(type.ref("tenant", it), partitions) == 0 }
            .take(count)
            .toList()

    @Test
    fun `appendAll gives each stream the outcome append would`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val (fits, behind, ahead, duplicate) = idsInPartitionZero(4, 4)
        log.append(stream(behind, 1))
        log.append(stream(duplicate, 1, requestId = "taken"))
        val outcomes = log.appendAll(
            listOf(
                stream(fits, 1),
                stream(behind, 1),
                stream(ahead, 3),
            ),
        )
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(1))
        outcomes[2].assert().isEqualTo(AppendOutcome.Conflict(0))
        val withDuplicate = log.appendAll(listOf(stream(ahead, 1), stream(duplicate, 2, requestId = "taken")))
        withDuplicate[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (withDuplicate[1] as AppendOutcome.AlreadyCommitted).existing.ref.id.assert().isEqualTo(duplicate)
    }

    @Test
    fun `appendAll sequences a group without gaps`(): Unit = runBlocking {
        val log = createLog(partitions = 4)
        val ids = idsInPartitionZero(10, 4)
        val outcomes = log.appendAll(ids.map { stream(it, 1) })
        outcomes.map { (it as AppendOutcome.Committed).position.sequence }.assert().isEqualTo((1L..10L).toList())
        val more = log.appendAll(ids.take(3).map { stream(it, 2) })
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

    protected companion object {
        private val json = JsonMapper.builder().build()
        const val DEFAULT_PARTITIONS = 8
        const val CONTEXT_SUFFIX = 12
        const val TAIL_TIMEOUT_MS = 30_000L
    }
}
