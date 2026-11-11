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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DAY
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DECLARATION
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.OTHER_TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.TENANT
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The fixtures and hooks of the snapshot contract ([QueryBackendSpec], the class a backend's test extends): the
 * backend's hooks ([store], [binding], [semanticDivergences]), the test's own aggregate [type], the [Fixture] a test
 * queries, and the snapshots it stores ([snapshot], [orders]).
 *
 * The contract's cases come in families, one class each: [SnapshotFilterCases], [SnapshotRecordCases],
 * [SnapshotKeysetCases], [SnapshotAggregationCases] and [SnapshotDateBucketCases], then [QueryBackendSpec] with the
 * timeouts and the gateway. They form one chain, in that order, each extending the one before, because a class has
 * one superclass and JUnit runs the cases a test class inherits. The order means nothing: every family reads only
 * this class's fixtures, hooks and constants, and the suite's public constants on [QueryBackendSpec], never a member
 * of the family it extends, so any order would run the same cases.
 */
abstract class SnapshotQueryFixtures internal constructor() {
    /** Stores [snapshots], all of [type], where the backend under test reads them. */
    protected abstract suspend fun store(snapshots: List<SnapshotRecord>)

    /** The query backend of [type]'s snapshots, with its storage adapter. */
    protected abstract fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>

    /** The ids of the [FilterSemantics] cases this backend is known to diverge on; they are skipped. */
    protected open val semanticDivergences: Set<String> = emptySet()

    /** The aggregate type of this test: a fresh context, so each test has its own records. */
    protected val type: AggregateType =
        AggregateType("tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX), "order")

    private val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)

    /** The backend under test and the schema compiled over its facts, once [snapshots] are stored. */
    protected class Fixture @InternalWowApi constructor(
        val backend: QueryBackend,
        @property:InternalWowApi val schema: QueryModelSchema,
    ) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            list(ListQuery(filter, sort = sort, limit = MAX_LIST)).map { it.id() }

        suspend fun aggregate(query: AggregationQuery): List<ObjectNode> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    protected suspend fun given(snapshots: List<SnapshotRecord>): Fixture {
        store(snapshots)
        val binding = binding(type)
        return Fixture(binding.backend, schemaOf(binding))
    }

    /**
     * The plan the host makes of [indexes], each a name and its keys (logical paths of the spec's order state, such as
     * `state.status`), on the schema this backend's facts compile to: what the backend's
     * [SnapshotQueries.manageIndexes][me.ahoo.wow.query.store.SnapshotQueries.manageIndexes] receives. Fails, as the
     * host's planning does, on a key the model does not know or the storage cannot index.
     */
    protected suspend fun indexPlan(vararg indexes: Pair<String, List<String>>): QueryIndexPlan =
        QueryIndexPlan.of(schemaOf(binding(type)), indexes.map { (name, keys) -> QueryIndexDeclaration(name, keys) })

    private suspend fun schemaOf(binding: QueryBackendBinding<QueryBackend>): QueryModelSchema =
        DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            binding.storage,
        ).schema()

    /** A snapshot of this test's [type] whose state is [state], a JSON object. */
    protected fun snapshot(
        id: String,
        state: String,
        tenantId: String = TENANT,
        scope: Scope = Scope("owner-1"),
        deleted: Boolean = false,
        version: Int = 1,
        lastEventTime: Long = T0,
        access: Map<String, Any?> = emptyMap(),
        operator: Operator? = null,
        firstOperator: Operator? = operator,
        schedules: Map<String, ScheduleRule> = emptyMap(),
    ) = SnapshotRecord(
        ref = type.ref(tenantId, id),
        version = version,
        schemaFingerprint = "tck",
        state = state,
        scope = scope,
        deleted = deleted,
        firstEventTime = T0 - DAY,
        lastEventTime = lastEventTime,
        access = access,
        operator = operator,
        firstOperator = firstOperator,
        schedules = schedules,
    )

    /** Five orders over two tenants, one deleted, one with access attributes: the records most tests read. */
    protected fun orders(): List<SnapshotRecord> = listOf(
        snapshot(
            "o1",
            """{"name":"Alpha","status":"PAID","amount":10.5,"quantity":1,"createdAt":$T0,"keywords":["a","b"],""" +
                """"lines":[{"sku":"A","price":5.0,"quantity":1},{"sku":"B","price":5.5,"quantity":2}]}""",
        ),
        snapshot(
            "o2",
            """{"name":"Beta","status":"PAID","amount":20,"quantity":2,"createdAt":${T0 + DAY},"keywords":["b"],""" +
                """"lines":[{"sku":"A","price":20,"quantity":1}]}""",
            scope = Scope("owner-2"),
            version = 2,
            lastEventTime = T0 + DAY,
        ),
        snapshot(
            "o3",
            """{"name":"Gamma","status":"NEW","amount":30,"quantity":2,"createdAt":${T0 + 2 * DAY},"keywords":[],""" +
                """"lines":[]}""",
            tenantId = OTHER_TENANT,
            scope = Scope("owner-1", "space-1"),
            version = 3,
            lastEventTime = T0 + 2 * DAY,
        ),
        snapshot(
            "o4",
            """{"name":"Delta","status":"NEW","amount":40,"quantity":3,"createdAt":${T0 + 3 * DAY},""" +
                """"lines":[{"sku":"C","price":40,"quantity":4}]}""",
            deleted = true,
            version = 4,
            lastEventTime = T0 + 3 * DAY,
        ),
        snapshot(
            "o5",
            """{"name":"Epsilon","status":"CANCELLED","amount":50,"quantity":3,"createdAt":${T0 + 4 * DAY},""" +
                """"lines":[{"sku":"B","price":10,"quantity":5}]}""",
            scope = Scope.NONE,
            version = 5,
            lastEventTime = T0 + 4 * DAY,
            access = mapOf("region" to "east", "teams" to listOf("a", "b")),
        ),
    )

    internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    internal fun numeric(function: AggregationFunction, alias: String) =
        AggregationMetric.Numeric(function, AggregationExpression.Field(AMOUNT), alias)

    internal companion object {
        const val CONTEXT_SUFFIX = 12
        const val MAX_LIST = 100
        val AMOUNT = QueryField("state.amount")
        val KEYWORDS = QueryField("state.keywords")

        fun ObjectNode.id(): String = path("aggregateId").stringValue()
    }
}
