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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The check of a V10 type whose tenant `migrate-data.json` fixes ([MigrationPlan.fixedTenants]), as the host's
 * `wow.aggregates.[<context>.<aggregate>].tenant` does: its 9.x instances in another tenant, which V10 would serve to no
 * caller, fail it, and a type left unfixed whose instances are all in one tenant other than the default one warns.
 */
class FixedTenantCheckTest {
    @Test
    fun `instances of a type whose tenant is fixed in another tenant fail it, unless the map excludes them`() {
        val source = InMemoryNineXEventSource(NineXExample.streams())
        val carts = NineXExample.CART_STREAMS.map(NineXExample::stream)
        val id = carts.first().aggregateId.id
        // Two more carts, in the tenant acme.
        listOf("cart-a", "cart-b").forEach { other ->
            carts.forEach { source.add(it.copy(id = "${it.id}-$other", aggregateId = NineXAggregateId(other, "acme"))) }
        }
        fun checked(tenant: String, map: MigrationMap = NineXExample.map) =
            MappingCheck(MigrationPlan(map, NineXExample.manifest, mapOf("example.cart" to tenant)))
                .check(source.inventory(), outsideTenant = source::instancesOutsideTenant)
        checked("acme").problems.assert().containsExactly(
            "example-service.cart: the tenant of the V10 aggregate example.cart is fixed to \"acme\" (migrate-data.json " +
                "tenants, as the host's wow.aggregates.[example.cart].tenant), but 1 9.x instances are in other tenants: " +
                "\"(0)\" (1 instances, 4 streams). V10 serves example.cart in that tenant only, so no caller could reach " +
                "them, and a command would create them again there; fix the tenant to the one they are in, in the host " +
                "and in tenants, or exclude each with {\"aggregate\": \"example-service.cart\", \"stream\": <the id as " +
                "listed>, \"reason\": \"…\"}: \"$id\"",
        )
        checked("(0)").problems.single().assert()
            .contains("but 2 9.x instances are in other tenants: \"acme\" (2 instances, 8 streams).")
            .endsWith(": \"cart-a\", \"cart-b\"")
        // Excluded, they pass; a type the map does not fix is not checked.
        val excluding = NineXExample.map.copy(
            excluded = listOf("cart-a", "cart-b").map {
                Exclusion(aggregate = "example-service.cart", reason = "Another tenant's", stream = it)
            },
        )
        checked("(0)", excluding).problems.assert().isEmpty()
        MappingCheck(MigrationPlan(NineXExample.map, NineXExample.manifest))
            .check(source.inventory(), outsideTenant = source::instancesOutsideTenant).problems.assert().isEmpty()
    }

    @Test
    fun `a problem lists the first twenty instances outside the fixed tenant, and counts the others`() {
        val outside = (1..22).associate { NineXAggregateId("cart-%02d".format(it), "acme") to 1L }
        val plan = MigrationPlan(NineXExample.map, NineXExample.manifest, mapOf("example.cart" to "(0)"))
        val inventory = InMemoryNineXEventSource(NineXExample.streams()).inventory()
        val problem = MappingCheck(plan).check(inventory, outsideTenant = { _, _ -> outside }).problems.single()
        problem.assert().contains("\"cart-20\" and 2 more").doesNotContain("cart-21")
    }

    @Test
    fun `a type left unfixed whose instances are all in one tenant other than the default one warns`() {
        fun warnings(vararg tenants: String): List<String> {
            val source = InMemoryNineXEventSource(emptyList())
            NineXExample.streams().filter { it.aggregate.name == "cart" }.forEach { stream ->
                tenants.forEachIndexed { index, tenant ->
                    val aggregateId = stream.aggregateId.copy(tenantId = tenant)
                    source.add(stream.copy(id = "${stream.id}-$index", aggregateId = aggregateId))
                }
            }
            return MappingCheck(MigrationPlan(NineXExample.map, NineXExample.manifest)).check(source.inventory())
                .warnings.filter { "tenant" in it }
        }
        warnings("acme").assert().containsExactly(
            "example-service.cart: all 1 9.x instances are in the tenant \"acme\", where V10 serves example.cart to that " +
                "tenant's callers only, unless its tenant is fixed: if 9.x fixed it (@StaticTenantId), fix it in the " +
                "host (wow.aggregates.[example.cart].tenant: acme) and in migrate-data.json (tenants)",
        )
        // In the default tenant, or in several, nothing is said; nor of a type whose tenant is fixed.
        warnings("(0)").assert().isEmpty()
        warnings("acme", "globex").assert().isEmpty()
    }

    @Test
    fun `a fixed tenant of no type of the Manifests, or a blank one, fails every command as the host refuses it`() {
        assertThrows<MigrationException> {
            MigrationPlan(NineXExample.map, NineXExample.manifest, mapOf("example.carts" to "acme"))
        }.message.assert().isEqualTo(
            "migrate-data.json: tenants.example.carts names no aggregate type the Manifests declare. Name one of the " +
                "aggregate types the Manifests declare: ${NineXExample.manifest.typeNames()}.",
        )
        assertThrows<MigrationException> {
            MigrationPlan(NineXExample.map, NineXExample.manifest, mapOf("example.cart" to " "))
        }.message.assert().isEqualTo("migrate-data.json: tenants.example.cart is blank. Name the tenant.")
        MigrationPlan(NineXExample.map, NineXExample.manifest, mapOf("example.cart" to "acme"))
            .fixedTenants.fixedTenant(AggregateType("example", "cart")).assert().isEqualTo("acme")
    }

    private fun Manifest.typeNames(): Set<String> =
        contexts.flatMap { context -> context.aggregates.map { "${context.name}.${it.name}" } }.toSet()
}
