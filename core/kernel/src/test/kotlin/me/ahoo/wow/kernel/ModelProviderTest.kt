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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** Decision D107: the binding index's ports. */
class ModelProviderTest {
    fun interface Pricing {
        fun price(sku: String): Long
    }

    /** A provider as the compiler generates one, registered in this module's test resources. */
    class CartModelProvider : ModelProvider {
        override val context: String = "shop"

        override val binding: Int = ModelProvider.BINDING
        override val fragment: String = "META-INF/wow/manifest/shop.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
            val pricing = dependencies.get(Pricing::class.java)
            return listOf(
                aggregate<CartState>(context, "cart") {
                    initial { CartState(it.id) }
                    decide<AddItem> { _, command -> decisionOf(ItemAdded(command.sku, pricing.price(command.sku))) }
                    evolve<ItemAdded> { }
                },
            )
        }
    }

    class CartState(val id: String)

    data class AddItem(val sku: String)

    data class ItemAdded(val sku: String, val price: Long)

    @Test
    fun `providers registered for ServiceLoader should be found`() {
        val provider = ModelProvider.load().single()
        provider.assert().isInstanceOf(CartModelProvider::class.java)
        provider.models(Dependencies.of(Pricing { 1 })).single().type.toString().assert().isEqualTo("shop.cart")
    }

    @Test
    fun `a provider generated for another binding should be refused when it is loaded`() {
        val stale = object : ModelProvider {
            override val binding: Int = ModelProvider.BINDING - 1
            override val context: String = "shop"
            override val fragment: String = "META-INF/wow/manifest/old.json"

            override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()
        }
        assertThrownBy<IllegalStateException> { ModelProvider.checkBinding(stale) }
            .hasMessageContaining("was generated for binding ${ModelProvider.BINDING - 1} of wow-kernel")
            .hasMessageContaining("which reads binding ${ModelProvider.BINDING}")
        ModelProvider.checkBinding(CartModelProvider())
    }

    @Test
    fun `a missing dependency should fail the models, naming its type`() {
        assertThrownBy<MissingDependencyException> { CartModelProvider().models(Dependencies.NONE) }
            .hasMessage(
                "No dependency of ${Pricing::class.java.name} to build the aggregate models with: register one with " +
                    "the host.",
            )
    }

    @Test
    fun `a missing qualified dependency should name its qualifier`() {
        val error = runCatching { Dependencies.of(Pricing { 1 }).get(Pricing::class.java, "retail") }
            .exceptionOrNull() as MissingDependencyException
        error.message.assert().contains("${Pricing::class.java.name} qualified \"retail\"")
        error.type.assert().isEqualTo(Pricing::class.java)
        error.qualifier.assert().isEqualTo("retail")
    }

    @Test
    fun `dependencies should be found by type, and by qualifier when one is given`() {
        val retail = Pricing { 2 }
        val wholesale = Pricing { 1 }
        val dependencies = Dependencies.of(mapOf("retail" to retail, "wholesale" to wholesale), "text")
        dependencies.get(Pricing::class.java, "wholesale").assert().isSameAs(wholesale)
        dependencies.get(String::class.java).assert().isEqualTo("text")
        dependencies.find(Pricing::class.java, null).assert().isNull()
        // A qualifier names one dependency, of the type asked for.
        dependencies.find(String::class.java, "retail").assert().isNull()
    }

    @Test
    fun `two dependencies of a type should be ambiguous without a qualifier`() {
        assertThrownBy<IllegalStateException> {
            Dependencies.of(Pricing { 1 }, Pricing { 2 }).get(Pricing::class.java)
        }.hasMessage("2 dependencies are ${Pricing::class.java.name}: qualify the one the models take.")
    }

    @Test
    fun `a dependency of another type than asked should fail`() {
        val wrong = Dependencies { _, _ -> "text" }
        assertThrownBy<IllegalStateException> { wrong.get(Pricing::class.java) }
            .hasMessage("The dependency of ${Pricing::class.java.name} is a java.lang.String.")
    }
}
