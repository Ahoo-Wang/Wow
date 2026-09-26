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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.spec.FilterSemantics
import me.ahoo.wow.api.query.spec.SemanticProbe
import me.ahoo.wow.api.query.spec.SemanticShape
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.POJONode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal

class RecordAdmissionTest {
    private val nodes = JsonNodeFactory.instance
    private val namedAggregate = MaterializedNamedAggregate("record", "admission")

    private fun record(tags: String = "{}", deleted: Boolean = false): ObjectNode =
        """{"aggregateId":"a1","tenantId":"t1","ownerId":"o1","spaceId":"s1","deleted":$deleted,
           "tags":$tags,"state":{"items":[{"sku":"x"},{"sku":"y"}],"name":"n","count":2}}""".toJsonNode()

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
        DeletionFilter(DeletionState.ALL).admitsRecord(record(deleted = true)).assert().isTrue()
    }

    @Test
    fun `field nodes cross arrays and unsupported nodes fail closed`() {
        val record = record()
        EqualFilter(QueryField("state.items.sku"), StringNode.valueOf("y")).admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.items.1.sku"), StringNode.valueOf("y")).admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.items.0.sku"), StringNode.valueOf("y")).admitsRecord(record).assert().isFalse()
        EqualFilter(QueryField("state.name"), StringNode.valueOf("x")).admitsRecord(record).assert().isFalse()
        ContainsFilter(QueryField("state.name"), "n").admitsRecord(record).assert().isFalse()
    }

    @Test
    fun `numbers compare by value whatever their json width`() {
        val record = record()
        val count = QueryField("state.count")
        EqualFilter(count, nodes.numberNode(2L)).admitsRecord(record).assert().isTrue()
        EqualFilter(count, nodes.numberNode(BigDecimal("2.0"))).admitsRecord(record).assert().isTrue()
        EqualFilter(count, nodes.numberNode(2.0)).admitsRecord(record).assert().isTrue()
        InFilter(count, listOf(nodes.numberNode(1L), nodes.numberNode(2L))).admitsRecord(record).assert().isTrue()
        NotEqualFilter(count, nodes.numberNode(2L)).admitsRecord(record).assert().isFalse()
        EqualFilter(count, IntNode.valueOf(3)).admitsRecord(record).assert().isFalse()
        EqualFilter(count, StringNode.valueOf("2")).admitsRecord(record).assert().isFalse()
    }

    @Test
    fun `a value wrapped as a java object compares as the json it serializes to`() {
        EqualFilter(QueryField("state.name"), POJONode("n")).admitsRecord(record()).assert().isTrue()
        InFilter(QueryField("state.count"), listOf(POJONode(2))).admitsRecord(record()).assert().isTrue()
    }

    @Test
    fun `eq and ne of null are lowered before the evaluation`() {
        val record = record()
        EqualFilter(QueryField("state.missing"), NullNode.instance).admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.name"), NullNode.instance).admitsRecord(record).assert().isFalse()
        NotEqualFilter(QueryField("state.name"), NullNode.instance).admitsRecord(record).assert().isTrue()
        NotEqualFilter(QueryField("state.missing"), NullNode.instance).admitsRecord(record).assert().isFalse()
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
                record.putObject("state").apply { probe.value?.let { set("probe", it) } }
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

    @Test
    fun `abac policy filters match the documented table`() {
        val principal = mapOf("dept" to listOf("eng", "ops")).toFilterExpression()
        principal.admitsRecord(record("""{"dept":["eng"]}""")).assert().isTrue()
        principal.admitsRecord(record("""{"dept":["hr"]}""")).assert().isFalse()
        principal.admitsRecord(record("""{}""")).assert().isTrue()
        principal.admitsRecord(record("""{"dept":[]}""")).assert().isTrue()
        val strict = mapOf("dept" to listOf("eng")).toFilterExpression(matchMissingTagKey = false)
        strict.admitsRecord(record("""{}""")).assert().isFalse()
        strict.admitsRecord(record("""{"dept":[]}""")).assert().isFalse()
        strict.admitsRecord(record("""{"dept":["eng"]}""")).assert().isTrue()
        val wildcard = mapOf("dept" to listOf("*")).toFilterExpression()
        wildcard.admitsRecord(record("""{"dept":["hr"]}""")).assert().isTrue()
        wildcard.admitsRecord(record("""{}""")).assert().isFalse()
    }

    @Test
    fun `maskRecord applies the schema masks and leaves an unmasked schema's record alone`() {
        val mask = MaskRule(SensitivityLevel.DISPLAY)
        val masked = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture(mask = mask))))
        masked.maskRecord("""{"state":{"secret":"abc"}}""".toJsonNode())["state"]["secret"].stringValue()
            .assert().isEqualTo("***")
        val plain = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture())))
        plain.maskRecord("""{"state":{"secret":"abc"}}""".toJsonNode())["state"]["secret"].stringValue()
            .assert().isEqualTo("abc")
    }

    private fun admits(
        admission: QueryAdmission,
        record: ObjectNode,
        schema: QueryModelSchema? = null,
        selection: FilterExpression = IdFilter("a1"),
        scope: QueryScope = QueryScope.NONE,
    ): Boolean = admission.admitRecord(selection, record, schema)
        .contextWrite { it.withQueryScope(scope).withQueryEntry(QueryEntry.HTTP) }
        .blockOptional().isPresent

    @Test
    fun `a point read runs the caller scope, the policies and the default deletion scope`() {
        val contexts = mutableListOf<QueryContext<*>>()
        fun policy(filter: FilterExpression) = QueryPolicy { _, context ->
            contexts += context
            Mono.just(filter)
        }
        val schema = boundSchemaFixture(objectFixture("state" to objectFixture("name" to scalarFixture())))
        val open = QueryAdmission(namedAggregate)
        admits(open, record()).assert().isTrue()
        admits(open, record(), scope = QueryScope(authenticated = TenantIdFilter("t1"))).assert().isTrue()
        admits(open, record(), scope = QueryScope(declared = OwnerIdFilter("other"))).assert().isFalse()
        admits(open, record(), selection = IdFilter("a2")).assert().isFalse()

        val restricted = QueryAdmission(namedAggregate, policies = listOf(policy(OwnerIdFilter("o1"))))
        admits(restricted, record(), schema).assert().isTrue()
        admits(QueryAdmission(namedAggregate, policies = listOf(policy(OwnerIdFilter("x")))), record(), schema)
            .assert().isFalse()
        contexts.map { it.queryType to it.entry }.distinct().assert().containsExactly(
            QueryType.SINGLE to QueryEntry.HTTP
        )
        (contexts.first().query as ISingleQuery).filter.assert().isEqualTo(IdFilter("a1"))

        // The model default hides a deleted record unless the selection or a policy states a deletion scope.
        admits(open, record(deleted = true)).assert().isFalse()
        admits(
            open,
            record(deleted = true),
            selection = AndFilter(listOf(IdFilter("a1"), DeletionFilter(DeletionState.ALL)))
        )
            .assert().isTrue()
        admits(
            QueryAdmission(namedAggregate, policies = listOf(policy(DeletionFilter(DeletionState.DELETED)))),
            record(deleted = true),
            schema,
        ).assert().isTrue()
    }

    @Test
    fun `a point read resolves aliases, masks the record and needs the schema for policies`() {
        val mask = MaskRule(SensitivityLevel.DISPLAY)
        val schema = boundSchemaFixture(
            objectFixture(
                "state" to objectFixture(
                    "name" to QueryValueSchema(
                        kind = scalarFixture().kind,
                        valueTypes = scalarFixture().valueTypes,
                        maskRule = mask,
                        aliases = setOf(QueryField("state.title")),
                    ),
                ),
            ),
        )
        val admission = QueryAdmission(namedAggregate)
        val scope = QueryScope(declared = EqualFilter(QueryField("state.title"), StringNode.valueOf("n")))
        admission.admitRecord(IdFilter("a1"), record(), schema)
            .contextWrite { it.withQueryScope(scope) }
            .block()!!["state"]["name"].stringValue().assert().isNotEqualTo("n")
        admits(admission, record(), scope = scope).assert().isFalse()

        QueryAdmission(namedAggregate, policies = listOf(QueryPolicy { _, _ -> Mono.just(MatchAllFilter) }))
            .admitRecord(IdFilter("a1"), record(), null).test()
            .expectErrorMessage("Point-read admission needs the snapshot query schema to evaluate query policies.")
            .verify()
        QueryAdmission(namedAggregate, policies = listOf(QueryPolicy { _, _ -> Mono.empty() }))
            .admitRecord(IdFilter("a1"), record(), schema).test()
            .expectErrorMessage("QueryPolicy must emit one filter.")
            .verify()
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
