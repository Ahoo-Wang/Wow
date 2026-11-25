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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.TraceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * What an observer sees of the commands an aggregate's lane runs as one batch ([Kernel.executeBatch]): each command it
 * settles observed as a command alone is ([Kernel.execute]), in its own caller's trace, and nothing of a decision it
 * hands back (#6855).
 */
class KernelBatchObservationTest {
    /** A trace named [name], current on the thread while a coroutine that carries it runs, as a tracer's is. */
    private class Trace(val name: String) : TraceContext(), ThreadContextElement<String?> {
        override val fields: Map<String, String> = mapOf("traceparent" to name)

        override fun updateThreadContext(context: CoroutineContext): String? = current.get().also { current.set(name) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = current.set(oldState)

        companion object {
            val current = ThreadLocal<String?>()
        }
    }

    /**
     * Records each execution, decision and append started, with the trace current as it starts, and each that ends,
     * with its outcome; each runs in its own trace, named after it.
     */
    private class Recording : Observer {
        val started = CopyOnWriteArrayList<String>()
        val ended = CopyOnWriteArrayList<String>()
        val rejections = CopyOnWriteArrayList<ObservedEvent.Rejected>()

        private fun stage(name: String): Observation {
            started += "$name in ${Trace.current.get()}"
            return object : Observation {
                private var outcome: String? = null
                override val context: CoroutineContext = Trace(name)

                override fun outcome(outcome: ObservedOutcome) {
                    this.outcome = outcome.value
                }

                override fun failed(error: Throwable) {
                    outcome = "failed ${error.javaClass.simpleName}"
                }

                override fun end() {
                    ended += "$name ${outcome ?: ObservedOutcome.OK.value}"
                }
            }
        }

        override fun executing(command: Command<*>, commandType: String): Observation = stage("execute ${command.id}")

        override fun deciding(command: Command<*>): Observation = stage("decide ${command.id}")

        override fun appending(stream: EventStreamRecord): Observation = stage("append ${stream.version}")

        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.Rejected) rejections += event
        }
    }

    /**
     * The log, whose [appendAll] commits only the first [commits] streams when set, the others conflicting, as when
     * another node committed at their versions; whose [appendAll] throws [failure] when set; and whose reads of a
     * state throw [unreadable] when set.
     */
    private class ScriptedLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var commits: Int? = null
        var failure: Exception? = null
        var unreadable: Exception? = null

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            failure?.let { throw it }
            val commits = commits ?: return delegate.appendAll(streams)
            return streams.mapIndexed { at, append ->
                if (at < commits) delegate.append(append.stream, append.transport) else AppendOutcome.Conflict(at)
            }
        }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            unreadable?.let { throw it }
            return delegate.read(ref, fromVersion, toVersion)
        }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private val observer = Recording()
    private val log = ScriptedLog()
    private val states = MapCache()
    private val kernel = Kernel(
        log,
        clock = { 0L },
        states = states,
        options = KernelOptions(recentRequests = 256),
        observer = observer,
    )
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    /** The next command's id, the one its execution is observed by. */
    private fun next(): String = "command-${sequence + 1}"

    private fun queued(body: Any, trace: Trace? = null): QueuedCommand {
        sequence++
        return QueuedCommand(Command("command-$sequence", "request-$sequence", ref, body), trace = trace)
    }

    private suspend fun create() {
        kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
        observer.started.clear()
        observer.ended.clear()
    }

    /** The stages of [id] that ended. */
    private fun endedOf(id: String): List<String> = observer.ended.filter { it.contains(" $id ") }

    @Test
    fun `the commands a batch commits should each end their execution and append, as a command alone does`(): Unit =
        runBlocking {
            kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
            // Alone: every stage ends, so a tracer exports its spans and wow.command.duration is recorded.
            observer.ended.assert().containsExactlyInAnyOrder(
                "decide create ok",
                "append 1 committed",
                "execute create committed"
            )
            observer.ended.clear()

            val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))

            outcomes.assert().allMatch { (it as BatchOutcome.Done).outcome is CommandOutcome.Committed }
            observer.ended.assert().containsExactlyInAnyOrder(
                "decide command-1 ok",
                "decide command-2 ok",
                "append 2 committed",
                "append 3 committed",
                "execute command-1 committed",
                "execute command-2 committed",
            )
        }

    @Test
    fun `each command of a batch should be observed in its own caller's trace, and its stream carry its append's`():
        Unit = runBlocking {
        create()
        // The batch runs in its first command's coroutine, the first caller's.
        val first = Trace("caller-1")
        withContext(first) {
            kernel.executeBatch(
                cartModel,
                listOf(queued(AddItem("a", 1), first), queued(AddItem("b", 1), Trace("caller-2")))
            )
        }
        // Its decision and its append are stages of its execution, as a command alone's are.
        observer.started.assert().containsExactly(
            "execute command-1 in caller-1",
            "decide command-1 in execute command-1",
            "execute command-2 in caller-2",
            "decide command-2 in execute command-2",
            "append 2 in execute command-1",
            "append 3 in execute command-2",
        )
        log.readEntries(ref).toList().drop(1).map { it.decodedTransport?.trace }.assert().containsExactly(
            mapOf("traceparent" to "append 2"),
            mapOf("traceparent" to "append 3"),
        )
    }

    @Test
    fun `a command the batch hands back should be observed only by the run that settles it`(): Unit = runBlocking {
        create()
        val full = queued(AddItem("c", 1))
        // The cart is full at the third product: the batch decides that command, keeps nothing of it, and hands it
        // back, to be decided alone.
        val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1)), full))
        val id = full.command.id
        outcomes[2].assert().isEqualTo(BatchOutcome.Again)
        endedOf(id).assert().isEmpty()

        // First in the lane: the batch decides it again, keeps nothing, and runs it alone in the same execution.
        kernel.executeBatch(cartModel, listOf(full, queued(AddItem("a", 1))))
        endedOf(id).assert().containsExactly("decide $id ok", "execute $id rejected")
        observer.started.filter { it.startsWith("execute $id ") }.assert().hasSize(2)
    }

    @Test
    fun `a batch member rejected without events should be observed rejected once, however often it is decided`():
        Unit = runBlocking {
        create()
        val full = queued(AddItem("c", 1))
        val logged = rejectionLines {
            // The cart is full at the third product: the batch hands that command back, to be decided alone.
            val outcomes = kernel.executeBatch(
                cartModel,
                listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1)), full)
            )
            outcomes[2].assert().isEqualTo(BatchOutcome.Again)
            // As the executor runs a command handed back at the head of an otherwise empty lane: alone.
            val alone = kernel.execute(cartModel, full.command)
            (alone as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("cart.full")
        }
        observer.rejections.map { it.code }.assert().containsExactly("cart.full")
        logged.assert().hasSize(1)
    }

    @Test
    fun `a rejected command handed back should be observed rejected once when the next batch decides it again`():
        Unit = runBlocking {
        create()
        val full = queued(AddItem("c", 1))
        val logged = rejectionLines {
            kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1)), full))
            // First in its lane, it leads the next batch, which decides it, hands it back and runs it alone.
            val again = kernel.executeBatch(cartModel, listOf(full, queued(AddItem("d", 1))))
            ((again[0] as BatchOutcome.Done).outcome as CommandOutcome.Rejected).rejection.code
                .assert().isEqualTo("cart.full")
            again[1].assert().isEqualTo(BatchOutcome.Again)
        }
        observer.rejections.map { it.code }.assert().containsExactly("cart.full")
        logged.map { it.level }.assert().containsExactly(Level.INFO)
    }

    /** The rejection lines [block] logs ([RejectionLog]). */
    private suspend fun rejectionLines(block: suspend () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(RejectionLog.CATEGORY) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
            return appender.list.filter { it.formattedMessage.startsWith("Rejected ") }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `the first command decided again alone should go on in the observation the batch started`(): Unit =
        runBlocking {
            create()
            kernel.executeBatch(cartModel, listOf(queued(CheckStock("a")), queued(AddItem("a", 1))))
            endedOf("command-1").assert().containsExactly("decide command-1 ok", "execute command-1 committed")
            observer.started.filter { it.startsWith("execute command-1 ") }.assert().hasSize(1)
        }

    @Test
    fun `a first command whose stream conflicts should be decided again alone in the same execution`(): Unit =
        runBlocking {
            create()
            kernel.execute(cartModel, Command("cached", "cached", ref, AddItem("a", 1)))
            // Another node appends version 3 while this node's cache holds version 2.
            log.append(
                EventStreamRecord(
                    ref = ref,
                    version = 3,
                    commandId = "other",
                    requestId = "other",
                    commandType = "example.cart.AddItem",
                    commitTime = 1L,
                    events = listOf(EventRecord("example.cart.ItemAdded", 1, """{"productId":"b","quantity":1}""")),
                    scope = Scope.NONE,
                ),
            )
            observer.ended.clear()
            val first = next()
            val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))

            ((outcomes[0] as BatchOutcome.Done).outcome as CommandOutcome.Committed).stream.version.assert().isEqualTo(
                4
            )
            outcomes[1].assert().isEqualTo(BatchOutcome.Again)
            // One execution, as a command alone that conflicted once: two decisions, two appends.
            endedOf(first).assert().containsExactly("decide $first ok", "decide $first ok", "execute $first committed")
            observer.ended.filter {
                it.startsWith(
                    "append "
                )
            }.assert().containsExactly("append 3 conflict", "append 4 committed")
            // The command after it is handed back: its decision's stage ended, its execution's is dropped.
            endedOf("command-2").assert().containsExactly("decide command-2 ok")
        }

    @Test
    fun `a later command whose stream conflicts should be handed back, its execution and append not ended`(): Unit =
        runBlocking {
            create()
            log.commits = 1
            val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))
            log.commits = null

            outcomes[1].assert().isEqualTo(BatchOutcome.Again)
            observer.ended.assert().containsExactlyInAnyOrder(
                "decide command-1 ok",
                "decide command-2 ok",
                "append 2 committed",
                "execute command-1 committed",
            )
        }

    @Test
    fun `an append that throws should fail each command's execution and append`(): Unit = runBlocking {
        create()
        log.failure = IOException("unreachable")
        val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))
        log.failure = null

        outcomes.assert().allMatch { it is BatchOutcome.Failed }
        observer.ended.assert().containsExactlyInAnyOrder(
            "decide command-1 ok",
            "decide command-2 ok",
            "append 2 failed IOException",
            "append 3 failed IOException",
            "execute command-1 failed IOException",
            "execute command-2 failed IOException",
        )
    }

    @Test
    fun `a batch that fails before it decides should fail the first command's execution, as execute fails it`():
        Unit = runBlocking {
        log.unreadable = IOException("unreadable")
        assertThrows<IOException> {
            kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))
        }
        observer.ended.assert().containsExactly("execute command-1 failed IOException")
    }

    @Test
    fun `without an observer a batch commits as before`(): Unit = runBlocking {
        val plain = Kernel(InMemoryEventLog(), clock = { 0L }, options = KernelOptions(recentRequests = 256))
        plain.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
        val outcomes = plain.executeBatch(
            cartModel,
            listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1), Trace("x")))
        )
        outcomes.map { ((it as BatchOutcome.Done).outcome as CommandOutcome.Committed).stream.version }
            .assert().containsExactly(2, 3)
    }
}
