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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestMergerTest {
    private val shared = mapOf("sample.Money" to mapOf("type" to "string"))

    @Test
    fun `fragments of different contexts should merge into one manifest`() {
        val shop = ManifestFragment("shop.json", Manifests.fragment("shop", defs = shared + ("a" to 1)))
        val billing = ManifestFragment("billing.json", Manifests.fragment("billing", defs = shared + ("b" to 2)))
        val service = ServiceInfo("store", "1.0.0")

        val merged = ManifestMerger.merge(listOf(shop, billing), service)

        merged.service.assert().isEqualTo(service)
        merged.contexts.map { it.name }.assert().containsExactly("billing", "shop")
        merged.defs.keys.assert().containsExactly("a", "b", "sample.Money")
    }

    @Test
    fun `merging should be the same whatever the order of the fragments`() {
        val shop = ManifestFragment("shop.json", Manifests.fragment("shop", defs = mapOf("z" to 1, "a" to 2)))
        val billing = ManifestFragment("billing.json", Manifests.fragment("billing", defs = mapOf("m" to 3)))
        ManifestMerger.merge(listOf(shop, billing)).assert().isEqualTo(ManifestMerger.merge(listOf(billing, shop)))
    }

    @Test
    fun `a context declared by two fragments should conflict`() {
        val exception = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(
                    ManifestFragment("a.json", Manifests.fragment("shop")),
                    ManifestFragment("b.json", Manifests.fragment("shop")),
                ),
            )
        }
        exception.conflicts.assert().contains("context shop is declared by a.json and b.json")
    }

    @Test
    fun `an aggregate declared twice should conflict`() {
        val fragment = Manifests.fragment(aggregates = listOf(Manifests.cart(), Manifests.cart()))
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().contains("aggregate shop.cart is declared twice in a.json")
    }

    @Test
    fun `a command name taken by another command's alias should conflict`() {
        val cart = Manifests.cart()
        val clash = cart.copy(commands = cart.commands + cart.commands[0].copy(name = "AddItem", aliases = emptyList()))
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", Manifests.fragment(aggregates = listOf(clash)))))
        }.conflicts
        conflicts.assert().containsExactly(
            "command type shop.cart.AddItem is declared by shop.cart.PutItem (a.json) and shop.cart.AddItem (a.json)",
        )
    }

    @Test
    fun `an event's full former name taken by an event of another aggregate should conflict`() {
        val basket = Manifests.cart(aggregate = "basket").let { basket ->
            val events = basket.events.map { it.copy(aliases = emptyList()) }
            basket.copy(events = events + EventManifest("ItemAdded", schema = "#/\$defs/x"))
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(ManifestFragment("a.json", Manifests.fragment(aggregates = listOf(Manifests.cart(), basket)))),
            )
        }.conflicts
        conflicts.assert().containsExactly(
            "event type shop.basket.ItemAdded is declared by shop.cart.ItemPut (a.json) and shop.basket.ItemAdded (a.json)",
        )
    }

    @Test
    fun `a subscription declared twice in a context should conflict`() {
        val fragment = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            fragment.copy(
                contexts = listOf(context.copy(subscriptions = context.subscriptions + context.subscriptions))
            )
        }
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().containsExactly(
            "subscription CartSummary.onEvent of context shop is declared twice in a.json"
        )
    }

    @Test
    fun `a schema that differs between fragments should conflict`() {
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(
                    ManifestFragment("a.json", Manifests.fragment("shop", defs = shared)),
                    ManifestFragment(
                        "b.json",
                        Manifests.fragment("billing", defs = mapOf("sample.Money" to mapOf("type" to "number")))
                    ),
                ),
            )
        }.conflicts
        conflicts.assert().containsExactly("schema sample.Money differs between a.json and b.json")
    }

    @Test
    fun `a fragment of another format should be refused`() {
        val exception = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", Manifests.fragment().copy(format = 2))))
        }
        exception.conflicts.assert().containsExactly("a.json is of format 2, not 1")
        exception.message.assert().contains("- a.json is of format 2, not 1")
    }
}
