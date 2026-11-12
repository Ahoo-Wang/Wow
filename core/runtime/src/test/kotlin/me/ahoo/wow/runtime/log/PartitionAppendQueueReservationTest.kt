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
package me.ahoo.wow.runtime.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.reservation.ReservationScope
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Commands whose appends the queue commits in one call of the log, as it merges what callers queued meanwhile: a call
 * that committed part of its streams answers each command by its own stream (EventLog.appendAll).
 */
class PartitionAppendQueueReservationTest {
    data class Register(val name: String)
    data class Registered(val name: String)

    class User(val id: String) {
        var name: String? = null
    }

    private val model = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            when {
                reserve(NAMES, body.name) -> Decision.events(Registered(body.name))
                else -> Decision.reject("taken", body.name)
            }
        }
        evolve<Registered> { name = it.name }
    }

    @Test
    fun `a command whose stream a partly committed call committed is answered committed and keeps its key`(): Unit =
        runBlocking {
            val lost = IllegalStateException("The pool timed out.")
            val log = PartlyCommitting(InMemoryEventLog(partitions = 1), lost)
            val queue = PartitionAppendQueue(log, AppendQueueOptions())
            val reservations = InMemoryReservationStore()
            val kernel = Kernel(queue, reservations = reservations)
            // The first append holds the lane, so the two commands after it queue and go in one call.
            val first = async(Dispatchers.Default) { queue.append(stream("first"), null) }
            log.entered.await()
            val ahoo = async(Dispatchers.Default) { kernel.execute(model, command("u-1", "ahoo")) }
            queue.queuedSoon(1)
            val bob = async(Dispatchers.Default) { kernel.execute(model, command("u-2", "bob")) }
            queue.queuedSoon(2)
            log.release.complete(Unit)

            first.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
            ahoo.await().assert().isInstanceOf(CommandOutcome.Committed::class.java)
            (bob.await() as CommandOutcome.Unknown).cause.assert().isSameAs(lost)
            log.calls.assert().isEqualTo(listOf(1, 2))
            val scope = ReservationScope(model.type, "t", NAMES)
            reservations.find(scope, "ahoo")!!.confirmed.assert().isTrue()
            // An unknown stream whose version slot is empty compensates nothing: its key stays pending until it
            // expires, since the stream may yet be found committed.
            reservations.find(scope, "bob")!!.confirmed.assert().isFalse()
            queue.close()
        }

    private fun command(user: String, name: String) =
        Command("c-$user", "r-$user", model.type.ref("t", user), Register(name))

    private fun stream(id: String) = EventStreamRecord(
        ref = AggregateType("test", "other").ref("t", id),
        version = 1,
        commandId = "c-$id",
        requestId = "r-$id",
        commandType = "test.other.Act",
        commitTime = 1L,
        events = listOf(EventRecord("test.other.Acted", 1, "{}")),
        scope = Scope.NONE,
    )

    private suspend fun PartitionAppendQueue.queuedSoon(count: Int) {
        withTimeout(5.seconds) {
            while (queued < count) {
                yield()
            }
        }
    }

    /**
     * Holds its first call until [release]; each later call commits its first stream and answers the rest unknown with
     * [lost], as a store whose later chunk of a call failed answers it. Records the size of each call.
     */
    private class PartlyCommitting(private val delegate: EventLog, private val lost: Exception) : EventLog by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            calls += streams.size
            if (calls.size == 1) {
                entered.complete(Unit)
                release.await()
                return delegate.appendAll(streams)
            }
            val entry = streams.first()
            val head = entry.stream
            val transport = entry.transport
            return listOf(delegate.append(head, transport)) + streams.drop(1).map { AppendOutcome.Unknown(lost) }
        }
    }

    private companion object {
        const val NAMES = "user.name"
    }
}
