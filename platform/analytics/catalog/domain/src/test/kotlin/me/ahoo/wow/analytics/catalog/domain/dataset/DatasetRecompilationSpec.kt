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

package me.ahoo.wow.analytics.catalog.domain.dataset

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetNeedsRevision
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRetired
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.RecompileDataset
import me.ahoo.wow.analytics.catalog.api.source.SourceTableMissing
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test

class DatasetRecompilationSpec {
    private val source = AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.SOURCE)
    private val dataset = AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.DATASET)

    private fun state(name: String, block: DatasetState.() -> Unit = {}) = DatasetState().apply {
        onSourcing(DatasetDrafted("shop", name, DEFINITION, listOf(REGIONS_ID)))
        block()
    }

    private fun published(name: String, block: DatasetState.() -> Unit = {}) = state(name) {
        val artifact = (
            DatasetCompiler.compile(
                DEFINITION,
                SOURCES,
                "shop",
                name,
                1
            ) as DatasetCompilation.Compiled
            ).artifact
        onSourcing(DatasetPublished(artifact, DEFINITION))
        block()
    }

    @Test
    fun `a source's change should recompile each published dataset whose published definition reads it`() {
        val datasets = listOf(
            published("orders"),
            published("broken") { onSourcing(DatasetNeedsRevision(listOf("gone"))) },
            state("draft"),
            published("retired") { onSourcing(DatasetRetired(1)) },
            // Revised to read no region since, but still published as it was: recompiled.
            published(
                "revised"
            ) { onSourcing(DatasetRevised(DEFINITION.copy(sources = DEFINITION.sources.take(2)), listOf())) },
        )
        val snapshots = datasets.map {
            Snapshot.of(
                dataset.ref(
                    AnalyticsCatalogContext.TENANT_ID,
                    "shop.${it.name}"
                ),
                1, Scope.NONE, emptyMap(), false, null, 0, 0, it
            )
        }
        val queries = mockk<QueryApi> {
            every {
                trusted().unbounded().snapshots(DatasetState::class.java).list(any())
            } returns flowOf(*snapshots.toTypedArray())
        }
        sagaSpec(datasetRecompilationHandlers(Dependencies.NONE)) {
            givenQueries(queries)
            whenEvent(
                stateEvent(
                    source.ref(AnalyticsCatalogContext.TENANT_ID, REGIONS_ID),
                    DatasetFixtures.REGIONS,
                    SourceTableMissing
                )
            ) {
                expectCommands(RecompileDataset, RecompileDataset, RecompileDataset)
                commands.map { it.ref.id }.assert().containsExactly("shop.orders", "shop.broken", "shop.revised")
            }
            whenEvent(
                stateEvent(
                    source.ref(AnalyticsCatalogContext.TENANT_ID, "other"),
                    DatasetFixtures.REGIONS,
                    SourceTableMissing
                )
            ) {
                expectNoCommand()
            }
        }
    }
}
