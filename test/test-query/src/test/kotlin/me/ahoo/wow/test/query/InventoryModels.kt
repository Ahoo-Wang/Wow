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

package me.ahoo.wow.test.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider

/** The tests' inventory, which the compiler would generate: its stocks and carts, by a fragment on the class path. */
class InventoryModels : ModelProvider {
    override val binding: Int = ModelProvider.BINDING
    override val context: String = "inventory"
    override val fragment: String = "META-INF/wow/manifest/test-query.json"

    /** As a module whose aggregates take a service: its states' classes come from the states a spec gives. */
    override fun models(dependencies: Dependencies): List<AggregateModel<*>> =
        throw MissingDependencyException(Warehouse::class.java, null)

    class Warehouse

    data class Stock(val available: Int)

    data class Cart(val items: Int)

    companion object {
        val STOCK = AggregateType("inventory", "stock")
        val CART = AggregateType("inventory", "cart")
    }
}
