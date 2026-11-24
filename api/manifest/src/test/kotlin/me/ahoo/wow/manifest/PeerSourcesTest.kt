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
import me.ahoo.wow.api.result.FunctionKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A subscription of another context's events by type reads the aggregates the peer contracts list them under. */
class PeerSourcesTest {
    private fun aggregate(name: String, vararg events: String) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef("sample.${name}State")),
        events = events.map { EventManifest(name = it.substringAfterLast('.'), schema = Manifest.schemaRef(it)) },
    )

    private val ordering = Manifest(
        contexts = listOf(
            ContextManifest(
                "ordering",
                aggregates = listOf(
                    aggregate("order", "ordering.OrderPaid", "ordering.OrderShipped", "ordering.Shared"),
                    aggregate("invoice", "ordering.InvoiceSent", "ordering.Shared"),
                ),
            ),
        ),
    )
    private val inventory = Manifest(
        contexts = listOf(ContextManifest("inventory", aggregates = listOf(aggregate("stock", "inventory.Reserved")))),
    )

    private fun service(vararg schemas: String, nullable: Boolean = true) = Manifest(
        contexts = listOf(
            ContextManifest(
                "payment",
                subscriptions = listOf(
                    SubscriptionManifest(
                        name = "Saga.onEvent",
                        kind = SubscriptionKind.SAGA,
                        source = SubscriptionSource(
                            events = schemas.map { it.substringAfterLast('.') },
                            nullableState = nullable,
                            eventSchemas = schemas.map(Manifest::schemaRef),
                        ),
                        function = FunctionManifest(FunctionKind.SAGA, "payment", "Saga", "onEvent"),
                        start = SubscriptionStart.BEGINNING,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a subscription reads the one aggregate whose events it takes`() {
        val resolved = PeerSources.resolve(service("ordering.OrderPaid"), listOf(ordering, inventory))
        val subscription = resolved.contexts.single().subscriptions.single()
        subscription.source.assert().isEqualTo(
            SubscriptionSource("ordering", listOf("order"), events = listOf("OrderPaid"), nullableState = true),
        )
        subscription.start.assert().isEqualTo(SubscriptionStart.BEGINNING)
        // A Manifest that takes no peer's events by type is itself.
        val plain = Manifest(contexts = listOf(ContextManifest("payment")))
        PeerSources.resolve(plain, listOf(ordering)).assert().isSameAs(plain)
    }

    @Test
    fun `a subscription of several events of one aggregate should read it once, and of two aggregates both`() {
        // The subtypes of a sealed event type, both events of the order (#6840): never left unresolved, which the node
        // would read as a subscription of its own context.
        PeerSources.resolve(service("ordering.OrderPaid", "ordering.OrderShipped"), listOf(ordering))
            .contexts.single().subscriptions.single().source.assert().isEqualTo(
                SubscriptionSource(
                    "ordering",
                    listOf("order"),
                    events = listOf("OrderPaid", "OrderShipped"),
                    nullableState = true,
                ),
            )
        PeerSources.resolve(service("ordering.OrderPaid", "ordering.InvoiceSent"), listOf(ordering))
            .contexts.single().subscriptions.single().source.aggregates.assert().containsExactly("invoice", "order")
    }

    @Test
    fun `an event two aggregates list, one no contract lists, or events of two contexts are problems`() {
        assertThrows<ContractException> { PeerSources.resolve(service("ordering.Shared"), listOf(ordering)) }
            .problems.single().assert()
            .contains("payment/Saga.onEvent takes ordering.Shared, an event of ordering.invoice, ordering.order")
            .contains("QUALIFIED_NAME")
        assertThrows<ContractException> { PeerSources.resolve(service("billing.Charged"), listOf(ordering)) }
            .problems.single().assert().contains(
                "takes billing.Charged, which no peer contract on the class path lists"
            )
        assertThrows<ContractException> {
            PeerSources.resolve(service("ordering.OrderPaid", "inventory.Reserved"), listOf(ordering, inventory))
        }.problems.single().assert().contains("takes events of the contexts inventory, ordering")
    }

    @Test
    fun `an event no contract lists should be named with the entry its class is in, a types-only release`() {
        val located = mapOf("billing.Charged" to "sample:billing-api:1.0.0-types")
        assertThrows<ContractException> {
            PeerSources.resolve(service("billing.Charged"), listOf(ordering), located::get)
        }.problems.single().assert().contains(
            "takes billing.Charged, which no peer contract on the class path lists as an event: it is in " +
                "sample:billing-api:1.0.0-types, whose api module carries no contract listing it",
            "a types-only release (-Pwow.contract.publish=false)",
            "Depend on the module's full release",
        )
        // A class the class path does not hold names the api module to depend on.
        assertThrows<ContractException> {
            PeerSources.resolve(service("billing.Charged"), listOf(ordering)) { null }
        }.problems.single().assert().endsWith("depend on the api module of the context that publishes it")
    }

    @Test
    fun `a fragment's event schemas read back from the tree, and a Manifest without them leaves them out`() {
        val manifest = service("ordering.OrderPaid")
        ManifestTree.read(ManifestTree.write(manifest), ManifestTree.REFUSE).assert().isEqualTo(manifest)
        @Suppress("UNCHECKED_CAST")
        val source = (
            (ManifestTree.write(PeerSources.resolve(manifest, listOf(ordering)))["contexts"] as List<Map<String, Any?>>)
                .single()["subscriptions"] as List<Map<String, Any?>>
            ).single()["source"] as Map<String, Any?>
        source.containsKey("eventSchemas").assert().isFalse()
    }
}
