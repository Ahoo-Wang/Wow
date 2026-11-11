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
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.resolve.QueryResolver
import me.ahoo.wow.query.schema.QuerySchemaValidationFixtures.validateQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

class QuerySchemaValidationTest {
    @Test
    fun `validation failures carry the structured violation their message renders`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val unknown = assertThrows<QuerySchemaValidationException> {
            validateQuery(ExistsFilter(QueryField("state.missing")), schema)
        }
        unknown.violation.assert().isEqualTo(QueryViolation.UnknownField(QueryField("state.missing")))
        unknown.message.assert().isEqualTo("Unknown logical field [state.missing].")
        val search = assertThrows<QuerySchemaValidationException> {
            validateQuery(SearchFilter("a"), schema)
        }
        search.violation.assert().isEqualTo(QueryViolation.ModelSearchUnsupported)
    }

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
        val count = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
        validateQuery(count, schema).assert().isSameAs(count)
    }

    @Test
    fun `validation preserves logical filters projections and sort without physical rewrites`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val predicate = EqualFilter(QueryField("name"), "Wow")
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
            error.errorCode.assert().isEqualTo(QueryErrors.INVALID)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("name"), "Wow"),
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
            .forEach { (field, value) -> validateQuery(EqualFilter(QueryField(field), value), schema) }
        listOf("integer" to 1.5, "integer" to "1", "decimal" to "1.5", "boolean" to 1, "string" to 1)
            .forEach { (field, value) ->
                assertThrows<QuerySchemaValidationException> {
                    validateQuery(
                        EqualFilter(QueryField(field), value),
                        schema
                    )
                }
            }
    }

    @Test
    fun `scalar lists retain equality operand shape while validating each value`() {
        val schema = boundSchemaFixture(objectFixture("tags" to arrayFixture(scalarFixture())))
        listOf<Any>(listOf("a", "b"), listOf("a")).forEach { values ->
            val filter = EqualFilter(QueryField("tags"), values)
            validateQuery(filter, schema).assert().isSameAs(filter)
            filter.value.assert().isSameAs(values)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("tags"), listOf("a", 1)),
                schema
            )
        }
        val nested = boundSchemaFixture(objectFixture("tags" to arrayFixture(arrayFixture(scalarFixture()))))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("tags"), "a"),
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
        validateQuery(EqualFilter(QueryField("amount"), 1.5), schema)
        listOf("unknown", "nil", "amount").forEach { name ->
            assertThrows<QuerySchemaValidationException> {
                validateQuery(
                    EqualFilter(QueryField(name), "bad"),
                    schema
                )
            }
        }
        validateQuery(EqualFilter(QueryField("nil"), null), schema)
    }

    @Test
    fun `null equality uses presence even when a declared value is not nullable`() {
        val schema = boundSchemaFixture(
            objectFixture("name" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE)
        )
        val filter = EqualFilter(QueryField("name"), null)
        validateQuery(filter, schema).assert().isSameAs(filter)
    }

    @Test
    fun `collection and empty string predicates use container shape rather than item type`() {
        val schema = boundSchemaFixture(
            objectFixture("tags" to arrayFixture(scalarFixture()), "name" to scalarFixture())
        )
        validateQuery(ContainsAllFilter(QueryField("tags"), listOf("one")), schema)
        validateQuery(IsEmptyFilter(QueryField("tags")), schema)
        validateQuery(IsEmptyStringFilter(QueryField("name")), schema)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ContainsAllFilter(QueryField("name"), listOf("one")),
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
        val relative = ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("city"), "Paris"))
        validateQuery(relative, schema).assert().isSameAs(relative)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                EqualFilter(QueryField("homes.home.city"), "Paris"),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("homes.home.city"), "Paris")),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("homes.home"), EqualFilter(QueryField("city.extra"), "Paris")),
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
                "orders" to arrayFixture(
                    objectFixture(
                        "lines" to arrayFixture(objectFixture("price" to scalarFixture(QueryValueType.INTEGER)))
                    )
                )
            )
        )
        val valid = ElementMatchFilter(
            QueryField("orders"),
            ElementMatchFilter(QueryField("lines"), EqualFilter(QueryField("price"), 10))
        )
        validateQuery(valid, schema).assert().isSameAs(valid)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(QueryField("orders"), EqualFilter(QueryField("lines.price"), 10)),
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
                ElementMatchFilter(QueryField("orders"), EqualFilter(QueryField("price"), 10)),
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
        val normalized = QueryResolver(schema, Instant.parse("1970-01-02T12:00:00Z")).filter(input) as AndFilter
        ((normalized.operands[0] as GreaterThanOrEqualFilter).value as Number).toLong().assert().isEqualTo(86_400L)
        ((normalized.operands[1] as LessThanFilter).value as Number).toLong().assert().isEqualTo(172_800L)
        (normalized.operands[0] as GreaterThanOrEqualFilter).field.assert().isEqualTo(QueryField("times"))
    }

    @Test
    fun `relative time rejects missing and opaque representations`() {
        val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.SECONDS))
        val formatted = scalarFixture(temporal = Temporal.Formatted("yyyy-MM-dd"))
        val mixed = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(epoch, scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)))
        )
        val schema = boundSchemaFixture(
            objectFixture("epoch" to epoch, "formatted" to formatted, "plain" to scalarFixture(), "mixed" to mixed)
        )
        validateQuery(TodayFilter(QueryField("formatted")), schema)
        validateQuery(TodayFilter(QueryField("epoch")), schema)
        listOf(
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
    fun `element field search is admitted inside its element only and where granted`() {
        fun schema(capabilities: Set<QueryCapability>) = boundSchemaFixture(
            objectFixture("state" to objectFixture("items" to arrayFixture(objectFixture("title" to scalarFixture())))),
            fieldCapabilities = capabilities + QueryCapability.ELEMENT_SCOPE,
        )
        val searchable = schema(setOf(QueryCapability.FULL_TEXT_TERMS))
        val inside = ElementMatchFilter(QueryField("state.items"), SearchFilter("x", setOf(QueryField("title"))))
        validateQuery(inside, searchable).assert().isSameAs(inside)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(SearchFilter("x", setOf(QueryField("state.items.title"))), searchable)
        }.violation.assert().isEqualTo(QueryViolation.ElementScopeRequired(QueryField("state.items.title")))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ElementMatchFilter(
                    QueryField("state.items"),
                    SearchFilter("x", setOf(QueryField("title")), SearchMode.PHRASE),
                ),
                searchable,
            )
        }.violation.assert().isInstanceOf(QueryViolation.UnsupportedCapability::class.java)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                inside,
                schema(setOf(QueryCapability.EXACT_MATCH))
            )
        }
            .violation.assert().isEqualTo(
                QueryViolation.UnsupportedCapability(
                    QueryField("state.items.title"),
                    setOf(QueryCapability.FULL_TEXT_TERMS),
                ),
            )
    }
}
