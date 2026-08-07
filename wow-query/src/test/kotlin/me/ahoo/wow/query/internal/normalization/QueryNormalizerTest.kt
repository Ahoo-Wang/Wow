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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.admission.RawAdmissionGuard
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.function.Consumer

class QueryNormalizerTest {

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val guard = RawAdmissionGuard(QueryAdmissionLimits.DEFAULT)
    private val fixedInstant = Instant.parse("2024-03-10T06:30:00Z")
    private val zoneId = ZoneId.of("America/New_York")

    @Test
    fun `should normalize all wire operators or reject native explicitly`() {
        Operator.entries.forEach { operator ->
            val invocation = countInvocation(validCondition(operator))
            if (operator == Operator.RAW) {
                assertThrownBy<QueryRejectedException> {
                    QueryNormalizer(Clock.fixed(fixedInstant, zoneId)).normalize(guard.admit(invocation))
                }.satisfies(
                    Consumer { error ->
                        error.rejection.category.assert().isEqualTo(QueryRejectionCategory.UNSUPPORTED_FEATURE)
                        error.rejection.code.assert().isEqualTo(QueryRejectionCode.NATIVE_BACKEND_UNBOUND)
                    }
                )
            } else {
                QueryNormalizer(Clock.fixed(fixedInstant, zoneId)).normalize(guard.admit(invocation))
            }
        }
    }

    @Test
    fun `should normalize system fields without guessing ordinary id paths`() {
        val condition = Condition.and(
            Condition.id("identity"),
            Condition.ids("identity-1", "identity-2"),
            Condition.aggregateId("aggregate"),
            Condition.aggregateIds("aggregate-1", "aggregate-2"),
            Condition.tenantId("tenant"),
            Condition.ownerId("owner"),
            Condition.spaceId("space"),
            Condition.deleted(DeletionState.ACTIVE),
            Condition.eq("id", "element-or-state-id"),
        )

        val normalized = normalizeCount(condition)
        val children = (normalized as NormalizedCondition.Junction).children

        (children[0] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.System(SystemFieldKind.IDENTITY),
        )
        (children[2] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.System(SystemFieldKind.AGGREGATE_ID),
        )
        (children[4] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.System(SystemFieldKind.TENANT_ID),
        )
        (children[7] as NormalizedCondition.Predicate).operator.assert().isEqualTo(PredicateOperator.IS_FALSE)
        (children[8] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("id"), PathBasis.ROOT),
        )
    }

    @Test
    fun `should normalize element match fields relative to each element scope`() {
        val condition = Condition.elemMatch(
            "items",
            Condition.and(
                Condition.eq("items.sku", "sku-a"),
                Condition.eq("id", "line-id"),
                Condition.elemMatch(
                    "attributes",
                    Condition.and(
                        Condition.eq("name", "size"),
                        Condition.eq("attributes.code", "size-code"),
                        Condition.eq("items.attributes.label", "size-label"),
                    ),
                ),
            ),
        )

        val normalized = normalizeCount(condition) as NormalizedCondition.ElementMatch
        normalized.field.assert().isEqualTo(LogicalField.Path(listOf("items"), PathBasis.ROOT))
        val children = (normalized.condition as NormalizedCondition.Junction).children
        (children[0] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("sku"), PathBasis.CURRENT_ELEMENT),
        )
        (children[1] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("id"), PathBasis.CURRENT_ELEMENT),
        )
        val nested = children[2] as NormalizedCondition.ElementMatch
        nested.field.assert().isEqualTo(
            LogicalField.Path(listOf("attributes"), PathBasis.CURRENT_ELEMENT),
        )
        val nestedChildren = (nested.condition as NormalizedCondition.Junction).children
        (nestedChildren[0] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("name"), PathBasis.CURRENT_ELEMENT),
        )
        (nestedChildren[1] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("code"), PathBasis.CURRENT_ELEMENT),
        )
        (nestedChildren[2] as NormalizedCondition.Predicate).field.assert().isEqualTo(
            LogicalField.Path(listOf("label"), PathBasis.CURRENT_ELEMENT),
        )
    }

    @Test
    fun `should resolve absolute and qualified prefixes through three element levels`() {
        val condition = Condition.elemMatch(
            "items",
            Condition.elemMatch(
                "attributes",
                Condition.elemMatch(
                    "attributes.values",
                    Condition.and(
                        Condition.eq("value", "large"),
                        Condition.eq("values.code", "large-code"),
                        Condition.eq("attributes.values.label", "large-label"),
                        Condition.eq("items.attributes.values.kind", "size-kind"),
                    ),
                ),
            ),
        )

        val firstLevel = normalizeCount(condition) as NormalizedCondition.ElementMatch
        val secondLevel = firstLevel.condition as NormalizedCondition.ElementMatch
        val thirdLevel = secondLevel.condition as NormalizedCondition.ElementMatch
        thirdLevel.field.assert().isEqualTo(LogicalField.Path(listOf("values"), PathBasis.CURRENT_ELEMENT))
        val fields = (thirdLevel.condition as NormalizedCondition.Junction).children.map { child ->
            (child as NormalizedCondition.Predicate).field
        }
        fields.assert().containsExactly(
            LogicalField.Path(listOf("value"), PathBasis.CURRENT_ELEMENT),
            LogicalField.Path(listOf("code"), PathBasis.CURRENT_ELEMENT),
            LogicalField.Path(listOf("label"), PathBasis.CURRENT_ELEMENT),
            LogicalField.Path(listOf("kind"), PathBasis.CURRENT_ELEMENT),
        )
    }

    @Test
    fun `should reject system operators inside element scope`() {
        val condition = Condition.elemMatch("items", Condition.id("line-id"))

        assertThrownBy<QueryRejectedException> {
            normalizeCount(condition)
        }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.INVALID_QUERY)
                error.rejection.code.assert().isEqualTo(QueryRejectionCode.SYSTEM_FIELD_IN_ELEMENT_SCOPE)
                error.rejection.path.toString().assert().isEqualTo("$.input.condition.children[0]")
            }
        )
    }

    @Test
    fun `should freeze clock once and expand time macros as DST safe half open ranges`() {
        val clock = CountingClock(fixedInstant, zoneId)
        val condition = Condition.and(
            Condition.today("createdAt"),
            Condition.tomorrow("updatedAt"),
        )
        val admitted = guard.admit(countInvocation(condition))

        val normalized = QueryNormalizer(clock).normalize(admitted)
        val children = ((normalized.input as NormalizedQueryInput.Count).userCondition as NormalizedCondition.Junction)
            .children

        clock.instantReads.assert().isEqualTo(1)
        children[0].assert().isEqualTo(
            halfOpenRange(
                "createdAt",
                Instant.parse("2024-03-10T05:00:00Z"),
                Instant.parse("2024-03-11T04:00:00Z"),
            ),
        )
        children[1].assert().isEqualTo(
            halfOpenRange(
                "updatedAt",
                Instant.parse("2024-03-11T04:00:00Z"),
                Instant.parse("2024-03-12T04:00:00Z"),
            ),
        )
    }

    @Test
    fun `should normalize search and Mongo baseline empty collection constants`() {
        normalizeCount(Condition.match("description", "distributed systems")).assert().isEqualTo(
            NormalizedCondition.Search(SearchScope("description"), "distributed systems"),
        )
        normalizeCount(Condition("field", Operator.IN, emptyList<Any>())).assert()
            .isEqualTo(NormalizedCondition.None)
        normalizeCount(Condition("field", Operator.NOT_IN, emptyList<Any>())).assert()
            .isEqualTo(NormalizedCondition.All)
        normalizeCount(Condition("field", Operator.ALL_IN, emptyList<Any>())).assert()
            .isEqualTo(NormalizedCondition.None)

        val allIn = normalizeCount(
            Condition("field", Operator.ALL_IN, listOf(1, 1L, 1.0)),
        ) as NormalizedCondition.Predicate
        (allIn.value as NormalizedValue.ListValue).values.assert().containsExactly(NormalizedValue.Int64(1))

        val orderedDocument = linkedMapOf<String, Any>("a" to 1, "b" to 2)
        val reversedDocument = linkedMapOf<String, Any>("b" to 2, "a" to 1)
        val documentIn = normalizeCount(
            Condition("field", Operator.IN, listOf(orderedDocument, reversedDocument)),
        ) as NormalizedCondition.Predicate
        (documentIn.value as NormalizedValue.ListValue).values.assert().containsExactly(
            NormalizedValue.ObjectValue(
                linkedMapOf("a" to NormalizedValue.Int64(1), "b" to NormalizedValue.Int64(2)),
            ),
            NormalizedValue.ObjectValue(
                linkedMapOf("b" to NormalizedValue.Int64(2), "a" to NormalizedValue.Int64(1)),
            ),
        )
    }

    @Test
    fun `should map field predicate operators without backend escaping`() {
        val comparisonOperators = mapOf(
            Operator.EQ to PredicateOperator.EQ,
            Operator.NE to PredicateOperator.NE,
            Operator.GT to PredicateOperator.GT,
            Operator.LT to PredicateOperator.LT,
            Operator.GTE to PredicateOperator.GTE,
            Operator.LTE to PredicateOperator.LTE,
            Operator.IN to PredicateOperator.IN,
            Operator.NOT_IN to PredicateOperator.NOT_IN,
            Operator.ALL_IN to PredicateOperator.ALL_IN,
            Operator.BETWEEN to PredicateOperator.BETWEEN,
        )
        comparisonOperators.forEach { (wire, expected) ->
            val rawValue = if (wire in COLLECTION_OPERATORS) listOf(1, 2) else 1
            val predicate = normalizeCount(Condition("field", wire, rawValue)) as NormalizedCondition.Predicate
            predicate.operator.assert().isEqualTo(expected)
        }

        val literal = normalizeCount(Condition.contains("field", "*?\\", ignoreCase = true))
            as NormalizedCondition.Predicate
        literal.value.assert().isEqualTo(NormalizedValue.Text("*?\\"))
        literal.operator.assert().isEqualTo(PredicateOperator.CONTAINS)
        literal.options.caseSensitivity.assert().isEqualTo(CaseSensitivity.INSENSITIVE)
    }

    @Test
    fun `should normalize constant boolean deletion and junction truth tables`() {
        val fieldless = mapOf(
            Operator.NULL to PredicateOperator.IS_NULL,
            Operator.NOT_NULL to PredicateOperator.NOT_NULL,
            Operator.TRUE to PredicateOperator.IS_TRUE,
            Operator.FALSE to PredicateOperator.IS_FALSE,
        )
        fieldless.forEach { (wire, expected) ->
            val predicate = normalizeCount(Condition("field", wire)) as NormalizedCondition.Predicate
            predicate.operator.assert().isEqualTo(expected)
            predicate.value.assert().isNull()
        }
        (normalizeCount(Condition.exists("field", false)) as NormalizedCondition.Predicate).value.assert()
            .isEqualTo(NormalizedValue.BooleanValue(false))
        (normalizeCount(Condition.deleted(true)) as NormalizedCondition.Predicate).operator.assert()
            .isEqualTo(PredicateOperator.IS_TRUE)
        (normalizeCount(Condition.deleted(false)) as NormalizedCondition.Predicate).operator.assert()
            .isEqualTo(PredicateOperator.IS_FALSE)
        normalizeCount(Condition.deleted(DeletionState.ALL)).assert().isEqualTo(NormalizedCondition.All)
        normalizeCount(Condition.nor(Condition.ALL)).assert().isEqualTo(NormalizedCondition.None)
        normalizeCount(Condition.nor(Condition("field", Operator.IN, emptyList<Any>()))).assert()
            .isEqualTo(NormalizedCondition.All)
        normalizeCount(Condition(operator = Operator.IDS, value = emptyList<String>())).assert()
            .isEqualTo(NormalizedCondition.None)
        normalizeCount(Condition(operator = Operator.AGGREGATE_IDS, value = emptyList<String>())).assert()
            .isEqualTo(NormalizedCondition.None)
    }

    @Test
    fun `should expand week month and relative time operators from the frozen instant`() {
        normalizeCount(Condition.thisWeek("field")).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-03-04T05:00:00Z"), Instant.parse("2024-03-11T04:00:00Z")),
        )
        normalizeCount(Condition.nextWeek("field")).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-03-11T04:00:00Z"), Instant.parse("2024-03-18T04:00:00Z")),
        )
        normalizeCount(Condition.lastWeek("field")).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-02-26T05:00:00Z"), Instant.parse("2024-03-04T05:00:00Z")),
        )
        normalizeCount(Condition.thisMonth("field")).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-03-01T05:00:00Z"), Instant.parse("2024-04-01T04:00:00Z")),
        )
        normalizeCount(Condition.lastMonth("field")).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-02-01T05:00:00Z"), Instant.parse("2024-03-01T05:00:00Z")),
        )
        normalizeCount(Condition.recentDays("field", 2)).assert().isEqualTo(
            halfOpenRange("field", Instant.parse("2024-03-09T05:00:00Z"), Instant.parse("2024-03-11T04:00:00Z")),
        )
        normalizeCount(Condition.earlierDays("field", 2)).assert().isEqualTo(
            NormalizedCondition.Predicate(
                LogicalField.Path(listOf("field"), PathBasis.ROOT),
                PredicateOperator.LT,
                NormalizedValue.InstantValue(Instant.parse("2024-03-09T05:00:00Z")),
            ),
        )
        normalizeCount(Condition.beforeToday("field", "12:30:00")).assert().isEqualTo(
            NormalizedCondition.Predicate(
                LogicalField.Path(listOf("field"), PathBasis.ROOT),
                PredicateOperator.LT,
                NormalizedValue.InstantValue(Instant.parse("2024-03-10T16:30:00Z")),
            ),
        )
    }

    @Test
    fun `should apply explicit time zone and date pattern without backend types`() {
        val condition = Condition(
            field = "field",
            operator = Operator.TODAY,
            options = mapOf(
                Condition.ZONE_ID_OPTION_KEY to "UTC",
                Condition.DATE_PATTERN_OPTION_KEY to "yyyy-MM-dd HH:mm",
            ),
        )

        normalizeCount(condition).assert().isEqualTo(
            NormalizedCondition.Junction(
                JunctionOperator.AND,
                listOf(
                    NormalizedCondition.Predicate(
                        LogicalField.Path(listOf("field"), PathBasis.ROOT),
                        PredicateOperator.GTE,
                        NormalizedValue.Text("2024-03-10 00:00"),
                    ),
                    NormalizedCondition.Predicate(
                        LogicalField.Path(listOf("field"), PathBasis.ROOT),
                        PredicateOperator.LT,
                        NormalizedValue.Text("2024-03-11 00:00"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `should reject an admitted formatter that cannot format the frozen instant`() {
        val formatter = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 1)
            .toFormatter()
        val condition = Condition(
            field = "field",
            operator = Operator.TODAY,
            options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to formatter),
        )

        assertRejected(QueryRejectionCode.INVALID_OPTION_VALUE, "$.input.condition.options['datePattern']") {
            normalizeCount(condition)
        }
    }

    @Test
    fun `should return typed rejections for malformed search element scope and temporal overflow`() {
        listOf("", "   ").forEach { text ->
            assertRejected(QueryRejectionCode.INVALID_VALUE_TYPE, "$.input.condition.value") {
                normalizeCount(Condition.match("field", text))
            }
        }
        assertRejected(QueryRejectionCode.INVALID_FIELD, "$.input.condition.children[0].field") {
            normalizeCount(Condition.elemMatch("items", Condition.eq("items", "value")))
        }
        listOf(Operator.RECENT_DAYS, Operator.EARLIER_DAYS).forEach { operator ->
            assertRejected(QueryRejectionCode.INVALID_TIME_VALUE, "$.input.condition.value") {
                normalizeCount(Condition("field", operator, Long.MAX_VALUE))
            }
        }
        assertRejected(
            QueryRejectionCode.NATIVE_BACKEND_UNBOUND,
            "$.input.condition",
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
        ) {
            normalizeCount(Condition.raw("{}"))
        }
    }

    @Test
    fun `should normalize projection sort list limit and long page offset`() {
        val pageInvocation = QueryInvocation(
            target = target,
            operation = QueryOperation.PAGE,
            resultShape = QueryResultShape.DYNAMIC,
            input = QueryInput.Page(
                PagedQuery(
                    condition = Condition.ALL,
                    projection = Projection(include = listOf("state.name", "state.amount")),
                    sort = listOf(
                        Sort("state.amount", Sort.Direction.DESC),
                        Sort("id", Sort.Direction.ASC),
                    ),
                    pagination = Pagination(Int.MAX_VALUE, Int.MAX_VALUE),
                ),
            ),
        )

        val normalized = normalize(pageInvocation)
        val page = normalized.input as NormalizedQueryInput.Page
        val projection = page.query.projection as NormalizedProjection.Include

        projection.fields.values.assert().containsExactly(
            LogicalField.Path(listOf("state", "name"), PathBasis.ROOT),
            LogicalField.Path(listOf("state", "amount"), PathBasis.ROOT),
        )
        page.query.sort.map { it.direction }.assert().containsExactly(
            NormalizedSortDirection.DESC,
            NormalizedSortDirection.ASC,
        )
        page.page.offset.assert().isEqualTo(
            (Int.MAX_VALUE.toLong() - 1) * Int.MAX_VALUE.toLong(),
        )

        val listInvocation = QueryInvocation(
            target = target,
            operation = QueryOperation.STREAM,
            resultShape = QueryResultShape.TYPED,
            input = QueryInput.Stream(ListQuery(Condition.ALL, limit = 0)),
        )
        (normalize(listInvocation).input as NormalizedQueryInput.Stream).limit.assert().isEqualTo(0)
    }

    @Test
    fun `should preserve mixed projection for planner policy and reject invalid limit or page`() {
        val mixed = SingleQuery(
            condition = Condition.ALL,
            projection = Projection(include = listOf("state.name"), exclude = listOf("state.secret")),
        )
        val normalizedMixed = normalize(
            QueryInvocation(
                target,
                QueryOperation.SINGLE,
                QueryResultShape.TYPED,
                QueryInput.Single(mixed),
            ),
        )
        val projection = (normalizedMixed.input as NormalizedQueryInput.Single).query.projection
        projection.assert().isInstanceOf(NormalizedProjection.Mixed::class.java)

        assertAdmissionRejected(QueryRejectionCode.INVALID_LIMIT) {
            guard.admit(
                QueryInvocation(
                    target,
                    QueryOperation.STREAM,
                    QueryResultShape.TYPED,
                    QueryInput.Stream(ListQuery(Condition.ALL, limit = -1)),
                ),
            )
        }
        listOf(Pagination(index = 0, size = 10), Pagination(index = 1, size = 0)).forEach { page ->
            assertAdmissionRejected(QueryRejectionCode.INVALID_PAGE) {
                guard.admit(
                    QueryInvocation(
                        target,
                        QueryOperation.PAGE,
                        QueryResultShape.TYPED,
                        QueryInput.Page(PagedQuery(Condition.ALL, pagination = page)),
                    ),
                )
            }
        }
    }

    private fun normalize(invocation: QueryInvocation): NormalizedQueryInvocation =
        QueryNormalizer(Clock.fixed(fixedInstant, zoneId)).normalize(guard.admit(invocation))

    private fun normalizeCount(condition: Condition): NormalizedCondition =
        (normalize(countInvocation(condition)).input as NormalizedQueryInput.Count).userCondition

    private fun countInvocation(condition: Condition): QueryInvocation =
        QueryInvocation(
            target = target,
            operation = QueryOperation.COUNT,
            resultShape = QueryResultShape.COUNT,
            input = QueryInput.Count(condition),
        )

    private fun assertAdmissionRejected(code: QueryRejectionCode, action: () -> Any?) {
        assertThrownBy<QueryRejectedException> {
            action()
        }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.INVALID_QUERY)
                error.rejection.code.assert().isEqualTo(code)
            }
        )
    }

    private fun assertRejected(
        code: QueryRejectionCode,
        path: String,
        category: QueryRejectionCategory = QueryRejectionCategory.INVALID_QUERY,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }

    private fun halfOpenRange(field: String, from: Instant, to: Instant): NormalizedCondition =
        NormalizedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                NormalizedCondition.Predicate(
                    LogicalField.Path(field.split('.'), PathBasis.ROOT),
                    PredicateOperator.GTE,
                    NormalizedValue.InstantValue(from),
                ),
                NormalizedCondition.Predicate(
                    LogicalField.Path(field.split('.'), PathBasis.ROOT),
                    PredicateOperator.LT,
                    NormalizedValue.InstantValue(to),
                ),
            ),
        )

    @Suppress("CyclomaticComplexMethod")
    private fun validCondition(operator: Operator): Condition =
        when (operator) {
            Operator.AND -> Condition.and(Condition.ALL)
            Operator.OR -> Condition.or(Condition.ALL)
            Operator.NOR -> Condition.nor(Condition.eq("field", "value"))
            Operator.ID -> Condition.id("id")
            Operator.IDS -> Condition.ids("id")
            Operator.AGGREGATE_ID -> Condition.aggregateId("id")
            Operator.AGGREGATE_IDS -> Condition.aggregateIds("id")
            Operator.TENANT_ID -> Condition.tenantId("tenant")
            Operator.OWNER_ID -> Condition.ownerId("owner")
            Operator.SPACE_ID -> Condition.spaceId("space")
            Operator.DELETED -> Condition.deleted(false)
            Operator.ALL -> Condition.ALL
            Operator.EQ,
            Operator.NE,
            Operator.GT,
            Operator.LT,
            Operator.GTE,
            Operator.LTE,
            -> Condition("field", operator, 1)
            Operator.CONTAINS,
            Operator.STARTS_WITH,
            Operator.ENDS_WITH,
            Operator.MATCH,
            -> Condition("field", operator, "value")
            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            -> Condition("field", operator, listOf(1, 2))
            Operator.BETWEEN -> Condition.between("field", 1, 2)
            Operator.ELEM_MATCH -> Condition.elemMatch("items", Condition.eq("name", "value"))
            Operator.NULL,
            Operator.NOT_NULL,
            Operator.TRUE,
            Operator.FALSE,
            -> Condition("field", operator)
            Operator.EXISTS -> Condition.exists("field")
            Operator.TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            -> Condition("field", operator)
            Operator.BEFORE_TODAY -> Condition.beforeToday("field", "12:30:00")
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS,
            -> Condition("field", operator, 2)
            Operator.RAW -> Condition.raw("{}")
        }

    private class CountingClock(
        private val fixedInstant: Instant,
        private val fixedZone: ZoneId,
    ) : Clock() {
        var instantReads: Int = 0
            private set

        override fun getZone(): ZoneId = fixedZone

        override fun withZone(zone: ZoneId): Clock = CountingClock(fixedInstant, zone)

        override fun instant(): Instant = fixedInstant.also { instantReads++ }
    }

    companion object {
        private val COLLECTION_OPERATORS = setOf(
            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            Operator.BETWEEN,
        )
    }
}
