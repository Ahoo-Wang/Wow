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

/** A published stream's declaration that it reveals its `DISPLAY` values, in the Manifest's tree (#6586). */
class PublishedRevealsTest {
    @Test
    fun `a stream should mask its DISPLAY values unless declared revealing`() {
        PublishedManifest("cart", "t").revealsDisplay.assert().isFalse()
        PublishedManifest("cart", "t", revealsDisplay = true).copyWith(topic = "u").revealsDisplay.assert().isTrue()
    }

    @Test
    fun `a stream declared revealing should say so, and one that masks should leave the key out`() {
        val revealing = Manifests.fragment().let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(published = context.published.map { it.copyWith(revealsDisplay = true) })
                },
            )
        }
        val tree = ManifestTree.write(revealing)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        context["published"].assert().isEqualTo(
            listOf(
                mapOf(
                    "aggregate" to "cart",
                    "topic" to "wow.shop.cart.state",
                    "includeState" to true,
                    "revealsDisplay" to true,
                ),
            ),
        )
        fun published(tree: Map<*, *>) = ManifestTree.read(tree, ManifestTree.REFUSE).contexts.single().published
        published(tree).single().revealsDisplay.assert().isTrue()
        published(ManifestTree.write(Manifests.fragment())).single().revealsDisplay.assert().isFalse()
        revealing.contexts.single().published.single().toString().assert().contains("revealsDisplay=true")
    }
}
