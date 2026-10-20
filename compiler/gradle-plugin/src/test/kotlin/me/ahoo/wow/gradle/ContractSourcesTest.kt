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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test

class ContractSourcesTest {
    @Test
    fun `the package should be the one the context's classes share`() {
        ContractSources.packageOf(listOf("shop.api.AddItem", "shop.api.cart.ItemAdded")).assert().isEqualTo("shop.api")
        ContractSources.packageOf(listOf("shop.api.AddItem", "billing.Pay")).assert().isEmpty()
        ContractSources.packageOf(listOf("AddItem")).assert().isEmpty()
        ContractSources.packageOf(emptyList()).assert().isEmpty()
    }

    @Test
    fun `names should become Pascal case`() {
        ContractSources.pascal("eshop-inventory").assert().isEqualTo("EshopInventory")
        ContractSources.pascal("order_item.line").assert().isEqualTo("OrderItemLine")
        ContractSources.pascal("cart").assert().isEqualTo("Cart")
    }

    @Test
    fun `a context of the default package should be generated at the source root`() {
        val aggregate = AggregateManifest(
            name = "stock-item",
            state = StateManifest(Manifest.schemaRef("StockState")),
            commands = listOf(CommandManifest("Reserve", schema = Manifest.schemaRef("Reserve"))),
            events = listOf(EventManifest("Reserved", schema = Manifest.schemaRef("Reserved"))),
        )
        val source = ContractSources.of(
            Manifest(contexts = listOf(ContextManifest("inventory", aggregates = listOf(aggregate))))
        )
            .single()
        source.path.assert().isEqualTo("InventoryContract.kt")
        source.text.assert().startsWith("import ").contains("object StockItem", "AggregateType(NAME, \"stock-item\")")
    }
}
