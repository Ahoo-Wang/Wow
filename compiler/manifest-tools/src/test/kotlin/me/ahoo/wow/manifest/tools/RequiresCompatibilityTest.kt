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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CART
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CONTEXT
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.cart
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.context
import org.junit.jupiter.api.Test

/**
 * The gate on the markers of `requires` (D484 ⑦): one added breaks wherever it stands, a new context's or
 * aggregate's included, since a reader that does not understand it refuses the whole Manifest; one removed is
 * additive.
 */
class RequiresCompatibilityTest {
    private fun changes(baseline: Manifest, current: Manifest): List<String> =
        ManifestCompatibility.compare(baseline, current).changes.map(ManifestChange::toString)

    private val added = "added, which a reader of the recorded Manifest refuses the whole Manifest for"

    @Test
    fun `a marker added should break at the level it stands on`() {
        changes(BASELINE, BASELINE.copyWith(requires = listOf("x"))).assert()
            .containsExactly("BREAKING requires: marker x $added")
        changes(BASELINE, BASELINE.context { it.copyWith(requires = listOf("x")) }).assert()
            .containsExactly("BREAKING $CONTEXT.requires: marker x $added")
        changes(BASELINE, BASELINE.cart { it.copyWith(requires = listOf("x", "y")) }).assert()
            .containsExactly("BREAKING $CART.requires: marker x $added", "BREAKING $CART.requires: marker y $added")
    }

    @Test
    fun `a marker on a new context or a new aggregate should break, the addition itself staying additive`() {
        val order = AggregateManifest(
            "order",
            state = StateManifest(Manifest.schemaRef("shop.CartState")),
            requires = listOf("x"),
        )
        changes(BASELINE, BASELINE.context { it.copyWith(aggregates = it.aggregates + order) }).assert()
            .containsExactly(
                "ADDITIVE $CONTEXT.aggregates[order]: aggregate added",
                "BREAKING $CONTEXT.aggregates[order].requires: marker x $added",
            )
        val store = ContextManifest("store", requires = listOf("c"), aggregates = listOf(order))
        changes(BASELINE, BASELINE.copyWith(contexts = BASELINE.contexts + store)).assert()
            .containsExactly(
                "ADDITIVE contexts[store]: context added",
                "BREAKING contexts[store].requires: marker c $added",
                "BREAKING contexts[store].aggregates[order].requires: marker x $added",
            )
        // Without markers, a new context or aggregate is only added.
        changes(BASELINE, BASELINE.copyWith(contexts = BASELINE.contexts + ContextManifest("store"))).assert()
            .containsExactly("ADDITIVE contexts[store]: context added")
    }

    @Test
    fun `a marker removed should be additive, and one kept no change`() {
        val requiring = BASELINE.copyWith(requires = listOf("x")).cart { it.copyWith(requires = listOf("y")) }
        changes(requiring, BASELINE).assert().containsExactly(
            "ADDITIVE requires: marker x removed",
            "ADDITIVE $CART.requires: marker y removed",
        )
        changes(requiring, requiring).assert().isEmpty()
    }
}
