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

package me.ahoo.wow.runtime.timer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.ORDER
import me.ahoo.wow.runtime.command.PlaceOrder
import me.ahoo.wow.runtime.command.Reserve
import me.ahoo.wow.runtime.command.Restock
import me.ahoo.wow.runtime.command.StockItems
import me.ahoo.wow.runtime.command.orderModel
import me.ahoo.wow.runtime.command.peer
import me.ahoo.wow.runtime.command.salesManifest
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import org.junit.jupiter.api.Test

/** The aggregate a context's derived or scheduled command names in its body, as the context's model or a contract reads it. */
class ContextCommandTypesTest {
    @Test
    fun `a peer's command is read by the property its contract names, a local one by its model`() {
        val sales = ContextCommandTypes(
            listOf(orderModel()),
            ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        )
        sales.aggregateIdOf(ITEM, Restock("i-9", 1)).assert().isEqualTo("i-9")
        // A command whose body names none, or one no contract names for the target.
        sales.aggregateIdOf(ITEM, Reserve(1)).assert().isNull()
        sales.aggregateIdOf(ORDER, PlaceOrder(1)).assert().isNull()
        sales.aggregateIdOf(ORDER, Restock("i-9", 1)).assert().isNull()
        val stock = ContextCommandTypes(listOf(StockItems().model), ContractDirectory(stockManifest()))
        stock.aggregateIdOf(ITEM, Restock("i-9", 1)).assert().isEqualTo("i-9")
    }

    @Test
    fun `a peer's command class two of its aggregates decide is named on the one it is scheduled to`() {
        val schema = Manifest.schemaRef(Refresh::class.java.name)
        val peer = Manifest(
            contexts = listOf(
                ContextManifest(
                    "peer",
                    aggregates = listOf("alpha", "beta").map { name ->
                        AggregateManifest(
                            name,
                            state = StateManifest(Manifest.schemaRef("peer.$name.State")),
                            commands = listOf(CommandManifest("Refresh", schema = schema)),
                        )
                    },
                ),
            ),
        )
        val types = ContextCommandTypes(
            listOf(orderModel()),
            ContractDirectory(salesManifest(), listOf(ManifestFragment("peer-api", peer)))
        )
        types.nameOf(AggregateType("peer", "alpha"), Refresh::class.java).assert().isEqualTo("peer.alpha.Refresh")
        types.nameOf(AggregateType("peer", "beta"), Refresh::class.java).assert().isEqualTo("peer.beta.Refresh")
        types.nameOf(ITEM, Refresh::class.java).assert().isNull()
    }

    class Refresh
}
