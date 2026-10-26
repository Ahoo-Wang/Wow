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

package me.ahoo.wow.runtime.contract

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import tools.jackson.databind.json.JsonMapper
import java.net.URLClassLoader

const val SHOP_SERVICE = "shop-service"

/** A class loader over the test resource directory [name] (`contract/<name>`) alone: its peers, no others. */
/** A class loader over the fixture directories `contract/<name>/`, each as an api jar would be on a class path. */
fun contractsLoader(vararg names: String): ClassLoader = URLClassLoader(
    names.map { requireNotNull(ContractFixtures::class.java.getResource("/contract/$it/")) }.toTypedArray(),
    null,
)

object ContractFixtures {
    /** The fixture peer contracts of `contract/inventory` (context inventory) and `contract/payment`. */
    val peers by lazy {
        ClassPathContracts.load(contractsLoader("inventory", "payment")) {
            JsonMapper.builder().build().readValue(it, Map::class.java)
        }
    }

    fun subscription(
        name: String,
        kind: SubscriptionKind = SubscriptionKind.SAGA,
        source: SubscriptionSource = SubscriptionSource("shop", listOf("cart")),
        derives: List<String>? = null,
        rejected: List<String>? = null,
        committed: List<String>? = null,
        calls: List<String>? = null,
    ): SubscriptionManifest = SubscriptionManifest(
        name = name,
        kind = kind,
        source = source,
        function = FunctionManifest(
            when (kind) {
                SubscriptionKind.PROJECTION -> FunctionKind.PROJECT
                SubscriptionKind.REACTOR -> FunctionKind.REACT
                SubscriptionKind.SAGA -> FunctionKind.SAGA
            },
            "shop",
            name.substringBefore('.'),
            name.substringAfter('.'),
        ),
        derives = derives,
        rejected = rejected,
        committed = committed,
        calls = calls,
    )

    /** The shop service's Manifest: context `shop`, its cart, a projection of it, and a saga on inventory's stock. */
    fun shop(
        subscriptions: List<SubscriptionManifest> = listOf(
            subscription("CartProjector.onCart", SubscriptionKind.PROJECTION),
            subscription(
                "OrderSaga.onStock",
                source = SubscriptionSource("inventory", listOf("stock")),
                derives = listOf(Manifest.schemaRef("sample.inventory.ReserveStock"), "shop.cart.AddItem"),
            ),
        ),
        contexts: List<ContextManifest> = emptyList(),
    ): Manifest = Manifest(
        service = ServiceInfo(SHOP_SERVICE),
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "cart",
                        state = StateManifest(Manifest.schemaRef("sample.shop.CartState")),
                        commands = listOf(CommandManifest("AddItem", schema = Manifest.schemaRef("sample.shop.AddItem"))),
                    ),
                ),
                subscriptions = subscriptions,
                published = listOf(PublishedManifest("cart", PublishedManifest.defaultTopic("shop", "cart"))),
            ),
        ) + contexts,
    )
}
