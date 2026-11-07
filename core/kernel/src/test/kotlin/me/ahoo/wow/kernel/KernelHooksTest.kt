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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

class KernelHooksTest {
    data class Open(val by: String)
    data class Touch(val reason: String)
    data class Refuse(val reason: String)
    data class Boom(val reason: String)
    data class Exhaust(val fatal: Boolean)

    data class Opened(val by: String)
    data class Touched(val reason: String)
    data class Audited(val command: String)

    class Counter(val id: String) {
        var touches = 0
        var audits = 0
    }

    private val trail = mutableListOf<String>()

    private val model = aggregate<Counter>("test", "counter") {
        initial { Counter(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body ->
            putResult("app", command.headers.getValue("app") + "/" + command.scope.ownerId)
            Decision.events(Opened(body.by))
        }
        decide<Touch> { _, command ->
            onAppendFailure { trail += "undo ${command.reason}" }
            afterAppend { trail += "done ${command.reason}" }
            Decision.events(Touched(command.reason))
        }
        decide<Refuse> { _, command ->
            onAppendFailure { trail += "undo ${command.reason}" }
            onAppendFailure { check(command.reason != "fragile") { "undo failed" } }
            Decision.reject("refused", command.reason)
        }
        decide<Boom> { _, command ->
            onAppendFailure { trail += "undo ${command.reason}" }
            onAppendFailure { check(command.reason != "fragile") { "undo failed" } }
            throw UnsupportedOperationException("boom")
        }
        decide<Exhaust> { _, command ->
            onAppendFailure { trail += "undo first" }
            onAppendFailure { throw if (command.fatal) OutOfMemoryError("not really") else StackOverflowError() }
            onAppendFailure { trail += "undo last" }
            throw UnsupportedOperationException("boom")
        }
        after(exclude = setOf(Open::class.java)) { _, command -> listOf(Audited(command.javaClass.simpleName)) }
        evolve<Opened> { }
        evolve<Touched> { touches++ }
        evolve<Audited> { audits++ }
    }
    private val ref = model.type.ref("t", "c-1")
    private var sequence = 0

    private fun command(body: Any) = Command("c-${++sequence}", "r-$sequence", ref, body)

    private suspend fun opened(log: EventLog = InMemoryEventLog()): EventLog {
        Kernel(log).execute(model, open())
        return log
    }

    private fun open() = Command(
        "c-0",
        "r-0",
        ref,
        Open("me"),
        scope = Scope("alice", null),
        headers = mapOf("app" to "console")
    )

    @Test
    fun `a decide function reads the admitted command and answers the caller`() = runTest {
        val outcome = Kernel(InMemoryEventLog()).execute(model, open()) as CommandOutcome.Committed
        outcome.results.assert().isEqualTo(mapOf("app" to "console/alice"))
    }

    @Test
    fun `an after hook appends its events to the command's own, in one stream`() = runTest {
        val log = opened()
        val outcome = Kernel(log).execute(model, command(Touch("x"))) as CommandOutcome.Committed
        outcome.stream.events.map { it.name }.assert().containsExactly("test.counter.Touched", "test.counter.Audited")
        (outcome.state as Counter).audits.assert().isEqualTo(1)
        log.read(ref).toList().first().events.map { it.name }.assert().containsExactly("test.counter.Opened")
    }

    @Test
    fun `an after hook runs after a lifecycle command only when it names it`() = runTest {
        val hooked = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
            after { _, command -> listOf(Audited("any:" + command.javaClass.simpleName)) }
            after(include = setOf(DeleteAggregate::class.java)) { _, _ -> listOf(Audited("delete")) }
            evolve<Opened> { }
            evolve<Audited> { audits++ }
        }
        val log = InMemoryEventLog()
        Kernel(log).execute(hooked, open())
        val outcome = Kernel(log).execute(hooked, command(DeleteAggregate)) as CommandOutcome.Committed
        outcome.stream.events.map { it.name }
            .assert().containsExactly("wow.lifecycle.AggregateDeleted", "test.counter.Audited")
        log.read(ref).toList().first().events.map { it.name }
            .assert().containsExactly("test.counter.Opened", "test.counter.Audited")
    }

    @Test
    fun `an after hook does not run when the command is rejected`() = runTest {
        val log = opened()
        Kernel(log).execute(model, command(Refuse("no"))).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        log.read(ref).toList().size.assert().isEqualTo(1)
    }

    @Test
    fun `after-append actions run once the events are committed`() = runTest {
        Kernel(opened()).execute(model, command(Touch("x")))
        trail.assert().containsExactly("done x")
    }

    @Test
    fun `a rejection runs the compensations`() = runTest {
        Kernel(opened()).execute(model, command(Refuse("no")))
        trail.assert().containsExactly("undo no")
    }

    @Test
    fun `a failing decide function runs the compensations and fails`() = runTest {
        val log = opened()
        runCatching { Kernel(log).execute(model, command(Boom("b"))) }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        trail.assert().containsExactly("undo b")
    }

    @Test
    fun `a failing compensation does not stop the others, and joins the failure it undoes`() = runTest {
        val log = opened()
        Kernel(log).execute(model, command(Refuse("fragile")))
            .assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        val failure = runCatching { Kernel(log).execute(model, command(Boom("fragile"))) }.exceptionOrNull()
        failure?.message.assert().isEqualTo("boom")
        failure?.suppressed?.single()?.message.assert().isEqualTo("undo failed")
        trail.assert().containsExactly("undo fragile", "undo fragile")
    }

    @Test
    fun `a compensation's fatal error is thrown once every compensation ran, the failure it undoes joined to it`() =
        runTest {
            // X-80: an out of memory is the node's, never a failure to log and go on from.
            val log = opened()
            val fatal = runCatching { Kernel(log).execute(model, command(Exhaust(fatal = true))) }.exceptionOrNull()
            fatal.assert().isInstanceOf(OutOfMemoryError::class.java)
            fatal?.suppressed?.single()?.message.assert().isEqualTo("boom")
            trail.assert().containsExactly("undo last", "undo first")
        }

    @Test
    fun `a compensation's stack overflow joins the failure it undoes, as any failure of it`() = runTest {
        val log = opened()
        val failure = runCatching { Kernel(log).execute(model, command(Exhaust(fatal = false))) }.exceptionOrNull()
        failure?.message.assert().isEqualTo("boom")
        failure?.suppressed?.single().assert().isInstanceOf(StackOverflowError::class.java)
        trail.assert().containsExactly("undo last", "undo first")
    }

    @Test
    fun `a conflict runs the attempt's compensations before deciding again`() = runTest {
        val log = opened()
        val racing = object : EventLog by log {
            var raced = false
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                if (!raced) {
                    raced = true
                    Kernel(log).execute(model, Command("other", "other", ref, Touch("other")))
                }
                return log.append(stream, transport)
            }
        }
        trail.clear()
        Kernel(racing).execute(model, command(Touch("x"))).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        trail.assert().containsExactly("done other", "undo x", "done x")
    }

    @Test
    fun `an unknown outcome runs no compensation, for the events may be committed`() = runTest {
        val log = opened()
        val unknown = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
                AppendOutcome.Unknown(IllegalStateException("timeout"))
        }
        Kernel(unknown).execute(model, command(Touch("x"))).assert().isInstanceOf(CommandOutcome.Unknown::class.java)
        trail.assert().isEmpty()
    }

    @Test
    fun `an after-append action's fatal error is thrown once every action ran, the events committed`() = runTest {
        val exhausting = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Open>(Creation.REQUIRED) { _, command ->
                afterAppend { throw OutOfMemoryError("not really") }
                afterAppend { trail += "notified" }
                Decision.events(Opened(command.by))
            }
            evolve<Opened> { }
        }
        val log = InMemoryEventLog()
        runCatching { Kernel(log).execute(exhausting, command(Open("me"))) }.exceptionOrNull()
            .assert().isInstanceOf(OutOfMemoryError::class.java)
        trail.assert().containsExactly("notified")
        log.read(ref).toList().map { it.version }.assert().containsExactly(1)
    }

    @Test
    fun `an after-append action's stack overflow does not fail the committed command`() = runTest {
        val recursing = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Open>(Creation.REQUIRED) { _, command ->
                afterAppend { throw StackOverflowError() }
                Decision.events(Opened(command.by))
            }
            evolve<Opened> { }
        }
        Kernel(InMemoryEventLog()).execute(recursing, command(Open("me")))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a failing after-append action does not fail the committed command`() = runTest {
        val failing = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Open>(Creation.REQUIRED) { _, command ->
                afterAppend { error("notify failed") }
                Decision.events(Opened(command.by))
            }
            evolve<Opened> { }
        }
        Kernel(InMemoryEventLog()).execute(failing, command(Open("me")))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `deciding that changes the state fails when purity is verified`() = runTest {
        val sneaky = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Open>(Creation.REQUIRED) { state, command ->
                state.touches = 99
                Decision.events(Opened(command.by))
            }
            evolve<Opened> { }
        }
        val failure = runCatching {
            Kernel(
                InMemoryEventLog(),
                options = KernelOptions(verifyDecidePurity = true)
            ).execute(sneaky, command(Open("me")))
        }.exceptionOrNull()
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().contains("changed the state")
        Kernel(InMemoryEventLog()).execute(sneaky, command(Open("me")))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    /** [body] as a command to an instance of [model]. */
    private fun commandOf(model: AggregateModel<*>, body: Any) =
        Command("c-${++sequence}", "r-$sequence", model.type.ref("t", "x-1"), body)

    /** A state whose `lateinit` property only the creation event's sourcing assigns, as a 9.x state's often is. */
    class Late(val id: String) {
        lateinit var by: String
    }

    @Test
    fun `a fresh state with an unassigned lateinit should be created with purity verified, and checked after`() =
        runTest {
            val late = aggregate<Late>("test", "late") {
                initial { Late(it.id) }
                decide<Open>(Creation.REQUIRED) { _, command -> Decision.events(Opened(command.by)) }
                decide<Touch> { _, command -> Decision.events(Touched(command.reason)) }
                evolve<Opened> { by = it.by }
                evolve<Touched> { }
            }
            val kernel = Kernel(InMemoryEventLog(), options = KernelOptions(verifyDecidePurity = true))
            listOf(Open("me"), Touch("t")).forEach { body ->
                val outcome = kernel.execute(late, commandOf(late, body))
                outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
            }
            // Past version 0 it is written as any state is: one still unassigned fails.
            val unassigned = aggregate<Late>("test", "late") {
                initial { Late(it.id) }
                decide<Open>(Creation.REQUIRED) { _, command -> Decision.events(Opened(command.by)) }
                decide<Touch> { _, command -> Decision.events(Touched(command.reason)) }
                evolve<Opened> { }
                evolve<Touched> { }
            }
            val log = InMemoryEventLog()
            val strict = Kernel(log, options = KernelOptions(verifyDecidePurity = true))
            strict.execute(unassigned, commandOf(unassigned, Open("me")))
            val touch = commandOf(unassigned, Touch("t"))
            runCatching { strict.execute(unassigned, touch) }.exceptionOrNull().assert().isNotNull()
        }

    /** A fresh state Jackson cannot write for another reason than an unassigned `lateinit`. */
    class Broken(val id: String) {
        val total: Int get() = error("not computable")
    }

    @Test
    fun `a fresh state that cannot be written for another reason should fail when purity is verified`() = runTest {
        val broken = aggregate<Broken>("test", "broken") {
            initial { Broken(it.id) }
            decide<Open>(Creation.REQUIRED) { _, command -> Decision.events(Opened(command.by)) }
            evolve<Opened> { }
        }
        val failure = runCatching {
            Kernel(InMemoryEventLog(), options = KernelOptions(verifyDecidePurity = true))
                .execute(broken, commandOf(broken, Open("me")))
        }.exceptionOrNull()
        generateSequence(failure) { it.cause }.any { it.message == "not computable" }.assert().isTrue()
    }
}
