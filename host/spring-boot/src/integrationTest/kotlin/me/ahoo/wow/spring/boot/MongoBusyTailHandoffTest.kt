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

package me.ahoo.wow.spring.boot

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.ExecutionLease
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * On MongoDB, another node's commit lands past the block its owner allocates from (an append without the lease reserves
 * a sequence of its own past the counter's top). The owner's tail delivers its own commits from memory, contiguous in
 * its block, and the probe's wake waits for the tail to be idle half a probe interval, which a node committing more
 * often never is. So the log retires the owner's block when the probe names the other node's block past it (#7085): the
 * owner's next commit lands past it, its hand-over leaves a gap, and the tail reads the other node's entry there, within
 * a probe interval as `HandoffLog`'s KDoc says, while its node keeps it busy. The owner's trickle is what keeps the tail
 * busy; the bound is the liveness bound of that promise, not an order the test compares.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoBusyTailHandoffTest {
    private val mongo = WowContainers.newMongo().also { it.start() }
    private val client: MongoClient = MongoClients.create(mongo.replicaSetUrl)
    private val type = AggregateType("busytail", "account")

    @AfterAll
    fun close() {
        client.close()
        mongo.stop()
    }

    private fun stream(ref: AggregateRef, version: Int) = EventStreamRecord(
        ref = ref,
        version = version,
        commandId = "command-${ref.id}-$version",
        requestId = "${ref.id}-$version",
        commandType = "${ref.type}.Deposit",
        commitTime = 1L,
        events = listOf(EventRecord("${ref.type}.Deposited", 1, """{"amount":$version}""")),
        scope = Scope.NONE,
    )

    @Test
    fun `another node's entry past the owner's block reaches a tail its node keeps busy within a probe interval`():
        Unit = runBlocking {
        val owner = MongoEventLog(client, "wow_busy_tail", type.context, 1).also { it.createSchema() }
        // A second process of the service: another node of the partition's counter.
        val other = MongoEventLog(client, "wow_busy_tail", type.context, 1)
        val handoff = HandoffLog(owner, type.context, HandoffOptions(probeInterval = PROBE))
        val seen = Channel<LogEntry>(Channel.UNLIMITED)
        val tail = launch(Dispatchers.Default) { handoff.tail(0).collect { seen.send(it) } }
        val mine = type.ref("tenant", "mine")
        // The owner's first commit reserves its block, and the tail is open on it.
        (handoff.append(stream(mine, 1), null) as AppendOutcome.Committed)
        withTimeoutOrNull(10.seconds) { seen.receive() }.assert().isNotNull()
        // The owner keeps committing, a little more often than half a probe interval.
        val trickle = launch(Dispatchers.Default) {
            var version = 1
            while (isActive) {
                delay(TRICKLE)
                handoff.append(stream(mine, ++version), null).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            }
        }
        delay(PROBE)
        // Another node commits without the lease: one sequence of its own, past the owner's block.
        val foreign = withContext(ExecutionLease.NOT_HELD) {
            other.append(stream(type.ref("tenant", "theirs"), 1)) as AppendOutcome.Committed
        }
        val landed = TimeSource.Monotonic.markNow()
        val read = withTimeoutOrNull(PROBE * 5) {
            var entry = seen.receive()
            while (entry.position != foreign.position) {
                entry = seen.receive()
            }
            entry
        }
        trickle.cancelAndJoin()
        read.assert()
            .describedAs(
                "another node's entry at ${foreign.position}, not read ${landed.elapsedNow()} after it landed while " +
                    "the owner committed every $TRICKLE (probe interval $PROBE)",
            )
            .isNotNull()
        tail.cancelAndJoin()
    }

    private companion object {
        val PROBE = 1.seconds
        val TRICKLE = 300.milliseconds
    }
}
