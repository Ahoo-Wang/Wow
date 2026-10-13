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
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
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
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
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
        validateQuery(TodayFilter(QueryField("formatted")), schema)
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
                "orders" to arrayFixture(
                    objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL))
                )
            )
        )
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("orders"), EqualFilter(QueryField("status"), "PAID"))),
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QueryField("amount")),
                        AggregationExpression.Constant(1.0),
                    ),
                    "total",
                ),
            ),
        )
        validateQuery(query, schema).assert().isSameAs(query)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(expandedSum("orders", "orders.amount"), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(expandedSum("orders", "missing"), schema)
        }
    }

    @Test
    fun `terms missing key requires a single valued string field`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "name" to scalarFixture(QueryValueType.STRING),
                "nickname" to QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(QueryValueType.STRING), QueryValueSchema(QueryValueKind.NULL))
                ),
                "label" to QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(QueryValueType.STRING), scalarFixture(QueryValueType.INTEGER))
                ),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "names" to arrayFixture(scalarFixture(QueryValueType.STRING)),
            )
        )
        fun termsWithMissingKey(field: String) = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField(field), field, missingKey = "UNKNOWN")),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        validateQuery(termsWithMissingKey("name"), schema).assert().isNotNull()
        validateQuery(termsWithMissingKey("nickname"), schema).assert().isNotNull()
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("amount"), schema)
        }.message.assert().isEqualTo("Field [amount] must be a single-valued string field to declare missingKey.")
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("names"), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("label"), schema)
        }
    }

    @Test
    fun `histogram and date histogram groups skip the terms missing key predicate`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "createdAt" to scalarFixture(QueryValueType.STRING, temporal = Temporal.Date),
            )
        )
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Histogram(QueryField("amount"), "amountRange", 10.0),
                AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        // Neither grouped field is a single-valued string: the missingKey predicate applies to
        // TERMS groups only, so both groups must validate on their own capabilities.
        validateQuery(query, schema).assert().isSameAs(query)
    }

    @Test
    fun `metric filters follow the current scope for root and element contexts`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "tenantId" to scalarFixture(),
                "orders" to arrayFixture(
                    objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL))
                )
            )
        )
        val paid = EqualFilter(QueryField("status"), "PAID")
        // Root scope: metric filters may use root-level filters (tenantId is declared at the root).
        validateQuery(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("tenantOrders", TenantIdFilter("tenant")))),
            schema,
        ).assert().isNotNull()
        // Element scope: fields of the current scope are legal; unknown fields are rejected.
        validateQuery(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("orders"))),
                metrics = listOf(AggregationMetric.Count("paid", paid)),
            ),
            schema,
        ).assert().isNotNull()
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                AggregationQuery(
                    elements = listOf(AggregationElement(QueryField("orders"))),
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("missing")),
                            "total",
                            paid,
                        ),
                    ),
                ),
                schema,
            )
        }
        // Unfiltered metrics (MatchAllFilter) skip metric filter validation inside element scope.
        validateQuery(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("orders"))),
                metrics = listOf(
                    AggregationMetric.Count("all"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    ),
                ),
            ),
            schema,
        ).assert().isNotNull()
    }

    @Test
    fun `distinct count accepts terms or numeric fields and rejects others while percentile follows numeric rules`() {
        val both = boundSchemaFixture(
            objectFixture(
                "customerId" to scalarFixture(),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
            )
        )
        val amount = AggregationExpression.Field(QueryField("amount"))
        val query = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("customerId")), "customers"),
                AggregationMetric.DistinctCount(amount, "amounts"),
                AggregationMetric.Numeric(AggregationFunction.STDDEV, amount, "stddev"),
                AggregationMetric.Numeric(AggregationFunction.VARIANCE, amount, "variance"),
                AggregationMetric.Percentile(amount, 95.0, "p95"),
                AggregationMetric.Percentile(amount, 50.0, "median"),
            ),
        )
        validateQuery(query, both).assert().isSameAs(query)

        val presenceOnly = boundSchemaFixture(
            objectFixture("code" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE),
        )
        val code = AggregationExpression.Field(QueryField("code"))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.DistinctCount(code, "codes")), presenceOnly)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Percentile(code, 95.0, "p")), presenceOnly)
        }
    }

    @Test
    fun `distinct count accepts terms-only fields while percentile and stddev require numeric`() {
        val termsOnly = boundSchemaFixture(
            objectFixture("customerId" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.AGGREGATE_TERMS),
        )
        val customer = AggregationExpression.Field(QueryField("customerId"))
        val query = metric(AggregationMetric.DistinctCount(customer, "customers"))
        validateQuery(query, termsOnly).assert().isSameAs(query)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Percentile(customer, 95.0, "p95")), termsOnly)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Numeric(AggregationFunction.STDDEV, customer, "std")), termsOnly)
        }
    }

    @Test
    fun `derived metrics need no schema capability`() {
        val schema = boundSchemaFixture(objectFixture("status" to scalarFixture()))
        val query = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Count("total"),
                AggregationMetric.Derived(
                    "half",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("total"),
                        DerivedExpression.Constant(2.0),
                    ),
                ),
            ),
        )
        validateQuery(query, schema).assert().isSameAs(query)
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

    @Test
    fun `any metrics require scalar values`() {
        val array = boundSchemaFixture(objectFixture("tags" to arrayFixture(scalarFixture())))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Any(QueryField("tags"), "tag")), array)
        }
    }

    private fun metric(metric: AggregationMetric) = AggregationQuery(metrics = listOf(metric))

    private fun expandedSum(path: String, field: String) = AggregationQuery(
        elements = listOf(AggregationElement(QueryField(path))),
        metrics = listOf(
            AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField(field)), "total"),
        ),
    )
}

/*
 * The validation this test pins runs in admission's single resolution pass; each helper admits through the trusted
 * path and returns its input, so a test reads as the rule it checks.
 */
private fun validateQuery(query: ISingleQuery, schema: QueryModelSchema): ISingleQuery =
    query.also { QueryAdmission.Trusted.single(it, schema) }

private fun validateQuery(query: IListQuery, schema: QueryModelSchema): IListQuery =
    query.also { QueryAdmission.Trusted.list(it, schema) }

private fun validateQuery(query: IPagedQuery, schema: QueryModelSchema): IPagedQuery =
    query.also { QueryAdmission.Trusted.paged(it, schema) }

/** The cursor's own sort: its first field stands in for the tie-breaker admission appends. */
private fun validateQuery(query: ICursorQuery, schema: QueryModelSchema): ICursorQuery = query.also {
    val tieBreaker = it.sort.firstOrNull()?.field ?: schema.requireIdentityField()
    QueryResolver(schema, Instant.now()).cursor(it, tieBreaker)
}

private fun validateQuery(query: FilterExpression, schema: QueryModelSchema): FilterExpression =
    query.also { QueryAdmission.Trusted.count(CountQuery(it), schema) }

private fun validateQuery(query: AggregationQuery, schema: QueryModelSchema): AggregationQuery =
    query.also { QueryAdmission.Trusted.aggregate(it, schema) }
