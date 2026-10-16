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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test

/** Blocking decide functions and after hooks (`@Blocking`, decisions D36, D227) run on the kernel's blocking context. */
class KernelBlockingTest {
    data class Open(val by: String)
    data class Read(val key: String)
    data class Opened(val by: String)
    data class Seen(val thread: String)

    class Ledger {
        val seen = mutableListOf<String>()
    }

    private val model = aggregate<Ledger>("test", "ledger") {
        initial { Ledger() }
        decide<Open>(Creation.REQUIRED) { _, open ->
            Decision.events(Opened(open.by), Seen("decide:" + Thread.currentThread().isVirtual))
        }
        decideBlocking<Read> { _, _ -> Decision.events(Seen("decideBlocking:" + Thread.currentThread().isVirtual)) }
        afterBlocking(include = setOf(Read::class.java)) { _, _ ->
            listOf(Seen("afterBlocking:" + Thread.currentThread().isVirtual))
        }
        evolve<Opened> { }
        evolve<Seen> { seen += it.thread }
    }
    private val ref = model.type.ref("t", "l-1")

    @Test
    fun `a blocking decide function and after hook run on a virtual thread, a plain one inline`() = runBlocking<Unit> {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(model, Command("c-1", "r-1", ref, Open("me")))
        val outcome = kernel.execute(model, Command("c-2", "r-2", ref, Read("k"))) as CommandOutcome.Committed
        (outcome.state as Ledger).seen.assert()
            .containsExactly("decide:false", "decideBlocking:true", "afterBlocking:true")
    }

    @Test
    fun `the blocking context is the kernel's to choose`() = runBlocking<Unit> {
        val names = mutableListOf<String?>()
        val traced = aggregate<Ledger>("test", "ledger") {
            initial { Ledger() }
            decideBlocking<Open>(Creation.REQUIRED) { _, open ->
                names += currentCoroutineContext()[CoroutineName]?.name
                Decision.events(Opened(open.by))
            }
            afterBlocking { _, _ ->
                names += currentCoroutineContext()[CoroutineName]?.name
                emptyList()
            }
            evolve<Opened> { }
            evolve<Seen> { }
        }
        val kernel = Kernel(InMemoryEventLog(), blocking = CoroutineName("blocking"))
        kernel.execute(traced, Command("c-1", "r-1", ref, Open("me")))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
        names.assert().containsExactly("blocking", "blocking")
    }

    @Test
    fun `the functions keep their flags`() {
        (model.decider(Read::class.java) as DecideFunction.Suspending).blocking.assert().isTrue()
        DecideFunction.Suspending<Ledger>(Creation.NONE) { _, _ -> Decision.NoOp }.blocking.assert().isFalse()
        AfterFunction.Suspending<Ledger>({ true }) { _, _ -> emptyList() }.blocking.assert().isFalse()
        (model.afterFunctions(Read::class.java).single() as AfterFunction.Suspending).blocking.assert().isTrue()
    }

    @Test
    fun `a suspending after hook that does not block runs inline`() = runBlocking<Unit> {
        val hooked = aggregate<Ledger>("test", "ledger") {
            initial { Ledger() }
            decideBlocking(Open::class.java, Creation.REQUIRED) { _, open -> Decision.events(Opened(open.by)) }
            afterSuspending { _, _ -> listOf(Seen("afterSuspending:" + Thread.currentThread().isVirtual)) }
            evolve<Opened> { }
            evolve<Seen> { seen += it.thread }
        }
        val outcome = Kernel(InMemoryEventLog()).execute(hooked, Command("c-1", "r-1", ref, Open("me")))
            as CommandOutcome.Committed
        (outcome.state as Ledger).seen.assert().containsExactly("afterSuspending:false")
        (hooked.decider(Open::class.java) as DecideFunction.Suspending).blocking.assert().isTrue()
    }
}
