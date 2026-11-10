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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test

/**
 * The [EventLog] contract (documentation/designs/03-log-and-snapshot.md §1) every backend passes. A backend's
 * test extends it and supplies a fresh, empty log from [createLog].
 *
 * The cases come in families, each a parent class of this one ([EventLogFixtures] lists them); this class holds
 * the uniqueness and concurrency cases.
 */
abstract class EventLogSpec : EventLogBatchCases() {
    @Test
    fun `a request id reused by another command type finds the stream committed for it`(): Unit = runBlocking {
        // One partition: a request key may be unique within a partition only.
        val log = createLog(partitions = 1)
        log.append(stream("a", 1, requestId = "request", commandType = "$context.aggregate.Open"))
        val again = log.append(stream("b", 1, requestId = "request", commandType = "$context.aggregate.Close"))
        (again as AppendOutcome.AlreadyCommitted).existing.commandType.assert().isEqualTo("$context.aggregate.Open")
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
            // One partition: a request key may be unique within a partition only.
            val log = createLog(partitions = 1)
            val first = stream("a", 1, requestId = "request")
            log.append(first)
            val again = log.append(stream("b", 1, requestId = "request")) as AppendOutcome.AlreadyCommitted
            again.existing.ref.assert().isEqualTo(first.ref)
            again.existing.requestId.assert().isEqualTo("request")
            log.append(stream("a", 1, requestId = "request", tenant = "other"))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.findByRequestId(type, "tenant", "request")?.ref.assert().isEqualTo(first.ref)
            log.findByRequestId(type, "tenant", "missing").assert().isNull()
            log.findByRequestId(type, "nobody", "request").assert().isNull()
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
            // A request key unique within a partition, as MongoDB's, does not meet the other partition's.
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
        // Both keys collide: the request key decides.
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
    fun `a request id is unique within its tenant and aggregate type`(): Unit = runBlocking {
        // One partition: a request key may be unique within a partition only.
        val log = createLog(partitions = 1)
        val first = stream("a", 1, requestId = "request")
        log.append(first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (log.append(stream("b", 1, requestId = "request")) as AppendOutcome.AlreadyCommitted)
            .existing.ref.assert().isEqualTo(first.ref)
        // Another aggregate type of the context keeps its own request key.
        log.append(stream("c", 1, requestId = "request", aggregate = other))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (log.append(stream("d", 1, requestId = "request", aggregate = other)) as AppendOutcome.AlreadyCommitted)
            .existing.ref.assert().isEqualTo(other.ref("tenant", "c"))
        // The lookup by aggregate type finds that type's stream only.
        log.findByRequestId(type, "tenant", "request")?.ref.assert().isEqualTo(first.ref)
        log.findByRequestId(other, "tenant", "request")?.ref.assert().isEqualTo(other.ref("tenant", "c"))
    }
}
