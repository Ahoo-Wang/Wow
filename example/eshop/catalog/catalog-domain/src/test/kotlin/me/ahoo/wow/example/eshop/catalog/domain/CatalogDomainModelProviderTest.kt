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

package me.ahoo.wow.example.eshop.catalog.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** What the compiler generated for the module: its binding index, found through `ServiceLoader`, and its fragment. */
class CatalogDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    private val fragment: JsonNode by lazy {
        val resource = requireNotNull(javaClass.classLoader.getResource(provider.fragment)) { provider.fragment }
        JsonMapper.builder().build().readTree(resource.readText())
    }

    @Test
    fun `the binding index should name the context and the fragment and build the three models`() {
        provider.context.assert().isEqualTo("eshop-catalog")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-catalog-domain.json")
        provider.models(Dependencies.of()).map { it.type.toString() }.assert()
            .containsExactlyInAnyOrder("eshop-catalog.product", "eshop-catalog.brand", "eshop-catalog.category")
    }

    @Test
    fun `the fragment should list the context's error codes with their status`() {
        val errors = fragment.findValues("errors").first().values().associate { it["code"].asString() to it["status"].asInt() }
        errors.assert().containsEntry("eshop-catalog.product.invalid-price", 422)
            .containsEntry("eshop-catalog.product.incomplete", 409)
            .containsEntry("eshop-catalog.product.not-published", 409)
            .containsEntry("eshop-catalog.brand.name-taken", 409)
            .containsEntry("eshop-catalog.category.name-taken", 409)
    }

    @Test
    fun `the product state should declare the storefront's indexes`() {
        val indexes = fragment.findValues("x-wow-index").flatMap { it.values() }
            .map { index -> index["keys"].values().map { it.asString() } }
        indexes.assert().contains(
            listOf("state.categoryId", "state.status", "state.price"),
            listOf("state.brandId", "state.status", "state.price"),
            listOf("state.status", "state.price"),
            listOf("state.price"),
        )
    }
}
