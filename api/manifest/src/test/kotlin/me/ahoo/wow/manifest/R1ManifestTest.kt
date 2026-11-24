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

/**
 * Deep review r1 (compiler, the Manifest): how a service's Manifest is resolved and merged. Each test states the
 * expected behaviour and fails on `ac58da98ac`.
 */
class R1ManifestTest {
    private fun aggregate(name: String, vararg events: String) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef("sample.${name}State")),
        events = events.map { EventManifest(name = it.substringAfterLast('.'), schema = Manifest.schemaRef(it)) },
    )

    /**
     * A handler of a sealed peer event type is written with one schema per concrete event
     * (`TypedEventResolver.kt:89`). `PeerSources.resolve` takes the owners of those schemas `distinct()` and gives up
     * when their count is not the schemas' (`PeerSources.kt:86`): two events of one aggregate leave the source
     * unresolved, with no problem, and at run time the handler reads its own context's log, never the peer's.
     */
    @Test
    fun `a subscription of two events of one peer aggregate should read that aggregate`() {
        val ordering = Manifest(
            contexts = listOf(
                ContextManifest(
                    "ordering",
                    aggregates = listOf(aggregate("order", "ordering.OrderPaid", "ordering.OrderShipped")),
                ),
            ),
        )
        val service = Manifest(
            contexts = listOf(
                ContextManifest(
                    "payment",
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = "Saga.onOrderEvent",
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(
                                events = listOf("OrderPaid", "OrderShipped"),
                                nullableState = true,
                                eventSchemas = listOf(
                                    Manifest.schemaRef("ordering.OrderPaid"),
                                    Manifest.schemaRef("ordering.OrderShipped"),
                                ),
                            ),
                            function = FunctionManifest(FunctionKind.SAGA, "payment", "Saga", "onOrderEvent"),
                            start = SubscriptionStart.BEGINNING,
                        ),
                    ),
                ),
            ),
        )
        val source = PeerSources.resolve(service, listOf(ordering)).contexts.single().subscriptions.single().source
        source.context.assert().isEqualTo("ordering")
        source.aggregates.assert().containsExactly("order")
    }

    /**
     * `ManifestMerger` promises "the same fragments always give the same Manifest", but the routes and errors that
     * server fragments add to a context are concatenated in fragment order (`ManifestMerger.kt:123`): the class
     * path's order in the build, the `ServiceLoader`'s in the node.
     */
    @Test
    fun `two server fragments in either order should merge into one Manifest`() {
        val domain = ManifestFragment("shop-domain.json", Manifests.fragment())
        fun server(file: String, route: String, code: String) = ManifestFragment(
            file,
            Manifest(
                contexts = listOf(
                    ContextManifest(
                        name = "shop",
                        errors = listOf(ErrorManifest(code, 409)),
                        routes = listOf(
                            ServiceRouteManifest(route, RouteManifest("GET", "/shop/$route"), errors = listOf(code)),
                        ),
                    ),
                ),
            ),
        )
        val reports = server("shop-reports.json", "reports", "shop.reports.closed")
        val summaries = server("shop-summaries.json", "summaries", "shop.summaries.closed")
        ManifestMerger.merge(listOf(domain, reports, summaries)).assert()
            .isEqualTo(ManifestMerger.merge(listOf(domain, summaries, reports)))
    }
}
