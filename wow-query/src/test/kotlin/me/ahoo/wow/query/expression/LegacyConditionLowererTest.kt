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
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.RelativeTimeOperation
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.converter.DeleteConditionGuard.guard
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryStructureLimits
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class LegacyConditionLowererTest {
    @Test
    fun `relative time descriptor validates field and round trips through authoritative JSON`() {
        val expression: QueryExpression = RelativeTimeExpression(
            "eventTime",
            RelativeTimeOperation.RECENT_DAYS,
            listOf(QueryValue.IntegerValue(3)),
            "Asia/Shanghai"
        )

        expression.toJsonString().toObject<QueryExpression>().assert().isEqualTo(expression)
        assertThrows<IllegalArgumentException> {
            RelativeTimeExpression("eventTime.$", RelativeTimeOperation.TODAY)
        }
    }

    private val frozenInstant = Instant.parse("2024-06-12T12:00:00Z")
    private val zoneId = ZoneId.of("UTC")
    private val eventTarget = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.EVENT_STREAM)
    private val snapshotTarget = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.SNAPSHOT)
    private val native = NativeExpression(
        capabilityId = QueryCapabilityId("x-wow:native"),
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
    fun `gateway lowering defers relative time without reading a clock`() {
        val condition = Condition.recentDays("state.time", 3).copy(
            options = mapOf(Condition.ZONE_ID_OPTION_KEY to "Asia/Shanghai")
        )

        val lowered = LegacyConditionLowering.lowerForGateway(condition, eventTarget)

        lowered.first.assert().isEqualTo(
            RelativeTimeExpression(
                field = "state.time",
                operation = RelativeTimeOperation.RECENT_DAYS,
                operands = listOf(QueryValue.IntegerValue(3)),
                zoneId = "Asia/Shanghai"
            )
        )
        lowered.second.assert().isEqualTo(DeletionScope.DEFAULT)
    }

    @Test
    fun `gateway lowering preserves nested relative descriptors in logical and element match expressions`() {
        val lowered = LegacyConditionLowering.lowerForGateway(
            Condition.nor(
                Condition.elemMatch("state.items", Condition.today("createdAt")),
                Condition.and(Condition.earlierDays("state.time", 2))
            ),
            eventTarget
        )

        lowered.first.assert().isEqualTo(
            me.ahoo.wow.api.query.expression.PortableLogicalExpression(
                LogicalOperator.NOR,
                listOf(
                    ElementMatchExpression(
                        LogicalField("state.items"),
                        RelativeTimeExpression(
                            "createdAt",
                            RelativeTimeOperation.TODAY
                        )
                    ),
                    RelativeTimeExpression(
                        "state.time",
                        RelativeTimeOperation.EARLIER_DAYS,
                        listOf(QueryValue.IntegerValue(2))
                    )
                )
            )
        )
    }

    @Test
    fun `gateway lowering extracts only direct snapshot deletion intent`() {
        val state = predicate("state.status", PortableOperator.EQ, string("ACTIVE"))
        val direct = LegacyConditionLowering.lowerForGateway(
            Condition.and(Condition.deleted(DeletionState.DELETED), Condition.eq("state.status", "ACTIVE")),
            snapshotTarget
        )
        direct.second.assert().isEqualTo(DeletionScope.DELETED)
        direct.first.assert().isEqualTo(state)

        val root = LegacyConditionLowering.lowerForGateway(Condition.deleted(DeletionState.ALL), snapshotTarget)
        root.second.assert().isEqualTo(DeletionScope.ALL)
        root.first.assert().isSameAs(MatchAll)

        val nested = LegacyConditionLowering.lowerForGateway(
            Condition.or(Condition.deleted(DeletionState.DELETED), Condition.eq("state.status", "ACTIVE")),
            snapshotTarget
        )
        nested.second.assert().isEqualTo(DeletionScope.DEFAULT)
        nested.first.assert().isEqualTo(or(predicate("deleted", PortableOperator.EQ, bool(true)), state))

        val event = LegacyConditionLowering.lowerForGateway(Condition.deleted(DeletionState.DELETED), eventTarget)
        event.second.assert().isEqualTo(DeletionScope.DEFAULT)
        event.first.assert().isEqualTo(predicate("deleted", PortableOperator.EQ, bool(true)))
    }

    @Test
    fun `gateway lowering rejects contradictory direct snapshot deletion intents`() {
        val error = assertThrows<QueryException> {
            LegacyConditionLowering.lowerForGateway(
                Condition.and(
                    Condition.deleted(DeletionState.ACTIVE),
                    Condition.deleted(DeletionState.DELETED)
                ),
                snapshotTarget
            )
        }

        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.NORMALIZE)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
    }

    @Test
    @Suppress("LongMethod")
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
            Operator.CONTAINS to Fixture(
                Condition.contains("state.name", "wang"),
                predicate(
                    "state.name",
                    PortableOperator.CONTAINS,
                    string("wang"),
                    stringComparison = StringComparisonMode.CASE_SENSITIVE
                )
            ),
            Operator.IN to Fixture(Condition.isIn("state.status", listOf("NEW", "DONE")), predicate("state.status", PortableOperator.IN, string("NEW"), string("DONE"))),
            Operator.NOT_IN to Fixture(Condition.notIn("state.status", listOf("NEW", "DONE")), predicate("state.status", PortableOperator.NOT_IN, string("NEW"), string("DONE"))),
            Operator.BETWEEN to Fixture(Condition.between("state.score", 1, 9), predicate("state.score", PortableOperator.BETWEEN, integer(1), integer(9))),
            Operator.ALL_IN to Fixture(Condition.all("state.tags", listOf("a", "b")), predicate("state.tags", PortableOperator.ALL_IN, string("a"), string("b"))),
            Operator.STARTS_WITH to Fixture(
                Condition.startsWith("state.name", "A"),
                predicate(
                    "state.name",
                    PortableOperator.STARTS_WITH,
                    string("A"),
                    stringComparison = StringComparisonMode.CASE_SENSITIVE
                )
            ),
            Operator.ENDS_WITH to Fixture(
                Condition.endsWith("state.name", "Z"),
                predicate(
                    "state.name",
                    PortableOperator.ENDS_WITH,
                    string("Z"),
                    stringComparison = StringComparisonMode.CASE_SENSITIVE
                )
            ),
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
    fun `legacy RAW remains admissible through compatible schema validation`() {
        val lowered = LegacyConditionLowerer.lower(
            Condition.raw(native),
            eventTarget,
            frozenInstant,
            zoneId
        )
        val validator = QueryExpressionValidator(
            QueryStructureLimits(
                maxDepth = 4,
                maxNodes = 4,
                maxMembershipItems = 4,
                maxNativeParameterBytes = 256
            )
        )
        val compatibleSchema = QuerySchema(
            eventTarget,
            listOf(
                QueryFieldSchema(
                    path = LogicalField("state.status"),
                    valueKind = QueryFieldValueKind.STRING,
                    nullable = false,
                    capabilities = setOf(native.capabilityId)
                )
            )
        )

        lowered.assert().isSameAs(native)
        validator.validateStructure(lowered).assert().isSameAs(native)
        validator.validateSchema(lowered, compatibleSchema).assert().isSameAs(native)

        assertThrows<QueryException> {
            validator.validateSchema(lowered, QuerySchema(eventTarget, emptyList()))
        }
        assertThrows<QueryException> {
            validator.validateSchema(
                lowered,
                QuerySchema(
                    eventTarget,
                    listOf(
                        QueryFieldSchema(
                            path = LogicalField("state.status"),
                            valueKind = QueryFieldValueKind.STRING,
                            nullable = false,
                            capabilities = setOf(QueryCapabilityId("x-wow:other"))
                        )
                    )
                )
            )
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
    fun `preserves legacy string comparison mode for every matching operator`() {
        val matchingOperators = listOf(
            PortableOperator.CONTAINS to Operator.CONTAINS,
            PortableOperator.STARTS_WITH to Operator.STARTS_WITH,
            PortableOperator.ENDS_WITH to Operator.ENDS_WITH
        )

        val nullOption = nullIgnoreCaseOptions()

        matchingOperators.forEach { (portableOperator, legacyOperator) ->
            val absent = lowerPredicate(Condition("state.name", legacyOperator, "needle"))
            val explicitNull = lowerPredicate(
                Condition("state.name", legacyOperator, "needle", options = nullOption)
            )
            val sensitive = lowerPredicate(
                Condition(
                    "state.name",
                    legacyOperator,
                    "needle",
                    options = Condition.ignoreCaseOptions(false)
                )
            )
            val insensitive = lowerPredicate(
                Condition(
                    "state.name",
                    legacyOperator,
                    "needle",
                    options = Condition.ignoreCaseOptions(true)
                )
            )

            absent.assert().isEqualTo(
                PredicateExpression(
                    LogicalField("state.name"),
                    portableOperator,
                    listOf(string("needle")),
                    StringComparisonMode.DEFAULT
                )
            )
            explicitNull.stringComparison.assert().isEqualTo(StringComparisonMode.DEFAULT)
            sensitive.stringComparison.assert().isEqualTo(StringComparisonMode.CASE_SENSITIVE)
            insensitive.stringComparison.assert().isEqualTo(StringComparisonMode.CASE_INSENSITIVE)
            sensitive.component4().assert().isEqualTo(StringComparisonMode.CASE_SENSITIVE)
            insensitive.component4().assert().isEqualTo(StringComparisonMode.CASE_INSENSITIVE)
            absent.assert().isNotEqualTo(sensitive)
            sensitive.assert().isNotEqualTo(insensitive)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `uses target-specific identity fields and preserves scalar versus membership arity`() {
        val active = predicate("deleted", PortableOperator.EQ, bool(false))
        val fixtures = listOf(
            Triple(
                snapshotTarget,
                Condition.id("record-1"),
                and(
                    active,
                    predicate("aggregateId", PortableOperator.EQ, string("record-1"))
                )
            ),
            Triple(
                snapshotTarget,
                Condition.ids("record-1", "record-2"),
                and(
                    active,
                    predicate(
                        "aggregateId",
                        PortableOperator.IN,
                        string("record-1"),
                        string("record-2")
                    )
                )
            ),
            Triple(
                snapshotTarget,
                Condition.aggregateId("aggregate-1"),
                and(
                    active,
                    predicate("aggregateId", PortableOperator.EQ, string("aggregate-1"))
                )
            ),
            Triple(
                snapshotTarget,
                Condition.aggregateIds("aggregate-1", "aggregate-2"),
                and(
                    active,
                    predicate(
                        "aggregateId",
                        PortableOperator.IN,
                        string("aggregate-1"),
                        string("aggregate-2")
                    )
                )
            ),
            Triple(
                eventTarget,
                Condition.id("record-1"),
                predicate("id", PortableOperator.EQ, string("record-1"))
            ),
            Triple(
                eventTarget,
                Condition.ids("record-1", "record-2"),
                predicate(
                    "id",
                    PortableOperator.IN,
                    string("record-1"),
                    string("record-2")
                )
            ),
            Triple(
                eventTarget,
                Condition.aggregateId("aggregate-1"),
                predicate("aggregateId", PortableOperator.EQ, string("aggregate-1"))
            ),
            Triple(
                eventTarget,
                Condition.aggregateIds("aggregate-1", "aggregate-2"),
                predicate(
                    "aggregateId",
                    PortableOperator.IN,
                    string("aggregate-1"),
                    string("aggregate-2")
                )
            )
        )

        fixtures.forEach { (target, condition, expected) ->
            val actual = LegacyConditionLowerer.lower(condition, target, frozenInstant, zoneId)
            actual.assert().isEqualTo(expected)

            val declaredSystemFields = QuerySystemFields.fields(target.documentKind).map { it.path }.toSet()
            actual.predicateFields().forEach { field ->
                declaredSystemFields.assert().contains(field)
            }
        }
    }

    @Test
    fun `snapshot deletion guard preserves every legacy root shape exactly`() {
        val active = predicate("deleted", PortableOperator.EQ, bool(false))
        val deleted = predicate("deleted", PortableOperator.EQ, bool(true))
        val state = predicate("state.status", PortableOperator.EQ, string("ACTIVE"))
        val directDeleted = Condition.and(
            Condition.deleted(DeletionState.DELETED),
            Condition.eq("state.status", "ACTIVE")
        )
        val nestedDeleted = Condition.and(
            Condition.and(
                Condition.deleted(DeletionState.DELETED),
                Condition.eq("state.status", "ACTIVE")
            )
        )
        val orDeleted = Condition.or(
            Condition.eq("state.status", "ACTIVE"),
            Condition.deleted(DeletionState.DELETED)
        )
        val norDeleted = Condition.nor(
            Condition.eq("state.status", "ACTIVE"),
            Condition.deleted(DeletionState.DELETED)
        )
        val alreadyActive = Condition.and(
            Condition.ACTIVE,
            Condition.eq("state.status", "ACTIVE")
        )
        val fixtures = listOf(
            Condition.ALL to active,
            directDeleted to and(deleted, state),
            nestedDeleted to and(active, deleted, state),
            orDeleted to and(active, or(state, deleted)),
            norDeleted to and(active, nor(state, deleted)),
            alreadyActive to and(active, state)
        )

        fixtures.forEach { (condition, expected) ->
            val snapshot = LegacyConditionLowerer.lower(condition, snapshotTarget, frozenInstant, zoneId)
            val explicitlyGuarded = LegacyConditionLowerer.lower(
                condition.guard(),
                eventTarget,
                frozenInstant,
                zoneId
            )
            snapshot.assert().isEqualTo(expected)
            snapshot.assert().isEqualTo(explicitlyGuarded)
        }
        LegacyConditionLowerer.lower(
            orDeleted,
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(or(state, deleted))
        LegacyConditionLowerer.lower(
            norDeleted,
            eventTarget,
            frozenInstant,
            zoneId
        ).assert().isEqualTo(nor(state, deleted))
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

    private fun predicate(
        field: String,
        operator: PortableOperator,
        vararg values: QueryValue,
        stringComparison: StringComparisonMode = StringComparisonMode.DEFAULT
    ) = PredicateExpression(LogicalField(field), operator, values.toList(), stringComparison)

    private fun lowerPredicate(condition: Condition): PredicateExpression =
        LegacyConditionLowerer.lower(condition, eventTarget, frozenInstant, zoneId) as PredicateExpression

    private fun QueryExpression.predicateFields(): Set<LogicalField> = when (this) {
        is PredicateExpression -> setOf(field)
        is PortableLogicalExpression -> operands.flatMap { it.predicateFields() }.toSet()
        else -> emptySet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun nullIgnoreCaseOptions(): Map<String, Any> =
        mapOf<String, Any?>(Condition.IGNORE_CASE_OPTION_KEY to null) as Map<String, Any>

    private fun and(vararg expressions: me.ahoo.wow.api.query.expression.PortableExpression) =
        PortableLogicalExpression(LogicalOperator.AND, expressions.toList())

    private fun or(vararg expressions: me.ahoo.wow.api.query.expression.PortableExpression) =
        PortableLogicalExpression(LogicalOperator.OR, expressions.toList())

    private fun nor(vararg expressions: me.ahoo.wow.api.query.expression.PortableExpression) =
        PortableLogicalExpression(LogicalOperator.NOR, expressions.toList())

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
