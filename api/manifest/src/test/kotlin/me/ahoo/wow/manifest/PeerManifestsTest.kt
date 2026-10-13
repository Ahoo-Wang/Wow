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

class PeerManifestsTest {
    private fun peer(service: String?, vararg contexts: String): ManifestFragment = ManifestFragment(
        "${service ?: "fragment"}.json",
        Manifest(service = service?.let { ServiceInfo(it) }, contexts = contexts.map { ContextManifest(it) }),
    )

    @Test
    fun `the resource of a peer should be under the peer directory`() {
        PeerManifests.resource("inventory").assert().isEqualTo("META-INF/wow/peers/inventory.json")
        PeerManifests.INDEX.assert().isEqualTo("META-INF/wow/peers/index")
    }

    @Test
    fun `peers of other contexts should not conflict`() {
        PeerManifests.conflicts(
            "shop",
            listOf("shop"),
            listOf(peer("inventory", "inventory"), peer("billing", "billing"))
        )
            .assert().isEmpty()
    }

    @Test
    fun `two peers declaring one context should conflict`() {
        PeerManifests.conflicts("shop", listOf("shop"), listOf(peer("inventory", "stock"), peer("warehouse", "stock")))
            .assert().containsExactly(
                "context stock is declared by the peers inventory (inventory.json) and warehouse (warehouse.json)",
            )
    }

    @Test
    fun `a peer declaring a context of the service should conflict`() {
        PeerManifests.conflicts("shop", listOf("shop"), listOf(peer("inventory", "inventory", "shop")))
            .assert().containsExactly(
                "context shop is declared by service shop and by its peer inventory (inventory.json)"
            )
    }

    @Test
    fun `the peers outside the served contexts should leave out those contexts and the peers left with none`() {
        val own = peer("shop", "shop")
        val outside = PeerManifests.outside(
            listOf("shop"),
            listOf(
                peer("inventory", "inventory"),
                own,
                own.copy(source = "again.json"),
                peer("monolith", "shop", "audit")
            ),
        )
        outside.map { it.manifest.service?.name }.assert().containsExactly("inventory", "monolith")
        outside.last().manifest.contexts.map { it.name }.assert().containsExactly("audit")
        outside.last().source.assert().isEqualTo("monolith.json")
        PeerManifests.conflicts(null, emptyList(), outside).assert().isEmpty()
    }

    @Test
    fun `the peers outside the served contexts should keep their conflicts among themselves`() {
        val outside = PeerManifests.outside(
            listOf("shop"),
            listOf(peer("inventory", "stock", "shop"), peer("warehouse", "stock"))
        )
        PeerManifests.conflicts(null, emptyList(), outside).assert().containsExactly(
            "context stock is declared by the peers inventory (inventory.json) and warehouse (warehouse.json)",
        )
    }

    @Test
    fun `a fragment, a repeated service or the service itself should not be a peer`() {
        PeerManifests.conflicts(
            "shop",
            emptyList(),
            listOf(peer(null, "a"), peer("b", "b"), peer("b", "c"), peer("shop", "d"))
        )
            .assert().containsExactly(
                "peer fragment.json is not a service's merged Manifest: it names no service",
                "service b is the peer of b.json and b.json",
                "peer shop.json is of service shop itself",
            )
    }
}
