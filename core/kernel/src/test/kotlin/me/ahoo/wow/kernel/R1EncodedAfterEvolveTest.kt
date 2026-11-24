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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (kernel): [Preparation] encodes a decision's events only after they evolved the state, so what the
 * log records is each event as the evolve functions of the decision left it, not as it was decided. An evolve function
 * that keeps a mutable value of its event in the state (a list it assigns rather than copies) lets the next event of
 * the same decision change the earlier event's payload before it is written: the cached state is the decided one, a
 * replay of the log is another.
 */
class R1EncodedAfterEvolveTest {
    data class Restore(val items: List<String>, val extra: String)
    data class CartRestored(val items: MutableList<String>)
    data class ItemAdded(val name: String)

    class Cart(val id: String) {
        var items: MutableList<String> = mutableListOf()
    }

    private val model = aggregate<Cart>("test", "cart") {
        initial { Cart(it.id) }
        decide<Restore>(Creation.ALLOWED) { _, body ->
            Decision.events(CartRestored(body.items.toMutableList()), ItemAdded(body.extra))
        }
        // Keeps the event's list as its own: the next event's evolve adds to it.
        evolve<CartRestored> { items = it.items }
        evolve<ItemAdded> { items.add(it.name) }
    }
    private val ref = model.type.ref("t", "c-1")

    @Test
    fun `the log records each event as it was decided, whatever the decision's evolve functions do to it`() = runTest {
        val log = InMemoryEventLog()
        val outcome = Kernel(log).execute(model, Command("c-1", "r-1", ref, Restore(listOf("a"), "b")))
        val decided = ((outcome as CommandOutcome.Committed).state as Cart).items.toList()
        decided.assert().containsExactly("a", "b")

        // The first event was decided with ["a"].
        val stored = log.read(ref).toList().single().events.first().payload
        stored.assert().isEqualTo("""{"items":["a"]}""")
        // A node that loads the aggregate from the log decides on the state the command left.
        val replayed = Kernel(log).stateAt(model, ref, 1)!!.state as Cart
        replayed.items.assert().containsExactly("a", "b")
    }
}
