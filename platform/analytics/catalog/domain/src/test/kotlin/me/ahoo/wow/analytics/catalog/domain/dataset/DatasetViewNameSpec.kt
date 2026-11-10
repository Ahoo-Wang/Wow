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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetNeedsRevision
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRolledBack
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetViewsDropped
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.DropDatasetViews
import me.ahoo.wow.analytics.catalog.api.dataset.PublishDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RecompileDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RetireDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RollbackDataset
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.MEMBERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/**
 * Where each version of a dataset is published (D403): a view of its own per exact id and version, and a version
 * published under the former name keeps the name it recorded.
 */
class DatasetViewNameSpec {
    private val catalog = SOURCES.toMutableMap<String, SourceState?>()
    private val warehouse = MemoryDatasetWarehouse()

    private fun spec(
        id: String = AnalyticsCatalogContext.datasetIdOf("shop", "orders"),
        block: AggregateSpec<DatasetState>.() -> Unit,
    ) = aggregateSpec(
        datasetModel(
            datasetSources = DatasetSources { catalog[it] },
            knownAggregates = KnownAggregates { _, _ -> false },
            datasetWarehouse = warehouse,
            datasetPolicy = DatasetPolicy(database = "bi"),
        ),
        id = id,
        tenantId = AnalyticsCatalogContext.TENANT_ID,
        block = block,
    )

    @Test
    fun `datasets whose ids read the same once made safe should publish views of their own`() {
        listOf("a-b", "a_b", "A_b").forEach { name ->
            spec(AnalyticsCatalogContext.datasetIdOf("shop", name)) {
                givenCommands(DraftDataset("shop", name, DEFINITION))
                whenCommand(PublishDataset) { expectEventTypes(DatasetPublished::class) }
            }
        }
        warehouse.created.assert().hasSize(3)
        warehouse.created.values.map { it.name }.assert().containsExactlyInAnyOrder("a-b", "a_b", "A_b")
    }

    @Test
    fun `a version published under the former view name should keep it to recompile, roll back and drop`() = spec {
        val compiled = DatasetCompiler.compile(DEFINITION, SOURCES, "shop", "orders", 1, "bi", "Orders")
        val current = (compiled as DatasetCompilation.Compiled).artifact
        // Published before view names carried the digest of the dataset's id.
        val former = "ds_shop_orders_v1"
        val artifact = current.copy(
            view = former,
            ddl = current.ddl.replace(current.view, former),
            columns = MemoryDatasetWarehouse.COLUMNS,
        )
        given(
            DatasetDrafted("shop", "orders", DEFINITION, listOf(ORDERS_ID, MEMBERS_ID, REGIONS_ID), "Orders"),
            DatasetPublished(artifact, DEFINITION),
        )
        warehouse.created["bi.$former"] = artifact
        // The definition compiles to what is published, under whatever name: nothing to publish.
        whenCommand(RecompileDataset) { expectUnchanged() }
        // A source gone: the version goes on serving while the view of its name still gives its columns.
        catalog[REGIONS_ID] = null
        whenCommand(RecompileDataset) {
            expectEvents(DatasetNeedsRevision(listOf("The catalog has no source $REGIONS_ID."), serving = true))
        }
        catalog[REGIONS_ID] = SOURCES[REGIONS_ID]
        givenCommands(RecompileDataset)
        // The next version takes the new name; the former one keeps its own.
        val fewer = DEFINITION.copy(columns = DEFINITION.columns.dropLast(1), eventTime = null)
        givenCommands(ReviseDataset(fewer, title = "Orders"), PublishDataset)
        warehouse.created.keys.assert().containsExactly("bi.$former", "bi.ds_shop_orders_7458f44cdb_v2")
        warehouse.created.remove("bi.$former")
        whenCommand(RollbackDataset(1)) {
            expectEvents(DatasetRolledBack(1, from = 2))
            expectState { serving()!!.view.assert().isEqualTo(former) }
        }
        warehouse.created.keys.assert().contains("bi.$former")
        givenCommands(RetireDataset)
        whenCommand(DropDatasetViews) {
            expectEvents(DatasetViewsDropped(listOf("bi.$former", "bi.ds_shop_orders_7458f44cdb_v2")))
        }
    }
}
