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
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId

class RelativeTimeNormalizerTest {
    private val target = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.EVENT_STREAM)

    @Test
    fun `uses half-open instants across DST spring-forward day`() {
        val actual = lower(
            Condition.today("state.time"),
            Instant.parse("2024-03-10T16:00:00Z"),
            ZoneId.of("America/New_York")
        )
        actual.assert().isEqualTo(range("2024-03-10T05:00:00Z", "2024-03-11T04:00:00Z"))
    }

    @Test
    fun `uses half-open instants across DST fall-back day`() {
        val actual = lower(
            Condition.today("state.time"),
            Instant.parse("2024-11-03T16:00:00Z"),
            ZoneId.of("America/New_York")
        )
        actual.assert().isEqualTo(range("2024-11-03T04:00:00Z", "2024-11-04T05:00:00Z"))
    }

    @Test
    fun `uses condition zone override instead of invocation zone`() {
        val condition = Condition.today(
            "state.time"
        ).copy(options = mapOf(Condition.ZONE_ID_OPTION_KEY to "Asia/Shanghai"))
        lower(condition, Instant.parse("2024-01-01T01:00:00Z"), ZoneId.of("America/New_York"))
            .assert().isEqualTo(range("2023-12-31T16:00:00Z", "2024-01-01T16:00:00Z"))
    }

    @Test
    fun `week month and year boundaries are calendar based`() {
        val frozen = Instant.parse("2024-01-01T12:00:00Z")
        val utc = ZoneId.of("UTC")
        lower(
            Condition.thisWeek("state.time"),
            frozen,
            utc
        ).assert().isEqualTo(range("2024-01-01T00:00:00Z", "2024-01-08T00:00:00Z"))
        lower(
            Condition.lastMonth("state.time"),
            frozen,
            utc
        ).assert().isEqualTo(range("2023-12-01T00:00:00Z", "2024-01-01T00:00:00Z"))
        lower(Condition.nextWeek("state.time"), Instant.parse("2024-12-30T12:00:00Z"), utc).assert()
            .isEqualTo(range("2025-01-06T00:00:00Z", "2025-01-13T00:00:00Z"))
    }

    @Test
    fun `rejects date pattern by presence without parsing it`() {
        val relativeOperators = listOf(
            Operator.TODAY,
            Operator.BEFORE_TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS
        )
        relativeOperators.forEach { operator ->
            val condition = Condition(
                field = "state.time",
                operator = operator,
                value = 1,
                options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to Any())
            )
            assertSafeInvalid { lower(condition, Instant.EPOCH, ZoneId.of("UTC")) }
        }
    }

    @Test
    fun `rejects zero negative non-integral and overflowing day counts`() {
        listOf<Any>(0, -1, 1.5, BigDecimal("2.1"), BigInteger("9223372036854775808"), Long.MAX_VALUE).forEach { days ->
            assertSafeInvalid {
                lower(Condition("state.time", Operator.RECENT_DAYS, days), Instant.EPOCH, ZoneId.of("UTC"))
            }
            assertSafeInvalid {
                lower(Condition("state.time", Operator.EARLIER_DAYS, days), Instant.EPOCH, ZoneId.of("UTC"))
            }
        }
    }

    @Test
    fun `accepts an exactly integral floating day count`() {
        lower(
            Condition("state.time", Operator.RECENT_DAYS, 2.0),
            Instant.parse("2024-06-12T12:00:00Z"),
            ZoneId.of("UTC")
        ).assert().isEqualTo(range("2024-06-11T00:00:00Z", "2024-06-13T00:00:00Z"))
    }

    private fun lower(condition: Condition, instant: Instant, zoneId: ZoneId) =
        LegacyConditionLowerer.lower(condition, target, instant, zoneId)

    private fun range(start: String, end: String) = PortableLogicalExpression(
        LogicalOperator.AND,
        listOf(
            PredicateExpression(
                LogicalField("state.time"),
                PortableOperator.GTE,
                listOf(QueryValue.InstantValue(Instant.parse(start)))
            ),
            PredicateExpression(
                LogicalField("state.time"),
                PortableOperator.LT,
                listOf(QueryValue.InstantValue(Instant.parse(end)))
            )
        )
    )

    private fun assertSafeInvalid(block: () -> Unit) {
        val error = assertThrows<QueryException>(block)
        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.NORMALIZE)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
    }
}
