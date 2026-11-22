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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The check of a map that fills the missing owner of a cart, one aggregate per owner in V10, with the cart's id
 * ([AggregateMapping.missingOwner]): it fills only the streams that record none, a cart with another owner still
 * fails it, and a warning counts what it fills and lists the carts whose owner it infers from the id alone.
 */
class MissingOwnerCheckTest {
    /** The example's streams, but its own cart's, and three carts: none records an owner; one records its id; one another. */
    private val source = InMemoryNineXEventSource(NineXExample.streams()).also { source ->
        NineXExample.cartAs("cart-n").forEach(source::add)
        NineXExample.cartAs("cart-p").forEachIndexed { index, stream ->
            source.add(if (index < 2) stream.copy(ownerId = "cart-p") else stream)
        }
        NineXExample.cartAs("cart-m").forEachIndexed { index, stream ->
            source.add(if (index == 0) stream else stream.copy(ownerId = "customer-2"))
        }
    }

    private fun checked(map: String, manifest: me.ahoo.wow.manifest.Manifest = NineXExample.ownedCartManifest) =
        MappingCheck(MigrationPlan(MigrationMap.read(map), manifest)).check(
            source.inventory(),
            ownersOf = source::ownersOtherThanIds,
            idOwned = source::instancesOwnedById,
        )

    @Test
    fun `a map that fills a missing owner warns of each stream it fills, and fails a cart with another owner`() {
        val report = checked(NineXExample.fillingMapText())
        report.problems.assert().containsExactly(
            "example-service.cart: the id of the V10 aggregate example.cart is its owner's, but the streams of these " +
                "1 9.x instances record another owner than their id, or none, so their owners could not reach them; " +
                "set each stream's ownerId to its aggregate id in the 9.x store before the import, or exclude each " +
                "with {\"aggregate\": \"example-service.cart\", \"stream\": <the id as listed>, \"reason\": \"…\"}: " +
                "\"cart-m\" (the owner \"customer-2\": 3 streams of the tenants [(0)])",
        )
        report.warnings.filter { "missingOwner" in it }.assert().containsExactly(
            "example-service.cart: missingOwner fills the owner of 6 streams of 2 9.x instances with their id, as V10 " +
                "records the owner of example.cart; 1 of them record the id as their owner on other streams, 1 on " +
                "none, so their owner is inferred from the id alone. A filled owner reaches an aggregate 9.x did not " +
                "let it reach, and an id that is another user's gives that user the aggregate: check each instance " +
                "whose owner is inferred: \"cart-n\" (no owner: 4 streams of the tenants [(0)])",
        )
    }

    @Test
    fun `without the key a stream that records no owner fails, the problem saying the map may fill it`() {
        val problem = checked(NineXExample.fillingMapText(fills = false)).problems.single()
        problem.assert()
            .contains("in the 9.x store before the import,$MISSING_OWNER_WAY or")
            .contains("\"cart-n\" (no owner: 4 streams")
            .contains("\"cart-p\" (no owner: 2 streams")
        // Only another owner: filling would not help, and the problem does not offer it.
        val others = checked(NineXExample.fillingMapText(listOf("cart-n", "cart-p", "cart-m"), fills = false))
        others.problems.single().assert().contains("\"${NineXExample.CART_ID}\" (the owner \"customer-1\"")
            .doesNotContain("missingOwner")
    }

    @Test
    fun `the key on an aggregate whose id is not its owner's fails the check, and fills nothing`() {
        val report = checked(NineXExample.fillingMapText(), NineXExample.manifest)
        report.problems.assert().containsExactly(
            "example-service.cart -> example.cart: missingOwner fills a missing owner with the id, but the id of " +
                "example.cart is not its owner's (idSource = OWNER): remove it",
        )
        report.warnings.none { "missingOwner fills the owner" in it }.assert().isTrue()
    }

    @Test
    fun `the warning lists the first twenty carts whose owner it infers, and counts the others`() {
        val many = InMemoryNineXEventSource(emptyList()).also { many ->
            (1..22).forEach { NineXExample.cartAs("cart-%02d".format(it)).forEach(many::add) }
        }
        val warning = MappingCheck(
            MigrationPlan(MigrationMap.read(NineXExample.fillingMapText(emptyList())), NineXExample.ownedCartManifest),
        ).check(many.inventory(), ownersOf = many::ownersOtherThanIds, idOwned = many::instancesOwnedById)
            .warnings.single { "missingOwner" in it }
        warning.assert().contains("88 streams of 22 9.x instances").contains("\"cart-20\" (no owner: 4 streams")
            .endsWith(" and 2 more").doesNotContain("cart-21")
    }

    @Test
    fun `the map reads the key, refuses another value, and draft-map never writes it`() {
        MigrationMap.read(NineXExample.fillingMapText()).aggregates.single { it.to == "example.cart" }.missingOwner
            .assert().isEqualTo(MissingOwner.AGGREGATE_ID)
        assertThrows<RuntimeException> {
            MigrationMap.read(NineXExample.fillingMapText().replace("\"aggregate-id\"", "\"id\""))
        }
        val draft = MapDrafter(NineXExample.ownedCartManifest).draft(source.inventory())
        draft.aggregates.map { it.missingOwner }.assert().containsOnly(null)
        draft.render().assert().doesNotContain("missingOwner")
    }
}
