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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionProjectContext
import me.ahoo.wow.kernel.ExecutionReactContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.Test

/** Decision D149: how the runtime takes the kernel's processor bindings from the binding indexes and calls them. */
class ProcessorAdapterTest {
    private val event = StateEvent(
        ORDER.ref(TENANT, "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf(Placed(listOf("apple"))),
        OrderState(),
        causation = Causation("root", "shop.order/t/o-0@1"),
    )

    /** A binding index as the compiler generates one for a module with [processors]. */
    private fun provider(context: String, vararg processors: ProcessorBinding<*>) = object : ModelProvider {
        override val context: String = context
        override val fragment: String = "META-INF/wow/manifest/$context.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()

        override fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> =
            processors.toList().also { dependencies.get(String::class.java) }
    }

    @Test
    fun `a blocking handler should run on a virtual thread, the others inline`(): Unit = runBlocking {
        var virtual: Boolean? = null
        val execution = ExecutionId("View.onEvent", event.ref, event.version)
        val blocking = ProcessorBinding.project("View", "onEvent", OrderState::class.java, blocking = true) { _, _ ->
            virtual = Thread.currentThread().isVirtual
        }
        SubscriptionBindings.call(blocking, event, ExecutionProjectContext(execution))
        virtual.assert().isEqualTo(true)
        val inline = ProcessorBinding.react("View", "onEvent", OrderState::class.java) { _, _ ->
            virtual = Thread.currentThread().isVirtual
        }
        SubscriptionBindings.call(inline, event, ExecutionReactContext(execution))
        virtual.assert().isEqualTo(false)
    }

    @Test
    fun `bindings should come from the binding indexes, by context, with unique names`() {
        val view = ProcessorBinding.project("View", "onEvent", OrderState::class.java) { _, _ -> }
        val saga = ProcessorBinding.saga("Saga", "onEvent", OrderState::class.java) { _, _ -> emptyList() }
        val bindings = SubscriptionBindings.of(
            Dependencies.of("service"),
            listOf(provider(SHOP, view), provider(SHOP, saga), provider("other")),
        )
        bindings.viewTypes(SHOP, listOf(orderModel(), cartModel())).assert().containsExactly(ORDER)
        bindings.stateReaders(SHOP, listOf(orderModel(), cartModel())).assert()
            .isEqualTo(mapOf("View.onEvent" to setOf(ORDER), "Saga.onEvent" to setOf(ORDER)))
        bindings.viewTypes("other", listOf(orderModel())).assert().isEmpty()
        assertThrownBy<IllegalArgumentException> {
            SubscriptionBindings(mapOf(SHOP to listOf(view, view)))
        }.hasMessageContaining("Context shop has two handlers named View.onEvent")
        assertThrownBy<IllegalArgumentException> {
            SubscriptionBindings(mapOf(SHOP to listOf(view))).viewTypes(SHOP, listOf(cartModel()))
        }.hasMessageContaining("View.onEvent reads the state ${OrderState::class.java.name}, which no aggregate")
        // No binding index on this module's class path declares processors.
        SubscriptionBindings.of(Dependencies.NONE).viewTypes(SHOP, listOf(orderModel())).assert().isEmpty()
    }

    @Test
    fun `a rejected derived command should say what was rejected`() {
        val command = Command("c-1", "r-1", CART.ref(TENANT, "alice"), "x")
        val failure = DerivedCommandRejectedException(command, Rejection(FORBIDDEN, "No."))
        failure.message.assert().isEqualTo(
            "The derived command c-1 on shop.cart/t/alice was rejected: cart.forbidden No."
        )
        failure.rejection.code.assert().isEqualTo(FORBIDDEN)
        failure.command.assert().isSameAs(command)
    }
}
