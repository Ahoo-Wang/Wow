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

package me.ahoo.wow.query.json

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.YesterdayFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.format.DateTimeFormatter

/** The JSON half of 9.x `FilterExpressionTest`, with the legacy `Condition` cases turned into rejections. */
class FilterExpressionJsonTest {
    private val mapper = QueryJson.mapper

    private fun read(json: String): FilterExpression = mapper.readValue(json, FilterExpression::class.java)

    @Test
    fun `new relative calendar filters should round trip through the common contract`() {
        val field = QueryField("state.createTime")
        val filters = listOf<RelativeTimeFilter>(
            YesterdayFilter(field, "UTC", "yyyy-MM-dd"),
            NextMonthFilter(field, "UTC", "yyyy-MM-dd"),
            LastYearFilter(field, "UTC", "yyyy-MM-dd"),
            ThisYearFilter(field, "UTC", "yyyy-MM-dd"),
            NextYearFilter(field, "UTC", "yyyy-MM-dd"),
        )

        filters.forEach { filter ->
            val json = mapper.writeValueAsString(filter)
            json.contains("dateFormatter").assert().isFalse()
            read(json).assert().isEqualTo(filter)
        }

        val runtimeFilter: RelativeTimeFilter = YesterdayFilter(
            field = field,
            dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE,
        )
        mapper.writeValueAsString(runtimeFilter).assert().isEqualTo(
            """{"field":"state.createTime","zoneId":null,"datePattern":null,"timeUnit":"MILLISECONDS","op":"YESTERDAY"}""",
        )
    }

    @Test
    fun `a dateFormatter in JSON should be ignored`() {
        read("""{"op":"TODAY","field":"state.createTime","dateFormatter":"ISO_LOCAL_DATE"}""")
            .assert().isEqualTo(TodayFilter(QueryField("state.createTime")))
    }

    @Test
    fun `should round trip polymorphic filter expression with op`() {
        val expression: FilterExpression = AndFilter(
            listOf(
                DeletionFilter(DeletionState.ACTIVE),
                EqualFilter(QueryField("state.status"), "PAID"),
                SearchFilter("wow", setOf(QueryField("state.name"))),
            ),
        )

        val json = mapper.writeValueAsString(expression)

        json.assert().isEqualTo(
            """{"operands":[{"state":"ACTIVE","op":"DELETION"},""" +
                """{"field":"state.status","value":"PAID","op":"EQ"},""" +
                """{"query":"wow","fields":["state.name"],"mode":"TERMS","op":"SEARCH"}],"op":"AND"}""",
        )
        read(json).assert().isEqualTo(expression)
    }

    @Test
    fun `deletion state should be read as state`() {
        read("""{"op":"DELETION","state":"ALL"}""").assert().isEqualTo(DeletionFilter(DeletionState.ALL))
    }

    @Test
    fun `empty string filters should round trip without a value`() {
        listOf("IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING").forEach { operator ->
            val decoded = read("""{"op":"$operator","field":"state.name"}""")
            mapper.writeValueAsString(decoded).assert().isEqualTo("""{"field":"state.name","op":"$operator"}""")
        }
    }

    @Test
    fun `a legacy condition should be rejected`() {
        assertThrows<IllegalArgumentException> {
            read("""{"field":"state.status","operator":"EQ","value":"PAID"}""")
        }.message.assert().isEqualTo("Filter expression must use op.")
        assertThrows<IllegalArgumentException> {
            read("""{"operator":"ALL","unexpected":true}""")
        }
    }

    @Test
    fun `should reject mixed filter expression discriminators`() {
        assertThrows<IllegalArgumentException> {
            read("""{"op":"MATCH_ALL","operator":"ALL"}""")
        }.message.assert().isEqualTo("op and operator cannot be used together.")
    }

    @Test
    fun `canonical filter tree should reject nested legacy condition`() {
        listOf(
            """{"op":"AND","operands":[{"field":"state.status","operator":"EQ","value":"PAID"}]}""",
            """{"op":"OR","operands":[{"op":"MATCH_ALL"},{"op":"NOR","operands":[{"operator":"ALL"}]}]}""",
            """{"op":"ELEMENT_MATCH","field":"state.items","predicate":{"field":"name","operator":"EQ","value":"x"}}""",
        ).forEach { json ->
            assertThrows<IllegalArgumentException> { read(json) }
                .message.assert().isEqualTo("Nested filter expression must use op.")
        }
    }

    @Test
    fun `logical and element nodes without children should be rejected`() {
        listOf(
            """{"op":"AND"}""",
            """{"op":"OR","operands":[]}""",
            """{"op":"ELEMENT_MATCH","field":"state.items"}""",
        ).forEach { json ->
            assertThrows<JacksonException> { read(json) }
        }
    }

    @Test
    fun `canonical filter properties should reject legacy condition JSON`() {
        val legacy = """{"field":"state.tags","operator":"EQ","value":["a"]}"""
        val count = """"metrics":[{"type":"COUNT","alias":"count"}]"""
        listOf(
            """{"filter":$legacy}""" to ListQuery::class.java,
            """{"filter":$legacy}""" to PagedQuery::class.java,
            """{"filter":$legacy}""" to SingleQuery::class.java,
            """{"filter":$legacy,$count}""" to AggregationQuery::class.java,
            """{"elements":[{"path":"state.items","filter":$legacy}],$count}""" to AggregationQuery::class.java,
        ).forEach { (json, type) ->
            assertThrows<JacksonException> { mapper.readValue(json, type) }
                .message.assert().contains("Filter expression properties must use op.")
        }
    }

    @Test
    fun `search mode should default to terms`() {
        val decoded = read("""{"op":"SEARCH","query":"wow","fields":["state.name"]}""") as SearchFilter

        decoded.assert().isEqualTo(SearchFilter("wow", setOf(QueryField("state.name"))))
        decoded.mode.assert().isEqualTo(SearchMode.TERMS)
    }

    @Test
    fun `phrase search should round trip`() {
        val phrase = SearchFilter(
            query = "event \"sourcing\"",
            fields = setOf(QueryField("state.description")),
            mode = SearchMode.PHRASE,
        )

        val json = mapper.writeValueAsString(phrase)

        json.assert().isEqualTo(
            """{"query":"event \"sourcing\"","fields":["state.description"],"mode":"PHRASE","op":"SEARCH"}""",
        )
        read(json).assert().isEqualTo(phrase)
    }

    @Test
    fun `metadata filters should round trip with dedicated operators`() {
        listOf(
            IdFilter("id-1"),
            IdsFilter(listOf("id-1", "id-2")),
            AggregateIdFilter("aggregate-1"),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            TenantIdFilter("tenant-1"),
            OwnerIdFilter("owner-1"),
            SpaceIdFilter("space-1"),
        ).forEach { filter ->
            read(mapper.writeValueAsString(filter)).assert().isEqualTo(filter)
        }
    }

    @Test
    fun `canonical equality JSON should reject array and object values`() {
        listOf("EQ", "NE").forEach { operator ->
            assertThrows<IllegalArgumentException> {
                read("""{"op":"$operator","field":"state.tags","value":["a","b"]}""")
            }.message.assert().isEqualTo("$operator value must be a JSON scalar in filter payloads.")
            assertThrows<JacksonException> {
                read("""{"op":"$operator","field":"state.tags","value":{"a":1}}""")
            }
        }
    }

    @Test
    fun `equality JSON should accept every scalar`() {
        listOf("\"PAID\"" to "PAID", "true" to true, "null" to null, "7" to 7).forEach { (json, value) ->
            read("""{"op":"EQ","field":"state.status","value":$json}""")
                .assert().isEqualTo(EqualFilter(QueryField("state.status"), value))
            read("""{"op":"NE","field":"state.status","value":$json}""")
                .assert().isEqualTo(NotEqualFilter(QueryField("state.status"), value))
        }
    }

    @Test
    fun `in-process array equality and runtime values should be written as Jackson writes them`() {
        mapper.writeValueAsString(EqualFilter(QueryField("state.tags"), listOf("a", "b")))
            .assert().isEqualTo("""{"field":"state.tags","value":["a","b"],"op":"EQ"}""")
        mapper.writeValueAsString(NotEqualFilter(QueryField("state.deleted"), DeletionState.DELETED))
            .assert().isEqualTo("""{"field":"state.deleted","value":"DELETED","op":"NE"}""")
    }

    @Test
    fun `numbers should read as WowJson reads them and be written unchanged`() {
        val json = """{"op":"IN","field":"state.amount","values":[2.50,7,9007199254740993,123456789012345678901234567890]}"""

        val decoded = read(json) as InFilter

        decoded.values.assert().containsExactly(
            BigDecimal("2.50"),
            7,
            9007199254740993L,
            BigInteger("123456789012345678901234567890"),
        )
        mapper.writeValueAsString(decoded).assert().isEqualTo(
            """{"field":"state.amount","values":[2.50,7,9007199254740993,123456789012345678901234567890],"op":"IN"}""",
        )
    }

    @Test
    fun `non scalar values should read as plain trees the filters reject`() {
        listOf(
            """{"op":"GT","field":"state.version","value":{"a":1}}""",
            """{"op":"GT","field":"state.version","value":[1]}""",
            """{"op":"GT","field":"state.version","value":null}""",
            """{"op":"IN","field":"state.status","values":[{"a":1}]}""",
            """{"op":"IN","field":"state.status","values":[[1]]}""",
            """{"op":"BETWEEN","field":"state.version","lowerBound":{},"upperBound":1}""",
        ).forEach { json ->
            assertThrows<JacksonException> { read(json) }
        }
        read("""{"op":"GT","field":"state.version","value":1}""")
            .assert().isEqualTo(GreaterThanFilter(QueryField("state.version"), 1))
    }

    @Test
    fun `an unknown op should fail`() {
        assertThrows<JacksonException> { read("""{"op":"LIKE","field":"state.name","value":"x"}""") }
    }

    @Test
    fun `should round trip relative time date pattern without exposing formatter`() {
        val expression: FilterExpression = TodayFilter(
            QueryField("state.createTime"),
            zoneId = "UTC",
            datePattern = "yyyy-MM-dd HH:mm:ss",
        )

        val json = mapper.writeValueAsString(expression)
        val decoded = read(json) as TodayFilter

        json.contains("dateFormatter").assert().isFalse()
        decoded.datePattern.assert().isEqualTo("yyyy-MM-dd HH:mm:ss")
        decoded.resolvedDateFormatter().assert().isNotNull()
    }

    @Test
    fun `relative-now filters round trip through JSON and default to a zero offset`() {
        val decoded = read("""{"op":"BEFORE_NOW","field":"state.timeoutAt"}""")
        decoded.assert().isEqualTo(BeforeNowFilter(QueryField("state.timeoutAt")))
        (decoded as BeforeNowFilter).offsetDuration.assert().isEqualTo(Duration.ZERO)
        val after = AfterNowFilter(QueryField("state.timeoutAt"), "-PT30M")
        val json = mapper.writeValueAsString(after)
        json.contains("offsetDuration").assert().isFalse()
        read(json).assert().isEqualTo(after)
    }

    @Test
    fun `query serialization should expose only filter`() {
        mapper.writeValueAsString(ListQuery(MatchAllFilter)).assert()
            .isEqualTo("""{"filter":{"op":"MATCH_ALL"},"projection":{},"limit":0}""")
    }
}
