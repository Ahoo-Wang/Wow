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

@file:Suppress("LongMethod")

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

class QuerySchemaResolverTest {
    @Test
    fun `filter capability matrix should rewrite exact literal range and presence fields`() {
        val field = LogicalField("state.value")
        val exact = LogicalField("document.value.keyword")
        val literal = LogicalField("document.value.literal")
        val range = LogicalField("document.value.range")
        val presence = LogicalField("document.value")
        val schema = schema(
            mapOf(
                field to fieldSchema(
                    QueryCapability.EXACT_MATCH to exact.value,
                    QueryCapability.LITERAL_MATCH to literal.value,
                    QueryCapability.RANGE to range.value,
                    QueryCapability.PRESENCE to presence.value,
                ),
            ),
        )
        val resolver = QuerySchemaResolver(schema)
        val one = json(1)
        val two = json(2)

        val cases = listOf(
            EqualFilter(field, one) to EqualFilter(exact, one),
            NotEqualFilter(field, one) to NotEqualFilter(exact, one),
            InFilter(field, listOf(one)) to InFilter(exact, listOf(one)),
            NotInFilter(field, listOf(one)) to NotInFilter(exact, listOf(one)),
            ContainsAllFilter(field, listOf(one)) to ContainsAllFilter(exact, listOf(one)),
            ContainsFilter(field, "one") to ContainsFilter(literal, "one"),
            StartsWithFilter(field, "one") to StartsWithFilter(literal, "one"),
            EndsWithFilter(field, "one") to EndsWithFilter(literal, "one"),
            GreaterThanFilter(field, one) to GreaterThanFilter(range, one),
            GreaterThanOrEqualFilter(field, one) to GreaterThanOrEqualFilter(range, one),
            LessThanFilter(field, two) to LessThanFilter(range, two),
            LessThanOrEqualFilter(field, two) to LessThanOrEqualFilter(range, two),
            BetweenFilter(field, one, two) to BetweenFilter(range, one, two),
            IsEmptyFilter(field) to IsEmptyFilter(presence),
            IsNullFilter(field) to IsNullFilter(presence),
            IsNotNullFilter(field) to IsNotNullFilter(presence),
            ExistsFilter(field) to ExistsFilter(presence),
            NotExistsFilter(field) to NotExistsFilter(presence),
        )

        cases.forEach { (input, expected) ->
            resolver.resolve(input).assert().isEqualTo(
                QuerySchemaResolution(expected, QueryCompatibilityLevel.EXACT),
            )
        }
    }

    @Test
    fun `every relative time filter should require and rewrite a range binding`() {
        val field = LogicalField("state.createdAt")
        val physical = LogicalField("document.created_at")
        val resolver = QuerySchemaResolver(
            schema(mapOf(field to fieldSchema(QueryCapability.RANGE to physical.value))),
        )
        val cases = listOf(
            TodayFilter(field) to TodayFilter(physical),
            BeforeTodayFilter(field, "12:00") to BeforeTodayFilter(physical, "12:00"),
            TomorrowFilter(field) to TomorrowFilter(physical),
            ThisWeekFilter(field) to ThisWeekFilter(physical),
            NextWeekFilter(field) to NextWeekFilter(physical),
            LastWeekFilter(field) to LastWeekFilter(physical),
            ThisMonthFilter(field) to ThisMonthFilter(physical),
            LastMonthFilter(field) to LastMonthFilter(physical),
            RecentDaysFilter(field, 7) to RecentDaysFilter(physical, 7),
            EarlierDaysFilter(field, 7) to EarlierDaysFilter(physical, 7),
            YesterdayFilter(field) to YesterdayFilter(physical),
            NextMonthFilter(field) to NextMonthFilter(physical),
            LastYearFilter(field) to LastYearFilter(physical),
            ThisYearFilter(field) to ThisYearFilter(physical),
            NextYearFilter(field) to NextYearFilter(physical),
        )

        cases.forEach { (input, expected) ->
            resolver.resolve(input).assert().isEqualTo(
                QuerySchemaResolution(expected, QueryCompatibilityLevel.EXACT),
            )
        }
    }

    @Test
    fun `root metadata filters should be exact when their system field has an exact binding`() {
        val schema = schema(
            mapOf(
                LogicalField("aggregateId") to fieldSchema(QueryCapability.EXACT_MATCH to "_id"),
                LogicalField("tenantId") to fieldSchema(QueryCapability.EXACT_MATCH to "tenant_id"),
                LogicalField("ownerId") to fieldSchema(QueryCapability.EXACT_MATCH to "owner_id"),
                LogicalField("spaceId") to fieldSchema(QueryCapability.EXACT_MATCH to "space_id"),
                LogicalField("deleted") to fieldSchema(QueryCapability.EXACT_MATCH to "is_deleted"),
            ),
        )
        val resolver = QuerySchemaResolver(schema)
        val filters = listOf(
            IdFilter("id"),
            IdsFilter(listOf("id")),
            AggregateIdFilter("id"),
            AggregateIdsFilter(listOf("id")),
            TenantIdFilter("tenant"),
            OwnerIdFilter("owner"),
            SpaceIdFilter("space"),
            DeletionFilter(DeletionState.ACTIVE),
        )

        filters.forEach { filter ->
            resolver.resolve(filter).assert().isEqualTo(
                QuerySchemaResolution(filter, QueryCompatibilityLevel.EXACT),
            )
        }
    }

    @Test
    fun `unknown field without dynamic ancestor should remain unchanged and compatible`() {
        val filter = EqualFilter(LogicalField("state.unknown"), json("value"))

        QuerySchemaResolver(schema()).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `dynamic ancestor should append the suffix and remain exact`() {
        val filter = EqualFilter(LogicalField("state.attributes.color"), json("blue"))
        val schema = schema(
            mapOf(
                LogicalField("state.attributes") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.attributes",
                    dynamicChildren = true,
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(
                filter.copy(field = LogicalField("document.attributes.color")),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `known field without required capability should be incompatible`() {
        val filter = GreaterThanFilter(LogicalField("state.status"), json(1))
        val schema = schema(
            mapOf(
                LogicalField("state.status") to fieldSchema(QueryCapability.EXACT_MATCH to "status_keyword"),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE),
        )
    }

    @Test
    fun `structural filters should rewrite operands and combine their compatibility levels`() {
        val field = LogicalField("state.status")
        val physical = LogicalField("document.status")
        val exact = EqualFilter(field, json("OPEN"))
        val unknown = EqualFilter(LogicalField("state.unknown"), json("value"))
        val incompatible = GreaterThanFilter(field, json(1))
        val resolver = QuerySchemaResolver(
            schema(mapOf(field to fieldSchema(QueryCapability.EXACT_MATCH to physical.value))),
        )

        resolver.resolve(AndFilter(listOf(exact, MatchAllFilter))).assert().isEqualTo(
            QuerySchemaResolution(
                AndFilter(listOf(exact.copy(field = physical), MatchAllFilter)),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(OrFilter(listOf(exact, unknown))).assert().isEqualTo(
            QuerySchemaResolution(
                OrFilter(listOf(exact.copy(field = physical), unknown)),
                QueryCompatibilityLevel.COMPATIBLE,
            ),
        )
        resolver.resolve(NorFilter(listOf(incompatible, MatchNoneFilter))).assert().isEqualTo(
            QuerySchemaResolution(
                NorFilter(listOf(incompatible, MatchNoneFilter)),
                QueryCompatibilityLevel.INCOMPATIBLE,
            ),
        )
    }

    @Test
    fun `nested element match should validate absolute logical fields and emit relative physical children`() {
        val filter = ElementMatchFilter(
            LogicalField("state.orders"),
            ElementMatchFilter(
                LogicalField("items"),
                EqualFilter(LogicalField("sku"), json("sku-1")),
            ),
        )
        val schema = schema(
            mapOf(
                LogicalField("state.orders") to fieldSchema(QueryCapability.ELEMENT_SCOPE to "document.orders"),
                LogicalField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
                LogicalField("state.orders.items.sku") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.orders.items.sku.keyword",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(
                ElementMatchFilter(
                    LogicalField("document.orders"),
                    ElementMatchFilter(
                        LogicalField("items"),
                        EqualFilter(LogicalField("sku.keyword"), json("sku-1")),
                    ),
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `search should use exact field bindings or an explicit model fallback`() {
        val title = LogicalField("state.title")
        val body = LogicalField("state.body")
        val schema = schema(
            fields = mapOf(
                title to fieldSchema(QueryCapability.FULL_TEXT_TERMS to "document.title.text"),
                body to fieldSchema(QueryCapability.FULL_TEXT_PHRASE to "document.body.text"),
            ),
            capabilities = setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE),
        )
        val resolver = QuerySchemaResolver(schema)

        resolver.resolve(SearchFilter("hello", linkedSetOf(title))).assert().isEqualTo(
            QuerySchemaResolution(
                SearchFilter("hello", linkedSetOf(LogicalField("document.title.text"))),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(SearchFilter("hello world", linkedSetOf(body), SearchMode.PHRASE)).assert().isEqualTo(
            QuerySchemaResolution(
                SearchFilter(
                    "hello world",
                    linkedSetOf(LogicalField("document.body.text")),
                    SearchMode.PHRASE,
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(SearchFilter("hello", linkedSetOf(LogicalField("state.unknown")))).assert().isEqualTo(
            QuerySchemaResolution(SearchFilter("hello"), QueryCompatibilityLevel.COMPATIBLE),
        )
        resolver.resolve(SearchFilter("hello")).assert().isEqualTo(
            QuerySchemaResolution(SearchFilter("hello"), QueryCompatibilityLevel.EXACT),
        )
    }

    @Test
    fun `search should be incompatible when neither field nor model supports its mode`() {
        val filter = SearchFilter("hello", linkedSetOf(LogicalField("state.title")))

        QuerySchemaResolver(schema()).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE),
        )
        QuerySchemaResolver(schema()).resolve(SearchFilter("hello")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `projection sort and list query should use their capability-specific physical paths`() {
        val field = LogicalField("state.name")
        val schema = schema(
            mapOf(
                field to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.name.keyword",
                    QueryCapability.PRESENCE to "document.name",
                    QueryCapability.SORT to "document.name.sort",
                ),
            ),
        )
        val resolver = QuerySchemaResolver(schema)
        val query = ListQuery(
            filter = EqualFilter(field, json("name")),
            projection = Projection(include = listOf(field.value), exclude = listOf(field.value)),
            sort = listOf(Sort(field.value, Sort.Direction.DESC)),
            limit = 7,
        )

        resolver.resolve(query).assert().isEqualTo(
            QuerySchemaResolution(
                ListQuery(
                    filter = EqualFilter(LogicalField("document.name.keyword"), json("name")),
                    projection = Projection(
                        include = listOf("document.name"),
                        exclude = listOf("document.name"),
                    ),
                    sort = listOf(Sort("document.name.sort", Sort.Direction.DESC)),
                    limit = 7,
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `aggregation should validate relative elements groups and numeric expression fields`() {
        val query = AggregationQuery(
            filter = EqualFilter(LogicalField("state.status"), json("OPEN")),
            elements = listOf(
                AggregationElement(
                    LogicalField("state.orders"),
                    EqualFilter(LogicalField("active"), json(true)),
                ),
                AggregationElement(
                    LogicalField("items"),
                    GreaterThanFilter(LogicalField("price"), json(0)),
                ),
            ),
            groupBy = listOf(
                AggregationGroup.Terms(LogicalField("category"), "category"),
                AggregationGroup.Histogram(LogicalField("price"), "price", 10.0),
                AggregationGroup.DateHistogram(
                    LogicalField("createdAt"),
                    "createdAt",
                    AggregationDateUnit.DAY,
                ),
            ),
            metrics = listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.SUBTRACT,
                        AggregationExpression.Field(LogicalField("price")),
                        AggregationExpression.Field(LogicalField("discount")),
                    ),
                    "total",
                ),
            ),
            sort = listOf(Sort("category", Sort.Direction.ASC)),
        )
        val schema = schema(
            mapOf(
                LogicalField("state.status") to fieldSchema(QueryCapability.EXACT_MATCH to "document.status"),
                LogicalField("state.orders") to fieldSchema(QueryCapability.ELEMENT_SCOPE to "document.orders"),
                LogicalField("state.orders.active") to fieldSchema(QueryCapability.EXACT_MATCH to "document.orders.active"),
                LogicalField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
                LogicalField("state.orders.items.category") to fieldSchema(
                    QueryCapability.AGGREGATE_TERMS to "document.orders.items.category",
                ),
                LogicalField("state.orders.items.price") to fieldSchema(
                    QueryCapability.RANGE to "document.orders.items.price",
                    QueryCapability.AGGREGATE_NUMERIC to "document.orders.items.price",
                ),
                LogicalField("state.orders.items.discount") to fieldSchema(
                    QueryCapability.AGGREGATE_NUMERIC to "document.orders.items.discount",
                ),
                LogicalField("state.orders.items.createdAt") to fieldSchema(
                    QueryCapability.AGGREGATE_TEMPORAL to "document.orders.items.created_at",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(query).assert().isEqualTo(
            QuerySchemaResolution(query, QueryCompatibilityLevel.EXACT),
        )
    }

    private fun schema(
        fields: Map<LogicalField, QueryFieldSchema> = emptyMap(),
        capabilities: Set<QueryCapability> = emptySet(),
    ) = QueryModelSchema(QueryModel.SNAPSHOT, capabilities, fields)

    private fun fieldSchema(
        vararg bindings: Pair<QueryCapability, String>,
        dynamicChildren: Boolean = false,
    ) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(QueryValueType.STRING),
        nullable = false,
        required = true,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = dynamicChildren,
        bindings = bindings.associate { (capability, path) ->
            capability to QueryFieldBinding(path, storageType = null)
        },
    )

    private fun json(value: Any): JsonNode = JsonNodeFactory.instance.pojoNode(value)
}
