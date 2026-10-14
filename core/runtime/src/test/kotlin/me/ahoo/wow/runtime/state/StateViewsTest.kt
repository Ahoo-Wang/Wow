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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ORDER
import me.ahoo.wow.runtime.binding.Place
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Decision D133: the state views a context's state consumers share, and when they are evicted. */
class StateViewsTest {
    private val codec = EventCodec()
    private val time = TestTimeSource()
    private val alice = CART.ref(TENANT, "alice")
    private val bob = CART.ref(TENANT, "bob")

    private fun views(capacity: Int = 16, maxAge: kotlin.time.Duration = 60.seconds) = StateViews(
        codec,
        mapOf(SNAPSHOT to setOf(CART), PROJECTION to setOf(CART, ORDER)),
        StateViewOptions(capacity, maxAge),
        time,
    )

    private fun view(version: Int) = SerializedState(version, "{}")

    @Test
    fun `the executor's commits should be recorded for the types some reader reads`(): Unit = runBlocking {
        val kernel = Kernel(InMemoryEventLog(), codec = codec)
        val readers = StateViews(codec, mapOf(PROJECTION to setOf(CART)))
        readers.types.assert().containsExactly(CART)
        readers.readers.assert().isEqualTo(mapOf(PROJECTION to setOf(CART)))
        val cart = kernel.execute(cartModel(), Command("c-1", "r-1", alice, AddItems(listOf("apple"))))
        readers.record(cartModel(), cart as CommandOutcome.Committed)
        readers.get(alice, 1)!!.state.assert().contains("apple")
        val order = kernel.execute(orderModel(), Command("c-2", "r-2", ORDER.ref(TENANT, "o-1"), Place(listOf("a"))))
        readers.record(orderModel(), order as CommandOutcome.Committed)
        readers.size.assert().isEqualTo(1)
    }

    @Test
    fun `a view should be kept until every reader of its type passed a later version`() {
        val views = views()
        views.put(alice, view(1))
        views.put(alice, view(2))
        views.put(alice, view(3))
        views.get(alice, 2).assert().isNotNull()
        views.floor(alice, 3)!!.version.assert().isEqualTo(2)
        views.floor(alice, 1).assert().isNull()
        views.pass(PROJECTION, alice, 3)
        views.size.assert().isEqualTo(3)
        views.pass(SNAPSHOT, alice, 2)
        // Both passed version 1; version 2 stays as the snapshot reader's base.
        views.get(alice, 1).assert().isNull()
        views.get(alice, 2).assert().isNotNull()
        views.pass(SNAPSHOT, alice, 3)
        views.size.assert().isEqualTo(1)
        views.get(alice, 3).assert().isNotNull()
        // A version every reader passed is not kept; one already held is kept as it is.
        views.put(alice, view(2))
        views.get(alice, 2).assert().isNull()
        val held = views.get(alice, 3)
        views.put(alice, view(3))
        views.get(alice, 3).assert().isSameAs(held)
    }

    @Test
    fun `a reader of another type, an unknown reader or an unknown aggregate should pass nothing`() {
        val views = views()
        views.put(alice, view(1))
        views.put(ORDER.ref(TENANT, "o-1"), view(1))
        views.pass(SNAPSHOT, ORDER.ref(TENANT, "o-1"), 5)
        views.pass("Unknown.reader", alice, 5)
        views.pass(PROJECTION, bob, 5)
        views.pass(PROJECTION, AggregateTypes.MAIL.ref(TENANT, "m"), 5)
        views.size.assert().isEqualTo(2)
        views.put(AggregateTypes.MAIL.ref(TENANT, "m"), view(1))
        views.size.assert().isEqualTo(2)
    }

    @Test
    fun `the views should be bounded by count, oldest first`() {
        val views = views(capacity = 2)
        views.put(alice, view(1))
        views.put(bob, view(1))
        views.put(alice, view(2))
        views.size.assert().isEqualTo(2)
        views.get(alice, 1).assert().isNull()
        views.get(bob, 1).assert().isNotNull()
        views.put(bob, view(2))
        views.put(alice, view(3))
        views.get(bob, 1).assert().isNull()
        views.get(alice, 2).assert().isNull()
        views.floor(alice, 3).assert().isNull()
    }

    @Test
    fun `the views should age out`() {
        val views = views(maxAge = 10.seconds)
        views.put(alice, view(1))
        time += 6.seconds
        views.put(bob, view(1))
        time += 6.seconds
        views.get(alice, 1).assert().isNull()
        views.get(bob, 1).assert().isNotNull()
        time += 6.seconds
        views.floor(bob, 2).assert().isNull()
        views.size.assert().isEqualTo(0)
    }

    @Test
    fun `the options and a view should be valid`() {
        assertThrownBy<IllegalArgumentException> { StateViewOptions(capacity = 0) }
        assertThrownBy<IllegalArgumentException> { StateViewOptions(maxAge = 0.seconds) }
        assertThrownBy<IllegalArgumentException> { SerializedState(0, "{}") }
        view(3).toString().assert().isEqualTo("SerializedState(version=3)")
    }

    private object AggregateTypes {
        val MAIL = me.ahoo.wow.api.aggregate.AggregateType("shop", "mail")
    }

    private companion object {
        const val SNAPSHOT = "snapshot"
        const val PROJECTION = "View.onEvent"
    }
}
