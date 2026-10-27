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

package me.ahoo.wow.query

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.RecordFilter
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.resolve.FilterNormalizer
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.tck.FilterSemantics
import me.ahoo.wow.query.tck.SemanticProbe
import me.ahoo.wow.query.tck.SemanticShape
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

class RecordAdmissionTest {
    private val nodes = JsonNodeFactory.instance
    private val aggregateType = AggregateType("record", "admission")
    private val uuid = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")

    private fun record(tags: String = "{}", deleted: Boolean = false): ObjectNode =
        WowJson.mapper.readTree(
            """{"aggregateId":"a1","tenantId":"t1","ownerId":"o1","spaceId":"s1","deleted":$deleted,
           "tags":$tags,"state":{"items":[{"sku":"x"},{"sku":"y"}],"name":"n","count":2,"empty":"",
           "nothing":null,"none":[],"codes":["a","b"],"uuid":"$uuid"}}"""
        ) as ObjectNode

    /** The evaluation admission runs: on the normalized filter. */
    private fun FilterExpression.admitsRecord(record: ObjectNode): Boolean =
        RecordFilter(FilterNormalizer().normalize(this)).admits(record)

    @Test
    fun `metadata and logical nodes`() {
        val record = record()
        MatchAllFilter.admitsRecord(record).assert().isTrue()
        MatchNoneFilter.admitsRecord(record).assert().isFalse()
        AndFilter(listOf(TenantIdFilter("t1"), OwnerIdFilter("o1"), SpaceIdFilter("s1"), IdFilter("a1")))
            .admitsRecord(record).assert().isTrue()
        AndFilter(listOf(TenantIdFilter("t1"), OwnerIdFilter("other"))).admitsRecord(record).assert().isFalse()
        OrFilter(listOf(OwnerIdFilter("other"), SpaceIdFilter("s1"))).admitsRecord(record).assert().isTrue()
        NorFilter(listOf(OwnerIdFilter("o1"))).admitsRecord(record).assert().isFalse()
        DeletionFilter(DeletionState.ACTIVE).admitsRecord(record).assert().isTrue()
        DeletionFilter(DeletionState.DELETED).admitsRecord(record).assert().isFalse()
        DeletionFilter(DeletionState.ACTIVE).admitsRecord(record(deleted = true)).assert().isFalse()
        DeletionFilter(DeletionState.DELETED).admitsRecord(record(deleted = true)).assert().isTrue()
        DeletionFilter(DeletionState.ALL).admitsRecord(record(deleted = true)).assert().isTrue()
        IdsFilter(listOf("a0", "a1")).admitsRecord(record).assert().isTrue()
        IdsFilter(listOf("a0")).admitsRecord(record).assert().isFalse()
        AggregateIdFilter("a1").admitsRecord(record).assert().isTrue()
        AggregateIdsFilter(listOf("a2")).admitsRecord(record).assert().isFalse()
        // A metadata field that is not a string matches no id.
        IdFilter("a1").admitsRecord(record().put("aggregateId", 1)).assert().isFalse()
    }

    @Test
    fun `field nodes cross arrays and unsupported nodes fail closed`() {
        val record = record()
        EqualFilter(QueryField("state.items.sku"), "y").admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.items.1.sku"), "y").admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.items.0.sku"), "y").admitsRecord(record).assert().isFalse()
        EqualFilter(QueryField("state.items.9.sku"), "y").admitsRecord(record).assert().isFalse()
        EqualFilter(QueryField("state.name.length"), "y").admitsRecord(record).assert().isFalse()
        EqualFilter(QueryField("state.name"), "x").admitsRecord(record).assert().isFalse()
        ContainsFilter(QueryField("state.name"), "n").admitsRecord(record).assert().isFalse()
        GreaterThanFilter(QueryField("state.count"), 1).admitsRecord(record).assert().isFalse()
    }

    @Test
    fun `numbers compare by value whatever their json width`() {
        val record = record()
        val count = QueryField("state.count")
        EqualFilter(count, 2L).admitsRecord(record).assert().isTrue()
        EqualFilter(count, BigDecimal("2.0")).admitsRecord(record).assert().isTrue()
        EqualFilter(count, 2.0).admitsRecord(record).assert().isTrue()
        EqualFilter(count, BigInteger.TWO).admitsRecord(record).assert().isTrue()
        InFilter(count, listOf(1L, 2L)).admitsRecord(record).assert().isTrue()
        NotEqualFilter(count, 2L).admitsRecord(record).assert().isFalse()
        EqualFilter(count, 3).admitsRecord(record).assert().isFalse()
        EqualFilter(count, "2").admitsRecord(record).assert().isFalse()
        // A non-finite number compares as a double: NaN equals nothing.
        val nan = record().also { (it.get("state") as ObjectNode).set("count", nodes.numberNode(Double.NaN)) }
        EqualFilter(count, Double.NaN).admitsRecord(nan).assert().isFalse()
        val infinite = record().also {
            (it.get("state") as ObjectNode).set("count", nodes.numberNode(Double.POSITIVE_INFINITY))
        }
        EqualFilter(count, Double.POSITIVE_INFINITY).admitsRecord(infinite).assert().isTrue()
    }

    @Test
    fun `a runtime value compares as the json it serializes to`() {
        EqualFilter(QueryField("state.uuid"), uuid).admitsRecord(record()).assert().isTrue()
        InFilter(QueryField("state.uuid"), listOf(uuid)).admitsRecord(record()).assert().isTrue()
        NotInFilter(QueryField("state.uuid"), listOf(uuid)).admitsRecord(record()).assert().isFalse()
    }

    @Test
    fun `eq and ne of null are lowered before the evaluation`() {
        val record = record()
        EqualFilter(QueryField("state.missing"), null).admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.name"), null).admitsRecord(record).assert().isFalse()
        NotEqualFilter(QueryField("state.name"), null).admitsRecord(record).assert().isTrue()
        NotEqualFilter(QueryField("state.missing"), null).admitsRecord(record).assert().isFalse()
    }

    /**
     * The operators point reads support, on the shapes of the filter semantics matrix (a scalar, an array, an empty
     * array, a null and a missing field): negations match a record without the field, a scalar operand matches any
     * element of an array field, an array operand of `EQ` matches the whole array.
     */
    @Test
    fun `the point read operators follow the filter semantics`() {
        val record = record()
        fun admits(filter: FilterExpression) = filter.admitsRecord(record)
        val name = QueryField("state.name")
        val codes = QueryField("state.codes")
        val none = QueryField("state.none")
        val nothing = QueryField("state.nothing")
        val missing = QueryField("state.missing")

        admits(EqualFilter(codes, "a")).assert().isTrue()
        admits(EqualFilter(codes, listOf("a", "b"))).assert().isTrue()
        admits(EqualFilter(codes, listOf("b", "a"))).assert().isFalse()
        admits(EqualFilter(codes, listOf("a"))).assert().isFalse()
        admits(NotEqualFilter(codes, "c")).assert().isTrue()
        admits(NotEqualFilter(missing, "c")).assert().isTrue()
        admits(InFilter(codes, listOf("c", "b"))).assert().isTrue()
        admits(InFilter(name, listOf("c"))).assert().isFalse()
        admits(NotInFilter(name, listOf("c"))).assert().isTrue()
        admits(NotInFilter(missing, listOf("c"))).assert().isTrue()

        admits(IsNullFilter(nothing)).assert().isTrue()
        admits(IsNullFilter(missing)).assert().isTrue()
        admits(IsNullFilter(name)).assert().isFalse()
        admits(IsNotNullFilter(name)).assert().isTrue()
        admits(IsNotNullFilter(nothing)).assert().isFalse()
        admits(IsNotNullFilter(missing)).assert().isFalse()
        admits(ExistsFilter(nothing)).assert().isTrue()
        admits(ExistsFilter(missing)).assert().isFalse()
        admits(NotExistsFilter(missing)).assert().isTrue()
        admits(NotExistsFilter(nothing)).assert().isFalse()
        admits(IsEmptyFilter(none)).assert().isTrue()
        admits(IsEmptyFilter(codes)).assert().isFalse()
        admits(IsEmptyFilter(missing)).assert().isFalse()
        admits(IsEmptyStringFilter(QueryField("state.empty"))).assert().isTrue()
        admits(IsEmptyStringFilter(name)).assert().isFalse()
        admits(IsNotEmptyStringFilter(name)).assert().isTrue()
        admits(IsNotEmptyStringFilter(QueryField("state.empty"))).assert().isFalse()
        admits(IsNotEmptyStringFilter(missing)).assert().isFalse()
    }

    /**
     * The filter semantics matrix every backend passes, run on the in-memory evaluation: the operators point reads
     * support reproduce it exactly, and every other operator matches no record (fails closed).
     */
    @TestFactory
    fun `the filter semantics matrix`(): List<DynamicTest> = FilterSemantics.CASES.map { case ->
        DynamicTest.dynamicTest(case.id) {
            val field = QueryField("state.probe")
            val probes = checkNotNull(SemanticProbe.ALL[case.shape])
            val selected = probes.filter { probe ->
                val record = nodes.objectNode()
                record.putObject("state").apply { probe.json?.let { set("probe", WowJson.mapper.readTree(it)) } }
                case.filter(field).admitsRecord(record)
            }.map { it.name }.toSet()
            val expected = if (case.operator in POINT_READ_OPERATORS) case.matches else emptySet()
            selected.assert().describedAs(case.id).isEqualTo(expected)
        }
    }

    @Test
    fun `the matrix covers every operator point reads support on each shape it has`() {
        FilterSemantics.CASES.map { it.operator }.toSet().containsAll(POINT_READ_OPERATORS).assert().isTrue()
        FilterSemantics.CASES.map { it.shape }.toSet().assert().isEqualTo(SemanticShape.entries.toSet())
    }

    private fun admits(
        admission: QueryAdmission,
        record: ObjectNode,
        schema: QueryModelSchema? = null,
        selection: FilterExpression = IdFilter("a1"),
        scope: QueryScope = QueryScope.NONE,
    ): Boolean {
        var admitted: ObjectNode? = null
        runTest {
            admitted = admission.admitRecord(selection, record, schema, QueryCaller(entry = QueryEntry.HTTP, scope = scope))
        }
        return admitted != null
    }

    @Test
    fun `a point read runs the caller scope, the policies and the default deletion scope`() {
        val contexts = mutableListOf<QueryContext<*>>()
        fun policy(filter: FilterExpression) = QueryPolicy { _, context ->
            contexts += context
            filter
        }
        val schema = boundSchemaFixture(objectFixture("state" to objectFixture("name" to scalarFixture())))
        val open = QueryAdmission(aggregateType)
        admits(open, record()).assert().isTrue()
        admits(open, record(), scope = QueryScope(authenticated = TenantIdFilter("t1"))).assert().isTrue()
        admits(open, record(), scope = QueryScope(declared = OwnerIdFilter("other"))).assert().isFalse()
        admits(open, record(), selection = IdFilter("a2")).assert().isFalse()

        val restricted = QueryAdmission(aggregateType, policies = listOf(policy(OwnerIdFilter("o1"))))
        admits(restricted, record(), schema).assert().isTrue()
        admits(QueryAdmission(aggregateType, policies = listOf(policy(OwnerIdFilter("x")))), record(), schema)
            .assert().isFalse()
        contexts.map { it.queryType to it.entry }.distinct().assert().containsExactly(
            QueryType.SINGLE to QueryEntry.HTTP
        )
        (contexts.first().query as SingleQuery).filter.assert().isEqualTo(IdFilter("a1"))

        // The model default hides a deleted record unless the selection or a policy states a deletion scope.
        admits(open, record(deleted = true)).assert().isFalse()
        admits(
            open,
            record(deleted = true),
            selection = AndFilter(listOf(IdFilter("a1"), DeletionFilter(DeletionState.ALL)))
        )
            .assert().isTrue()
        admits(
            QueryAdmission(aggregateType, policies = listOf(policy(DeletionFilter(DeletionState.DELETED)))),
            record(deleted = true),
            schema,
        ).assert().isTrue()
    }

    @Test
    fun `a point read resolves aliases and needs the schema for policies`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "state" to objectFixture(
                    "name" to QueryValueSchema(
                        kind = scalarFixture().kind,
                        valueTypes = scalarFixture().valueTypes,
                        aliases = setOf(QueryField("state.title")),
                    ),
                ),
            ),
        )
        val admission = QueryAdmission(aggregateType)
        val scope = QueryScope(declared = EqualFilter(QueryField("state.title"), "n"))
        admits(admission, record(), schema, scope = scope).assert().isTrue()
        // Without the schema, the alias names a field the record does not have.
        admits(admission, record(), scope = scope).assert().isFalse()

        val error = runCatching {
            runTest {
                QueryAdmission(aggregateType, policies = listOf(QueryPolicy { _, _ -> MatchAllFilter }))
                    .admitRecord(IdFilter("a1"), record(), null, QueryCaller.IN_PROCESS)
            }
        }.exceptionOrNull()
        error.assert().isInstanceOf(QueryExecutionException::class.java)
        error!!.message.assert()
            .isEqualTo("Point-read admission needs the snapshot query schema to evaluate query policies.")
    }

    @Test
    fun `a point read runs the entry gates of a query route`() {
        val required = QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(requireAuthenticatedScope = true))
        assertThrows<QueryScopeRequiredException> { admits(required, record()) }
        admits(required, record(), scope = QueryScope(authenticated = TenantIdFilter("t1"))).assert().isTrue()
        val explicit = QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(requireExplicitEntry = true))
        assertThrows<QueryRequestException> {
            runTest { explicit.admitRecord(IdFilter("a1"), record(), null, QueryCaller.UNSPECIFIED) }
        }
    }

    private companion object {
        val POINT_READ_OPERATORS = setOf(
            FilterOperator.EQ,
            FilterOperator.NE,
            FilterOperator.IN,
            FilterOperator.NOT_IN,
            FilterOperator.IS_NULL,
            FilterOperator.IS_NOT_NULL,
            FilterOperator.EXISTS,
            FilterOperator.NOT_EXISTS,
            FilterOperator.IS_EMPTY,
            FilterOperator.IS_EMPTY_STRING,
            FilterOperator.IS_NOT_EMPTY_STRING,
        )
    }
}
