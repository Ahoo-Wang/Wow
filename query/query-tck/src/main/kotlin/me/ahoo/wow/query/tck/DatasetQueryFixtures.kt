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

package me.ahoo.wow.query.tck
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.CONTEXT
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ID
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ORDERS
import org.junit.jupiter.api.BeforeEach
import tools.jackson.databind.node.ObjectNode

/**
 * The fixtures and hooks of the dataset contract ([DatasetQueryBackendSpec], the class a backend's test extends): the
 * backend's hooks ([createDatasetBindings], [createTable], [semanticDivergences]), the [Target] a test queries, the
 * [orders] every test reads, and the dataset a table compiles to ([target]).
 *
 * The contract's cases come in families, one class each: [DatasetRecordCases], [DatasetAggregationCases] and
 * [DatasetDateBucketCases], then [DatasetQueryBackendSpec] with the gateway. They form one chain, in that order, each
 * extending the one before, because a class has one superclass and JUnit runs the cases a test class inherits. The
 * order means nothing: every family reads only this class's fixtures, hooks and constants, and the suite's public
 * constants on [DatasetQueryBackendSpec], never a member of the family it extends, so any order would run the same
 * cases.
 */
abstract class DatasetQueryFixtures internal constructor() {
    internal lateinit var bindings: QueryBindings<QueryDataset>
        private set

    // `orders` and `walk` are protected, not internal, only because they name the protected [Target]; what the
    // families share among themselves is internal.
    protected lateinit var orders: Target
        private set

    protected abstract fun createDatasetBindings(): QueryBindings<QueryDataset>

    /** Creates [table] in the storage, replacing any earlier one, loads its rows and returns the dataset's binding. */
    protected abstract suspend fun createTable(table: DatasetTable): ObjectNode

    /**
     * Semantic cases ([FilterSemantics], or a case a test names, such as
     * [DatasetDateBucketCases.BEFORE_1970_PARTIAL_CHANGE]) this backend is known to diverge on, such as a table that
     * cannot tell a missing value from a null; each one is skipped and reported, never silently passed.
     */
    protected open val semanticDivergences: Set<String> = emptySet()

    /** A dataset's backend with the schema its Catalog compiled. */
    protected class Target @InternalWowApi constructor(
        val dataset: QueryDataset,
        val backend: QueryBackend,
        @property:InternalWowApi val schema: QueryModelSchema,
    ) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun count(filter: FilterExpression): Long =
            backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter), schema)).count

        /** [query]'s groups; with [residual], the core computes every window the storage would. */
        suspend fun aggregate(query: AggregationQuery, residual: Boolean = false): List<ObjectNode> =
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, if (residual) schema.withResidualWindows() else schema),
            ).toList()
    }

    @BeforeEach
    open fun setup() {
        bindings = createDatasetBindings()
        orders = blocking { target(ORDERS) }
    }

    internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    /** The dataset of [table], its backend, and the schema a Catalog over both compiles. */
    protected suspend fun target(table: DatasetTable): Target {
        val dataset = dataset(table)
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = catalog(datasets)
        val binding = checkNotNull(bindings.binding(dataset)) { "The bindings serve no dataset [${dataset.name}]." }
        return Target(dataset, binding.backend, catalog.schema(dataset.name, QueryModel.DATASET))
    }

    internal fun catalog(datasets: QueryDatasets) = QuerySchemaCatalog(
        storages = QueryBackendStorages(datasetBindings = bindings, datasets = datasets),
        compiler = QueryModelCompiler.of(datasets = datasets),
        datasets = datasets,
    )

    protected suspend fun walk(sort: Sort, target: Target = orders, size: Int = 2): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = listOf(sort), size = size, cursor = cursor)
            val page = target.backend.cursor(QueryAdmission.Trusted.cursor(query, target.schema))
            ids += page.list.map { it.path(ID).stringValue() }
            cursor = page.nextCursor
        } while (cursor != null && ids.size <= MAX_LIST)
        return ids
    }

    private suspend fun dataset(table: DatasetTable): QueryDataset {
        val storage = createTable(table)
        return QueryDataset.of(AggregateType(CONTEXT, table.name)) {
            tenant(TENANT_COLUMN.path)
            identity(ID)
            storage(storage)
            table.columns.forEach { column ->
                field(column.name) {
                    when (column.type) {
                        DatasetColumn.Type.STRING -> types(QueryValueType.STRING)
                        DatasetColumn.Type.INTEGER -> types(QueryValueType.INTEGER)
                        DatasetColumn.Type.DECIMAL -> types(QueryValueType.DECIMAL)
                        DatasetColumn.Type.DATE -> {
                            types(QueryValueType.STRING)
                            semantic(Temporal.Date)
                        }
                        DatasetColumn.Type.STRING_ARRAY -> {
                            kind(QueryValueKind.ARRAY)
                            items {
                                types(QueryValueType.STRING)
                                nullable(false)
                            }
                        }
                    }
                    nullable(column.nullable)
                }
            }
        }
    }

    internal fun numeric(function: AggregationFunction, field: QueryField<*>, alias: String) =
        AggregationMetric.Numeric(function, AggregationExpression.Field(field), alias)

    internal companion object {
        const val MAX_LIST = 100
        const val EPSILON = 1e-9
        val TENANT_COLUMN = QueryField("tenant_id")
        val CITY = QueryField("city")
        val AMOUNT = QueryField("amount")
        val CREATED_AT = QueryField("created_at")
    }
}

private fun AggregationSupport.withResidualWindows() = AggregationSupport(
    having, topN, denseFill, percentile, distinctCount, firstLast, AggregationSupport.RESIDUAL_WINDOWS, rootGroups, termsTopN,
)

private fun StorageSupport.withAggregation(aggregation: AggregationSupport) =
    StorageSupport(paging, aggregation, parallelArraySort, absentValues, arrayEquality, datasetAccess)

/** This schema with every window computed by the core, so a spec can hold the storage's native windows to it. */
internal fun QueryModelSchema.withResidualWindows() = QueryModelSchema(
    model,
    capabilities,
    definition,
    bindings,
    fullProjectionAvailable,
    approximateMetrics,
    storage.withAggregation(storage.aggregation.withResidualWindows()),
    provisional,
    profile,
)
