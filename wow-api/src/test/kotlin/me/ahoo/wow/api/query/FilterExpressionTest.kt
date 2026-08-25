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
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class FilterExpressionTest {
    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `new relative calendar filters should round trip through the common contract`() {
        val filters = listOf<RelativeTimeFilter>(
            TodayFilter(LogicalField("nativeDate", FieldType.Temporal.Date)),
            TodayFilter(
                LogicalField(
                    "epochSeconds",
                    FieldType.Temporal.Number(TimeUnit.SECONDS),
                ),
            ),
            TodayFilter(
                LogicalField(
                    "dateText",
                    FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
                ),
            ),
        )

        filters.forEach { filter ->
            val json = jsonMapper.writeValueAsString(filter)
            jsonMapper.readValue(json, FilterExpression::class.java).assert().isEqualTo(filter)
        }
    }

    @Test
    fun `relative calendar filters should reject invalid common configuration`() {
        val field = LogicalField("state.createTime")

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            YesterdayFilter(field, zoneId = "")
        }
        org.junit.jupiter.api.assertThrows<java.time.DateTimeException> {
            NextMonthFilter(field, zoneId = "Not/AZone")
        }
    }

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
    fun `search mode should default to terms`() {
        val decoded = jsonMapper.readValue(
            """{"op":"SEARCH","query":"wow","fields":["state.name"]}""",
            FilterExpression::class.java,
        ) as SearchFilter

        decoded.assert().isEqualTo(SearchFilter("wow", setOf(LogicalField("state.name"))))
        decoded.mode.assert().isEqualTo(SearchMode.TERMS)
    }

    @Test
    fun `phrase search should round trip`() {
        val phrase = SearchFilter(
            query = "event sourcing",
            fields = setOf(LogicalField("state.description")),
            mode = SearchMode.PHRASE,
        )

        val json = jsonMapper.writeValueAsString(phrase)
        val decoded = jsonMapper.readValue(json, FilterExpression::class.java)

        json.contains("\"op\":\"SEARCH\"").assert().isTrue()
        json.contains("\"mode\":\"PHRASE\"").assert().isTrue()
        decoded.assert().isEqualTo(phrase)
    }

    @Test
    fun `phrase search should preserve embedded quotes`() {
        val filter = SearchFilter("event \"sourcing\"", mode = SearchMode.PHRASE)

        filter.query.assert().isEqualTo("event \"sourcing\"")
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

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy runtime date formatter`() {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE

        Condition.today("createdAt", formatter).toFilterExpression().assert().isEqualTo(
            TodayFilter(
                LogicalField(
                    "createdAt",
                    FieldType.Temporal.String(dateFormatter = formatter),
                ),
            ),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should convert legacy metadata and logical conditions immediately`() {
        val resolved = Condition.and(
            Condition.id("id-1"),
            Condition.aggregateIds("aggregate-1", "aggregate-2"),
            Condition.tenantId("tenant-1"),
        ).toFilterExpression() as AndFilter

        resolved.operands.assert().containsExactly(
            IdFilter("id-1"),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            TenantIdFilter("tenant-1"),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy collection equality as array equality`() {
        val resolved = Condition.eq("state.tags", listOf("a", "b"))
            .toFilterExpression() as EqualFilter

        resolved.value.isArray.assert().isTrue()
        resolved.value[0].asString().assert().isEqualTo("a")
        resolved.value[1].asString().assert().isEqualTo("b")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy native equality value`() {
        data class NativeValue(val id: String)
        val native = NativeValue("native-1")
        val resolved = Condition.eq("state.native", native)
            .toFilterExpression() as EqualFilter

        resolved.value.isPojo.assert().isTrue()
        (resolved.value as tools.jackson.databind.node.POJONode).pojo.assert().isSameAs(native)
    }

    @Suppress("DEPRECATION", "LongMethod")
    @Test
    fun `should resolve every legacy operator with its options`() {
        val field = LogicalField("state.value")
        val dateField = LogicalField("state.createdAt")
        val nestedField = LogicalField("name")
        val one = jsonMapper.valueToTree<JsonNode>(1)
        val two = jsonMapper.valueToTree<JsonNode>(2)
        val text = jsonMapper.valueToTree<JsonNode>("Wow")
        val equal = EqualFilter(field, one)
        val relativeOptions = mapOf(
            Condition.ZONE_ID_OPTION_KEY to "UTC",
            Condition.DATE_PATTERN_OPTION_KEY to "yyyy-MM-dd",
        )
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val formatterOptions = mapOf(
            Condition.ZONE_ID_OPTION_KEY to ZoneId.of("UTC"),
            Condition.DATE_PATTERN_OPTION_KEY to formatter,
        )
        val patternDateField = LogicalField(
            dateField.name,
            FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
        )
        val formatterDateField = LogicalField(
            dateField.name,
            FieldType.Temporal.String(dateFormatter = formatter),
        )
        val cases = listOf(
            Condition.and(Condition.eq(field.name, 1)) to AndFilter(listOf(equal)),
            Condition.or(Condition.eq(field.name, 1)) to OrFilter(listOf(equal)),
            Condition.nor(Condition.eq(field.name, 1)) to NorFilter(listOf(equal)),
            Condition.id("id-1") to IdFilter("id-1"),
            Condition.ids("id-1", "id-2") to IdsFilter(listOf("id-1", "id-2")),
            Condition.aggregateId("aggregate-1") to AggregateIdFilter("aggregate-1"),
            Condition.aggregateIds("aggregate-1", "aggregate-2") to
                AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            Condition.tenantId("tenant-1") to TenantIdFilter("tenant-1"),
            Condition.ownerId("owner-1") to OwnerIdFilter("owner-1"),
            Condition.spaceId("space-1") to SpaceIdFilter("space-1"),
            Condition.deleted(DeletionState.DELETED) to DeletionFilter(DeletionState.DELETED),
            Condition.ALL to MatchAllFilter,
            Condition.eq(field.name, 1) to equal,
            Condition.ne(field.name, 1) to NotEqualFilter(field, one),
            Condition.gt(field.name, 1) to GreaterThanFilter(field, one),
            Condition.lt(field.name, 1) to LessThanFilter(field, one),
            Condition.gte(field.name, 1) to GreaterThanOrEqualFilter(field, one),
            Condition.lte(field.name, 1) to LessThanOrEqualFilter(field, one),
            Condition.contains(field.name, "Wow", ignoreCase = true) to
                ContainsFilter(field, "Wow", StringComparison.CASE_INSENSITIVE),
            Condition.isIn(field.name, listOf<Any>(1, 2)) to InFilter(field, listOf(one, two)),
            Condition.notIn(field.name, listOf<Any>(1, 2)) to NotInFilter(field, listOf(one, two)),
            Condition.between(field.name, 1, 2) to BetweenFilter(field, one, two),
            Condition.all(field.name, listOf<Any>(1, 2)) to ContainsAllFilter(field, listOf(one, two)),
            Condition.startsWith(field.name, "Wow") to StartsWithFilter(field, "Wow"),
            Condition.endsWith(field.name, "Wow", ignoreCase = true) to
                EndsWithFilter(field, "Wow", StringComparison.CASE_INSENSITIVE),
            Condition.elemMatch("state.items", Condition.eq(nestedField.name, "Wow")) to
                ElementMatchFilter(LogicalField("state.items"), EqualFilter(nestedField, text)),
            Condition(
                field = "state.items",
                operator = Operator.ELEM_MATCH,
                children = listOf(Condition.eq(nestedField.name, "Wow"), Condition.gt("price", 1)),
            ) to ElementMatchFilter(
                LogicalField("state.items"),
                AndFilter(
                    listOf(
                        EqualFilter(nestedField, text),
                        GreaterThanFilter(LogicalField("price"), one),
                    ),
                ),
            ),
            Condition.isNull(field.name) to IsNullFilter(field),
            Condition.notNull(field.name) to IsNotNullFilter(field),
            Condition.isTrue(field.name) to EqualFilter(field, jsonMapper.valueToTree(true)),
            Condition.isFalse(field.name) to EqualFilter(field, jsonMapper.valueToTree(false)),
            Condition.exists(field.name) to ExistsFilter(field),
            Condition.exists(field.name, false) to NotExistsFilter(field),
            Condition(
                field = dateField.name,
                operator = Operator.TODAY,
                options = relativeOptions,
            ) to TodayFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.BEFORE_TODAY,
                value = LocalTime.of(8, 30),
                options = formatterOptions,
            ) to BeforeTodayFilter(formatterDateField, "08:30", "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.TOMORROW,
                options = relativeOptions,
            ) to TomorrowFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.THIS_WEEK,
                options = relativeOptions,
            ) to ThisWeekFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.NEXT_WEEK,
                options = relativeOptions,
            ) to NextWeekFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.LAST_WEEK,
                options = relativeOptions,
            ) to LastWeekFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.THIS_MONTH,
                options = relativeOptions,
            ) to ThisMonthFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.LAST_MONTH,
                options = relativeOptions,
            ) to LastMonthFilter(patternDateField, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.RECENT_DAYS,
                value = 7,
                options = relativeOptions,
            ) to RecentDaysFilter(patternDateField, 7, "UTC"),
            Condition(
                field = dateField.name,
                operator = Operator.EARLIER_DAYS,
                value = 30,
                options = relativeOptions,
            ) to EarlierDaysFilter(patternDateField, 30, "UTC"),
            Condition.match("state.description", "Wow") to
                SearchFilter("Wow", setOf(LogicalField("state.description"))),
            Condition.match("", "Wow") to SearchFilter("Wow"),
        )

        cases.map { it.first.operator }.toSet().assert().containsExactly(*Operator.entries.toTypedArray())
        cases.forEach { (condition, expected) ->
            condition.toFilterExpression().assert().isEqualTo(expected)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should normalize empty legacy collections`() {
        val field = LogicalField("state.values")
        val emptyArray = jsonMapper.valueToTree<JsonNode>(emptyList<Any>())
        val cases = listOf(
            Condition.ids(emptyList()) to MatchNoneFilter,
            Condition.aggregateIds(emptyList()) to MatchNoneFilter,
            Condition.isIn(field.name, emptyList()) to MatchNoneFilter,
            Condition.notIn(field.name, emptyList()) to MatchAllFilter,
            Condition.all(field.name, emptyList()) to MatchNoneFilter,
            Condition.eq(field.name, emptyList<Any>()) to EqualFilter(field, emptyArray),
        )

        cases.forEach { (condition, expected) ->
            condition.toFilterExpression().assert().isEqualTo(expected)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy backend field should remain executable`() {
        val executable = Condition.eq("@timestamp", "now")
            .toFilterExpression() as EqualFilter

        executable.field.name.assert().isEqualTo("@timestamp")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should reject empty legacy logical and element match nodes`() {
        listOf(Operator.AND, Operator.OR, Operator.NOR).forEach { operator ->
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                Condition(operator = operator).toFilterExpression()
            }
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Condition(field = "items", operator = Operator.ELEM_MATCH)
                .toFilterExpression()
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
    fun `legacy query constructor should serialize filter only`() {
        val condition = Condition.eq("@timestamp", "now")

        listOf(ListQuery(condition), PagedQuery(condition), SingleQuery(condition)).forEach { query ->
            val json = jsonMapper.writeValueAsString(query)
            json.contains("\"condition\"").assert().isFalse()
            json.contains("\"filter\"").assert().isTrue()
            json.contains("@timestamp").assert().isTrue()
        }
    }
}
