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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class QuerySchemaValidationTest {
    @Test
    fun `unavailable full projection fails reads while explicit fields count and aggregation remain independent`() {
        val bound = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val schema = QueryModelSchema(
            bound.model,
            bound.capabilities,
            bound.definition,
            bound.bindings,
            fullProjectionAvailable = false
        )
        assertThrows<QuerySchemaValidationException> { validateQuery(SingleQuery(MatchAllFilter), schema) }
        assertThrows<QuerySchemaValidationException> { validateQuery(ListQuery(MatchAllFilter), schema) }
        assertThrows<QuerySchemaValidationException> { validateQuery(PagedQuery(MatchAllFilter), schema) }
        assertThrows<QuerySchemaValidationException> { validateQuery(CursorQuery(MatchAllFilter), schema) }
        val explicit = ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("name"))))
        validateQuery(explicit, schema).assert().isSameAs(explicit)
        validateQuery(MatchAllFilter, schema).assert().isSameAs(MatchAllFilter)
        val count = aggregation { count("count") }
        validateQuery(count, schema).assert().isSameAs(count)
    }

    @Test
    fun `validation preserves logical filters projections and sort without physical rewrites`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val predicate = EqualFilter(QueryField("name"), json("Wow"))
        val projection = Projection(include = listOf(QueryField("name")))
        val sorts = listOf(Sort(QueryField("name"), Sort.Direction.ASC))
        val single = SingleQuery(predicate, projection, sorts)
        val list = ListQuery(predicate, projection, sorts)
        val paged = PagedQuery(predicate, projection, sorts)
        val cursor = CursorQuery(predicate, projection, sorts)
        validateQuery(predicate, schema).assert().isSameAs(predicate)
        validateQuery(single, schema).assert().isSameAs(single)
        validateQuery(list, schema).assert().isSameAs(list)
        validateQuery(paged, schema).assert().isSameAs(paged)
        validateQuery(cursor, schema).assert().isSameAs(cursor)
        schema.physicalField(QueryField("name"), QueryCapability.SORT).assert().isEqualTo(QueryField("native.name"))
    }

    @Test
    fun `unknown logical paths native names and missing operation bindings fail closed`() {
        val schema = boundSchemaFixture(
            objectFixture("name" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE)
        )
        listOf("unknown", "native.name", "name.extra").forEach { field ->
            val error = assertThrows<QuerySchemaValidationException> {
                validateQuery(
                    ExistsFilter(QueryField(field)),
                    schema
                )
            }
            error.errorCode.assert().isEqualTo(QuerySchemaValidationException.ERROR_CODE)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("name"), json("Wow")),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC))),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                SingleQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("unknown")))),
                schema
            )
        }
    }

    @Test
    fun `built in operand types accept exact integer decimal boolean and string values`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "integer" to scalarFixture(QueryValueType.INTEGER),
                "decimal" to scalarFixture(QueryValueType.DECIMAL),
                "boolean" to scalarFixture(QueryValueType.BOOLEAN),
                "string" to scalarFixture(),
            )
        )
        listOf("integer" to 1, "integer" to BigDecimal("1.0"), "decimal" to 1.5, "boolean" to true, "string" to "one")
            .forEach { (field, value) -> validateQuery(EqualFilter(QueryField(field), json(value)), schema) }
        listOf("integer" to 1.5, "integer" to "1", "decimal" to "1.5", "boolean" to 1, "string" to 1)
            .forEach { (field, value) ->
                assertThrows<QuerySchemaValidationException> {
                    validateQuery(
                        EqualFilter(QueryField(field), json(value)),
                        schema
                    )
                }
            }
    }

    @Test
    fun `scalar arrays and runtime arrays retain equality operand shape while validating each value`() {
        val schema = boundSchemaFixture(objectFixture("tags" to arrayFixture(scalarFixture())))
        listOf(json(listOf("a", "b")), POJONode(arrayOf("a", "b")), POJONode(listOf("a", "b"))).forEach { values ->
            val filter = EqualFilter(QueryField("tags"), values)
            validateQuery(filter, schema).assert().isSameAs(filter)
            filter.value.assert().isSameAs(values)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("tags"), json(listOf("a", 1))),
                schema
            )
        }
        val nested = boundSchemaFixture(objectFixture("tags" to arrayFixture(arrayFixture(scalarFixture()))))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("tags"), json("a")),
                nested
            )
        }
    }

    @Test
    fun `numeric unions match an operand branch but unknown and null only values reject non null operands`() {
        val union = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalarFixture(QueryValueType.INTEGER), scalarFixture(QueryValueType.DECIMAL))
        )
        val schema = boundSchemaFixture(
            objectFixture(
                "amount" to union,
                "unknown" to QueryValueSchema(QueryValueKind.UNKNOWN),
                "nil" to QueryValueSchema(QueryValueKind.NULL)
            )
        )
        validateQuery(EqualFilter(QueryField("amount"), json(1.5)), schema)
        listOf("unknown", "nil", "amount").forEach { name ->
            assertThrows<QuerySchemaValidationException> {
                validateQuery(
                    EqualFilter(QueryField(name), json("bad")),
                    schema
                )
            }
        }
        validateQuery(EqualFilter(QueryField("nil"), json(null)), schema)
    }

    @Test
    fun `null equality uses presence even when a declared value is not nullable`() {
        val schema = boundSchemaFixture(
            objectFixture("name" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE)
        )
        val filter = EqualFilter(QueryField("name"), json(null))
        validateQuery(filter, schema).assert().isSameAs(filter)
    }

    @Test
    fun `collection and empty string predicates use container shape rather than item type`() {
        val schema = boundSchemaFixture(
            objectFixture("tags" to arrayFixture(scalarFixture()), "name" to scalarFixture())
        )
        validateQuery(ContainsAllFilter(QueryField("tags"), listOf(json("one"))), schema)
        validateQuery(IsEmptyFilter(QueryField("tags")), schema)
        validateQuery(IsEmptyStringFilter(QueryField("name")), schema)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ContainsAllFilter(QueryField("name"), listOf(json("one"))),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> { validateQuery(IsEmptyFilter(QueryField("name")), schema) }
        assertThrows<QuerySchemaValidationException> { validateQuery(IsEmptyStringFilter(QueryField("tags")), schema) }
    }

    @Test
    fun `map list predicates require relative declared element scope and reject unknown suffixes`() {
        val root = objectFixture(
            "homes" to QueryValueSchema(
                QueryValueKind.OBJECT,
                additionalProperties = arrayFixture(objectFixture("city" to scalarFixture()))
            )
        )
        val schema = boundSchemaFixture(root)
        val relative = ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("city"), json("Paris")))
        validateQuery(relative, schema).assert().isSameAs(relative)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("homes.home.city"), json("Paris")),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("homes.home.city"), json("Paris"))),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("city.extra"), json("Paris"))),
                schema
            )
        }
        validateQuery(
            SingleQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("homes.home.city")))),
            schema
        )
    }

    @Test
    fun `every named array ancestor must be entered while unnamed nested arrays have no predicate scope`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "orders" to arrayFixture(objectFixture("lines" to arrayFixture(objectFixture("price" to scalarFixture(QueryValueType.INTEGER)))))
            )
        )
        val valid = ElementMatchFilter(
            QueryField("orders"),
            ElementMatchFilter(QueryField("lines"), EqualFilter(QueryField("price"), json(10)))
        )
        validateQuery(valid, schema).assert().isSameAs(valid)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("orders"), EqualFilter(QueryField("lines.price"), json(10))),
                schema
            )
        }
        val nested = boundSchemaFixture(
            objectFixture(
                "orders" to arrayFixture(arrayFixture(objectFixture("price" to scalarFixture(QueryValueType.INTEGER))))
            )
        )
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("orders"), EqualFilter(QueryField("price"), json(10))),
                nested
            )
        }
    }

    @Test
    fun `relative time normalization reads array item epoch units from the same schema`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "times" to arrayFixture(scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.SECONDS)))
            )
        )
        val input = TodayFilter(QueryField("times"), zoneId = "UTC")
        validateQuery(input, schema).assert().isSameAs(input)
        val normalizer = FilterNormalizer(
            Clock.fixed(Instant.parse("1970-01-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val normalized = normalizer.normalize(input, schema) as AndFilter
        (normalized.operands[0] as GreaterThanOrEqualFilter).value.longValue().assert().isEqualTo(86_400L)
        (normalized.operands[1] as LessThanFilter).value.longValue().assert().isEqualTo(172_800L)
        (normalized.operands[0] as GreaterThanOrEqualFilter).field.assert().isEqualTo(QueryField("times"))
        input.timeUnit.assert().isEqualTo(TimeUnit.MILLISECONDS)
    }

    @Test
    fun `relative time rejects missing conflicting and opaque representations`() {
        val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.SECONDS))
        val formatted = scalarFixture(temporal = Temporal.Formatted("yyyy-MM-dd"))
        val mixed = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(epoch, scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)))
        )
        val schema = boundSchemaFixture(
            objectFixture("epoch" to epoch, "formatted" to formatted, "plain" to scalarFixture(), "mixed" to mixed)
        )
        validateQuery(TodayFilter(QueryField("formatted"), datePattern = "yyyy-MM-dd"), schema)
        listOf(
            TodayFilter(QueryField("epoch"), datePattern = "yyyy-MM-dd"),
            TodayFilter(QueryField("formatted"), datePattern = "yyyy/MM/dd"),
            TodayFilter(QueryField("formatted"), dateFormatter = DateTimeFormatter.ISO_DATE),
            TodayFilter(QueryField("plain")),
            TodayFilter(QueryField("mixed")),
            TodayFilter(QueryField("missing")),
        ).forEach { assertThrows<QuerySchemaValidationException> { validateQuery(it, schema) } }
    }

    @Test
    fun `model and explicit field search capabilities never fall back to unknown caller fields`() {
        val schema = boundSchemaFixture(
            objectFixture("name" to scalarFixture()),
            capabilities = setOf(QueryCapability.FULL_TEXT_TERMS)
        )
        validateQuery(SearchFilter("one"), schema)
        validateQuery(SearchFilter("one", setOf(QueryField("name"))), schema)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                SearchFilter("one", setOf(QueryField("missing"))),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                SearchFilter("one", mode = SearchMode.PHRASE),
                schema
            )
        }
        val event = boundSchemaFixture(objectFixture("id" to scalarFixture()), model = QueryModel.EVENT_STREAM)
        validateQuery(IdFilter("id"), event)
    }

    @Test
    fun `cursor admission is independent from ordinary sort and rejects array scopes`() {
        val sort = listOf(Sort(QueryField("rank"), Sort.Direction.ASC))
        val ordinary = boundSchemaFixture(
            objectFixture("rank" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.SORT)
        )
        validateQuery(ListQuery(MatchAllFilter, sort = sort), ordinary)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                CursorQuery(MatchAllFilter, sort = sort),
                ordinary
            )
        }
        val array = boundSchemaFixture(objectFixture("rank" to arrayFixture(scalarFixture())))
        validateQuery(ListQuery(MatchAllFilter, sort = sort), array)
        assertThrows<QuerySchemaValidationException> { validateQuery(CursorQuery(MatchAllFilter, sort = sort), array) }
    }

    @Test
    fun `aggregation consumes logical relative inputs without rewriting nested filters`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "orders" to arrayFixture(objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL)))
            )
        )
        val query = aggregation {
            expand("orders") { "status" eq "PAID" }
            terms("status", "status")
            sum(field("amount") + constant(1.0), "total")
        }
        validateQuery(query, schema).assert().isSameAs(query)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    expand("orders")
                    sum("orders.amount", "total")
                },
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    expand("orders")
                    sum("missing", "total")
                },
                schema
            )
        }
    }

    @Test
    fun `distinct count accepts terms or numeric fields and rejects others while percentile follows numeric rules`() {
        val both = boundSchemaFixture(
            objectFixture(
                "customerId" to scalarFixture(),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
            )
        )
        val query = aggregation {
            distinctCount("customerId", "customers")
            distinctCount("amount", "amounts")
            stddev("amount", "stddev")
            variance("amount", "variance")
            percentile("amount", 95.0, "p95")
            median("amount", "median")
        }
        validateQuery(query, both).assert().isSameAs(query)

        val presenceOnly = boundSchemaFixture(
            objectFixture("code" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE),
        )
        assertThrows<QuerySchemaValidationException> {
            validateQuery(aggregation { distinctCount("code", "codes") }, presenceOnly)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(aggregation { percentile("code", 95.0, "p") }, presenceOnly)
        }
    }

    @Test
    fun `masked values stay queryable but public cursor and aggregate admission reject them`() {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        val mask = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        val schema = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture(mask = mask))))
        validateQuery(EqualFilter(QueryField("state.secret"), json("one")), schema)
        validateQuery(
            ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.secret"), Sort.Direction.ASC))),
            schema
        )
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.secret"), Sort.Direction.ASC))),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    terms("state.secret", "secret")
                    count("count")
                },
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    distinctCount("state.secret", "secrets")
                    count("count")
                },
                schema
            )
        }
        schema.field(
            QueryField("state.secret")
        )!!.bindings.keys.assert().contains(QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS)
    }

    @Test
    fun `masked element descendant does not block count or public metrics`() {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        val mask = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        val schema = boundSchemaFixture(
            objectFixture(
                "state" to objectFixture(
                    "items" to arrayFixture(
                        objectFixture(
                            "secret" to scalarFixture(mask = mask),
                            "visible" to scalarFixture(QueryValueType.INTEGER),
                        )
                    )
                )
            )
        )
        val count = aggregation {
            expand("state.items")
            count("count")
        }
        val publicMetric = aggregation {
            expand("state.items")
            sum("visible", "total")
        }
        validateQuery(count, schema).assert().isSameAs(count)
        validateQuery(publicMetric, schema).assert().isSameAs(publicMetric)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    expand("state.items")
                    any("secret", "secret")
                },
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                aggregation {
                    expand("state.items")
                    terms("secret", "secret")
                    count("count")
                },
                schema
            )
        }
    }

    @Test
    fun `any metrics require scalar values and event payload projections retain body type`() {
        val array = boundSchemaFixture(objectFixture("tags" to arrayFixture(scalarFixture())))
        assertThrows<QuerySchemaValidationException> { validateQuery(aggregation { any("tags", "tag") }, array) }
        val event = boundSchemaFixture(
            objectFixture(
                "body" to arrayFixture(
                    objectFixture(
                        "bodyType" to scalarFixture(), "body" to objectFixture("name" to scalarFixture()),
                    )
                )
            ),
            model = QueryModel.EVENT_STREAM
        )
        validateQuery(
            SingleQuery(
                MatchAllFilter,
                projection = Projection(include = listOf(QueryField("body.bodyType"), QueryField("body.body.name")))
            ),
            event
        )
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                SingleQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("body.body.name")))),
                event
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                SingleQuery(MatchAllFilter, projection = Projection(exclude = listOf(QueryField("body.bodyType")))),
                event
            )
        }
    }

    private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)
    private data class Masked(@field:Mask val secret: String)
}
