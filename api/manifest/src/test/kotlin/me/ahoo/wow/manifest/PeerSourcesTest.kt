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
                    aggregate("order", "ordering.OrderPaid", "ordering.Shared"),
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
    fun `an event two aggregates list, one no contract lists, or events of two contexts are problems`() {
        assertThrows<ContractException> { PeerSources.resolve(service("ordering.Shared"), listOf(ordering)) }
            .problems.single().assert()
            .contains("payment/Saga.onEvent takes ordering.Shared, an event of ordering.invoice, ordering.order")
            .contains("QUALIFIED_NAME")
        assertThrows<ContractException> { PeerSources.resolve(service("billing.Charged"), listOf(ordering)) }
            .problems.single().assert().contains("takes billing.Charged, which no peer contract on the class path lists")
        assertThrows<ContractException> {
            PeerSources.resolve(service("ordering.OrderPaid", "inventory.Reserved"), listOf(ordering, inventory))
        }.problems.single().assert().contains("takes events of the contexts inventory, ordering")
    }

    @Test
    fun `a fragment's event schemas read back from the tree, and a Manifest without them leaves them out`() {
        val manifest = service("ordering.OrderPaid")
        ManifestTree.read(ManifestTree.write(manifest)).assert().isEqualTo(manifest)
        @Suppress("UNCHECKED_CAST")
        val source = ((ManifestTree.write(PeerSources.resolve(manifest, listOf(ordering)))["contexts"] as List<Map<String, Any?>>)
            .single()["subscriptions"] as List<Map<String, Any?>>).single()["source"] as Map<String, Any?>
        source.containsKey("eventSchemas").assert().isFalse()
    }
}
