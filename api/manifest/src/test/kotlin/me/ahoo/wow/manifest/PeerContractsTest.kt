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

class PeerContractsTest {
    private val mounted = CommandManifest("Import", schema = Manifest.schemaRef("sample.Import"), mounted = true)

    /** The sample fragment with a mounted command, a scheduled saga and schemas referenced or not. */
    private fun fragment(context: String = "shop"): Manifest {
        val cart = Manifests.cart(context).let { it.withCommands(it.commands + mounted) }
        val fragment = Manifests.fragment(
            context,
            listOf(cart),
            defs = mapOf(
                "sample.CartState" to mapOf("type" to "object", "\$defs" to mapOf("x" to "#/\$defs/sample.Item")),
                "sample.Item" to mapOf("type" to "object"),
                "sample.CreateCart" to mapOf("type" to "object"),
                "sample.Import" to mapOf("type" to "object"),
                "sample.Unreferenced" to mapOf("type" to "object"),
            ),
        )
        val scheduled = SubscriptionManifest(
            name = "Expiry.onSchedule",
            kind = SubscriptionKind.SAGA,
            source = SubscriptionSource(),
            function = FunctionManifest(FunctionKind.SAGA, context, "Expiry", "onSchedule"),
            schedules = listOf("$context.cart.PutItem"),
            retry = RetryManifest(maxAttempts = 3),
            job = JobManifest("expiry", "0 0 * * * *"),
            start = SubscriptionStart.END,
        )
        val shop = fragment.contexts.single()
        return fragment.withContexts(
            listOf(
                ContextManifest(
                    shop.name,
                    shop.description,
                    shop.aggregates,
                    shop.subscriptions + scheduled,
                    shop.published,
                    shop.errors,
                ),
            ),
        )
    }

    @Test
    fun `the contract should keep what a peer reads and leave out the rest`() {
        val contract = PeerContracts.contractOf(listOf(fragment()))
        contract.service.assert().isNull()
        val context = contract.contexts.single()
        context.name.assert().isEqualTo("shop")
        context.published.map { it.aggregate }.assert().containsExactly("cart")
        context.errors.map { it.code }.assert().containsExactly("cart.empty")
        val cart = context.aggregates.single()
        cart.commands.map { it.name }.assert().containsExactly("CreateCart", "PutItem")
        cart.commands.flatMap { it.routes }.assert().isEmpty()
        cart.commands.first().produces.assert().containsExactly("CartCreated")
        cart.commands[1].aliases.assert().containsExactly("AddItem")
        cart.events.map { it.revision }.assert().containsExactly(1, 2)
        // The query models a peer reads, without their routes; and who the aggregate belongs to.
        cart.queries.map { it.model }.assert().containsExactly(QueryRoutes.SNAPSHOT, QueryRoutes.EVENTS)
        cart.queries.flatMap { it.routes }.assert().isEmpty()
        cart.ownership.assert().isEqualTo(Ownership.OWNER)
        cart.lifecycle.assert().isEmpty()
        cart.state.fingerprint.assert().isEqualTo("abc123")
        val saga = context.subscriptions.first { it.name == "CheckoutSaga.onRefundRejected" }
        saga.rejected.assert().containsExactly(Manifest.schemaRef("sales.Refund"))
        context.subscriptions.first { it.name == "CheckoutSaga.onOrderPaid" }.source.nullableState.assert().isTrue()
        val scheduled = context.subscriptions.first { it.name == "Expiry.onSchedule" }
        scheduled.schedules.assert().isNull()
        scheduled.retry.assert().isNull()
        scheduled.job.assert().isNull()
        scheduled.start.assert().isNull()
        // The referenced schemas and those they refer to; neither the mounted command's nor an unreferenced one.
        contract.defs.keys.assert().containsExactly("sample.CartState", "sample.CreateCart", "sample.Item")
    }

    @Test
    fun `the contract should withhold the state of an aggregate published without it`() {
        val shop = fragment().contexts.single()
        val cart = shop.aggregates.single().copyWith(ownership = Ownership.TENANT)
        val stateless = fragment().withContexts(
            listOf(
                shop.copyWith(
                    aggregates = listOf(cart),
                    published = shop.published.map { it.copyWith(includeState = false) }
                )
            ),
        )
        val contract = PeerContracts.contractOf(listOf(stateless))
        val aggregate = contract.contexts.single().aggregates.single()
        aggregate.ownership.assert().isEqualTo(Ownership.TENANT)
        aggregate.state.schema.assert().isEqualTo(Manifest.schemaRef("sample.CartState"))
        // An object of undisclosed structure, and none of the definitions only the state refers to.
        contract.defs["sample.CartState"].assert().isEqualTo(mapOf("type" to "object"))
        contract.defs.keys.assert().containsExactly("sample.CartState", "sample.CreateCart")
    }

    @Test
    fun `a withheld state that a command also refers to should keep its schema`() {
        val shop = fragment().contexts.single()
        val cart = shop.aggregates.single().let { it.withState(StateManifest(it.commands.first().schema)) }
        val stateless = fragment().withContexts(
            listOf(
                shop.copyWith(
                    aggregates = listOf(cart),
                    published = shop.published.map { it.copyWith(includeState = false) }
                )
            ),
        )
        PeerContracts.contractOf(listOf(stateless)).defs.keys.assert().containsExactly("sample.CreateCart")
    }

    @Test
    fun `the contract of several fragments should hold their contexts by name`() {
        val contract = PeerContracts.contractOf(listOf(fragment("sales"), fragment("billing")))
        contract.contexts.map { it.name }.assert().containsExactly("billing", "sales")
        PeerContracts.contractOf(emptyList()).contexts.assert().isEmpty()
    }

    @Test
    fun `the classes of a context should be its commands' and events'`() {
        PeerContracts.classesOf(PeerContracts.contractOf(listOf(fragment())).contexts.single()).assert()
            .containsExactly("sample.CreateCart", "sample.AddItem", "sample.CartCreated", "sample.ItemAdded")
    }

    private fun contract(source: String, vararg contexts: String) = ManifestFragment(
        source,
        PeerContracts.contractOf(contexts.map { fragment(it) }),
    )

    @Test
    fun `an api module should carry the contexts whose classes are all its own`() {
        val owned = setOf("sample.CreateCart", "sample.AddItem", "sample.CartCreated", "sample.ItemAdded")
        val assembled = PeerContracts.assemble(":shop-api", listOf(contract("shop", "shop")), owned::contains)!!
        assembled.contexts.map { it.name }.assert().containsExactly("shop")
        assembled.defs.keys.assert().containsExactly("sample.CartState", "sample.CreateCart", "sample.Item")
        assembled.errors.assert().isEqualTo(Manifests.fragment().errors)
        // A context with none of its classes is another api module's.
        PeerContracts.assemble(":other-api", listOf(contract("shop", "shop"))) { false }.assert().isNull()
        // A context without commands and events is any api module's.
        val empty = ManifestFragment("empty", Manifest(contexts = listOf(ContextManifest("audit"))))
        PeerContracts.assemble(":other-api", listOf(empty)) { false }!!.contexts.map { it.name }
            .assert().containsExactly("audit")
    }

    @Test
    fun `a context with classes outside the api module, or declared twice, should fail`() {
        val owned = setOf("sample.CreateCart", "sample.AddItem", "sample.CartCreated")
        val failure = assertThrows<IllegalStateException> {
            PeerContracts.assemble(
                ":shop-api",
                listOf(contract("a", "shop"), contract("b", "sales")),
                owned::contains,
            )
        }
        failure.message.assert().contains(
            "The contract of :shop-api cannot be assembled:",
            "- context shop of a has classes that are not in :shop-api: sample.ItemAdded",
            "- context sales of b has classes that are not in :shop-api: sample.ItemAdded",
        )
        assertThrows<IllegalStateException> {
            PeerContracts.assemble(":shop-api", listOf(contract("a", "shop"), contract("b", "shop"))) { true }
        }.message.assert().contains("- context shop of :shop-api is declared by a and b")
    }

    @Test
    fun `two contracts with different schemas of one class should fail`() {
        val other = contract("b", "sales").manifest.let {
            it.withDefs(it.defs + ("sample.CartState" to mapOf("type" to "string")))
        }
        assertThrows<IllegalStateException> {
            PeerContracts.assemble(":api", listOf(contract("a", "shop"), ManifestFragment("b", other))) { true }
        }.message.assert().contains("- schema sample.CartState of b differs from that of another contract of :api")
    }

    @Test
    fun `a contract should name the service that hosts its contexts, one per api module`() {
        PeerContracts.contractOf(listOf(fragment()), "shop-service").service.assert()
            .isEqualTo(ServiceInfo("shop-service"))
        PeerContracts.contractOf(listOf(fragment()), " ").service.assert().isNull()
        fun named(source: String, context: String, service: String?) = contract(source, context).let {
            ManifestFragment(it.source, PeerContracts.contractOf(listOf(fragment(context)), service))
        }
        PeerContracts.assemble(":api", listOf(named("a", "shop", "shop-service"), named("b", "sales", null))) { true }!!
            .service.assert().isEqualTo(ServiceInfo("shop-service"))
        assertThrows<IllegalStateException> {
            PeerContracts.assemble(
                ":api",
                listOf(named("a", "shop", "shop-service"), named("b", "sales", "sales-service"))
            ) {
                true
            }
        }.message.assert().contains(
            "the contracts of :api name different services: shop-service (a), sales-service (b)"
        )
    }

    private fun peer(source: String, vararg contexts: String): ManifestFragment =
        ManifestFragment(source, Manifest(contexts = contexts.map { ContextManifest(it) }))

    @Test
    fun `the contracts outside the served contexts should leave out those contexts and the contracts left with none`() {
        val own = peer("shop-api", "shop")
        val outside = PeerContracts.outside(
            listOf("shop"),
            listOf(
                peer("inventory-api", "inventory"),
                own,
                peer("again", "shop"),
                peer("monolith-api", "shop", "audit")
            ),
        )
        outside.map { it.source }.assert().containsExactly("inventory-api", "monolith-api")
        outside.last().manifest.contexts.map { it.name }.assert().containsExactly("audit")
        PeerContracts.conflicts(outside).assert().isEmpty()
    }

    @Test
    fun `two contracts declaring one context should conflict`() {
        PeerContracts.conflicts(listOf(peer("inventory-api", "stock"), peer("warehouse-api", "stock", "bin")))
            .assert().containsExactly("context stock is declared by the contracts inventory-api and warehouse-api")
        PeerContracts.RESOURCE.assert().isEqualTo("META-INF/wow/contract.json")
    }
}
