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

package me.ahoo.wow.mongo

import com.mongodb.MongoException
import com.mongodb.client.MongoClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The log and the leases across a failover of a replica set of three (roadmap §6 item 18), when `wow.mongo.url`
 * names one (`test/mongo-cluster/cluster.sh test rs`, whose members restart when stopped); skipped otherwise.
 *
 * What a failover may do to an append is make its outcome unknown, never lose a commit: every append acknowledged as
 * committed is in the log after it, every sequence the tail passes is final, and an unknown append is there or not,
 * once.
 */
class MongoFailoverTest {
    private val client = MongoLogFixture.client
    private val admin = client.getDatabase("admin")

    /** Whether the tests run on a replica set of three. */
    private val onThree: Boolean by lazy {
        !MongoLogFixture.sharded && hello().getList("hosts", String::class.java).orEmpty().size >= MEMBERS
    }

    @BeforeEach
    fun onReplicaSet() {
        assumeTrue(onThree, "Runs on a replica set of three: -Dwow.mongo.url of one.")
        awaitHealthy()
    }

    @AfterEach
    fun healthy() {
        if (onThree) awaitHealthy()
    }

    @Test
    fun `appends across a step-down are committed or unknown, and no commit is lost`(): Unit = runBlocking {
        appendAcross { stepDown() }
    }

    @Test
    fun `appends across the primary's crash and restart are committed or unknown, and no commit is lost`(): Unit =
        runBlocking {
            assumeTrue(System.getProperty(CONTAINER) != null, "Kills a member in the cluster's container: -D$CONTAINER.")
            appendAcross { crashPrimary() }
        }

    @Test
    fun `a lease held across a step-down is renewed by its owner and taken by no other`(): Unit = runBlocking {
        val leases = MongoLeaseStore(client, MongoLogFixture.DATABASE).also { it.createSchema() }
        val group = "failover-${MongoLogFixture.newContext()}"
        val held = checkNotNull(leases.acquire(group, 0, "node-a", 1.minutes))
        stepDown()
        awaitPrimary()
        val renewed = retrying { leases.renew(group, listOf(0), "node-a", 1.minutes) }
        renewed.single().epoch.assert().isEqualTo(held.epoch)
        retrying { leases.acquire(group, 0, "node-b", 1.minutes) }.assert().isNull()
    }

    /**
     * Appends from [WRITERS] writers, a new aggregate each time, while [fail] fails the primary over; then reads the
     * partition's tail to its end.
     */
    private suspend fun appendAcross(fail: suspend () -> Unit) {
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val log = MongoLogFixture.log(1, context, aggregates = listOf("order"))
        val committed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val unknown = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // An append the store was unavailable for wrote nothing: the runtime answers it as retryable.
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val appended = AtomicInteger()
        kotlinx.coroutines.coroutineScope {
            val writers = (0 until WRITERS).map { writer ->
                async(Dispatchers.Default) {
                    repeat(APPENDS) { n ->
                        val id = "w$writer-$n"
                        try {
                            when (val outcome = log.append(stream(type, id))) {
                                is AppendOutcome.Committed -> committed += id
                                is AppendOutcome.Unknown -> unknown += id
                                else -> throw AssertionError("$id: neither committed nor unknown, $outcome")
                            }
                        } catch (_: StorageUnavailableException) {
                            failed += id
                        }
                        appended.incrementAndGet()
                    }
                }
            }
            while (appended.get() < WRITERS * APPENDS / 4) delay(5)
            fail()
            writers.awaitAll()
        }
        awaitPrimary()
        // After the failover the log takes appends again.
        retrying { log.append(stream(type, AFTER)) }.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        committed += AFTER
        val tailed = tailed(log)
        tailed.toSet().size.assert().isEqualTo(tailed.size)
        tailed.toSet().assert().containsAll(committed)
        (tailed.toSet() - committed).assert().allMatch { it in unknown }
        tailed.filter { it in failed }.assert().isEmpty()
        println("failover: ${committed.size} committed, ${unknown.size} unknown, ${failed.size} unavailable")
        committed.forEach { id -> log.read(type.ref("tenant", id)).toList().size.assert().isEqualTo(1) }
    }

    /**
     * The streams of partition 0 up to [AFTER]'s, in sequence order: appended last by this log, its sequence is past
     * every other's, so the tail has passed all of them when it reaches it.
     */
    private suspend fun tailed(log: EventLog): List<String> = withTimeout(TAIL_TIMEOUT) {
        log.tail(0).map { it.stream.ref.id }.transformWhile { id -> emit(id); id != AFTER }.toList()
    }

    private fun stepDown() {
        try {
            admin.runCommand(Document("replSetStepDown", STEP_DOWN_SECONDS).append("force", true))
        } catch (_: MongoException) {
            // The primary closes its connections as it steps down.
        }
    }

    /**
     * Kills the primary's process, a crash (`kill -9` in the cluster's container, which [CONTAINER] names); the
     * cluster's supervisor starts it again on its data.
     */
    private fun crashPrimary() {
        val port = checkNotNull(hello().getString("primary")).substringAfterLast(':')
        val kill = ProcessBuilder(
            "docker", "exec", System.getProperty(CONTAINER),
            "sh", "-c", "kill -9 \$(cat /data/cluster/rs0-$port.pid)",
        ).redirectErrorStream(true).start()
        check(kill.waitFor() == 0) { "The primary on $port was not killed: ${kill.inputStream.reader().readText()}" }
    }

    private fun hello(): Document = admin.runCommand(Document("hello", 1))

    private suspend fun awaitPrimary() = withTimeout(ELECTION_TIMEOUT) {
        while (runCatching { hello().getBoolean("isWritablePrimary") }.getOrNull() != true) delay(POLL_MS)
    }

    /** Waits until all three members are up, so the next test starts from a full set. */
    private fun awaitHealthy() = runBlocking {
        withTimeout(ELECTION_TIMEOUT * 2) {
            while (true) {
                val members = runCatching {
                    admin.runCommand(Document("replSetGetStatus", 1)).getList("members", Document::class.java)
                }.getOrNull()
                val up = members?.count { it.getString("stateStr") in setOf("PRIMARY", "SECONDARY") }
                if (up == MEMBERS && members.any { it.getString("stateStr") == "PRIMARY" }) break
                delay(POLL_MS)
            }
        }
    }

    /** [block] again while the set has no primary yet, as a caller retries an unavailable store. */
    private suspend fun <T> retrying(block: suspend () -> T): T = withTimeout(ELECTION_TIMEOUT) {
        while (true) {
            try {
                return@withTimeout block()
            } catch (_: MongoException) {
                delay(POLL_MS)
            } catch (_: StorageUnavailableException) {
                delay(POLL_MS)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun stream(type: AggregateType, id: String) = EventStreamRecord(
        ref = type.ref("tenant", id),
        version = 1,
        commandId = "command-$id",
        requestId = "request-$id",
        commandType = "${type.context}.order.Create",
        commitTime = 1L,
        events = listOf(EventRecord("${type.context}.order.Created", 1, "{}")),
        scope = Scope.NONE,
    )

    private companion object {
        const val MEMBERS = 3

        /** The system property naming the cluster's container, which cluster.sh sets. */
        const val CONTAINER = "wow.mongo.container"
        const val AFTER = "after"
        const val WRITERS = 8
        const val APPENDS = 40
        const val STEP_DOWN_SECONDS = 10
        const val POLL_MS = 100L
        val ELECTION_TIMEOUT = 60.seconds
        val TAIL_TIMEOUT = 60.seconds
    }
}
