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

class QueryRoutesTest {
    @Test
    fun `a query model should be queried and described on one path, which no command route takes`() {
        QueryRoutes.of("shop", "cart", QueryRoutes.EVENTS).assert().containsExactly(
            RouteManifest("POST", "/shop/cart/events/query"),
            RouteManifest("GET", "/shop/cart/events/query"),
        )
        CommandRoutes.isReserved(QueryRoutes.SEGMENT).assert().isTrue()
    }

    @Test
    fun `an aggregate should have the snapshot model only when it keeps snapshots`() {
        QueryRoutes.queries(
            "shop",
            "cart",
            snapshots = true
        ).map { it.model }.assert().containsExactly("snapshot", "events")
        QueryRoutes.queries("shop", "cart", snapshots = false).map { it.model }.assert().containsExactly("events")
    }

    @Test
    fun `a published stream should default to the topic of its aggregate type`() {
        PublishedManifest.defaultTopic("shop", "cart").assert().isEqualTo("wow.shop.cart.state")
    }

    @Test
    fun `a projection should start from the beginning, a reactor and a saga from the end, or join a peer's stream`() {
        SubscriptionStart.defaultFor(SubscriptionKind.PROJECTION).assert().isEqualTo(SubscriptionStart.BEGINNING)
        SubscriptionStart.defaultFor(SubscriptionKind.PROJECTION, peer = true)
            .assert().isEqualTo(SubscriptionStart.BEGINNING)
        SubscriptionStart.defaultFor(SubscriptionKind.REACTOR).assert().isEqualTo(SubscriptionStart.END)
        SubscriptionStart.defaultFor(SubscriptionKind.SAGA).assert().isEqualTo(SubscriptionStart.END)
        SubscriptionStart.defaultFor(SubscriptionKind.REACTOR, peer = true).assert().isEqualTo(SubscriptionStart.JOIN)
        SubscriptionStart.defaultFor(SubscriptionKind.SAGA, peer = true).assert().isEqualTo(SubscriptionStart.JOIN)
    }
}
