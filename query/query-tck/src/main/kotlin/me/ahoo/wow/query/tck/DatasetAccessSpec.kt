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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessOperator
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetBuilder
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.policy.AccessFilters
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The access restriction of a dataset bound to an aggregate (D390 C4, [DatasetAccess]) on a dataset backend: each row
 * is read by the current access attributes of the aggregate it is of, as the kernel's [AccessPredicate.allows] decides
 * on them, over every predicate of [AccessFilterSpec]'s matrix and its documents.
 *
 * Each document has two rows in the caller's tenant, so a row shows exactly when its aggregate does; one more row is
 * of an aggregate whose access the storage holds no record of, and the same document id in another tenant has other
 * attributes there. Some records arrive older than the one before them, which never replaces it. A backend's test
 * supplies its bindings ([createDatasetBindings]), loads the table ([createTable]) and the access records
 * ([storeAccess]) where those bindings read them.
 */
abstract class DatasetAccessSpec {
    private lateinit var bindings: QueryBindings<QueryDataset>

    protected abstract fun createDatasetBindings(): QueryBindings<QueryDataset>

    /** Creates [table] in the storage, replacing any earlier one, loads its rows and returns the dataset's binding. */
    protected abstract suspend fun createTable(table: DatasetTable): ObjectNode

    /**
     * Stores [records] as the commits of [aggregate] that the storage keeps its current access records of, in order:
     * each replaces the record of its tenant and aggregate id unless that one is of a later version.
     */
    protected abstract suspend fun storeAccess(aggregate: AggregateType, records: List<AccessRecord>)

    /** The access attributes after an aggregate's commit of [version]: null for a commit that recorded none. */
    class AccessRecord @InternalWowApi constructor(
        val tenantId: String,
        val aggregateId: String,
        val version: Int,
        val access: Map<String, Any?>?,
    ) {
        override fun toString(): String = "AccessRecord($tenantId/$aggregateId@$version, $access)"
    }

    /** The aggregate the documents are: a fresh context, so each test has records of its own. */
    protected val aggregate: AggregateType =
        AggregateType("tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX), "document")

    @BeforeEach
    open fun setup() {
        bindings = createDatasetBindings()
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    @TestFactory
    fun `access restriction matrix`(): List<DynamicTest> {
        val gateway = blocking { documents() }
        return AccessFilterSpec.CASES.map { (name, predicate) ->
            DynamicTest.dynamicTest(name) {
                val restriction = restrictionOf(predicate)
                val expected = if (restriction == MatchAllFilter) {
                    ROWS.filter { it.tenant == T1 }.map { it.id }
                } else {
                    val readable = AccessFilterSpec.RECORDS.filter { record ->
                        record.stamped && predicate.allows { valueOf(record, it) }
                    }.map { it.id }.toSet()
                    ROWS.filter { it.tenant == T1 && it.document in readable }.map { it.id }
                }
                blocking { gateway.ids(restriction, T1) }.toSet()
                    .assert().describedAs("$name as $restriction").isEqualTo(expected.toSet())
            }
        }
    }

    @Test
    fun `a row is read by its aggregate's record of the row's own tenant`() = blocking {
        val gateway = documents()
        gateway.ids(restrictionOf(SALES), T2).assert().isEmpty()
        gateway.ids(restrictionOf(LEGAL), T2).assert().containsExactly("d1-t2")
        gateway.ids(restrictionOf(LEGAL), T1).assert().isEmpty()
        Unit
    }

    @Test
    fun `an aggregate without a record, or with one of no attributes, is never read`() = blocking {
        val gateway = documents()
        val absent = AccessPredicate.Comparison(DEPARTMENT, AccessOperator.IS_NULL)
        // d3's and d4's departments: d3 holds none, d4 is sales now, whatever its older record said.
        gateway.ids(restrictionOf(absent), T1).toSet().assert().isEqualTo(setOf("d3-1", "d3-2"))
        gateway.ids(restrictionOf(AccessPredicate.Not(absent)), T1).toSet().assert().isEqualTo(
            setOf("d1-1", "d1-2", "d2-1", "d2-2", "d4-1", "d4-2", "d6-1", "d6-2"),
        )
        // A grant of every aggregate reads no record: every row of the tenant, d5's and d7's among them.
        gateway.ids(restrictionOf(AccessPredicate.Allowed), T1).size.assert().isEqualTo(ROWS.count { it.tenant == T1 })
        Unit
    }

    @Test
    fun `every operation reads the rows the restriction reads`() = blocking {
        val gateway = documents()
        val restriction = restrictionOf(SALES)
        val expected = setOf("d1-1", "d1-2", "d4-1", "d4-2")
        val caller = callerOf(T1)
        val policed = gateway.policed(restriction)
        policed.count(CountQuery(MatchAllFilter), caller).count.assert().isEqualTo(expected.size.toLong())
        policed.paged(PagedQuery(MatchAllFilter, pagination = Pagination(1, 10)), caller).list
            .map { it.path(ID).stringValue() }.toSet().assert().isEqualTo(expected)
        val cursor = policed.cursor(
            CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField(ID), Sort.Direction.ASC)), size = 3),
            caller
        )
        val next = policed.cursor(
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort(QueryField(ID), Sort.Direction.ASC)),
                size = 3,
                cursor = cursor.nextCursor
            ),
            caller,
        )
        val walked = (cursor.list + next.list).map { it.path(ID).stringValue() }
        walked.assert().containsExactly("d1-1", "d1-2", "d4-1", "d4-2")
        policed.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField(DOCUMENT_ID), DOCUMENT_ID)),
                metrics = listOf(AggregationMetric.Count("rows")),
            ),
            caller,
        ).toList().associate { it.path(DOCUMENT_ID).stringValue() to it.path("rows").asLong() }
            .assert().isEqualTo(mapOf("d1" to 2L, "d4" to 2L))
        Unit
    }

    @Test
    fun `a platform-level dataset reads the records of the platform tenant`() = blocking {
        val storage = createTable(
            DatasetTable(
                "access_platform_documents",
                listOf(
                    DatasetColumn(ID, DatasetColumn.Type.STRING),
                    DatasetColumn(DOCUMENT_ID, DatasetColumn.Type.STRING)
                ),
                listOf(mapOf(ID to "p1", DOCUMENT_ID to "d1"), mapOf(ID to "p2", DOCUMENT_ID to "d2")),
            ),
        )
        storeAccess(
            aggregate,
            listOf(
                AccessRecord(QueryDatasetScope.PLATFORM_TENANT, "d1", 1, mapOf("department" to "sales")),
                AccessRecord(T1, "d2", 1, mapOf("department" to "sales")),
            ),
        )
        val dataset = QueryDataset.of(AggregateType(CONTEXT, "access_platform_documents")) {
            platform()
            identity(ID)
            access("${aggregate.context}.${aggregate.name}", DOCUMENT_ID)
            storage(storage)
            listOf(ID, DOCUMENT_ID).forEach { string(it) }
        }
        gateway(dataset).ids(restrictionOf(SALES), QueryDatasetScope.PLATFORM_TENANT).assert().containsExactly("p1")
        Unit
    }

    /** The documents' dataset, its rows and the records of their aggregates loaded, as [Gateway]. */
    private suspend fun documents(): Gateway {
        val storage = createTable(TABLE)
        storeAccess(aggregate, accessRecords())
        val dataset = QueryDataset.of(AggregateType(CONTEXT, TABLE.name)) {
            tenant(TENANT)
            identity(ID)
            access("${aggregate.context}.${aggregate.name}", DOCUMENT_ID)
            storage(storage)
            listOf(ID, TENANT, DOCUMENT_ID).forEach { string(it) }
        }
        return gateway(dataset)
    }

    private fun gateway(dataset: QueryDataset): Gateway = Gateway(dataset)

    private fun QueryDatasetBuilder.string(name: String) = field(name) {
        types(QueryValueType.STRING)
        nullable(false)
    }

    /** The gateway of [dataset] under the caller's scope and one access restriction, as a node admits it. */
    private inner class Gateway(private val dataset: QueryDataset) {
        fun policed(restriction: FilterExpression): DatasetQueryGateway {
            val datasets = QueryDatasets(listOf(dataset))
            val catalog = QuerySchemaCatalog(
                storages = QueryBackendStorages(datasetBindings = bindings, datasets = datasets),
                compiler = QueryModelCompiler.of(datasets = datasets),
                datasets = datasets,
            )
            val policy = QueryPolicy { _, _ -> restriction }
            val gateways = DatasetQueryGateways.of(
                datasets,
                catalog,
                bindings,
                policies = listOf(ScopeQueryPolicy(), policy)
            )
            return checkNotNull(gateways[dataset.name])
        }

        suspend fun ids(restriction: FilterExpression, tenant: String): List<String> =
            policed(restriction).list(ListQuery(MatchAllFilter, limit = MAX_LIST), callerOf(tenant)).toList()
                .map { it.path(ID).stringValue() }
    }

    /** Each document's records in the caller's tenant, older ones among them, and one in the other tenant. */
    private fun accessRecords(): List<AccessRecord> {
        val current = AccessFilterSpec.RECORDS.map { record ->
            AccessRecord(T1, record.id, 2, if (record.stamped) accessOf(record) else null)
        }
        val d1 = current.first { it.aggregateId == "d1" }.access
        return listOf(
            // d4's older record grants what d1's does; its current one, after it, replaces it.
            AccessRecord(T1, "d4", 1, d1),
        ) + current + listOf(
            // An older record of d2, arriving after its current one: it never replaces it.
            AccessRecord(T1, "d2", 1, d1),
            AccessRecord(T2, "d1", 2, mapOf("owner" to "zed", "space" to null, "department" to "legal")),
        )
    }

    private companion object {
        const val CONTEXT = "tck"
        const val ID = "id"
        const val TENANT = "tenant_id"
        const val DOCUMENT_ID = "document_id"
        const val T1 = "t1"
        const val T2 = "t2"
        const val MAX_LIST = 100
        const val CONTEXT_SUFFIX = 12

        val DEPARTMENT: AccessAttribute.Single<AccessFilterSpec.Document, String> =
            AccessAttribute.single("department", String::class.java)
        val SALES = AccessPredicate.Comparison(DEPARTMENT, AccessOperator.EQUAL, listOf("sales"))
        val LEGAL = AccessPredicate.Comparison(DEPARTMENT, AccessOperator.EQUAL, listOf("legal"))

        /** A row of the documents' dataset: its [id], its [tenant] and the [document] it is of. */
        class Row(val id: String, val tenant: String, val document: String)

        /** Two rows of each document in the caller's tenant, one of d7, which has no record, and d1 in another tenant. */
        val ROWS: List<Row> = (AccessFilterSpec.RECORDS.map { it.id } + "d7").flatMap { document ->
            listOf(Row("$document-1", T1, document), Row("$document-2", T1, document))
        } + Row("d1-t2", T2, "d1")

        val TABLE = DatasetTable(
            "access_documents",
            listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT, DatasetColumn.Type.STRING),
                DatasetColumn(DOCUMENT_ID, DatasetColumn.Type.STRING),
            ),
            ROWS.map { mapOf(ID to it.id, TENANT to it.tenant, DOCUMENT_ID to it.document) },
        )

        fun callerOf(tenant: String) = QueryCaller(Caller(tenantId = tenant), entry = QueryEntry.HTTP)

        /** The restriction a node appends for [predicate], compiled on the access record's fields. */
        fun restrictionOf(predicate: AccessPredicate): FilterExpression =
            DatasetAccess.restriction(AccessFilters.compile(predicate, DatasetAccess::fieldOf))

        /** [record]'s attributes as a stream records them: the owner and space, then each declared one it holds. */
        fun accessOf(record: AccessFilterSpec.AccessRecord): Map<String, Any?> =
            linkedMapOf<String, Any?>("owner" to record.owner, "space" to record.space) +
                record.values.mapValues { (_, value) -> value?.let { written(AccessFilterSpec.stored(it)) } }

        /** [value] as JSON writes it: an enum by its name, a collection's values each so. */
        fun written(value: Any): Any = when (value) {
            is Enum<*> -> WowJson.mapper.valueToTree<JsonNode>(value).stringValue()
            is Collection<*> -> value.map { written(it!!) }
            else -> value
        }

        /** [attribute]'s value in [record], as the kernel derives it: null where the record lacks it. */
        fun valueOf(record: AccessFilterSpec.AccessRecord, attribute: AccessAttribute<*, *>): Any? = when (attribute) {
            AccessAttribute.OWNER -> record.owner
            AccessAttribute.SPACE -> record.space
            else -> record.values[attribute.name]
        }
    }
}
