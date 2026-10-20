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
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRetired
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRolledBack
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetViewsDropped
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.DropDatasetViews
import me.ahoo.wow.analytics.catalog.api.dataset.PublishDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RetireDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RollbackDataset
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.MEMBERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.analytics.catalog.domain.source.SourceRejections
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

/** A warehouse in memory: the views created, by name, and those dropped; it refuses to create while [failing]. */
class MemoryDatasetWarehouse : DatasetWarehouse {
    companion object {
        /** The columns every view it creates has. */
        val COLUMNS = listOf(SourceColumn("tenant_id", "String"), SourceColumn("order_id", "String"))
    }

    val created = linkedMapOf<String, DatasetArtifact>()
    val dropped = mutableListOf<String>()
    var failing: String? = null

    override suspend fun create(artifact: DatasetArtifact): List<SourceColumn> {
        failing?.let { throw DatasetWarehouseException(it) }
        created["${artifact.database}.${artifact.view}"] = artifact
        return COLUMNS
    }

    override suspend fun drop(database: String, views: List<String>) {
        views.forEach {
            created.remove("$database.$it")
            dropped += "$database.$it"
        }
    }
}

class DatasetSpec {
    private val known = KnownAggregates { context, aggregate -> context == "shop" && aggregate == "member" }
    private val catalog = SOURCES.toMutableMap<String, SourceState?>()
    private val sources = DatasetSources { catalog[it] }
    private val warehouse = MemoryDatasetWarehouse()
    private val policy = DatasetPolicy(database = "bi", retention = 7.days)

    private fun spec(id: String = ID, block: AggregateSpec<DatasetState>.() -> Unit) = aggregateSpec(
        datasetModel(AnalyticsCatalogContext.NAME, sources, known, warehouse, policy),
        id = id,
        tenantId = AnalyticsCatalogContext.TENANT_ID,
        block = block,
    )

    @Test
    fun `a dataset should be drafted and revised, its definition checked against the catalog each time`() = spec {
        whenCommand(DraftDataset("shop", "orders", DEFINITION, title = "Orders")) {
            expectEvents(
                DatasetDrafted("shop", "orders", DEFINITION, listOf(ORDERS_ID, MEMBERS_ID, REGIONS_ID), "Orders")
            )
            expectState {
                exists.assert().isTrue()
                context.assert().isEqualTo("shop")
                name.assert().isEqualTo("orders")
                title.assert().isEqualTo("Orders")
                definition.assert().isEqualTo(DEFINITION)
                status.assert().isEqualTo(DatasetStatus.DRAFT)
            }
        }
        whenCommand(ReviseDataset(DEFINITION, title = "Orders")) { expectUnchanged() }
        val fewer = DEFINITION.copy(
            sources = DEFINITION.sources.take(1),
            joins = emptyList(),
            columns = DEFINITION.columns.take(2),
            identity = null,
            eventTime = null
        )
        whenCommand(ReviseDataset(fewer)) {
            expectEvents(DatasetRevised(fewer, listOf(ORDERS_ID)))
            expectState {
                sources.assert().containsExactly(ORDERS_ID)
                title.assert().isNull()
            }
        }
        whenCommand(ReviseDataset(fewer, title = "Orders")) { expectEventTypes(DatasetRevised::class) }
        whenCommand(ReviseDataset(fewer.copy(columns = listOf(DatasetColumn("x", from = "o.nothing"))))) {
            expectRejected(
                DatasetRejections.DEFINITION_INVALID
            ) { message.assert().contains("nothing is not a column") }
        }
    }

    @Test
    fun `a draft should be refused for another id, a malformed name, an aggregate's name or a definition that fails`() {
        spec("shop.other") {
            whenCommand(DraftDataset("shop", "orders", DEFINITION)) { expectRejected(SourceRejections.INVALID) }
        }
        spec("shop.or ders") {
            whenCommand(DraftDataset("shop", "or ders", DEFINITION)) {
                expectRejected(SourceRejections.INVALID) { message.assert().contains("is not a dataset name") }
            }
        }
        spec("shop.member") {
            whenCommand(DraftDataset("shop", "member", DEFINITION)) { expectRejected(DatasetRejections.NAME_TAKEN) }
        }
        spec {
            whenCommand(DraftDataset("shop", "orders", DEFINITION.copy(subject = "x"))) {
                expectRejected(DatasetRejections.DEFINITION_INVALID)
            }
        }
    }

    private fun AggregateSpec<DatasetState>.drafted() {
        whenCommand(
            DraftDataset("shop", "orders", DEFINITION, title = "Orders")
        ) { expectEventTypes(DatasetDrafted::class) }
    }

    @Test
    fun `each publication should create its version's view, which queries then read`() = spec {
        drafted()
        whenCommand(RetireDataset) { expectRejected(DatasetRejections.NOT_PUBLISHED) }
        whenCommand(RollbackDataset(1)) { expectRejected(SourceRejections.INVALID) }
        whenCommand(PublishDataset) {
            expectEventTypes(DatasetPublished::class)
            expectState {
                status.assert().isEqualTo(DatasetStatus.PUBLISHED)
                current.assert().isEqualTo(1)
                serving()!!.view.assert().isEqualTo("ds_shop_orders_v1")
                serving()!!.database.assert().isEqualTo("bi")
                serving()!!.title.assert().isEqualTo("Orders")
                serving()!!.columns.assert().isEqualTo(MemoryDatasetWarehouse.COLUMNS)
                published()!!.definition.assert().isEqualTo(DEFINITION)
            }
        }
        warehouse.created.keys.assert().containsExactly("bi.ds_shop_orders_v1")
        // Nothing to publish: the definition compiles to what is published.
        whenCommand(PublishDataset) { expectUnchanged() }
        val fewer = DEFINITION.copy(columns = DEFINITION.columns.dropLast(1), eventTime = null)
        whenCommand(ReviseDataset(fewer, title = "Orders")) { expectEventTypes(DatasetRevised::class) }
        // Revised but not published: queries still read version 1.
        whenCommand(
            ReviseDataset(fewer, title = "Orders")
        ) { expectState { serving()!!.version.assert().isEqualTo(1) } }
        whenCommand(PublishDataset) {
            expectEventTypes(DatasetPublished::class)
            expectState {
                current.assert().isEqualTo(2)
                versions.map { it.artifact.version }.assert().containsExactly(1, 2)
            }
        }
        warehouse.created.keys.assert().containsExactly("bi.ds_shop_orders_v1", "bi.ds_shop_orders_v2")
        // Back to version 1, its view created again if it went.
        warehouse.created.remove("bi.ds_shop_orders_v1")
        whenCommand(RollbackDataset(1)) {
            expectEvents(DatasetRolledBack(1, from = 2))
            expectState { serving()!!.version.assert().isEqualTo(1) }
        }
        warehouse.created.keys.assert().contains("bi.ds_shop_orders_v1")
        whenCommand(RollbackDataset(1)) { expectUnchanged() }
        whenCommand(RollbackDataset(3)) {
            expectRejected(SourceRejections.INVALID) { message.assert().contains("never published") }
        }
    }

    @Test
    fun `a publication should be refused when the definition no longer compiles or the warehouse fails`() = spec {
        drafted()
        warehouse.failing = "Code: 497. Not enough privileges."
        whenCommand(PublishDataset) {
            expectRejected(DatasetRejections.VIEW_FAILED) { message.assert().contains("Not enough privileges") }
            expectState { current.assert().isEqualTo(0) }
        }
        warehouse.failing = null
        whenCommand(PublishDataset) { expectEventTypes(DatasetPublished::class) }
        catalog[REGIONS_ID] = null
        whenCommand(ReviseDataset(DEFINITION, title = "Other")) { expectRejected(DatasetRejections.DEFINITION_INVALID) }
        whenCommand(PublishDataset) {
            expectRejected(DatasetRejections.DEFINITION_INVALID) { message.assert().contains("no source $REGIONS_ID") }
        }
        warehouse.failing = "gone"
        whenCommand(RetireDataset) { expectEventTypes(DatasetRetired::class) }
        whenCommand(RollbackDataset(1)) { expectRejected(DatasetRejections.VIEW_FAILED) }
    }

    @Test
    fun `a retired dataset should keep its views for the retention, then drop them`() = spec {
        drafted()
        whenCommand(PublishDataset) { expectEventTypes(DatasetPublished::class) }
        whenCommand(DropDatasetViews) { expectUnchanged() }
        whenCommand(RetireDataset) {
            expectEvents(DatasetRetired(1))
            expectScheduled(DROP_VIEWS, DropDatasetViews, ScheduleRule.after(7.days))
            expectState {
                status.assert().isEqualTo(DatasetStatus.RETIRED)
                serving().assert().isNull()
            }
        }
        whenCommand(RetireDataset) { expectUnchanged() }
        // Published again before the retention ends: the views stay.
        whenCommand(RollbackDataset(1)) {
            expectEvents(DatasetRolledBack(1, from = 1))
            expectScheduleCancelled(DROP_VIEWS)
        }
        whenCommand(DropDatasetViews) { expectUnchanged() }
        whenCommand(RetireDataset) { expectEventTypes(DatasetRetired::class) }
        whenCommand(DropDatasetViews) {
            expectEvents(DatasetViewsDropped(listOf("bi.ds_shop_orders_v1")))
            expectState { dropped.assert().isTrue() }
        }
        warehouse.dropped.assert().containsExactly("bi.ds_shop_orders_v1")
        whenCommand(DropDatasetViews) { expectUnchanged() }
        // A dropped dataset published again gets its next version.
        whenCommand(PublishDataset) {
            expectScheduleCancelled(DROP_VIEWS)
            expectState {
                current.assert().isEqualTo(2)
                dropped.assert().isFalse()
            }
        }
    }

    @Test
    fun `a policy should name a database and keep views a while`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { DatasetPolicy(database = " ") }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { DatasetPolicy(retention = 0.days) }
        kotlinx.coroutines.runBlocking {
            org.junit.jupiter.api.assertThrows<DatasetWarehouseException> {
                DatasetWarehouse.NONE.create(
                    DatasetCompiler.compile(DEFINITION, SOURCES, "shop", "orders", 1).let {
                        (it as DatasetCompilation.Compiled).artifact
                    }
                )
            }
            DatasetWarehouse.NONE.drop("bi", listOf("v"))
        }
    }

    private companion object {
        const val DROP_VIEWS = "analytics-catalog.dataset.DropDatasetViews"
        val ID = AnalyticsCatalogContext.datasetIdOf("shop", "orders")
    }
}
