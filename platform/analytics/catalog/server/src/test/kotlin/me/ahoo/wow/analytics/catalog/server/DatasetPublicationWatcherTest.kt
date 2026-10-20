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

package me.ahoo.wow.analytics.catalog.server

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetField
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetNeedsRevision
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRetired
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetScope
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSensitivity
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetState
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalWowApi::class, InternalWowApi::class)
class DatasetPublicationWatcherTest {
    private var rows: List<Snapshot<DatasetState>> = emptyList()
    private val queries = mutableListOf<IListQuery>()
    private val api = mockk<QueryApi> {
        every { trusted().unbounded().snapshots(DatasetState::class.java).list(any()) } answers {
            queries += firstArg<IListQuery>()
            flowOf(*rows.toTypedArray())
        }
    }
    private var now = 1_000_000L
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
    private val file = QueryDataset.of(AggregateType("shop", "filed")) {
        tenant("tenant_id")
        field("tenant_id") {}
    }
    private val datasets = QueryDatasets(listOf(file))
    private val watcher =
        DatasetPublicationWatcher({ api }, datasets, Duration.ofSeconds(5), Duration.ofMinutes(5), clock)

    @Test
    fun `a node should serve each published version, read incrementally, and drop what is retired or gone`(): Unit =
        runBlocking {
            watcher.synced.assert().isFalse()
            watcher.syncAge().isNaN().assert().isTrue()
            rows = listOf(row("orders", 1, at = 900_000L))
            watcher.sync().assert().isEqualTo(1)
            watcher.synced.assert().isTrue()
            // The first read reads every dataset; its changes are not lag.
            queries.last().filter.operator.assert().isEqualTo(FilterOperator.MATCH_ALL)
            watcher.lag.assert().isEqualTo(Duration.ZERO)
            val served = datasets[AggregateType("shop", "orders")]!!
            served.storage["table"].asString().assert().isEqualTo("wow_datasets.ds_shop_orders_v1")
            served.scope.tenant.assert().isEqualTo(QueryField("tenant_id"))
            served.scope.owner.assert().isEqualTo(QueryField("owner_id"))
            served.identity.assert().isEqualTo(QueryField("phone"))
            served.title.assert().isEqualTo("Orders")
            served.declaration.fields.keys.assert()
                .containsExactlyInAnyOrder(
                    QueryField("tenant_id"),
                    QueryField("owner_id"),
                    QueryField("phone"),
                    QueryField("note")
                )
            served.declaration.fields.getValue(QueryField("phone")).maskRule.toString().assert().contains("DISPLAY")

            // The next reads are incremental, from the newest change seen less the clocks' margin.
            now += 5_000
            watcher.sync().assert().isEqualTo(0)
            queries.last().filter.operator.assert().isEqualTo(FilterOperator.GTE)
            queries.last().filter.toString().assert().contains((900_000L - 30_000L).toString())
            watcher.syncAge().assert().isEqualTo(0.0)
            rows = listOf(row("orders", 2, at = now - 2_000))
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "orders")]!!.storage["table"].asString().assert()
                .isEqualTo("wow_datasets.ds_shop_orders_v2")
            watcher.lag.assert().isEqualTo(Duration.ofSeconds(2))
            now += 1_000
            watcher.syncAge().assert().isEqualTo(1.0)

            // A dataset file of the same name takes precedence; a retired dataset is no longer served.
            rows = listOf(row("filed", 1, at = now), row("orders", 2, at = now, retired = true))
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "filed")].assert().isSameAs(file)
            datasets[AggregateType("shop", "orders")].assert().isNull()
            watcher.sync().assert().isEqualTo(0)

            // A dataset gone from the snapshots leaves at the next full read.
            rows = listOf(row("orders", 3, at = now))
            watcher.sync().assert().isEqualTo(1)
            rows = emptyList()
            watcher.sync().assert().isEqualTo(0)
            now += Duration.ofMinutes(5).toMillis()
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "orders")].assert().isNull()
        }

    @Test
    fun `a dataset needing revision should be served while its version holds, and unavailable once it does not`(): Unit =
        runBlocking {
            rows = listOf(row("orders", 1, at = now, revision = true))
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "orders")]!!.unavailable.assert().isNull()
            rows = listOf(row("orders", 1, at = now, revision = false))
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "orders")]!!.unavailable.assert().isEqualTo("needs revision")
            watcher.sync().assert().isEqualTo(0)
            rows = listOf(row("orders", 1, at = now))
            watcher.sync().assert().isEqualTo(1)
            datasets[AggregateType("shop", "orders")]!!.unavailable.assert().isNull()
        }

    @Test
    fun `a platform dataset without identity should be served without a tenant`() {
        val artifact = artifact("regions", 1).copy(
            scope = DatasetScope(platform = true),
            identity = null,
            eventTime = "month",
            title = null,
            fields = listOf(
                DatasetField("month"),
                DatasetField("name", sensitivity = DatasetSensitivity()),
                DatasetField(
                    "member",
                    reference = me.ahoo.wow.analytics.catalog.api.dataset.DatasetReference("crm", "member")
                ),
            ),
        )
        val dataset = DatasetPublicationWatcher.datasetOf(artifact)
        dataset.scope.platform.assert().isTrue()
        dataset.eventTime.assert().isEqualTo(QueryField("month"))
        dataset.declaration.fields.getValue(QueryField("name")).maskRule.toString().assert().doesNotContain("DISPLAY")
        dataset.declaration.fields.getValue(QueryField("member")).semanticType.toString().assert()
            .contains("contextName=crm").contains("aggregateName=member")
    }

    @Test
    fun `the watcher should start and stop`() {
        rows = emptyList()
        watcher.start()
        watcher.isRunning.assert().isTrue()
        watcher.stop()
        watcher.isRunning.assert().isFalse()
    }

    private fun artifact(name: String, version: Int) = DatasetArtifact(
        context = "shop",
        name = name,
        version = version,
        database = "wow_datasets",
        view = "ds_shop_${name}_v$version",
        ddl = "CREATE OR REPLACE VIEW ...",
        scope = DatasetScope(tenant = "tenant_id", owner = "owner_id"),
        fields = listOf(
            DatasetField("phone", title = "Phone", sensitivity = DatasetSensitivity(SensitivityLevel.DISPLAY, 3)),
            DatasetField("note"),
        ),
        sources = listOf("s"),
        title = "Orders",
        identity = "phone",
    )

    private fun row(
        name: String,
        version: Int,
        at: Long,
        retired: Boolean = false,
        revision: Boolean? = null,
    ): Snapshot<DatasetState> {
        val definition =
            DatasetDefinition(listOf(DatasetSource("o", "s")), "o", listOf(DatasetColumn("phone", from = "o.p")))
        val state = DatasetState().apply {
            onSourcing(DatasetDrafted("shop", name, definition, listOf("s")))
            (1..version).forEach { onSourcing(DatasetPublished(artifact(name, it), definition)) }
            if (retired) onSourcing(DatasetRetired(version))
            revision?.let { onSourcing(DatasetNeedsRevision(listOf("a source changed"), serving = it)) }
        }
        val type = AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.DATASET)
        return Snapshot.of(
            type.ref(
                AnalyticsCatalogContext.TENANT_ID,
                "shop.$name"
            ),
            version, Scope.NONE, emptyMap(), false, null, 0, at, state
        )
    }
}
