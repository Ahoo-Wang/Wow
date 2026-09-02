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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
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
import me.ahoo.wow.api.query.QueryField
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
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

@Suppress("LargeClass")
class QuerySchemaResolverTest {
    @Test
    fun `filter capability matrix should rewrite exact literal range and presence fields`() {
        val field = QueryField("state.value")
        val exact = QueryField("document.value.keyword")
        val literal = QueryField("document.value.literal")
        val range = QueryField("document.value.range")
        val presence = QueryField("document.value")
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
            ContainsFilter(field, "one") to ContainsFilter(literal, "one"),
            StartsWithFilter(field, "one") to StartsWithFilter(literal, "one"),
            EndsWithFilter(field, "one") to EndsWithFilter(literal, "one"),
            GreaterThanFilter(field, one) to GreaterThanFilter(range, one),
            GreaterThanOrEqualFilter(field, one) to GreaterThanOrEqualFilter(range, one),
            LessThanFilter(field, two) to LessThanFilter(range, two),
            LessThanOrEqualFilter(field, two) to LessThanOrEqualFilter(range, two),
            BetweenFilter(field, one, two) to BetweenFilter(range, one, two),
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
    fun `declared built-in field types should reject incompatible predicate values`() {
        val integer = QueryField("state.createdAt")
        val string = QueryField("state.status")
        val boolean = QueryField("state.active")
        val strings = QueryField("state.labels")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    integer to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.created_at",
                        QueryCapability.RANGE to "document.created_at",
                        valueTypes = setOf(QueryValueType.INTEGER),
                    ),
                    string to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.status",
                        valueTypes = setOf(QueryValueType.STRING),
                    ),
                    boolean to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.active",
                        valueTypes = setOf(QueryValueType.BOOLEAN),
                    ),
                    strings to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.labels",
                        valueTypes = setOf(QueryValueType.STRING),
                        cardinality = QueryCardinality.MANY,
                    ),
                ),
            ),
        )
        val text = JsonNodeFactory.instance.stringNode("not-a-timestamp")
        val number = JsonNodeFactory.instance.numberNode(1)
        val filters = listOf(
            EqualFilter(integer, text),
            EqualFilter(integer, JsonNodeFactory.instance.numberNode(1.5)),
            EqualFilter(integer, JsonNodeFactory.instance.pojoNode(1.5)),
            EqualFilter(strings, JsonNodeFactory.instance.pojoNode(listOf("value", 1))),
            NotEqualFilter(boolean, number),
            InFilter(string, listOf(number)),
            NotInFilter(string, listOf(number)),
            ContainsAllFilter(strings, listOf(number)),
            GreaterThanFilter(integer, text),
            GreaterThanOrEqualFilter(integer, text),
            LessThanFilter(integer, text),
            LessThanOrEqualFilter(integer, text),
            BetweenFilter(integer, number, text),
        )

        filters.forEach { filter ->
            val resolution = resolver.resolve(filter)

            resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
            QuerySchemaValidationMode.entries.forEach { mode ->
                assertThrows<QuerySchemaValidationException> {
                    resolution.requireAccepted(mode)
                }
            }
        }
    }

    @Test
    fun `declared built-in field types should accept matching values`() {
        val field = QueryField("state.value")
        val physical = QueryField("document.value")

        listOf(
            QueryValueType.STRING to JsonNodeFactory.instance.stringNode("value"),
            QueryValueType.INTEGER to JsonNodeFactory.instance.numberNode(1),
            QueryValueType.INTEGER to JsonNodeFactory.instance.numberNode(1.0),
            QueryValueType.INTEGER to JsonNodeFactory.instance.pojoNode(1.0),
            QueryValueType.INTEGER to JsonNodeFactory.instance.pojoNode(BigDecimal("1.0")),
            QueryValueType.DECIMAL to JsonNodeFactory.instance.numberNode(1.5),
            QueryValueType.BOOLEAN to JsonNodeFactory.instance.booleanNode(true),
            QueryValueType.OBJECT to JsonNodeFactory.instance.pojoNode(mapOf("id" to "value")),
            QueryValueType.STRING to JsonNodeFactory.instance.pojoNode(listOf("one", "two")),
            QueryValueType.INTEGER to JsonNodeFactory.instance.pojoNode(Instant.EPOCH),
            QueryValueType.STRING to JsonNodeFactory.instance.pojoNode(UUID.randomUUID()),
            QueryValueType.from("UUID") to JsonNodeFactory.instance.stringNode("value"),
        ).forEach { (valueType, value) ->
            val resolver = QuerySchemaResolver(
                schema(
                    mapOf(
                        field to fieldSchema(
                            QueryCapability.EXACT_MATCH to physical.value,
                            valueTypes = setOf(valueType),
                        ),
                    ),
                ),
            )

            resolver.resolve(EqualFilter(field, value)).assert().isEqualTo(
                QuerySchemaResolution(
                    EqualFilter(physical, value),
                    QueryCompatibilityLevel.EXACT,
                ),
            )
        }
    }

    @Test
    fun `collection filters should require collection cardinality`() {
        val single = QueryField("state.single")
        val many = QueryField("state.many")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    single to fieldSchema(
                        QueryCapability.PRESENCE to "document.single",
                        QueryCapability.EXACT_MATCH to "document.single",
                    ),
                    many to fieldSchema(
                        QueryCapability.PRESENCE to "document.many",
                        QueryCapability.EXACT_MATCH to "document.many",
                        cardinality = QueryCardinality.MANY,
                    ),
                ),
            ),
        )

        val values = listOf(json("value"))
        listOf(
            IsEmptyFilter(single),
            ContainsAllFilter(single, values),
        ).forEach { filter ->
            resolver.resolve(filter).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        }
        listOf(
            IsEmptyFilter(many) to IsEmptyFilter(QueryField("document.many")),
            ContainsAllFilter(many, values) to ContainsAllFilter(QueryField("document.many"), values),
        ).forEach { (filter, expected) ->
            resolver.resolve(filter).assert().isEqualTo(
                QuerySchemaResolution(expected, QueryCompatibilityLevel.EXACT),
            )
        }
        listOf(
            IsEmptyFilter(QueryField("state.unknown")),
            ContainsAllFilter(QueryField("state.unknown"), values),
        ).forEach { filter ->
            resolver.resolve(filter).compatibility.assert().isEqualTo(QueryCompatibilityLevel.COMPATIBLE)
        }

        val dynamic = QueryField("tags.department")
        QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("tags") to fieldSchema(
                        QueryCapability.PRESENCE to "document.tags",
                        QueryCapability.EXACT_MATCH to "document.tags",
                        dynamicChildren = true,
                    ),
                ),
            ),
        ).let { dynamicResolver ->
            listOf(
                IsEmptyFilter(dynamic) to IsEmptyFilter(QueryField("document.tags.department")),
                ContainsAllFilter(dynamic, values) to ContainsAllFilter(
                    QueryField("document.tags.department"),
                    values,
                ),
            ).forEach { (filter, expected) ->
                dynamicResolver.resolve(filter).assert().isEqualTo(
                    QuerySchemaResolution(expected, QueryCompatibilityLevel.EXACT),
                )
            }
        }
    }

    @Test
    fun `empty string filters should require a single string field`() {
        val name = QueryField("state.name")
        val aliases = QueryField("state.aliases")
        val count = QueryField("state.count")
        val untyped = QueryField("state.untyped")
        val custom = QueryField("state.custom")
        val mixed = QueryField("state.mixed")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    name to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.name.keyword",
                        valueTypes = setOf(QueryValueType.STRING),
                    ),
                    aliases to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.aliases.keyword",
                        cardinality = QueryCardinality.MANY,
                        valueTypes = setOf(QueryValueType.STRING),
                    ),
                    count to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.count",
                        valueTypes = setOf(QueryValueType.INTEGER),
                    ),
                    untyped to fieldSchema(QueryCapability.EXACT_MATCH to "document.untyped"),
                    custom to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.custom",
                        valueTypes = setOf(QueryValueType("CUSTOM")),
                    ),
                    mixed to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.mixed",
                        valueTypes = setOf(QueryValueType.STRING, QueryValueType.INTEGER),
                    ),
                ),
            ),
        )

        listOf("IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING").forEach { operator ->
            fun filter(field: QueryField) = JsonSerializer.readValue(
                """{"op":"$operator","field":"${field.path}"}""",
                FilterExpression::class.java,
            )

            val resolved = resolver.resolve(filter(name))
            resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            JsonSerializer.writeValueAsString(resolved.value).contains("document.name.keyword").assert().isTrue()
            listOf(aliases, count, untyped, custom, mixed).forEach { incompatible ->
                resolver.resolve(filter(incompatible)).compatibility.assert()
                    .isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
            }
        }
    }

    @Test
    fun `null equality filters should use presence bindings without changing their shape`() {
        val field = QueryField("state.note")
        val physical = QueryField("document.note")
        val nullValue = JsonNodeFactory.instance.nullNode()
        val resolver = QuerySchemaResolver(
            schema(mapOf(field to fieldSchema(QueryCapability.PRESENCE to physical.value))),
        )

        resolver.resolve(EqualFilter(field, nullValue)).assert().isEqualTo(
            QuerySchemaResolution(
                EqualFilter(physical, nullValue),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(NotEqualFilter(field, nullValue)).assert().isEqualTo(
            QuerySchemaResolution(
                NotEqualFilter(physical, nullValue),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `deserialized null equality should resolve before normalizing to presence`() {
        val filter = JsonSerializer.readValue(
            """{"op":"EQ","field":"state.note","value":null}""",
            FilterExpression::class.java,
        )
        val schema = schema(
            mapOf(
                QueryField("state.note") to fieldSchema(
                    QueryCapability.PRESENCE to "document.note",
                ),
            ),
        )

        val resolved = QuerySchemaResolver(schema).resolve(filter)
            .requireAccepted(QuerySchemaValidationMode.STRICT)
        val normalized = FilterNormalizer(defaultDeletionState = null).normalize(resolved)

        normalized.assert().isEqualTo(IsNullFilter(QueryField("document.note")))
    }

    @Test
    fun `every relative time filter should apply the negotiated epoch unit and physical field`() {
        val field = QueryField("state.createdAt")
        val physical = QueryField("document.created_at")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to physical.value,
                        semanticType = Temporal.Epoch(TimeUnit.SECONDS),
                    ),
                ),
            ),
        )
        val cases = listOf(
            TodayFilter(field) to TodayFilter(physical, timeUnit = TimeUnit.SECONDS),
            BeforeTodayFilter(field, "12:00") to BeforeTodayFilter(
                physical,
                "12:00",
                timeUnit = TimeUnit.SECONDS,
            ),
            TomorrowFilter(field) to TomorrowFilter(physical, timeUnit = TimeUnit.SECONDS),
            ThisWeekFilter(field) to ThisWeekFilter(physical, timeUnit = TimeUnit.SECONDS),
            NextWeekFilter(field) to NextWeekFilter(physical, timeUnit = TimeUnit.SECONDS),
            LastWeekFilter(field) to LastWeekFilter(physical, timeUnit = TimeUnit.SECONDS),
            ThisMonthFilter(field) to ThisMonthFilter(physical, timeUnit = TimeUnit.SECONDS),
            LastMonthFilter(field) to LastMonthFilter(physical, timeUnit = TimeUnit.SECONDS),
            RecentDaysFilter(field, 7) to RecentDaysFilter(physical, 7, timeUnit = TimeUnit.SECONDS),
            EarlierDaysFilter(field, 7) to EarlierDaysFilter(physical, 7, timeUnit = TimeUnit.SECONDS),
            YesterdayFilter(field) to YesterdayFilter(physical, timeUnit = TimeUnit.SECONDS),
            NextMonthFilter(field) to NextMonthFilter(physical, timeUnit = TimeUnit.SECONDS),
            LastYearFilter(field) to LastYearFilter(physical, timeUnit = TimeUnit.SECONDS),
            ThisYearFilter(field) to ThisYearFilter(physical, timeUnit = TimeUnit.SECONDS),
            NextYearFilter(field) to NextYearFilter(physical, timeUnit = TimeUnit.SECONDS),
        )

        cases.forEach { (input, expected) ->
            resolver.resolve(input).assert().isEqualTo(
                QuerySchemaResolution(expected, QueryCompatibilityLevel.EXACT),
            )
        }
    }

    @Test
    fun `formatted temporal should inject its pattern without changing the filter time unit`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Formatted("yyyy-MM-dd"),
                    ),
                ),
            ),
        )

        resolver.resolve(TodayFilter(field, timeUnit = TimeUnit.SECONDS)).assert().isEqualTo(
            QuerySchemaResolution(
                TodayFilter(
                    QueryField("document.created_at"),
                    datePattern = "yyyy-MM-dd",
                    timeUnit = TimeUnit.SECONDS,
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(TodayFilter(field, datePattern = "yyyy-MM-dd")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `formatted temporal should reject a conflicting explicit pattern`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Formatted("yyyy-MM-dd"),
                    ),
                ),
            ),
        )

        resolver.resolve(TodayFilter(field, datePattern = "yyyy/MM/dd")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `epoch temporal should reject an explicit date pattern`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Epoch(TimeUnit.SECONDS),
                    ),
                ),
            ),
        )

        resolver.resolve(TodayFilter(field, datePattern = "yyyy-MM-dd")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `date temporal should preserve native configuration and reject an explicit pattern`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Date,
                    ),
                ),
            ),
        )

        resolver.resolve(TodayFilter(field)).assert().isEqualTo(
            QuerySchemaResolution(
                TodayFilter(QueryField("document.created_at")),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(TodayFilter(field, datePattern = "yyyy-MM-dd")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `relative time filter should reject a field without temporal semantics`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(mapOf(field to fieldSchema(QueryCapability.RANGE to "document.created_at"))),
        )

        resolver.resolve(TodayFilter(field)).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `unknown relative time field should preserve caller configuration as compatible`() {
        val filter = TodayFilter(
            QueryField("state.createdAt"),
            datePattern = "yyyy-MM-dd",
            timeUnit = TimeUnit.SECONDS,
        )

        QuerySchemaResolver(schema()).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `epoch temporal should reject an opaque formatter in strict mode`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Epoch(TimeUnit.SECONDS),
                    ),
                ),
            ),
        )
        val resolution = resolver.resolve(
            TodayFilter(field, dateFormatter = DateTimeFormatter.ISO_DATE),
        )

        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        assertThrows<QuerySchemaValidationException> {
            resolution.requireAccepted(QuerySchemaValidationMode.STRICT)
        }
    }

    @Test
    fun `formatted temporal should reject an opaque formatter with matching or missing pattern`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Formatted("yyyy-MM-dd"),
                    ),
                ),
            ),
        )

        listOf(
            TodayFilter(field, dateFormatter = DateTimeFormatter.ISO_DATE),
            TodayFilter(
                field,
                datePattern = "yyyy-MM-dd",
                dateFormatter = DateTimeFormatter.ISO_DATE,
            ),
        ).forEach { filter ->
            resolver.resolve(filter).compatibility
                .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        }
    }

    @Test
    fun `date temporal should reject an opaque formatter`() {
        val field = QueryField("state.createdAt")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.RANGE to "document.created_at",
                        semanticType = Temporal.Date,
                    ),
                ),
            ),
        )

        resolver.resolve(TodayFilter(field, dateFormatter = DateTimeFormatter.ISO_DATE)).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `root metadata filters should be exact when their system field has an exact binding`() {
        val schema = schema(
            mapOf(
                QueryField("aggregateId") to fieldSchema(QueryCapability.EXACT_MATCH to "_id"),
                QueryField("tenantId") to fieldSchema(QueryCapability.EXACT_MATCH to "tenant_id"),
                QueryField("ownerId") to fieldSchema(QueryCapability.EXACT_MATCH to "owner_id"),
                QueryField("spaceId") to fieldSchema(QueryCapability.EXACT_MATCH to "space_id"),
                QueryField("deleted") to fieldSchema(QueryCapability.EXACT_MATCH to "is_deleted"),
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
        val filter = EqualFilter(QueryField("state.unknown"), json("value"))
        val schema = schema(
            mapOf(QueryField("state") to fieldSchema(dynamicChildren = false)),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `dynamic ancestor should append the suffix and remain exact`() {
        val filter = EqualFilter(QueryField("state.attributes.color"), json("blue"))
        val schema = schema(
            mapOf(
                QueryField("state.attributes") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.attributes",
                    dynamicChildren = true,
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(
                filter.copy(field = QueryField("document.attributes.color")),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `bound tags without dynamic support should be incompatible`() {
        val filter = EqualFilter(QueryField("tags.department"), json("eng"))
        val schema = schema(
            mapOf(
                QueryField("tags") to fieldSchema(dynamicChildren = false),
            ),
        )

        val resolution = QuerySchemaResolver(schema).resolve(filter)

        resolution.assert().isEqualTo(QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE))
        assertThrows<QuerySchemaValidationException> {
            resolution.requireAccepted(QuerySchemaValidationMode.COMPATIBLE)
        }
    }

    @Test
    fun `dynamic nested suffix should not become an exact element scope`() {
        val query = AggregationQuery(
            elements = listOf(
                AggregationElement(QueryField("state.orders")),
                AggregationElement(QueryField("items")),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val schema = schema(
            mapOf(
                QueryField("state.orders") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders",
                    dynamicChildren = true,
                ),
            ),
        )

        val resolution = QuerySchemaResolver(schema).resolve(query)

        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.COMPATIBLE)
        assertThrows<QuerySchemaValidationException> {
            resolution.requireAccepted(QuerySchemaValidationMode.STRICT)
        }
    }

    @Test
    fun `known field without required capability should be incompatible`() {
        val filter = GreaterThanFilter(QueryField("state.status"), json(1))
        val schema = schema(
            mapOf(
                QueryField("state.status") to fieldSchema(QueryCapability.EXACT_MATCH to "status_keyword"),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE),
        )
    }

    @Test
    fun `structural filters should rewrite operands and combine their compatibility levels`() {
        val field = QueryField("state.status")
        val physical = QueryField("document.status")
        val exact = EqualFilter(field, json("OPEN"))
        val unknown = EqualFilter(QueryField("state.unknown"), json("value"))
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
            QueryField("state.orders"),
            ElementMatchFilter(
                QueryField("items"),
                EqualFilter(QueryField("sku"), json("sku-1")),
            ),
        )
        val schema = schema(
            mapOf(
                QueryField("state.orders") to fieldSchema(QueryCapability.ELEMENT_SCOPE to "document.orders"),
                QueryField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
                QueryField("state.orders.items.sku") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.orders.items.sku.keyword",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(
                ElementMatchFilter(
                    QueryField("document.orders"),
                    ElementMatchFilter(
                        QueryField("items"),
                        EqualFilter(QueryField("sku.keyword"), json("sku-1")),
                    ),
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `element match should reject a child binding outside its physical container`() {
        val filter = ElementMatchFilter(
            QueryField("state.orders"),
            EqualFilter(QueryField("sku"), json("sku-1")),
        )
        val schema = schema(
            mapOf(
                QueryField("state.orders") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders",
                ),
                QueryField("state.orders.sku") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.skus.keyword",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(filter).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `root filter should reject a field below an element scope`() {
        val field = QueryField("state.orders.price")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    field to fieldSchema(QueryCapability.EXACT_MATCH to "document.orders.price"),
                ),
            ),
        )

        resolver.resolve(EqualFilter(field, json(1))).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `root search should not downgrade an element scoped field to model full text`() {
        val field = QueryField("state.orders.note")
        val resolver = QuerySchemaResolver(
            schema(
                fields = mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    field to fieldSchema(QueryCapability.FULL_TEXT_TERMS to "document.orders.note"),
                ),
                capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
            ),
        )
        val filter = SearchFilter("note", setOf(field))

        resolver.resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE),
        )
    }

    @Test
    fun `search should use exact field bindings or an explicit model fallback`() {
        val title = QueryField("state.title")
        val body = QueryField("state.body")
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
                SearchFilter("hello", linkedSetOf(QueryField("document.title.text"))),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(SearchFilter("hello world", linkedSetOf(body), SearchMode.PHRASE)).assert().isEqualTo(
            QuerySchemaResolution(
                SearchFilter(
                    "hello world",
                    linkedSetOf(QueryField("document.body.text")),
                    SearchMode.PHRASE,
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        resolver.resolve(SearchFilter("hello", linkedSetOf(QueryField("state.unknown")))).assert().isEqualTo(
            QuerySchemaResolution(SearchFilter("hello"), QueryCompatibilityLevel.COMPATIBLE),
        )
        resolver.resolve(SearchFilter("hello")).assert().isEqualTo(
            QuerySchemaResolution(SearchFilter("hello"), QueryCompatibilityLevel.EXACT),
        )
    }

    @Test
    fun `search should be incompatible when neither field nor model supports its mode`() {
        val filter = SearchFilter("hello", linkedSetOf(QueryField("state.title")))

        QuerySchemaResolver(schema()).resolve(filter).assert().isEqualTo(
            QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE),
        )
        QuerySchemaResolver(schema()).resolve(SearchFilter("hello")).compatibility
            .assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `projection sort and list query should use their capability-specific physical paths`() {
        val field = QueryField("state.name")
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
            projection = Projection(include = listOf(field.path), exclude = listOf(field.path)),
            sort = listOf(Sort(field.path, Sort.Direction.DESC)),
            limit = 7,
        )

        resolver.resolve(query).assert().isEqualTo(
            QuerySchemaResolution(
                ListQuery(
                    filter = EqualFilter(QueryField("document.name.keyword"), json("name")),
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
    fun `projection should use its path without requiring a presence binding`() {
        val field = QueryField("state.name")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    field to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.name.keyword",
                        projectionPath = "document.name",
                    ),
                ),
            ),
        )

        resolver.resolve(Projection(include = listOf(field.path))).assert().isEqualTo(
            QuerySchemaResolution(
                Projection(include = listOf("document.name")),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `root sort should reject a field below an element scope`() {
        val field = QueryField("state.orders.price")
        val sort = listOf(Sort(field.path, Sort.Direction.ASC))
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    field to fieldSchema(QueryCapability.SORT to "document.orders.price"),
                ),
            ),
        )

        resolver.resolve(sort).assert().isEqualTo(
            QuerySchemaResolution(sort, QueryCompatibilityLevel.INCOMPATIBLE),
        )
    }

    @Test
    fun `projection should allow source fields below an element scope`() {
        val field = QueryField("state.orders.price")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    field to fieldSchema(QueryCapability.PRESENCE to "document.orders.price"),
                ),
            ),
        )

        resolver.resolve(Projection(include = listOf(field.path))).assert().isEqualTo(
            QuerySchemaResolution(
                Projection(include = listOf("document.orders.price")),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `projection should preserve a raw backend wildcard as compatible`() {
        val projection = Projection(include = listOf("state.*"))

        QuerySchemaResolver(schema()).resolve(projection).assert().isEqualTo(
            QuerySchemaResolution(projection, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `sort should preserve a raw backend wildcard as compatible`() {
        val sort = listOf(Sort("state.*", Sort.Direction.ASC))

        QuerySchemaResolver(schema()).resolve(sort).assert().isEqualTo(
            QuerySchemaResolution(sort, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `query should combine component compatibility by the strictest level`() {
        val compatible = QueryField("state.unknown")
        val incompatible = QueryField("state.declared")
        val resolver = QuerySchemaResolver(schema(mapOf(incompatible to fieldSchema())))
        val filters = mapOf(
            QueryCompatibilityLevel.EXACT to MatchAllFilter,
            QueryCompatibilityLevel.COMPATIBLE to EqualFilter(compatible, json(1)),
            QueryCompatibilityLevel.INCOMPATIBLE to EqualFilter(incompatible, json(1)),
        )
        val projections = mapOf(
            QueryCompatibilityLevel.EXACT to Projection.ALL,
            QueryCompatibilityLevel.COMPATIBLE to Projection(include = listOf(compatible.value)),
            QueryCompatibilityLevel.INCOMPATIBLE to Projection(include = listOf(incompatible.value)),
        )
        val sorts = mapOf(
            QueryCompatibilityLevel.EXACT to emptyList(),
            QueryCompatibilityLevel.COMPATIBLE to listOf(Sort(compatible.value, Sort.Direction.ASC)),
            QueryCompatibilityLevel.INCOMPATIBLE to listOf(Sort(incompatible.value, Sort.Direction.ASC)),
        )

        QueryCompatibilityLevel.entries.forEach { filterLevel ->
            QueryCompatibilityLevel.entries.forEach { projectionLevel ->
                QueryCompatibilityLevel.entries.forEach { sortLevel ->
                    val expected = maxOf(filterLevel, projectionLevel, sortLevel)
                    resolver.resolve(
                        ListQuery(
                            filter = filters.getValue(filterLevel),
                            projection = projections.getValue(projectionLevel),
                            sort = sorts.getValue(sortLevel),
                            limit = 1,
                        ),
                    ).compatibility.assert().isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `empty projection and cursor sort should preserve masking contracts`() {
        val snapshotResolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.secret") to fieldSchema(maskRule = fullMaskRule()),
                ),
            ),
        )
        snapshotResolver.resolve(Projection()).assert().isEqualTo(
            QuerySchemaResolution(Projection.ALL, QueryCompatibilityLevel.EXACT),
        )

        val eventResolver = QuerySchemaResolver(
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField("body.body.secret") to fieldSchema(maskRule = fullMaskRule()),
                    QueryField("body.bodyType") to fieldSchema(
                        QueryCapability.PRESENCE to "document.events.type",
                    ),
                ),
            ),
        )
        eventResolver.resolve(Projection()).assert().isEqualTo(
            QuerySchemaResolution(Projection.ALL, QueryCompatibilityLevel.EXACT),
        )
        eventResolver.resolve(CursorQuery(MatchAllFilter)).assert().isEqualTo(
            QuerySchemaResolution(CursorQuery(MatchAllFilter), QueryCompatibilityLevel.EXACT),
        )
    }

    @Test
    fun `aggregation should validate relative elements groups and numeric expression fields`() {
        val query = AggregationQuery(
            filter = EqualFilter(QueryField("state.status"), json("OPEN")),
            elements = listOf(
                AggregationElement(
                    QueryField("state.orders"),
                    EqualFilter(QueryField("active"), json(true)),
                ),
                AggregationElement(
                    QueryField("items"),
                    GreaterThanFilter(QueryField("price"), json(0)),
                ),
            ),
            groupBy = listOf(
                AggregationGroup.Terms(QueryField("category"), "category"),
                AggregationGroup.Histogram(QueryField("price"), "price", 10.0),
                AggregationGroup.DateHistogram(
                    QueryField("createdAt"),
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
                        AggregationExpression.Field(QueryField("price")),
                        AggregationExpression.Field(QueryField("discount")),
                    ),
                    "total",
                ),
            ),
            sort = listOf(Sort("category", Sort.Direction.ASC)),
        )
        val schema = schema(
            mapOf(
                QueryField("state.status") to fieldSchema(QueryCapability.EXACT_MATCH to "document.status"),
                QueryField("state.orders") to fieldSchema(QueryCapability.ELEMENT_SCOPE to "document.orders"),
                QueryField("state.orders.active") to fieldSchema(QueryCapability.EXACT_MATCH to "document.orders.active"),
                QueryField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
                QueryField("state.orders.items.category") to fieldSchema(
                    QueryCapability.AGGREGATE_TERMS to "document.orders.items.category",
                ),
                QueryField("state.orders.items.price") to fieldSchema(
                    QueryCapability.RANGE to "document.orders.items.price",
                    QueryCapability.AGGREGATE_NUMERIC to "document.orders.items.price",
                ),
                QueryField("state.orders.items.discount") to fieldSchema(
                    QueryCapability.AGGREGATE_NUMERIC to "document.orders.items.discount",
                ),
                QueryField("state.orders.items.createdAt") to fieldSchema(
                    QueryCapability.AGGREGATE_TEMPORAL to "document.orders.items.created_at",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(query).assert().isEqualTo(
            QuerySchemaResolution(
                query.copy(
                    filter = EqualFilter(QueryField("document.status"), json("OPEN")),
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `aggregation should return rewritten root and nested element filters without mutating the request`() {
        val rootFilter = TodayFilter(QueryField("state.createdAt"))
        val outerFilter = EqualFilter(QueryField("status"), json("PAID"))
        val innerFilter = TodayFilter(QueryField("createdAt"))
        val query = AggregationQuery(
            filter = rootFilter,
            elements = listOf(
                AggregationElement(QueryField("state.orders"), outerFilter),
                AggregationElement(QueryField("items"), innerFilter),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val schema = schema(
            mapOf(
                QueryField("state.createdAt") to fieldSchema(
                    QueryCapability.RANGE to "document.created_at",
                    semanticType = Temporal.Epoch(TimeUnit.SECONDS),
                ),
                QueryField("state.orders") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders",
                ),
                QueryField("state.orders.status") to fieldSchema(
                    QueryCapability.EXACT_MATCH to "document.orders.status.keyword",
                ),
                QueryField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
                QueryField("state.orders.items.createdAt") to fieldSchema(
                    QueryCapability.RANGE to "document.orders.items.created_at",
                    semanticType = Temporal.Formatted("yyyy-MM-dd"),
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(query).assert().isEqualTo(
            QuerySchemaResolution(
                query.copy(
                    filter = TodayFilter(QueryField("document.created_at"), timeUnit = TimeUnit.SECONDS),
                    elements = listOf(
                        query.elements[0].copy(
                            filter = outerFilter.copy(field = QueryField("status.keyword")),
                        ),
                        query.elements[1].copy(
                            filter = TodayFilter(QueryField("created_at"), datePattern = "yyyy-MM-dd"),
                        ),
                    ),
                ),
                QueryCompatibilityLevel.EXACT,
            ),
        )
        query.filter.assert().isSameAs(rootFilter)
        query.elements[0].filter.assert().isSameAs(outerFilter)
        query.elements[1].filter.assert().isSameAs(innerFilter)
    }

    @Test
    fun `aggregation elements should resolve paths relative to the root element`() {
        val query = AggregationQuery(
            elements = listOf(
                AggregationElement(QueryField("state.orders")),
                AggregationElement(QueryField("items")),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val schema = schema(
            mapOf(
                QueryField("state.orders") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders",
                ),
                QueryField("state.orders.items") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders.items",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(query).assert().isEqualTo(
            QuerySchemaResolution(query, QueryCompatibilityLevel.EXACT),
        )
    }

    @Test
    fun `aggregation groups and expressions should resolve relative to the innermost element`() {
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            groupBy = listOf(
                AggregationGroup.Terms(QueryField("category"), "category"),
            ),
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
            ),
        )
        val schema = schema(
            mapOf(
                QueryField("state.orders") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "document.orders",
                ),
                QueryField("state.orders.category") to fieldSchema(
                    QueryCapability.AGGREGATE_TERMS to "document.orders.category",
                ),
                QueryField("state.orders.amount") to fieldSchema(
                    QueryCapability.AGGREGATE_NUMERIC to "document.orders.amount",
                ),
            ),
        )

        QuerySchemaResolver(schema).resolve(query).assert().isEqualTo(
            QuerySchemaResolution(query, QueryCompatibilityLevel.EXACT),
        )
    }

    @Test
    fun `aggregation fields should remain relative to the innermost element`() {
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("body"))),
            groupBy = listOf(AggregationGroup.Terms(QueryField("body.data"), "data")),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("body") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "event.body",
                    ),
                    QueryField("body.body.data") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "event.body.body.data.keyword",
                    ),
                ),
            ),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `aggregation any should require a single terms-capable field in the innermost element`() {
        val productName = QueryField("state.orders.lines.productName")
        val baseFields = linkedMapOf(
            QueryField("state.orders") to fieldSchema(
                QueryCapability.ELEMENT_SCOPE to "document.orders",
                cardinality = QueryCardinality.MANY,
                valueTypes = setOf(QueryValueType.OBJECT),
            ),
            QueryField("state.orders.lines") to fieldSchema(
                QueryCapability.ELEMENT_SCOPE to "document.orders.lines",
                cardinality = QueryCardinality.MANY,
                valueTypes = setOf(QueryValueType.OBJECT),
            ),
            productName to fieldSchema(
                QueryCapability.AGGREGATE_TERMS to "document.orders.lines.productName.keyword",
                valueTypes = setOf(QueryValueType.STRING),
            ),
        )
        val query = AggregationQuery(
            elements = listOf(
                AggregationElement(QueryField("state.orders")),
                AggregationElement(QueryField("lines")),
            ),
            metrics = listOf(AggregationMetric.Any(QueryField("productName"), "productName")),
        )

        QuerySchemaResolver(schema(baseFields)).resolve(query).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.EXACT)

        val manyField = baseFields.getValue(productName).copy(cardinality = QueryCardinality.MANY)
        QuerySchemaResolver(schema(baseFields + (productName to manyField)))
            .resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)

        val withoutTerms = baseFields.getValue(productName).copy(bindings = emptyMap())
        QuerySchemaResolver(schema(baseFields + (productName to withoutTerms)))
            .resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `masked fields should be rejected only from aggregation references`() {
        val rootSecret = QueryField("state.secret")
        val nestedSecret = QueryField("state.orders.secret")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    rootSecret to fieldSchema(
                        QueryCapability.EXACT_MATCH to "document.secret",
                        QueryCapability.SORT to "document.secret.keyword",
                        QueryCapability.AGGREGATE_TERMS to "document.secret.keyword",
                        QueryCapability.AGGREGATE_NUMERIC to "document.secret",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    nestedSecret to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "document.orders.secret.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val aggregations = listOf(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(rootSecret, "secret")),
                metrics = listOf(AggregationMetric.Count("count"))
            ),
            AggregationQuery(metrics = listOf(AggregationMetric.Any(rootSecret, "secret"))),
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.ADD,
                            AggregationExpression.Field(rootSecret),
                            AggregationExpression.Constant(1.0),
                        ),
                        "total",
                    ),
                ),
            ),
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("state.orders"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("secret"), "secret")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )

        aggregations.forEach { query ->
            resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        }
        resolver.resolve(EqualFilter(rootSecret, json("secret"))).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.EXACT)
        resolver.resolve(listOf(Sort(rootSecret.value, Sort.Direction.ASC))).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `aggregation should reject a masked root field physical binding`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.email") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "state.email.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.email.keyword"), "email")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `aggregation should reject a masked descendant when logical and physical parents differ`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    QueryField("state.orders.email") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "document.orders.email.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            groupBy = listOf(AggregationGroup.Terms(QueryField("email.keyword"), "email")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `aggregation should reject a nested masked binding used as the logical fallback path`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.orders") to fieldSchema(
                        QueryCapability.ELEMENT_SCOPE to "document.orders",
                    ),
                    QueryField("state.orders.email") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "state.orders.email.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            groupBy = listOf(AggregationGroup.Terms(QueryField("email.keyword"), "email")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `aggregation should reject a masked projection path used as the logical fallback path`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.emailAlias") to fieldSchema(
                        projectionPath = "state.email",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.email"), "email")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `aggregation should reject a declared field bound to a masked projection path`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.emailAlias") to fieldSchema(
                        projectionPath = "state.email",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("state.email") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "document.email.keyword",
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.email"), "email")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `aggregation should keep an unrelated declared field exact beside a masked alias`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.emailAlias") to fieldSchema(
                        projectionPath = "state.email",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("state.status") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "document.status.keyword",
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `aggregation should keep an unknown non-masked path compatible`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.email") to fieldSchema(
                        QueryCapability.AGGREGATE_TERMS to "state.email.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.unknown.keyword"), "unknown")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.COMPATIBLE)
    }

    @Test
    fun `cursor sort should require exact unmasked bindings while ordinary sort remains allowed`() {
        val secret = QueryField("state.secret")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    secret to fieldSchema(
                        QueryCapability.SORT to "document.secret.keyword",
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )

        resolver.resolve(ListQuery(MatchAllFilter, sort = listOf(Sort(secret.value, Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(secret.value, Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort("state.unknown", Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)

        val dynamic = QueryField("state.dynamic")
        val dynamicResolver = QuerySchemaResolver(
            schema(
                mapOf(
                    dynamic to fieldSchema(
                        QueryCapability.SORT to "document.dynamic",
                        dynamicChildren = true,
                        maskRule = fullMaskRule(),
                    ),
                ),
            ),
        )
        dynamicResolver.resolve(
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort("state.dynamic.secret", Sort.Direction.ASC)),
            ),
        ).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `cursor sort should reject masked projection and physical aliases`() {
        val projectionAlias = QueryField("state.email")
        val siblingProjectionAlias = QueryField("state.emailSibling")
        val physicalAlias = QueryField("state.secretAlias")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.emailAlias") to fieldSchema(
                        projectionPath = projectionAlias.value,
                        maskRule = fullMaskRule(),
                    ),
                    projectionAlias to fieldSchema(QueryCapability.SORT to "document.email.keyword"),
                    siblingProjectionAlias to fieldSchema(
                        QueryCapability.SORT to "document.email.raw",
                        projectionPath = projectionAlias.value,
                    ),
                    QueryField("state.secret") to fieldSchema(
                        QueryCapability.SORT to "document.secret.keyword",
                        maskRule = fullMaskRule(),
                    ),
                    physicalAlias to fieldSchema(QueryCapability.SORT to "document.secret.keyword"),
                ),
            ),
        )

        listOf(projectionAlias, siblingProjectionAlias, physicalAlias).forEach { alias ->
            resolver.resolve(ListQuery(MatchAllFilter, sort = listOf(Sort(alias.value, Sort.Direction.ASC))))
                .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(alias.value, Sort.Direction.ASC))))
                .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        }
    }

    @Test
    fun `cursor sort should reject multi valued fields while ordinary sort remains allowed`() {
        val many = QueryField("state.many")
        val single = QueryField("state.single")
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    many to fieldSchema(
                        QueryCapability.SORT to "document.many",
                        cardinality = QueryCardinality.MANY,
                    ),
                    single to fieldSchema(QueryCapability.SORT to "document.single"),
                ),
            ),
        )

        resolver.resolve(ListQuery(MatchAllFilter, sort = listOf(Sort(many.value, Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(many.value, Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(single.value, Sort.Direction.ASC))))
            .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `cursor sort should reject aliases resolved to unstable metadata fields`() {
        val aliases = mapOf(
            QueryField("state.scoreRank") to "_score",
            QueryField("state.documentRank") to "_doc",
            QueryField("state.shardRank") to "_shard_doc",
        )
        val resolver = QuerySchemaResolver(
            schema(
                aliases.mapValues { (_, physical) ->
                    fieldSchema(QueryCapability.SORT to physical)
                },
            ),
        )

        aliases.keys.forEach { alias ->
            resolver.resolve(ListQuery(MatchAllFilter, sort = listOf(Sort(alias.value, Sort.Direction.ASC))))
                .compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            resolver.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(alias.value, Sort.Direction.ASC))))
                .compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
        }
    }

    @Test
    fun `cursor sort should reject distinct logical fields resolved to the same physical field`() {
        val resolver = QuerySchemaResolver(
            schema(
                mapOf(
                    QueryField("state.idAlias") to fieldSchema(QueryCapability.SORT to "_id"),
                    QueryField("aggregateId") to fieldSchema(QueryCapability.SORT to "_id"),
                ),
            ),
        )
        val query = CursorQuery(
            MatchAllFilter,
            sort = listOf(
                Sort("state.idAlias", Sort.Direction.DESC),
                Sort("aggregateId", Sort.Direction.ASC),
            ),
        )

        resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `masked event projection should include body type for internal validation`() {
        val resolver = QuerySchemaResolver(
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField("body.body.secret") to fieldSchema(
                        QueryCapability.PRESENCE to "body.body.secret",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("body.bodyType") to fieldSchema(
                        QueryCapability.PRESENCE to "document.events.type",
                    ),
                ),
            ),
        )

        val resolved = resolver.resolve(
            Projection(include = listOf("body.body.secret")),
        ).value

        resolved.include.assert().containsExactly("body.body.secret", "document.events.type")
    }

    @Test
    fun `masked event projection should reject wildcard exclusion of body type`() {
        val resolver = QuerySchemaResolver(
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField("body.body.secret") to fieldSchema(
                        QueryCapability.PRESENCE to "body.body.secret",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("body.bodyType") to fieldSchema(
                        QueryCapability.PRESENCE to "body.bodyType",
                    ),
                ),
            ),
        )

        resolver.resolve(Projection(exclude = listOf("body.bodyT*"))).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `masked event projection should allow body type wildcard exclusion when body is not selected`() {
        val resolver = QuerySchemaResolver(
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField("body.body.secret") to fieldSchema(
                        QueryCapability.PRESENCE to "body.body.secret",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("body.bodyType") to fieldSchema(
                        QueryCapability.PRESENCE to "body.bodyType",
                    ),
                ),
            ),
        )
        val projection = Projection(
            include = listOf("body.aggregateId"),
            exclude = listOf("body.bodyT*"),
        )

        resolver.resolve(projection).assert().isEqualTo(
            QuerySchemaResolution(projection, QueryCompatibilityLevel.COMPATIBLE),
        )
    }

    @Test
    fun `masked event projection should restore body type excluded through an alias`() {
        val resolver = QuerySchemaResolver(
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField("body.body.secret") to fieldSchema(
                        QueryCapability.PRESENCE to "body.body.secret",
                        maskRule = fullMaskRule(),
                    ),
                    QueryField("body.bodyType") to fieldSchema(
                        QueryCapability.PRESENCE to "body.bodyType",
                    ),
                    QueryField("eventTypeAlias") to fieldSchema(
                        QueryCapability.PRESENCE to "body.bodyType",
                    ),
                ),
            ),
        )

        resolver.resolve(Projection(exclude = listOf("eventTypeAlias"))).value.assert()
            .isEqualTo(Projection.ALL)
    }

    private fun schema(
        fields: Map<QueryField, QueryFieldSchema> = emptyMap(),
        capabilities: Set<QueryCapability> = emptySet(),
    ) = QueryModelSchema(QueryModel.SNAPSHOT, capabilities, fields)

    private fun fieldSchema(
        vararg bindings: Pair<QueryCapability, String>,
        dynamicChildren: Boolean = false,
        semanticType: QuerySemanticType? = null,
        projectionPath: String? = null,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
        valueTypes: Set<QueryValueType> = emptySet(),
        maskRule: MaskRule? = null,
    ) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = valueTypes,
        nullable = false,
        required = true,
        cardinality = cardinality,
        semanticType = semanticType,
        dynamicChildren = dynamicChildren,
        maskRule = maskRule,
        bindings = bindings.associate { (capability, path) ->
            capability to QueryFieldBinding(path, storageType = null)
        },
        projectionPath = projectionPath
            ?: bindings.firstOrNull { it.first == QueryCapability.PRESENCE }?.second,
    )

    private fun json(value: Any): JsonNode = JsonNodeFactory.instance.pojoNode(value)

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)
}
