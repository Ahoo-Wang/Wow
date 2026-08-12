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

package me.ahoo.wow.query.expression

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class LegacyConditionLowererTest {
    private val frozenInstant = Instant.parse("2024-06-12T12:00:00Z")
    private val zoneId = ZoneId.of("UTC")
    private val eventTarget = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.EVENT_STREAM)
    private val snapshotTarget = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.SNAPSHOT)
    private val native = NativeExpression(
        capabilityId = QueryCapabilityId("native.mongo"),
        backendId = "mongo",
        templateId = "active-orders",
        parameters = mapOf("status" to QueryValue.StringValue("ACTIVE")),
        declaredFields = setOf(LogicalField("state.status"))
    )

    private data class Fixture(
        val condition: Condition,
        val expected: QueryExpression
    )

    @Test
    fun `every legacy operator has one exact canonical lowering`() {
        val fixtures = mapOf(
            Operator.AND to Fixture(Condition(operator = Operator.AND), MatchAll),
            Operator.OR to Fixture(Condition(operator = Operator.OR), MatchNone),
            Operator.NOR to Fixture(Condition(operator = Operator.NOR), MatchNone),
            Operator.ID to Fixture(Condition.id("id-1"), predicate("id", PortableOperator.EQ, string("id-1"))),
            Operator.IDS to Fixture(Condition.ids("id-1", "id-2"), predicate("id", PortableOperator.IN, string("id-1"), string("id-2"))),
            Operator.AGGREGATE_ID to Fixture(Condition.aggregateId("order-1"), predicate("aggregateId", PortableOperator.EQ, string("order-1"))),
            Operator.AGGREGATE_IDS to Fixture(Condition.aggregateIds("order-1", "order-2"), predicate("aggregateId", PortableOperator.IN, string("order-1"), string("order-2"))),
            Operator.TENANT_ID to Fixture(Condition.tenantId("tenant-1"), predicate("tenantId", PortableOperator.EQ, string("tenant-1"))),
            Operator.OWNER_ID to Fixture(Condition.ownerId("owner-1"), predicate("ownerId", PortableOperator.EQ, string("owner-1"))),
            Operator.SPACE_ID to Fixture(Condition.spaceId("space-1"), predicate("spaceId", PortableOperator.EQ, string("space-1"))),
            Operator.DELETED to Fixture(Condition.deleted(DeletionState.DELETED), predicate("deleted", PortableOperator.EQ, bool(true))),
            Operator.ALL to Fixture(Condition.ALL, MatchAll),
            Operator.EQ to Fixture(Condition.eq("state.score", 10), predicate("state.score", PortableOperator.EQ, integer(10))),
            Operator.NE to Fixture(Condition.ne("state.score", 10), predicate("state.score", PortableOperator.NE, integer(10))),
            Operator.GT to Fixture(Condition.gt("state.score", 10), predicate("state.score", PortableOperator.GT, integer(10))),
            Operator.LT to Fixture(Condition.lt("state.score", 10), predicate("state.score", PortableOperator.LT, integer(10))),
            Operator.GTE to Fixture(Condition.gte("state.score", 10), predicate("state.score", PortableOperator.GTE, integer(10))),
            Operator.LTE to Fixture(Condition.lte("state.score", 10), predicate("state.score", PortableOperator.LTE, integer(10))),
            Operator.CONTAINS to Fixture(Condition.contains("state.name", "wang"), predicate("state.name", PortableOperator.CONTAINS, string("wang"))),
            Operator.IN to Fixture(Condition.isIn("state.status", listOf("NEW", "DONE")), predicate("state.status", PortableOperator.IN, string("NEW"), string("DONE"))),
            Operator.NOT_IN to Fixture(Condition.notIn("state.status", listOf("NEW", "DONE")), predicate("state.status", PortableOperator.NOT_IN, string("NEW"), string("DONE"))),
            Operator.BETWEEN to Fixture(Condition.between("state.score", 1, 9), predicate("state.score", PortableOperator.BETWEEN, integer(1), integer(9))),
            Operator.ALL_IN to Fixture(Condition.all("state.tags", listOf("a", "b")), predicate("state.tags", PortableOperator.ALL_IN, string("a"), string("b"))),
            Operator.STARTS_WITH to Fixture(Condition.startsWith("state.name", "A"), predicate("state.name", PortableOperator.STARTS_WITH, string("A"))),
            Operator.ENDS_WITH to Fixture(Condition.endsWith("state.name", "Z"), predicate("state.name", PortableOperator.ENDS_WITH, string("Z"))),
            Operator.ELEM_MATCH to Fixture(Condition.elemMatch("state.lines", Condition.eq("sku", "sku-1")), ElementMatchExpression(LogicalField("state.lines"), predicate("sku", PortableOperator.EQ, string("sku-1")))),
            Operator.NULL to Fixture(Condition.isNull("state.memo"), predicate("state.memo", PortableOperator.NULL)),
            Operator.NOT_NULL to Fixture(Condition.notNull("state.memo"), predicate("state.memo", PortableOperator.NOT_NULL)),
            Operator.TRUE to Fixture(Condition.isTrue("state.enabled"), predicate("state.enabled", PortableOperator.TRUE)),
            Operator.FALSE to Fixture(Condition.isFalse("state.enabled"), predicate("state.enabled", PortableOperator.FALSE)),
            Operator.EXISTS to Fixture(Condition.exists("state.memo", false), predicate("state.memo", PortableOperator.EXISTS, bool(false))),
            Operator.TODAY to Fixture(Condition.today("state.time"), range("state.time", "2024-06-12T00:00:00Z", "2024-06-13T00:00:00Z")),
            Operator.BEFORE_TODAY to Fixture(Condition.beforeToday("state.time", LocalTime.of(17, 0)), predicate("state.time", PortableOperator.LT, instant("2024-06-12T17:00:00Z"))),
            Operator.TOMORROW to Fixture(Condition.tomorrow("state.time"), range("state.time", "2024-06-13T00:00:00Z", "2024-06-14T00:00:00Z")),
            Operator.THIS_WEEK to Fixture(Condition.thisWeek("state.time"), range("state.time", "2024-06-10T00:00:00Z", "2024-06-17T00:00:00Z")),
            Operator.NEXT_WEEK to Fixture(Condition.nextWeek("state.time"), range("state.time", "2024-06-17T00:00:00Z", "2024-06-24T00:00:00Z")),
            Operator.LAST_WEEK to Fixture(Condition.lastWeek("state.time"), range("state.time", "2024-06-03T00:00:00Z", "2024-06-10T00:00:00Z")),
            Operator.THIS_MONTH to Fixture(Condition.thisMonth("state.time"), range("state.time", "2024-06-01T00:00:00Z", "2024-07-01T00:00:00Z")),
            Operator.LAST_MONTH to Fixture(Condition.lastMonth("state.time"), range("state.time", "2024-05-01T00:00:00Z", "2024-06-01T00:00:00Z")),
            Operator.RECENT_DAYS to Fixture(Condition.recentDays("state.time", 3), range("state.time", "2024-06-10T00:00:00Z", "2024-06-13T00:00:00Z")),
            Operator.EARLIER_DAYS to Fixture(Condition.earlierDays("state.time", 3), predicate("state.time", PortableOperator.LT, instant("2024-06-10T00:00:00Z"))),
            Operator.MATCH to Fixture(Condition.match("state.description", "red shoes"), FullTextExpression(QueryCapabilityId("full-text"), "red shoes", setOf(LogicalField("state.description")))),
            Operator.RAW to Fixture(Condition.raw(native), native)
        )

        fixtures.keys.assert().isEqualTo(Operator.entries.toSet())
        fixtures.forEach { (_, fixture) ->
            LegacyConditionLowerer.lower(fixture.condition, eventTarget, frozenInstant, zoneId)
                .assert()
                .isEqualTo(fixture.expected)
        }
    }

    @Test
    fun `normalizes logical identities without changing NOR meaning`() {
        val child = Condition.eq("state.status", "ACTIVE")
        val expected = predicate("state.status", PortableOperator.EQ, string("ACTIVE"))

        LegacyConditionLowerer.lower(
            Condition.and(child),
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(expected)
        LegacyConditionLowerer.lower(
            Condition.or(child),
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(expected)
        LegacyConditionLowerer.lower(Condition.nor(child), eventTarget, frozenInstant, zoneId).assert().isEqualTo(
            PortableLogicalExpression(LogicalOperator.NOR, listOf(expected))
        )
        LegacyConditionLowerer.lower(
            Condition.and(child, Condition.and(Condition.ALL, child)),
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(PortableLogicalExpression(LogicalOperator.AND, listOf(expected, expected)))
    }

    @Test
    fun `snapshot exactly applies legacy deletion guard while event stream does not`() {
        val active = predicate("deleted", PortableOperator.EQ, bool(false))
        val deleted = predicate("deleted", PortableOperator.EQ, bool(true))
        val state = predicate("state.status", PortableOperator.EQ, string("ACTIVE"))

        LegacyConditionLowerer.lower(Condition.ALL, snapshotTarget, frozenInstant, zoneId).assert().isEqualTo(active)
        LegacyConditionLowerer.lower(
            Condition.deleted(DeletionState.ACTIVE),
            snapshotTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(active)
        LegacyConditionLowerer.lower(
            Condition.deleted(DeletionState.DELETED),
            snapshotTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(deleted)
        LegacyConditionLowerer.lower(
            Condition.deleted(DeletionState.ALL),
            snapshotTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(MatchAll)
        LegacyConditionLowerer.lower(
            Condition.eq("state.status", "ACTIVE"),
            snapshotTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(
            PortableLogicalExpression(LogicalOperator.AND, listOf(active, state))
        )
        LegacyConditionLowerer.lower(
            Condition.eq("state.status", "ACTIVE"),
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(state)
    }

    @Test
    fun `rejects every unstructured RAW payload with safe dimensions`() {
        listOf("{}", mapOf("state.status" to "ACTIVE"), Any()).forEach { payload ->
            val error = assertThrows<QueryException> {
                LegacyConditionLowerer.lower(Condition.raw(payload), eventTarget, frozenInstant, zoneId)
            }
            error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
            error.stage.assert().isEqualTo(QueryStage.NORMALIZE)
            error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
            error.message.orEmpty().assert().doesNotContain(payload.toString())
        }
    }

    @Test
    fun `returns the already validated native descriptor by identity`() {
        LegacyConditionLowerer.lower(Condition.raw(native), eventTarget, frozenInstant, zoneId)
            .assert()
            .isSameAs(native)
    }

    private fun predicate(field: String, operator: PortableOperator, vararg values: QueryValue) =
        PredicateExpression(LogicalField(field), operator, values.toList())

    private fun range(field: String, start: String, end: String) = PortableLogicalExpression(
        LogicalOperator.AND,
        listOf(
            predicate(field, PortableOperator.GTE, instant(start)),
            predicate(field, PortableOperator.LT, instant(end))
        )
    )

    private fun string(value: String) = QueryValue.StringValue(value)
    private fun integer(value: Long) = QueryValue.IntegerValue(value)
    private fun integer(value: Int) = integer(value.toLong())
    private fun bool(value: Boolean) = QueryValue.BooleanValue(value)
    private fun instant(value: String) = QueryValue.InstantValue(Instant.parse(value))
}
