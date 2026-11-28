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
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The markers of `requires` on the Manifest, a context and an aggregate (D484 ⑦ F13): a reader refuses the whole
 * Manifest for one it does not understand, wherever it stands; the writer and every merge keep each where it stands,
 * sorted and distinct.
 */
class ManifestRequiresTest {
    @Test
    fun `a marker this reader does not understand should refuse the Manifest wherever it stands, naming where`() {
        // The table every Manifest reader holds to: wow-client's checkManifest, wow-generator's validateManifest too.
        val table = jacksonObjectMapper().readValue(
            requireNotNull(javaClass.getResource("/manifest-requires-cases.json")).readText(),
            Map::class.java,
        )
        val cases = table["cases"] as List<*>
        cases.size.assert().isEqualTo(7)
        cases.map { it as Map<*, *> }.forEach { case ->
            val manifest = case["manifest"] as Map<*, *>
            assertThrows<IllegalArgumentException> { ManifestTree.read(manifest, ManifestTree.REFUSE) }
                .message.assert().describedAs(case["name"].toString())
                .contains("${case["refused"]}, which this reader does not understand")
        }
    }

    @Test
    fun `a refused marker should name what to upgrade, and the known markers be none yet`() {
        assertThrows<IllegalArgumentException> { ManifestFeatures.check("the aggregate shop.cart", listOf("x")) }
            .message.assert().isEqualTo(
                "The aggregate shop.cart requires x, which this reader does not understand: read the Manifest with a " +
                    "Wow whose wow-manifest does (upgrade the node, the Gradle plugin or the tool that reads it).",
            )
        ManifestFeatures.check("the context shop", emptyList())
        ManifestFeatures.KNOWN.assert().isEmpty()
    }

    @Test
    fun `each level's markers should be written where they stand, sorted and distinct, and left out when none`() {
        val fragment = Manifests.fragment()
        val shop = fragment.contexts.single()
        val marked = fragment.copyWith(
            requires = listOf("m", "a", "m"),
            contexts = listOf(
                shop.copyWith(
                    requires = listOf("c", "b"),
                    aggregates = shop.aggregates.map { it.copyWith(requires = listOf("y", "x", "x")) },
                ),
            ),
        )
        val tree = ManifestTree.write(marked)
        tree["requires"].assert().isEqualTo(listOf("a", "m"))
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        context.keys.take(2).assert().containsExactly("name", "requires")
        context["requires"].assert().isEqualTo(listOf("b", "c"))
        val aggregate = (context["aggregates"] as List<*>).first() as Map<*, *>
        aggregate.keys.take(2).assert().containsExactly("name", "requires")
        aggregate["requires"].assert().isEqualTo(listOf("x", "y"))

        val plain = ManifestTree.write(fragment)
        val plainContext = (plain["contexts"] as List<*>).single() as Map<*, *>
        (plain.keys + plainContext.keys + ((plainContext["aggregates"] as List<*>).first() as Map<*, *>).keys)
            .assert().doesNotContain("requires")
    }

    @Test
    fun `a merge should unite the markers of what it makes one, and keep each where it stands`() {
        val shop = Manifests.fragment().contexts.single()
        val domain = Manifests.fragment().copyWith(
            requires = listOf("d"),
            contexts = listOf(
                shop.copyWith(
                    requires = listOf("c"),
                    aggregates = shop.aggregates.map { it.copyWith(requires = listOf("a")) },
                ),
            ),
        )
        // A server module's routes added to the context: what a reader must understand of them stays the context's.
        val server = Manifest(
            requires = listOf("s", "d"),
            contexts = listOf(
                ContextManifest(
                    "shop",
                    routes = listOf(ServiceRouteManifest("summary", RouteManifest("GET", "/shop/summary"))),
                    requires = listOf("r", "c"),
                ),
            ),
        )
        val fragments = listOf(ManifestFragment("domain.json", domain), ManifestFragment("server.json", server))
        val merged = ManifestMerger.merge(fragments)
        merged.requires.assert().containsExactly("d", "s")
        val context = merged.contexts.single()
        context.requires.assert().containsExactly("c", "r")
        context.aggregates.map { it.requires }.distinct().assert().containsExactly(listOf("a"))
        ManifestFeatures.union(listOf(listOf("b", "a"), emptyList(), listOf("a", "c"))).assert()
            .containsExactly("a", "b", "c")
    }

    @Test
    fun `a context's and an aggregate's markers should be part of their values`() {
        ContextManifest("shop", requires = listOf("x")).assert().isNotEqualTo(ContextManifest("shop"))
        ContextManifest("shop", requires = listOf("x")).hashCode().assert()
            .isEqualTo(ContextManifest("shop", requires = listOf("x")).hashCode())
        ContextManifest("shop", requires = listOf("x")).toString().assert().contains("requires=[x]")
        val cart = Manifests.cart()
        cart.copyWith(requires = listOf("x")).assert().isNotEqualTo(cart)
        cart.copyWith(requires = listOf("x")).toString().assert().contains("requires=[x]")
        cart.copyWith(requires = listOf("x")).copyWith(name = "basket").requires.assert().containsExactly("x")
    }
}
