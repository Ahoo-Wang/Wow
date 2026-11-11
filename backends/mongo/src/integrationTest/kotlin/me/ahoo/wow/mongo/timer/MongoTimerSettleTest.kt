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

package me.ahoo.wow.mongo.timer

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * MongoDB's `settle` (SPI-3): one `bulkWrite`, whose matched count says which changes were written when it is all of
 * them, none, or the batch is one change; a short count of several reads the batch back once. Its client runs a hook
 * right after the bulk write's `update` returns, so another writer's change lands between the write and the read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoTimerSettleTest {
    private val sent = ConcurrentLinkedQueue<String>()
    private val afterUpdate = AtomicReference<(() -> Unit)?>()
    private val client = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
            .addCommandListener(
                object : CommandListener {
                    override fun commandStarted(event: CommandStartedEvent) {
                        sent += event.commandName
                    }

                    override fun commandSucceeded(event: CommandSucceededEvent) {
                        if (event.commandName == "update") afterUpdate.getAndSet(null)?.invoke()
                    }
                },
            )
            .build(),
    )
    private val context = MongoLogFixture.newContext("settle")
    private val store =
        MongoTimerStore(client, MongoLogFixture.DATABASE, context, PARTITIONS).also { it.createSchema() }

    /** Another node's store, on a client of its own. */
    private val other = MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, PARTITIONS)

    @AfterAll
    fun close() = client.close()

    @Test
    fun `a batch whose changes all matched is one round trip`(): Unit = runBlocking {
        val (first, second) = put("first", "second")
        commands { store.settle(listOf(first.firing(), second.firing())) }.let { (written, commands) ->
            written.assert().containsExactly(true, true)
            commands.assert().containsExactly("update")
        }
    }

    @Test
    fun `a batch of one says exactly whether this call wrote it`(): Unit = runBlocking {
        val (timer) = put("only")
        store.settle(listOf(timer.firing())).assert().containsExactly(true)
        commands { store.settle(listOf(timer.firing())) }.let { (written, commands) ->
            written.assert().containsExactly(false)
            commands.assert().containsExactly("update")
        }
    }

    @Test
    fun `another writer's identical change of one this call lost makes the read disagree, none reported`(): Unit =
        runBlocking {
            val (first, second) = put("first", "second")
            other.settle(listOf(second.firing())).assert().containsExactly(true)
            commands { store.settle(listOf(first.firing(), second.firing())) }.let { (written, commands) ->
                // One matched, two found holding their next: the read cannot say which is this call's.
                written.assert().containsExactly(false, false)
                commands.assert().containsExactly("update", "find")
            }
            store.find(first.owner, "first").assert().isEqualTo(first.firing().next)
        }

    @Test
    fun `a change this call wrote that another replaced before the read is not reported`(): Unit = runBlocking {
        val (first, second) = put("first", "second")
        val stale = TimerChange(second.copyWith(attempts = 5), second.firing().next.copyWith(attempts = 5))
        afterUpdate.set { runBlocking { other.settle(listOf(first.firing().then())) } }
        store.settle(listOf(first.firing(), stale)).assert().containsExactly(false, false)
        store.find(first.owner, "first").assert().isEqualTo(first.firing().then().next)
    }

    @Test
    fun `at once, the change the store holds is reported and the replaced one is not`(): Unit = runBlocking {
        val (first, second) = put("first", "second")
        other.settle(listOf(second.firing())).assert().containsExactly(true)
        afterUpdate.set { runBlocking { other.settle(listOf(first.firing().then())) } }
        // One matched (the first), one found holding its next (the second, another writer's identical change).
        store.settle(listOf(first.firing(), second.firing())).assert().containsExactly(false, true)
    }

    private suspend fun put(vararg keys: String): List<Timer> {
        val owner = AggregateType(context, "order").ref("tenant", "o-${System.nanoTime()}")
        return keys.map { key ->
            Timer(
                id = "$owner#$key@1",
                owner = owner,
                key = key,
                version = 1,
                partition = Partitioner.partitionOf(owner, PARTITIONS),
                rule = ScheduleRule.At(DUE),
                dueAt = DUE,
                target = owner,
                commandType = "$context.order.Cancel",
                body = "{}",
            ).also { store.put(it).assert().isTrue() }
        }
    }

    /** This timer claimed. */
    private fun Timer.firing() = TimerChange(this, copyWith(status = TimerStatus.FIRING, dueAt = DUE + CLAIM))

    /** The claim's outcome recorded: the change that follows this one. */
    private fun TimerChange.then() =
        TimerChange(next, next.copyWith(status = TimerStatus.FIRED, attempts = 1, outcome = TimerOutcomes.COMMITTED))

    /** What [block] answered, and the commands it sent, the driver's monitoring left out. */
    private suspend fun <T> commands(block: suspend () -> T): Pair<T, List<String>> {
        sent.clear()
        val answer = block()
        return answer to sent.filter { it !in MONITORING }
    }

    private companion object {
        const val PARTITIONS = 4
        const val DUE = 1_000L
        const val CLAIM = 60_000L
        val MONITORING = setOf("hello", "isMaster", "ismaster", "buildInfo", "endSessions", "ping")
    }
}
