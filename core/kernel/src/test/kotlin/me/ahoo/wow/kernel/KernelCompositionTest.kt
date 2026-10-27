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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes

/**
 * Decision D336: a model's own command changes the lifecycle by producing the lifecycle event beside its domain event,
 * in one commit, and the kernel keeps the deleted mark and the schedules by it as by the lifecycle commands; a command
 * marked `onDeleted` is decided on a deleted aggregate, any other refused.
 */
class KernelCompositionTest {
    data class Open(val by: String)
    data class Close(val reason: String)
    data class Reopen(val reason: String)
    data class Note(val text: String)
    data object Touch
    data class Opened(val by: String)
    data class Closed(val reason: String)
    data class Reopened(val reason: String)
    data class Noted(val text: String)
    data object Touched

    class Ticket(val id: String) {
        var reason: String? = null
    }

    private val model = aggregate<Ticket>("test", "ticket") {
        initial { Ticket(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body ->
            schedule("expire", ScheduleRule.after(30.minutes), Close("expired"))
            Decision.events(Opened(body.by))
        }
        decide<Close> { _, body -> Decision.events(Closed(body.reason), AggregateDeleted) }
        decide<Reopen>(onDeleted = true) { _, body -> Decision.events(Reopened(body.reason), AggregateRestored) }
        decide<Note>(onDeleted = true) { _, body -> Decision.events(Noted(body.text)) }
        decide<Touch> { _, _ -> Decision.events(Touched) }
        evolve<Opened> { }
        evolve<Closed> { reason = it.reason }
        evolve<Reopened> { reason = null }
        evolve<Noted> { }
        evolve<Touched> { }
    }
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log)
    private val ref = model.type.ref("t", "ticket-1")
    private var sequence = 0

    private suspend fun send(body: Any): CommandOutcome {
        sequence++
        return kernel.execute(model, Command("c-$sequence", "r-$sequence", ref, body))
    }

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    @Test
    fun `a model command that produces AggregateDeleted deletes the aggregate and drops its schedules`(): Unit = runTest {
        send(Open("ahoo")).committed().schedules.keys.assert().containsExactly("expire")
        val closed = send(Close("duplicate")).committed()
        closed.stream.events.map { it.name }.assert().containsExactly("test.ticket.Closed", Lifecycle.AGGREGATE_DELETED)
        closed.deleted.assert().isTrue()
        closed.schedules.assert().isEmpty()
        // A command not marked onDeleted is refused, as on an aggregate the lifecycle command deleted.
        send(Touch).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
        send(Close("again")).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
    }

    @Test
    fun `an onDeleted command is decided on a deleted aggregate, and restores it when it produces AggregateRestored`(): Unit =
        runTest {
            send(Open("ahoo"))
            send(Close("duplicate"))
            // An onDeleted command without AggregateRestored leaves the aggregate deleted.
            send(Note("checked")).committed().deleted.assert().isTrue()
            val reopened = send(Reopen("not a duplicate")).committed()
            reopened.stream.events.map { it.name }.assert()
                .containsExactly("test.ticket.Reopened", Lifecycle.AGGREGATE_RESTORED)
            reopened.deleted.assert().isFalse()
            send(Touch).committed().stream.version.assert().isEqualTo(5)
            // It is decided on an aggregate that is not deleted too.
            send(Note("again")).committed()
        }

    /** A model whose own decide function of `DeleteAggregate` adds a domain rule and a domain event. */
    private fun overriding(transfer: Boolean = false) = aggregate<Ticket>("test", "ticket") {
        initial { Ticket(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decide<DeleteAggregate> { state, _ ->
            when {
                state.reason == "keep" -> Decision.reject("ticket.kept", "A kept ticket is not deleted.")
                transfer -> Decision.events(Closed("deleted"), AggregateDeleted, OwnerTransferred("mallory"))
                else -> Decision.events(Closed("deleted"), AggregateDeleted)
            }
        }
        evolve<Opened> { }
        evolve<Closed> { reason = it.reason }
    }

    @Test
    fun `a lifecycle command's own decide function decides its own lifecycle event beside the aggregate's`(): Unit =
        runTest {
            val model = overriding()
            kernel.execute(model, Command("c-1", "r-1", ref, Open("ahoo")))
            val deleted = kernel.execute(model, Command("c-2", "r-2", ref, DeleteAggregate)).committed()
            deleted.stream.events.map { it.name }.assert()
                .containsExactly("test.ticket.Closed", Lifecycle.AGGREGATE_DELETED)
            deleted.deleted.assert().isTrue()
        }

    @Test
    fun `a lifecycle command's own decide function that decides another lifecycle event fails the command`(): Unit =
        runTest {
            val model = overriding(transfer = true)
            kernel.execute(model, Command("c-1", "r-1", ref, Open("ahoo")))
            val failure = runCatching { kernel.execute(model, Command("c-2", "r-2", ref, DeleteAggregate)) }
            (failure.getOrNull() !is CommandOutcome.Committed).assert().isTrue()
            // The failure names the model, the command and the event it may not decide.
            LifecycleOverride.runCatching {
                checked(model.type, DeleteAggregate::class.java, Decision.events(OwnerTransferred("mallory")))
            }.exceptionOrNull()?.message.assert().contains("test.ticket", "DeleteAggregate", "OwnerTransferred")
            log.read(ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `suspending and blocking decide functions take onDeleted, and override a lifecycle command alike`(): Unit =
        runTest {
            val model = aggregate<Ticket>("test", "ticket") {
                initial { Ticket(it.id) }
                decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
                decideSuspending<DeleteAggregate> { state, _ ->
                    if (state.reason == "keep") {
                        Decision.reject("ticket.kept", "A kept ticket is not deleted.")
                    } else {
                        Decision.events(Closed("deleted"), AggregateDeleted)
                    }
                }
                decideSuspending<Note>(onDeleted = true) { _, body -> Decision.events(Noted(body.text)) }
                decideBlocking<Reopen>(onDeleted = true) { _, body ->
                    Decision.events(Reopened(body.reason), AggregateRestored)
                }
                decideBlocking<Close> { _, body -> Decision.events(Closed(body.reason)) }
                evolve<Opened> { }
                evolve<Closed> { reason = it.reason }
                evolve<Noted> { }
                evolve<Reopened> { reason = null }
            }
            suspend fun send(body: Any) = kernel.execute(model, Command("c-${++sequence}", "r-$sequence", ref, body))
            send(Open("ahoo"))
            // A rejection passes the override's check as it is.
            send(Close("keep"))
            send(DeleteAggregate).rejection().assert().isEqualTo("ticket.kept")
            send(Close("done"))
            send(DeleteAggregate).committed().deleted.assert().isTrue()
            send(Note("checked")).committed().deleted.assert().isTrue()
            send(Reopen("back")).committed().deleted.assert().isFalse()
            val blocking = DecideFunction.Suspending<Ticket>(Creation.NONE, true) { _, _ -> Decision.NoOp }
            blocking.onDeleted.assert().isFalse()
            blocking.blocking.assert().isTrue()
        }

    @Test
    fun `a model decides no schedule command, and its RestoreAggregate takes no onDeleted`() {
        assertThrows<IllegalArgumentException> {
            aggregate<Ticket>("test", "ticket") {
                initial { Ticket(it.id) }
                decide<CancelSchedule> { _, _ -> Decision.NoOp }
            }
        }.message.assert().contains("schedule command the kernel decides")
        assertThrows<IllegalArgumentException> {
            aggregate<Ticket>("test", "ticket") {
                initial { Ticket(it.id) }
                decide<RestoreAggregate>(onDeleted = true) { _, _ -> Decision.NoOp }
            }
        }.message.assert().contains("decided on a deleted aggregate already")
    }
}
