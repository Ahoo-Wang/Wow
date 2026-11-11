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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DAY
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.OTHER_TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.PROBE_FIELDS
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.probeId
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The filter cases of the snapshot contract: the semantics matrix, system fields, logical filters, element match,
 * relative time and expressions. They read only [SnapshotQueryFixtures]'s fixtures and hooks; see
 * [SnapshotQueryFixtures] for the chain of families it is part of.
 */
abstract class SnapshotFilterCases internal constructor() : SnapshotQueryFixtures() {
    // Filters

    @TestFactory
    fun `filter semantics matrix`(): List<DynamicTest> {
        val probes = SemanticProbe.ALL.values.flatten()
        val fixture = blocking {
            given(
                probes.map { probe ->
                    val property = PROBE_FIELDS.getValue(probe.shape).removePrefix("state.")
                    val state = probe.json?.let { """{"$property":$it}""" } ?: "{}"
                    snapshot(probeId(probe), state)
                },
            )
        }
        return FilterSemantics.CASES.map { case ->
            DynamicTest.dynamicTest(case.id) {
                Assumptions.assumeFalse(case.id in semanticDivergences) {
                    "Known divergence from the canonical semantics: ${case.id}."
                }
                val shapeProbes = checkNotNull(SemanticProbe.ALL[case.shape])
                val filter = AndFilter(
                    listOf(
                        AggregateIdsFilter(shapeProbes.map(::probeId)),
                        case.filter(QueryField(PROBE_FIELDS.getValue(case.shape))),
                    ),
                )
                val byId = shapeProbes.associateBy(::probeId)
                blocking { fixture.ids(filter) }.map { checkNotNull(byId[it]) { "Unexpected record [$it]." }.name }
                    .toSet().assert().describedAs(case.id).isEqualTo(case.matches)
            }
        }
    }

    @Test
    fun `filter semantics cover every field operator`() {
        val covered = FilterSemantics.CASES.map { it.operator }.toSet() + FilterSemantics.UNCOVERED.keys
        val fieldOperators = FilterOperator.entries.filter {
            me.ahoo.wow.query.spec.FilterOperatorSpec.of(it).target == me.ahoo.wow.query.spec.OperatorTarget.FIELD
        }
        covered.containsAll(fieldOperators).assert().isTrue()
    }

    @Test
    fun `system field filters select by id, tenant, owner, space and deletion`() = blocking {
        val fixture = given(orders())
        fixture.ids(AggregateIdFilter("o2")).assert().containsExactly("o2")
        fixture.ids(IdsFilter(listOf("o1", "o3"))).assert().containsExactlyInAnyOrder("o1", "o3")
        fixture.ids(TenantIdFilter(OTHER_TENANT)).assert().containsExactly("o3")
        fixture.ids(OwnerIdFilter("owner-1")).assert().containsExactlyInAnyOrder("o1", "o3", "o4")
        fixture.ids(SpaceIdFilter("space-1")).assert().containsExactly("o3")
        fixture.ids(DeletionFilter(DeletionState.ACTIVE)).assert().containsExactlyInAnyOrder("o1", "o2", "o3", "o5")
        fixture.ids(DeletionFilter(DeletionState.DELETED)).assert().containsExactly("o4")
        fixture.ids(DeletionFilter(DeletionState.ALL)).assert().hasSize(5)
        fixture.ids(EqualFilter(QueryField("access.region"), "east")).assert().containsExactly("o5")
        fixture.ids(EqualFilter(QueryField("access.teams"), "b")).assert().containsExactly("o5")
        fixture.ids(EqualFilter(QueryField("version"), 2)).assert().containsExactly("o2")
        fixture.ids(GreaterThanFilter(QueryField("lastEventTime"), T0 + 2 * DAY))
            .assert().containsExactlyInAnyOrder("o4", "o5")
        Unit
    }

    @Test
    fun `the schedules system field filters by a rule's parts and reads back`() = blocking {
        val monthly = ScheduleRule.Cron("0 0 0 1 * *", "Asia/Shanghai")
        val fixture = given(
            listOf(
                snapshot("s1", """{"name":"One"}""", schedules = mapOf("tick" to monthly)),
                snapshot("s2", """{"name":"Two"}""", schedules = mapOf("tick" to ScheduleRule.Cron("0 0 3 * * *"))),
                snapshot("s3", """{"name":"Three"}""", schedules = mapOf("pay" to ScheduleRule.After(60_000))),
                snapshot("s4", """{"name":"Four"}"""),
            ),
        )
        fixture.ids(EqualFilter(QueryField("schedules.tick.timeZone"), "Asia/Shanghai")).assert().containsExactly("s1")
        fixture.ids(EqualFilter(QueryField("schedules.tick.type"), "cron")).assert()
            .containsExactlyInAnyOrder("s1", "s2")
        fixture.ids(EqualFilter(QueryField("schedules.pay.millis"), 60_000)).assert().containsExactly("s3")
        val record = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("s1")), fixture.schema)),
        )
        record.path("schedules").path("tick").path("expression").stringValue().assert().isEqualTo("0 0 0 1 * *")
        record.path("schedules").path("tick").path("misfire").stringValue().assert().isEqualTo("coalesce")
        val none = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("s4")), fixture.schema)),
        )
        none.path("schedules").isObject.assert().isTrue()
        none.path("schedules").size().assert().isEqualTo(0)
        Unit
    }

    @Test
    fun `operator system fields filter, sort and read back`() = blocking {
        val alice = Operator("alice", TENANT)
        val bob = Operator("bob", OTHER_TENANT, crossScope = true)
        val fixture = given(
            listOf(
                snapshot("p1", """{"name":"One"}""", operator = alice),
                snapshot("p2", """{"name":"Two"}""", operator = bob, firstOperator = alice),
                snapshot("p3", """{"name":"Three"}""", operator = bob),
                snapshot("p4", """{"name":"Four"}"""),
            ),
        )
        fixture.ids(EqualFilter(QueryField("operator.id"), "bob")).assert().containsExactlyInAnyOrder("p2", "p3")
        fixture.ids(EqualFilter(QueryField("firstOperator.id"), "alice")).assert().containsExactlyInAnyOrder("p1", "p2")
        fixture.ids(EqualFilter(QueryField("operator.tenantId"), OTHER_TENANT))
            .assert().containsExactlyInAnyOrder("p2", "p3")
        fixture.ids(EqualFilter(QueryField("operator.crossScope"), true)).assert().containsExactlyInAnyOrder("p2", "p3")
        fixture.ids(IsNullFilter(QueryField("operator"))).assert().containsExactly("p4")
        fixture.ids(
            AggregateIdsFilter(listOf("p1", "p2", "p3")),
            listOf(
                Sort(QueryField("operator.id"), Sort.Direction.DESC),
                Sort(QueryField("aggregateId"), Sort.Direction.ASC)
            ),
        ).assert().containsExactly("p2", "p3", "p1")
        val record = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("p2")), fixture.schema)),
        )
        record.path("operator").path("id").stringValue().assert().isEqualTo("bob")
        record.path("operator").path("tenantId").stringValue().assert().isEqualTo(OTHER_TENANT)
        record.path("operator").path("crossScope").booleanValue().assert().isTrue()
        record.path("firstOperator").path("id").stringValue().assert().isEqualTo("alice")
        val anonymous = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("p4")), fixture.schema)),
        )
        anonymous.path("operator").isNull.assert().isTrue()
        Unit
    }

    @Test
    fun `logical filters combine AND, OR and NOR`() = blocking {
        val fixture = given(orders())
        val paid = EqualFilter(QueryField("state.status"), "PAID")
        val large = GreaterThanOrEqualFilter(QueryField("state.amount"), 20)
        fixture.ids(AndFilter(listOf(paid, large))).assert().containsExactly("o2")
        fixture.ids(OrFilter(listOf(paid, EqualFilter(QueryField("state.status"), "CANCELLED"))))
            .assert().containsExactlyInAnyOrder("o1", "o2", "o5")
        fixture.ids(NorFilter(listOf(paid, large))).assert().isEmpty()
        fixture.ids(NorFilter(listOf(paid))).assert().containsExactlyInAnyOrder("o3", "o4", "o5")
        Unit
    }

    @Test
    fun `a search that names no field reads the fields marked as prose, where the storage offers one`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.supports(QueryCapability.FULL_TEXT_TERMS)) {
            "The storage offers no search that names no field."
        }
        // `state.name` is marked as prose; `state.status` holds words too, and is not.
        fixture.ids(SearchFilter("gamma")).assert().containsExactly("o3")
        fixture.ids(SearchFilter("paid")).assert().isEmpty()
        Unit
    }

    @Test
    fun `element match selects records with one element matching every predicate`() = blocking {
        val fixture = given(orders())
        val sameLine = ElementMatchFilter(
            QueryField("state.lines"),
            AndFilter(
                listOf(
                    EqualFilter(QueryField("sku"), "B"),
                    GreaterThanOrEqualFilter(QueryField("quantity"), 2),
                ),
            ),
        )
        fixture.ids(sameLine).assert().containsExactlyInAnyOrder("o1", "o5")
        // An element field is reached only through its element scope.
        val outside = runCatching { fixture.ids(EqualFilter(QueryField("state.lines.sku"), "A")) }.exceptionOrNull()
        (outside as QuerySchemaValidationException).violation.assert()
            .isEqualTo(QueryViolation.ElementScopeRequired(QueryField("state.lines.sku")))
        Unit
    }

    @Test
    fun `relative-now filters resolve against the server clock in the field's encoding`() = blocking {
        val now = System.currentTimeMillis()
        val fixture = given(
            listOf(
                snapshot("past", """{"createdAt":${now - DAY}}"""),
                snapshot("future", """{"createdAt":${now + DAY}}"""),
            ),
        )
        fixture.ids(BeforeNowFilter(QueryField("state.createdAt"))).assert().containsExactly("past")
        fixture.ids(AfterNowFilter(QueryField("state.createdAt"))).assert().containsExactly("future")
        Unit
    }

    @Test
    fun `EXPRESSION filter compares the computed value and skips records without one`() = blocking {
        val fixture = given(orders() + snapshot("empty", "{}"))
        val total = AggregationExpression.Binary(
            me.ahoo.wow.api.query.AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(QueryField("state.amount")),
            AggregationExpression.Field(QueryField("state.quantity")),
        )
        fixture.ids(ExpressionFilter(total, ComparisonOperator.GTE, 120.0))
            .assert().containsExactlyInAnyOrder("o4", "o5")
        Unit
    }

    @Test
    fun `a numeric expression over integer fields divides without truncating`() = blocking {
        // (lastEventTime - firstEventTime) / version: 5 / 2 is 2.5 on every storage, not an integer division's 2.
        val fixture = given(
            listOf(
                snapshot("d1", "{}", version = 2, lastEventTime = T0 - DAY + 5),
                snapshot("d2", "{}", version = 4, lastEventTime = T0 - DAY + 4),
            ),
        )
        val rate = AggregationExpression.Binary(
            me.ahoo.wow.api.query.AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Binary(
                me.ahoo.wow.api.query.AggregationExpressionOperator.SUBTRACT,
                AggregationExpression.Field(QueryField("lastEventTime")),
                AggregationExpression.Field(QueryField("firstEventTime")),
            ),
            AggregationExpression.Field(QueryField("version")),
        )
        fixture.ids(ExpressionFilter(rate, ComparisonOperator.GT, 2.25)).assert().containsExactly("d1")
        val average = fixture.aggregate(
            AggregationQuery(metrics = listOf(AggregationMetric.Numeric(AggregationFunction.AVG, rate, "rate"))),
        ).single()
        average.path("rate").doubleValue().assert().isEqualTo(1.75)
        Unit
    }
}
