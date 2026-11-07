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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.clockOf
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

/** A node's fatal errors (X-80): the first makes it fatal for good, each is recorded and observed once. */
class NodeFatalsTest {
    private val observed = CopyOnWriteArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            observed += event
        }
    }
    private val fatals = NodeFatals("node-1", observer, clockOf { 1_000 })

    @Test
    fun `the first error should make the node fatal, each error recorded and observed once`() {
        val heap = OutOfMemoryError("Java heap space")
        val internal = InternalError("a JIT failure")
        fatals.isFatal.assert().isFalse()

        fatals.record(Component.SUBSCRIPTION, "svc/bank/audit", heap, partition = 3)
        fatals.record(Component.SUBSCRIPTION, "svc/bank/audit", heap, partition = 3)
        fatals.record(Component.EXECUTOR, "bank.account", internal)

        fatals.isFatal.assert().isTrue()
        fatals.first.assert().isEqualTo(
            NodeFatal(Component.SUBSCRIPTION, "svc/bank/audit", 3, "java.lang.OutOfMemoryError", 1_000),
        )
        fatals.count.assert().isEqualTo(2)
        observed.assert().containsExactly(
            ObservedEvent.Fatal(Component.SUBSCRIPTION, "svc/bank/audit", "java.lang.OutOfMemoryError"),
            ObservedEvent.Fatal(Component.EXECUTOR, "bank.account", "java.lang.InternalError"),
        )
    }

    @Test
    fun `a listener should hear the first error once, at once when it listens after it`() {
        val heard = CopyOnWriteArrayList<NodeFatal>()
        fatals.listen { heard += it }
        fatals.record(Component.TIMER, "bank", OutOfMemoryError("one"), partition = 0)
        fatals.record(Component.TIMER, "bank", OutOfMemoryError("two"), partition = 1)
        heard.map { it.partition }.assert().containsExactly(0)

        val late = CopyOnWriteArrayList<NodeFatal>()
        fatals.listen { late += it }
        late.single().partition.assert().isEqualTo(0)
    }

    @Test
    fun `a listener that fails should leave the error recorded`() {
        fatals.listen { error("the listener fails") }
        fatals.record(Component.NODE, "background", OutOfMemoryError())
        fatals.isFatal.assert().isTrue()
    }

    @Test
    fun `an error that escapes a scope should be recorded as the scope's part of the node`(): Unit = runBlocking {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + fatals.contextOf(Component.OWNERSHIP, "svc/bank")
        )
        val heap = OutOfMemoryError("Java heap space")
        scope.launch { throw heap }
        eventually { fatals.isFatal }
        fatals.first!!.component.assert().isEqualTo(Component.OWNERSHIP)
        fatals.first!!.name.assert().isEqualTo("svc/bank")
        fatals.first!!.partition.assert().isNull()
        // The loop that met it recorded it already: the handler does not count it again.
        fatals.record(Component.OWNERSHIP, "svc/bank", heap)
        fatals.count.assert().isEqualTo(1)
    }

    @Test
    fun `any other failure that escapes a scope should be logged, not fatal`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + fatals.contextOf(Component.NODE, "jobs"))
        scope.launch { throw NotImplementedError("a missing branch") }.join()
        val unnamed = CoroutineScope(SupervisorJob() + Dispatchers.Default + fatals.handler)
        unnamed.launch { throw OutOfMemoryError() }.join()
        fatals.first!!.component.assert().isEqualTo(Component.NODE)
        fatals.first!!.name.assert().isEqualTo("node-1")
        fatals.count.assert().isEqualTo(1)
    }

    @Test
    fun `a stack overflow should not be fatal, recorded neither by a loop nor by the handler`(): Unit = runBlocking {
        // X-80: it unwinds its own thread alone and comes back on the same input; fatal, it would stop every owner.
        NodeFatals.isFatal(StackOverflowError()).assert().isFalse()
        NodeFatals.isFatal(OutOfMemoryError()).assert().isTrue()
        NodeFatals.isFatal(InternalError()).assert().isTrue()
        NodeFatals.isFatal(UnknownError()).assert().isTrue()
        NodeFatals.isFatal(NotImplementedError()).assert().isFalse()
        fatals.record(Component.SUBSCRIPTION, "svc/bank/audit", StackOverflowError(), partition = 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + fatals.contextOf(Component.NODE, "jobs"))
        scope.launch { throw StackOverflowError() }.join()
        fatals.isFatal.assert().isFalse()
        observed.assert().isEmpty()
    }

    @Test
    fun `none should record nothing and give its scopes no handler`() {
        NodeFatals.NONE.record(Component.SUBSCRIPTION, "svc/bank/audit", OutOfMemoryError())
        NodeFatals.NONE.isFatal.assert().isFalse()
        NodeFatals.NONE.count.assert().isEqualTo(0)
        NodeFatals.NONE.contextOf(Component.SUBSCRIPTION, "svc/bank").assert().isEqualTo(EmptyCoroutineContext)
    }
}
