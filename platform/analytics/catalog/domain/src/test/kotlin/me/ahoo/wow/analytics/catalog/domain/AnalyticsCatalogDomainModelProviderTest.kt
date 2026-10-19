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

package me.ahoo.wow.analytics.catalog.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.domain.source.SourceRejections
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.analytics.catalog.domain.warehouse.TrustedWriters
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.ManifestTree
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** What the compiler generated for the analytics catalog: the source aggregate and the Manifest fragment. */
class AnalyticsCatalogDomainModelProviderTest {
    private val provider = ModelProvider.load().single()
    private val dependencies = Dependencies.of(
        WarehouseCatalog.NONE,
        TrustedWriters.NONE,
        KnownAggregates { _, _ -> false }
    )

    @Test
    fun `the binding index should build the source model`() {
        provider.context.assert().isEqualTo(AnalyticsCatalogContext.NAME)
        provider.models(dependencies).map { it.type.toString() }.assert().containsExactly("analytics-catalog.source")
    }

    @Test
    fun `the fragment should declare a tenant's source without lifecycle, and the catalog's errors`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val context = ManifestTree.read(tree).contexts.single()
        val source = context.aggregates.single()
        source.name.assert().isEqualTo(AnalyticsCatalogContext.SOURCE)
        source.ownership.name.assert().isEqualTo("TENANT")
        source.lifecycle.assert().isEmpty()
        context.errors.associate { it.code to it.status }.assert()
            .containsEntry(SourceRejections.INVALID, 400)
            .containsEntry(SourceRejections.TABLE_NOT_FOUND, 404)
            .containsEntry(SourceRejections.WOW_SOURCE, 409)
            .containsEntry(SourceRejections.INCOMPLETE, 409)
            .containsEntry(SourceRejections.LOOSENING, 409)
            .containsEntry(SourceRejections.UNKNOWN_AGGREGATE, 400)
            .containsEntry(SourceRejections.TABLE_MISSING, 409)
    }
}
