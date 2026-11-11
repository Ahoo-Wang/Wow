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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.timer.TestClock
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A walk's runs: taken over once a dead node's lease expires, a failing run logged and the next one run, a run past its
 * deadline left for the next, a claim lost or moved, and a walk another node leads left to it.
 */
class AccessWalkRunsTest : AccessWalkingFixtures() {
    /** [log], whose claims, or advances, another node's claim of the schema `other` precedes. */
    private class Contended(
        val log: InMemoryEventLog,
        val claims: Boolean = false,
        val advances: Boolean = false,
    ) : EventLog by log,
        AccessWalks by log {
        override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean {
            if (claims) log.claim(type, AccessWalk("other", walk.heads), expected)
            return log.claim(type, walk, expected)
        }

        override suspend fun advance(
            type: AggregateType,
            schema: String,
            cursor: String?,
            walked: Long,
            completed: Boolean,
        ): Boolean {
            if (advances) log.claim(type, AccessWalk("other", emptyMap()), schema)
            return log.advance(type, schema, cursor, walked, completed)
        }
    }

    @Test
    fun `a walk whose node died is taken over once its lease expires, from the cursor it recorded`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val ids = (1..6).map { "d-$it" }
        unstamped(log, ids, "sales", "legal")
        val clock = TestClock()
        val leases = InMemoryLeaseStore(clock)
        val inWalkOrder = log.aggregates(type, null, ids.size).refs.map { it.id }
        // A slow walk that its run's deadline leaves part way, its lease held: what a node's death leaves behind.
        val dying = AccessWalking(
            "office",
            "service/office",
            "n-1",
            listOf(model),
            Kernel(log),
            { log },
            leases,
            AccessStampOptions(walkBatch = 1, walkRate = 10, interval = 250.milliseconds),
        )
        dying.maintain().assert().isTrue()
        val left = log.walk(type)!!
        left.completed.assert().isFalse()
        left.cursor.assert().isNotNull()
        val recorded = left.walked.toInt()
        recorded.assert().isBetween(1, ids.size - 1)

        val taking = Recording(log)
        val next = AccessWalking(
            "office",
            "service/office",
            "n-2",
            listOf(model),
            Kernel(taking),
            { taking },
            leases,
            options,
        )
        // While the dead node's lease lives, the walk stays its own.
        next.maintain().assert().isFalse()
        taking.calls.assert().isEmpty()
        log.walk(type).assert().isEqualTo(left)

        clock.advance(options.leaseTtl + 1.milliseconds)
        withTimeout(10.seconds) {
            while (next.maintain()) continue
        }
        // From the recorded cursor: what the dead node recorded is not read again, the rest once each.
        taking.calls.filter { it.startsWith("read") }.assert()
            .containsExactlyElementsOf(inWalkOrder.drop(recorded).map { "read $it" })
        ids.forEach { history(log, it).assert().containsExactly(access("legal"), access("legal")) }
        log.walk(type)!!.let {
            it.completed.assert().isTrue()
            it.walked.assert().isEqualTo(ids.size.toLong())
            it.schema.assert().isEqualTo(left.schema)
            it.heads.assert().isEqualTo(left.heads)
        }
        next.walks().getValue(type).completed.assert().isTrue()
    }

    @Test
    fun `a run that fails is logged and the next one runs, and stopping leaves the membership`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val leases = InMemoryLeaseStore()
        var broken = false
        val walking = AccessWalking(
            "office",
            "service/office",
            "n-1",
            listOf(model),
            Kernel(log),
            { if (broken) error("The log is down.") else log },
            leases,
            AccessStampOptions(walkRate = 1_000_000, interval = 50.milliseconds),
            observer = observer,
        )
        broken = true
        val scope = CoroutineScope(Dispatchers.Default)
        walking.start(scope)
        delay(100)
        log.walk(type).assert().isNull()
        broken = false
        eventually(5.seconds) { log.walk(type)?.completed == true }
        leases.members("service/office#access").map { it.node }.assert().containsExactly("n-1")
        walking.stop()
        leases.members("service/office#access").assert().isEmpty()
        scope.cancel()
        walking.toString().assert().isEqualTo("AccessWalking(office)")
    }

    @Test
    fun `a walk past its run's deadline is left for the next run, which goes on from its cursor`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
        val walking = walkingOf(
            log,
            options = AccessStampOptions(walkBatch = 1, walkRate = 10, interval = 50.milliseconds)
        )
        walking.maintain().assert().isTrue()
        log.walk(type)!!.walked.assert().isLessThan(3)
        var runs = 1
        while (walking.maintain()) runs++
        runs.assert().isGreaterThan(1)
        log.walk(type)!!.let {
            it.completed.assert().isTrue()
            it.walked.assert().isEqualTo(3)
        }
        listOf("d-1", "d-2", "d-3").forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a claim lost to another node takes its walk, and a walk whose claim moved meanwhile stops`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
            val lost = walkingOf(Contended(log, claims = true))
            lost.maintain().assert().isFalse()
            lost.walks().getValue(type).schema.assert().isEqualTo("other")
            // No node serves `other`: this node's schema is claimed in its place, and moves again after a page.
            val moved = walkingOf(Contended(log, advances = true))
            moved.maintain().assert().isFalse()
            log.walk(type)!!.schema.assert().isEqualTo("other")
            moved.walks().getValue(type).schema.assert().isEqualTo("other")
        }

    @Test
    fun `a walk another node leads is left to it`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val leases = InMemoryLeaseStore()
        leases.acquire("service/office#access-walk/document", 0, "n-0", 30.seconds)
        walkingOf(log, leases).maintain().assert().isFalse()
        log.walk(type)!!.walked.assert().isEqualTo(0)
        history(log, "d-1").assert().containsExactly(null)
    }
}
