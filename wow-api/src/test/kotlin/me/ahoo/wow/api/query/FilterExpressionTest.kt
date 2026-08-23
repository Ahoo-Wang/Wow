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

    @Suppress("DEPRECATION")
    @Test
    fun `metadata filters should round trip with dedicated operators`() {
        val filters = listOf<FilterExpression>(
            IdFilter("id-1"),
            IdsFilter(listOf("id-1", "id-2")),
            AggregateIdFilter("aggregate-1"),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            TenantIdFilter("tenant-1"),
            OwnerIdFilter("owner-1"),
            SpaceIdFilter("space-1"),
        )

        filters.forEach { filter ->
            val decoded = jsonMapper.readValue(
                jsonMapper.writeValueAsString(filter),
                FilterExpression::class.java,
            )
            decoded.assert().isEqualTo(filter)
            decoded.toCondition().operator.name.assert().isEqualTo(filter.operator.name)
        }
    }

    @Test
    fun `plural metadata filters should reject empty values`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { IdsFilter(emptyList()) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { AggregateIdsFilter(emptyList()) }
    }

    @Test
    fun `element match should reject root metadata filters`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ElementMatchFilter(LogicalField("state.items"), TenantIdFilter("tenant-1"))
        }
    }

    @Test
    fun `should reject non scalar predicate value`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            EqualFilter(LogicalField("state"), jsonMapper.readTree("{}"))
        }
    }

    @Test
    fun `should accept scalar array equality value`() {
        EqualFilter(LogicalField("state.tags"), jsonMapper.valueToTree<JsonNode>(listOf("a", "b")))
        NotEqualFilter(LogicalField("state.tags"), jsonMapper.valueToTree<JsonNode>(listOf("a", "b")))
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
        val appended = Condition.eq("state.tenant", "tenant")

        query.filter.toCondition().assert().isEqualTo(query.condition)
        query.withFilter(MatchAllFilter).condition.assert().isEqualTo(Condition.ALL)
        query.appendCondition(appended).condition.assert().isEqualTo(query.condition.appendCondition(appended))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy collection equality`() {
        val condition = Condition.eq("state.tags", listOf("a", "b"))
        val filter = condition.toFilterExpression()

        filter.toCondition().assert().isEqualTo(condition)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should resolve legacy metadata and logical conditions once`() {
        val wrapped = Condition.and(
            Condition.id("id-1"),
            Condition.aggregateIds("aggregate-1", "aggregate-2"),
            Condition.tenantId("tenant-1"),
        ).toFilterExpression()

        val resolved = wrapped.toExecutableFilter() as AndFilter

        resolved.operands.assert().containsExactly(
            IdFilter("id-1"),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            TenantIdFilter("tenant-1"),
        )
        resolved.toExecutableFilter().assert().isSameAs(resolved)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should resolve legacy wrappers nested in typed trees without rebuilding pure typed trees`() {
        val typed = EqualFilter(LogicalField("state.status"), jsonMapper.valueToTree("CREATED"))
        val legacy = Condition.eq("state.tenant", "tenant-1").toFilterExpression()

        val and = AndFilter(listOf(legacy, typed)).toExecutableFilter() as AndFilter
        (and.operands.first() as EqualFilter).field.value.assert().isEqualTo("state.tenant")
        and.operands.last().assert().isSameAs(typed)

        val or = OrFilter(listOf(typed, legacy)).toExecutableFilter() as OrFilter
        (or.operands.last() as EqualFilter).field.value.assert().isEqualTo("state.tenant")

        val nor = NorFilter(listOf(legacy)).toExecutableFilter() as NorFilter
        (nor.operands.single() as EqualFilter).field.value.assert().isEqualTo("state.tenant")

        val element = ElementMatchFilter(LogicalField("state.items"), legacy).toExecutableFilter() as ElementMatchFilter
        (element.predicate as EqualFilter).field.value.assert().isEqualTo("state.tenant")

        val pureTyped = AndFilter(listOf(typed, OrFilter(listOf(typed))))
        pureTyped.toExecutableFilter().assert().isSameAs(pureTyped)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy collection equality as array equality`() {
        val resolved = Condition.eq("state.tags", listOf("a", "b"))
            .toFilterExpression()
            .toExecutableFilter() as EqualFilter

        resolved.value.isArray.assert().isTrue()
        resolved.toCondition().assert().isEqualTo(Condition.eq("state.tags", listOf("a", "b")))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy native equality value`() {
        data class NativeValue(val id: String)
        val native = NativeValue("native-1")
        val resolved = Condition.eq("state.native", native)
            .toFilterExpression()
            .toExecutableFilter() as EqualFilter

        resolved.value.isPojo.assert().isTrue()
        resolved.toCondition().value.assert().isSameAs(native)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should reject invalid legacy logical and element match nodes`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Condition(operator = Operator.AND).toFilterExpression().toExecutableFilter()
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Condition(field = "items", operator = Operator.ELEM_MATCH)
                .toFilterExpression().toExecutableFilter()
        }
    }

    @Test
    fun `query serialization should expose only filter`() {
        val json = jsonMapper.writeValueAsString(ListQuery(MatchAllFilter))

        json.contains("\"filter\"").assert().isTrue()
        json.contains("\"condition\"").assert().isFalse()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy query serialization should preserve condition wire shape`() {
        val json = jsonMapper.writeValueAsString(ListQuery(Condition.eq("@timestamp", "now")))

        json.contains("\"condition\"").assert().isTrue()
        json.contains("\"filter\"").assert().isFalse()
        json.contains("@timestamp").assert().isTrue()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy queryable serialization should preserve condition wire shape`() {
        val json = jsonMapper.writeValueAsString(LegacyQueryable(Condition.eq("state.status", "CREATED")))

        json.contains("\"condition\"").assert().isTrue()
        json.contains("\"filter\"").assert().isFalse()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy query DTO methods should preserve conditions`() {
        val initial = Condition.eq("state.status", "CREATED")
        val replacement = Condition.eq("state.status", "PAID")
        val appended = initial.appendCondition(replacement)

        ListQuery(initial).also {
            it.condition.assert().isEqualTo(initial)
            it.withCondition(replacement).condition.assert().isEqualTo(replacement)
            it.appendCondition(replacement).condition.assert().isEqualTo(appended)
            it.copy(condition = replacement).condition.assert().isEqualTo(replacement)
        }
        PagedQuery(initial).also {
            it.condition.assert().isEqualTo(initial)
            it.withCondition(replacement).condition.assert().isEqualTo(replacement)
            it.appendCondition(replacement).condition.assert().isEqualTo(appended)
            it.copy(condition = replacement).condition.assert().isEqualTo(replacement)
        }
        SingleQuery(initial).also {
            it.condition.assert().isEqualTo(initial)
            it.withCondition(replacement).condition.assert().isEqualTo(replacement)
            it.appendCondition(replacement).condition.assert().isEqualTo(appended)
            it.copy(condition = replacement).condition.assert().isEqualTo(replacement)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy append should preserve typed filter details`() {
        val search = SearchFilter(
            query = "wow",
            fields = linkedSetOf(LogicalField("state.name"), LogicalField("state.description")),
        )
        val appended = Condition.tenantId("tenant")

        val rewritten = ListQuery(search).appendCondition(appended).filter as AndFilter

        rewritten.operands.first().assert().isSameAs(search)
        rewritten.operands.last().toCondition().assert().isEqualTo(appended)
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
