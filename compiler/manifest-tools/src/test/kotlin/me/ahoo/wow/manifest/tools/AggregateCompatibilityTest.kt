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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test

/** An aggregate's changes ([compareAggregate]) the classification table of [ManifestCompatibilityTest] leaves out. */
class AggregateCompatibilityTest {
    private val baseline = ManifestCompatibilityTest.BASELINE

    private fun Manifest.cart(change: (AggregateManifest) -> AggregateManifest): Manifest =
        copyWith(contexts = contexts.map { it.copyWith(aggregates = it.aggregates.map(change)) })

    private fun changes(baseline: Manifest, current: Manifest): List<String> =
        ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

    /** The baseline with `AddItem` routed by [routes] and its aggregate's id assigned by [idSource]. */
    private fun addItem(idSource: AggregateIdSource, vararg routes: RouteManifest): Manifest = baseline.cart { cart ->
        cart.copyWith(
            idSource = idSource,
            commands = cart.commands.map { if (it.system) it else it.copyWith(routes = routes.toList()) },
        )
    }

    private val byId = RouteManifest("POST", "/shop/cart/{id}/add-item")
    private val byNoId = RouteManifest("POST", "/shop/cart/add-item")

    @Test
    fun `a route by id removed beside an id source change should be reported, whichever way the change goes`() {
        val removed = "BREAKING contexts[shop].aggregates[cart].commands[AddItem].routes: " +
            "route POST /shop/cart/{id}/add-item removed"
        changes(addItem(AggregateIdSource.OWNER, byId, byNoId), addItem(AggregateIdSource.ASSIGNED, byNoId))
            .assert().containsExactlyInAnyOrder(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from OWNER to ASSIGNED",
                removed,
            )
        changes(addItem(AggregateIdSource.ASSIGNED, byId, byNoId), addItem(AggregateIdSource.OWNER, byNoId))
            .assert().containsExactlyInAnyOrder(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from ASSIGNED to OWNER",
                removed,
            )
    }

    @Test
    fun `a route by no id the owned side alone has should go with the id source change`() {
        changes(addItem(AggregateIdSource.OWNER, byId, byNoId), addItem(AggregateIdSource.ASSIGNED, byId))
            .assert().containsExactly(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from OWNER to ASSIGNED",
            )
    }

    @Test
    fun `a readers' break should take the place only of the same change at its path, not of another`() {
        // The event's productId made optional, which its readers break on, and bounded, which they do not.
        val current = baseline.copyWith(
            defs = baseline.defs + (
                "shop.ItemAdded" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("productId" to mapOf("type" to "string", "maxLength" to 3)),
                    "required" to emptyList<String>(),
                )
                ),
        )
        changes(baseline, current).assert().containsExactlyInAnyOrder(
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded].schema.productId: maxLength 3 imposed",
            "BREAKING contexts[shop].aggregates[cart].events[ItemAdded].schema.productId: field made optional: " +
                "the subscribers of its published stream read it",
        )
    }

    @Test
    fun `an event should be retired by each of its names, full or simple, whichever the other is`() {
        // Its name is simple and retired by its full name; its alias is full and retired by its simple name.
        val aliased = baseline.cart { cart ->
            cart.copyWith(events = cart.events.map { it.copyWith(aliases = listOf("shop.cart.ItemPut")) })
        }
        val retired = aliased.cart {
            it.copyWith(
                events = emptyList(),
                retired = listOf("shop.cart.ItemAdded", "ItemPut")
            )
        }
        changes(aliased, retired).assert().containsExactlyInAnyOrder(
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded]: event retired",
            "ADDITIVE contexts[shop].aggregates[cart].retired: retired event shop.cart.ItemAdded added",
            "ADDITIVE contexts[shop].aggregates[cart].retired: retired event ItemPut added",
        )
    }
}
