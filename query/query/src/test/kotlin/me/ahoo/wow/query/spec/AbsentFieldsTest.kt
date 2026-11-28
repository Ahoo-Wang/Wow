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

package me.ahoo.wow.query.spec

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * [matchesAbsentField] answers, for every operator, what the in-memory backend, the reference semantics, answers on a
 * state where the field is absent: the polarity by which admission holds a condition on an event's body to the events
 * that declare its path (`DeclaredBodies`). A new operator fails to compile here until it has a sample.
 */
class AbsentFieldsTest {
    private val type = ManifestFixtures.ORDER

    /** One field of each shape a condition takes, none of them stored. */
    private val schema = runBlocking {
        ManifestFixtures.schemaOf(
            ManifestQuerySchemaSource(
                ManifestFixtures.stateManifest(
                    mapOf(
                        "s" to mapOf("type" to "string"),
                        "n" to mapOf("type" to "integer"),
                        "a" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "e" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "object", "properties" to mapOf("v" to mapOf("type" to "string"))),
                        ),
                        "d" to mapOf("type" to "string", "format" to "date-time"),
                    ),
                ),
            ),
        )
    }

    private val backend = InMemoryQueryStorage().let { storage ->
        runBlocking {
            storage.save(
                SnapshotRecord(
                    ref = type.ref("tenant", "absent"),
                    version = 1,
                    schemaFingerprint = "test",
                    state = "{}",
                    scope = Scope.NONE,
                    firstEventTime = 1,
                    lastEventTime = 1,
                ),
            )
        }
        storage.binding(type).backend
    }

    /** Whether the in-memory backend matches the state, every field of which is absent, by [filter]. */
    private fun matches(filter: FilterExpression): Boolean = runBlocking {
        backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 1), schema)).toList().isNotEmpty()
    }

    @Test
    fun `every operator matches an absent field as the in-memory backend matches it`() {
        FilterOperator.entries.forEach { operator ->
            samples(operator).forEach { sample ->
                when {
                    sample.matchesAbsentField() == null -> sample.spec.target.assert().describedAs("$sample")
                        .isIn(OperatorTarget.NONE, OperatorTarget.LOGICAL, OperatorTarget.SYSTEM_FIELD)
                    // The reference grants no full-text search: a search finds no word in a field that is absent.
                    operator == FilterOperator.SEARCH -> {
                        sample.matchesAbsentField().assert().isFalse()
                        runCatching { matches(sample) }.exceptionOrNull().let { it as QuerySchemaValidationException }
                            .violation?.code.assert().isEqualTo(QueryErrorCodes.UNSUPPORTED_CAPABILITY)
                    }
                    else -> sample.matchesAbsentField().assert().describedAs("$sample").isEqualTo(matches(sample))
                }
            }
        }
    }

    private companion object {
        val S = QueryField("state.s")
        val N = QueryField("state.n")
        val A = QueryField("state.a")
        val E = QueryField("state.e")
        val D = QueryField("state.d")

        /** The conditions of [operator] the test runs; exhaustive, so a new operator must be added here. */
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun samples(operator: FilterOperator): List<FilterExpression> = when (operator) {
            FilterOperator.MATCH_ALL -> listOf(MatchAllFilter)
            FilterOperator.MATCH_NONE -> listOf(MatchNoneFilter)
            FilterOperator.ID -> listOf(IdFilter("id"))
            FilterOperator.IDS -> listOf(IdsFilter(listOf("id")))
            FilterOperator.AGGREGATE_ID -> listOf(AggregateIdFilter("id"))
            FilterOperator.AGGREGATE_IDS -> listOf(AggregateIdsFilter(listOf("id")))
            FilterOperator.TENANT_ID -> listOf(TenantIdFilter("t"))
            FilterOperator.OWNER_ID -> listOf(OwnerIdFilter("o"))
            FilterOperator.SPACE_ID -> listOf(SpaceIdFilter("s"))
            FilterOperator.DELETION -> listOf(DeletionFilter(DeletionState.ACTIVE))
            FilterOperator.AND -> listOf(AndFilter(listOf(MatchAllFilter, MatchAllFilter)))
            FilterOperator.OR -> listOf(OrFilter(listOf(MatchAllFilter, MatchAllFilter)))
            FilterOperator.NOR -> listOf(NorFilter(listOf(MatchAllFilter)))
            FilterOperator.EQ -> listOf(EqualFilter(S, "x"), EqualFilter(S, null))
            FilterOperator.NE -> listOf(NotEqualFilter(S, "x"), NotEqualFilter(S, null))
            FilterOperator.GT -> listOf(GreaterThanFilter(N, 1))
            FilterOperator.GTE -> listOf(GreaterThanOrEqualFilter(N, 1))
            FilterOperator.LT -> listOf(LessThanFilter(N, 1))
            FilterOperator.LTE -> listOf(LessThanOrEqualFilter(N, 1))
            FilterOperator.CONTAINS -> listOf(ContainsFilter(S, "x"))
            FilterOperator.STARTS_WITH -> listOf(StartsWithFilter(S, "x"))
            FilterOperator.ENDS_WITH -> listOf(EndsWithFilter(S, "x"))
            FilterOperator.IN -> listOf(InFilter(S, listOf("x")))
            FilterOperator.NOT_IN -> listOf(NotInFilter(S, listOf("x")))
            FilterOperator.BETWEEN -> listOf(BetweenFilter(N, 1, 2))
            FilterOperator.CONTAINS_ALL -> listOf(ContainsAllFilter(A, listOf("x")))
            FilterOperator.IS_EMPTY -> listOf(IsEmptyFilter(A))
            FilterOperator.IS_EMPTY_STRING -> listOf(IsEmptyStringFilter(S))
            FilterOperator.IS_NOT_EMPTY_STRING -> listOf(IsNotEmptyStringFilter(S))
            FilterOperator.IS_NULL -> listOf(IsNullFilter(S))
            FilterOperator.IS_NOT_NULL -> listOf(IsNotNullFilter(S))
            FilterOperator.EXISTS -> listOf(ExistsFilter(S))
            FilterOperator.NOT_EXISTS -> listOf(NotExistsFilter(S))
            FilterOperator.ELEMENT_MATCH -> listOf(ElementMatchFilter(E, EqualFilter(QueryField("v"), "x")))
            FilterOperator.SEARCH -> listOf(SearchFilter("x", setOf(S)))
            FilterOperator.TODAY -> listOf(TodayFilter(D))
            FilterOperator.BEFORE_TODAY -> listOf(BeforeTodayFilter(D, "10:00"))
            FilterOperator.TOMORROW -> listOf(TomorrowFilter(D))
            FilterOperator.THIS_WEEK -> listOf(ThisWeekFilter(D))
            FilterOperator.NEXT_WEEK -> listOf(NextWeekFilter(D))
            FilterOperator.LAST_WEEK -> listOf(LastWeekFilter(D))
            FilterOperator.THIS_MONTH -> listOf(ThisMonthFilter(D))
            FilterOperator.LAST_MONTH -> listOf(LastMonthFilter(D))
            FilterOperator.RECENT_DAYS -> listOf(RecentDaysFilter(D, 1))
            FilterOperator.EARLIER_DAYS -> listOf(EarlierDaysFilter(D, 1))
            FilterOperator.YESTERDAY -> listOf(YesterdayFilter(D))
            FilterOperator.NEXT_MONTH -> listOf(NextMonthFilter(D))
            FilterOperator.LAST_YEAR -> listOf(LastYearFilter(D))
            FilterOperator.THIS_YEAR -> listOf(ThisYearFilter(D))
            FilterOperator.NEXT_YEAR -> listOf(NextYearFilter(D))
            FilterOperator.BEFORE_NOW -> listOf(BeforeNowFilter(D))
            FilterOperator.AFTER_NOW -> listOf(AfterNowFilter(D, "-PT30M"))
            FilterOperator.EXPRESSION -> listOf(
                ExpressionFilter(AggregationExpression.Field(N), ComparisonOperator.GT, 0.0),
                ExpressionFilter(AggregationExpression.Field(N), ComparisonOperator.NE, 0.0),
            )
        }
    }
}
