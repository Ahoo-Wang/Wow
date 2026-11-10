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

package me.ahoo.wow.query.dataset

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.CachedQueryBindings
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.error.QueryDatasetUnavailableException
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How the datasets, their catalog, gateways and bindings follow a change: listeners, recompiling, unavailable datasets, storages. */
internal class DatasetChangesTest : DatasetQueryGatewayFixtures() {
    @Test
    fun `datasets change in place, every listener validating before any applies`() {
        val datasets = QueryDatasets()
        val seen = mutableListOf<String>()
        datasets.listen(
            object : QueryDatasetListener {
                override fun validate(change: QueryDatasetChange) {
                    require(change.dataset.title != "refused") { "refused" }
                    seen += "validate ${change::class.simpleName}"
                }

                override fun apply(change: QueryDatasetChange) {
                    seen += "apply ${change::class.simpleName}"
                }
            },
        )
        datasets.add(orders)
        assertThrows<IllegalArgumentException> { datasets.add(orders) }
        datasets.replace(orders.with(title = "Orders"))
        datasets[orders.name]!!.title.assert().isEqualTo("Orders")
        assertThrows<IllegalArgumentException> { datasets.replace(orders.with(title = "refused")) }
        datasets[orders.name]!!.title.assert().isEqualTo("Orders")
        assertThrows<IllegalArgumentException> { datasets.replace(orders.with(name = OTHER)) }
        datasets.put(orders.with(title = "Put"))
        datasets[orders.name]!!.title.assert().isEqualTo("Put")
        datasets.remove(orders.name).assert().isTrue()
        datasets.remove(orders.name).assert().isFalse()
        datasets.all.assert().isEmpty()
        datasets.put(orders)
        seen.assert().containsExactly(
            "validate Added",
            "apply Added",
            "validate Replaced",
            "apply Replaced",
            "validate Replaced",
            "apply Replaced",
            "validate Removed",
            "apply Removed",
            "validate Added",
            "apply Added",
        )
        val ignoring = object : QueryDatasetListener {
            override fun apply(change: QueryDatasetChange) = Unit
        }
        ignoring.validate(QueryDatasetChange.Removed(orders))
        assertThrows<IllegalStateException> { QueryDatasets.NONE.add(orders) }
        assertThrows<IllegalStateException> { QueryDatasets.NONE.listen(ignoring) }
    }

    @Test
    fun `the catalog recompiles a dataset that changes and refuses one that takes an aggregate's name`() = runTest {
        val datasets = QueryDatasets(listOf(orders))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetBindings = { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) },
                datasets = datasets
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            aggregates = listOf(OTHER),
            datasets = datasets,
        )
        catalog.schema(orders.name, QueryModel.DATASET).field(QueryField("city")).assert().isNotNull()
        val renamed = QueryDataset.of(orders.name) {
            tenant("tenant_id")
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("region") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        datasets.replace(renamed)
        val schema = catalog.schema(orders.name, QueryModel.DATASET)
        schema.field(QueryField("region")).assert().isNotNull()
        schema.field(QueryField("city")).assert().isNull()
        catalog.versions().map { it.aggregate }.assert().contains("analytics.orders")
        catalog.revalidate("analytics.orders", QueryModel.DATASET).single().version.assert().isEqualTo(schema.version)
        assertThrows<IllegalArgumentException> { datasets.add(orders.with(name = OTHER)) }
            .message.assert().contains("aggregate")
        datasets[OTHER].assert().isNull()
        datasets.remove(orders.name)
        assertThrows<QuerySchemaUnavailableException> { catalog.schema(orders.name, QueryModel.DATASET) }
    }

    @Test
    fun `a dataset never takes the name of an aggregate of its context`() {
        assertThrows<IllegalArgumentException> {
            QuerySchemaCatalog(aggregates = listOf(orders.name), datasets = QueryDatasets(listOf(orders)))
        }
    }

    @Test
    fun `gateways follow the datasets, and cached bindings rebind a changed dataset`() = runTest {
        val datasets = QueryDatasets()
        val created = mutableListOf<QueryDataset>()
        val factory = object : CachedQueryBindings<QueryDataset>(QueryDataset::name) {
            override fun createBinding(key: QueryDataset): QueryBackendBinding<RecordingBackend> {
                created += key
                return QueryBackendBinding(RecordingBackend(key.name), ADAPTER)
            }
        }
        val gateways = DatasetQueryGateways.of(datasets, requireServable = { require(it.title != "unservable") }) {
            factory.binding(it)
            gateway(dataset = it)
        }
        datasets.add(orders)
        val first = gateways[orders.name]!!
        datasets.replace(orders.with(title = "Orders"))
        gateways[orders.name].assert().isNotSameAs(first)
        gateways[orders.name]!!.dataset.title.assert().isEqualTo("Orders")
        factory.binding(orders.with(title = "Orders"))
        created.size.assert().isEqualTo(2)
        factory.forget(orders.with(title = "Orders"))
        factory.binding(orders.with(title = "Orders"))
        created.size.assert().isEqualTo(3)
        assertThrows<IllegalArgumentException> {
            datasets.add(orders.with(name = OTHER, title = "unservable"))
        }
        gateways[OTHER].assert().isNull()
        datasets.remove(orders.name)
        gateways.all.assert().isEmpty()
        DatasetQueryGateways.of(QueryDatasets.NONE) { gateway(dataset = it) }.all.assert().isEmpty()
        DatasetQueryGateways.NONE.all.assert().isEmpty()
        assertThrows<IllegalArgumentException> { DatasetQueryGateways(listOf(gateway(), gateway())) }
    }

    @Test
    fun `an unavailable dataset is served with its reason, and every query of it answered so`() = runTest {
        val datasets = QueryDatasets(listOf(orders.withUnavailable("needs revision")))
        val gateways = DatasetQueryGateways.of(datasets) { gateway(dataset = it) }
        val gateway = gateways[orders.name]!!
        gateway.dataset.unavailable.assert().isEqualTo("needs revision")
        gateway.aggregateType.assert().isEqualTo(orders.name)
        gateway.entryPolicy.assert().isEqualTo(QueryEntryPolicy.DEFAULT)
        val caller = QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
        val failures = listOf<suspend () -> Any?>(
            { gateway.single(SingleQuery(MatchAllFilter), caller) },
            { gateway.list(ListQuery(MatchAllFilter), caller).toList() },
            { gateway.stream(ListQuery(MatchAllFilter), caller).toList() },
            { gateway.paged(PagedQuery(MatchAllFilter), caller) },
            { gateway.cursor(CursorQuery(MatchAllFilter), caller) },
            { gateway.count(CountQuery(MatchAllFilter), caller) },
            { gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("n"))), caller).toList() },
            { gateway.describe(QueryEntry.HTTP) },
        )
        failures.forEach { query ->
            val failure = assertThrows<QueryDatasetUnavailableException> { query() }
            failure.errorCode.assert().isEqualTo(QueryErrors.DATASET_UNAVAILABLE)
            failure.errorMsg.assert().isEqualTo("Dataset [${orders.name}] cannot be queried now: needs revision.")
            failure.reason.assert().isEqualTo("needs revision")
            failure.dataset.assert().isEqualTo(orders.name)
        }
        // Available again: an ordinary gateway.
        datasets.put(orders)
        gateways[orders.name].assert().isNotInstanceOf(UnavailableDatasetQueryGateway::class.java)
        datasets.put(orders.withUnavailable("needs revision"))
        gateways[orders.name].assert().isInstanceOf(UnavailableDatasetQueryGateway::class.java)
        assertThrows<IllegalArgumentException> { orders.withUnavailable(" ") }
    }

    @Test
    fun `gateways over bindings and a catalog serve each dataset and refuse one the bindings do not serve`() = runTest {
        val datasets = QueryDatasets(listOf(orders))
        val factory = QueryBindings<QueryDataset> { dataset ->
            QueryBackendBinding(RecordingBackend(dataset.name), ADAPTER).takeIf { dataset.title != "unbound" }
        }
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = factory, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val gateways = DatasetQueryGateways.of(datasets, catalog, factory, policies = listOf(ScopeQueryPolicy()))
        gateways[orders.name]!!.count(
            CountQuery(MatchAllFilter),
            QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
        )
            .assert().isEqualTo(CountResult(1, exact = true))
        assertThrows<IllegalArgumentException> { datasets.add(orders.with(name = OTHER, title = "unbound")) }
        gateways[OTHER].assert().isNull()
    }

    @Test
    fun `storages locate a dataset's backend by name`() {
        val factory = QueryBindings<QueryDataset> { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) }
        val storages = QueryBackendStorages(datasetBindings = factory, datasets = QueryDatasets(listOf(orders)))
        storages.storage(orders.name, QueryModel.DATASET).assert().isSameAs(ADAPTER)
        storages.storage(QueryDatasetTest.OTHER, QueryModel.DATASET).assert().isNull()
        storages.storage(orders.name, QueryModel.SNAPSHOT).assert().isNull()
        storages.storage(orders.name, QueryModel("custom")).assert().isNull()
        QueryBackendStorages(datasets = QueryDatasets(listOf(orders)))
            .storage(orders.name, QueryModel.DATASET).assert().isNull()
    }

    @Test
    fun `cached bindings create each dataset's binding once and ask again for one they do not serve`() {
        var created = 0
        val factory = object : CachedQueryBindings<QueryDataset>(QueryDataset::name) {
            override fun createBinding(key: QueryDataset): QueryBackendBinding<RecordingBackend>? {
                created++
                return QueryBackendBinding(RecordingBackend(key.name), ADAPTER).takeIf { key.title != "unbound" }
            }
        }
        factory.binding(orders).assert().isSameAs(factory.binding(orders))
        created.assert().isEqualTo(1)
        val unbound = orders.with(name = OTHER, title = "unbound")
        factory.binding(unbound).assert().isNull()
        factory.binding(unbound).assert().isNull()
        created.assert().isEqualTo(3)
    }

    @Test
    fun `bindings that hold nothing forget nothing`() {
        val bindings = QueryBindings<QueryDataset> { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) }
        bindings.forget(orders)
        bindings.binding(orders)?.storage.assert().isSameAs(ADAPTER)
    }
}

/** This dataset under [name] and with [title]; the dataset's other properties unchanged. */
private fun QueryDataset.with(name: AggregateType = this.name, title: String? = this.title): QueryDataset =
    QueryDataset(name, scope, declaration, identity, eventTime, title, storage, unavailable)
