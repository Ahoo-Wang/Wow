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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper

class FilterExpressionTest {
    private val jsonMapper = jsonMapper()

    @Test
    fun `should round trip polymorphic filter expression with op`() {
        val expression: FilterExpression = AndFilter(
            listOf(
                DeletionFilter(DeletionState.ACTIVE),
                EqualFilter(LogicalField("state.status"), jsonMapper.valueToTree<JsonNode>("PAID")),
                SearchFilter("wow", setOf(LogicalField("state.name"))),
            ),
        )

        val json = jsonMapper.writeValueAsString(expression)
        val decoded = jsonMapper.readValue(json, FilterExpression::class.java)

        json.contains("\"op\":\"AND\"").assert().isTrue()
        json.contains("\"operator\"").assert().isFalse()
        decoded.assert().isEqualTo(expression)
    }

    @Test
    fun `should reject non scalar predicate value`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            EqualFilter(LogicalField("state"), jsonMapper.readTree("{}"))
        }
    }

    @Test
    fun `should reject null range value`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            GreaterThanFilter(LogicalField("state.version"), jsonMapper.nullNode())
        }
    }

    @Test
    fun `should reject null collection value`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            InFilter(LogicalField("state.status"), listOf(jsonMapper.nullNode()))
        }
    }

    @Test
    fun `should round trip relative time date pattern without exposing formatter`() {
        val expression: FilterExpression = TodayFilter(
            LogicalField("state.createTime"),
            zoneId = "UTC",
            datePattern = "yyyy-MM-dd HH:mm:ss",
        )

        val json = jsonMapper.writeValueAsString(expression)
        val decoded = jsonMapper.readValue(json, FilterExpression::class.java) as TodayFilter

        json.contains("dateFormatter").assert().isFalse()
        decoded.datePattern.assert().isEqualTo("yyyy-MM-dd HH:mm:ss")
        decoded.resolvedDateFormatter().assert().isNotNull()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy queryable implementation should inherit filter compatibility`() {
        val query = LegacyQueryable(Condition.eq("state.status", "CREATED"))

        (query.filter as EqualFilter).field.value.assert().isEqualTo("state.status")
        query.withFilter(MatchAllFilter).condition.assert().isEqualTo(Condition.ALL)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy collection equality`() {
        val condition = Condition.eq("state.tags", listOf("a", "b"))
        val filter = condition.toFilterExpression() as EqualFilter

        filter.value.isArray.assert().isTrue()
        filter.toCondition().assert().isEqualTo(condition)
    }

    @Test
    fun `query serialization should expose only filter`() {
        val json = jsonMapper.writeValueAsString(ListQuery(MatchAllFilter))

        json.contains("\"filter\"").assert().isTrue()
        json.contains("\"condition\"").assert().isFalse()
    }

    @Suppress("DEPRECATION")
    private data class LegacyQueryable(
        override val condition: Condition,
        override val projection: Projection = Projection.ALL,
        override val sort: List<Sort> = emptyList(),
    ) : Queryable<LegacyQueryable> {
        override fun withCondition(newCondition: Condition): LegacyQueryable = copy(condition = newCondition)

        override fun withProjection(newProjection: Projection): LegacyQueryable = copy(projection = newProjection)
    }
}
