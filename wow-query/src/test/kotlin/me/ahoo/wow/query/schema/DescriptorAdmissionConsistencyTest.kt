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

package me.ahoo.wow.query.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.descriptor.FieldDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.api.query.spec.OperatorTarget
import me.ahoo.wow.api.query.spec.ValueRule
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBudget
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * The descriptor is a verifiable contract (§10, §13): every capability it lists is admitted on its own, and every
 * field operator, sort, group and metric it leaves out is rejected. For each schema and entry budget below, each
 * listed or unlisted item becomes a minimal query and is run through the entry gate and admission. Both read the
 * field's compiled capability record and the specs' cost classes, so this guards that they keep reading the same.
 */
class DescriptorAdmissionConsistencyTest {
    private val nodes = JsonNodeFactory.instance
    private val filterClasses: Map<String, KClass<*>> =
        FilterExpression::class.java.getAnnotation(JsonSubTypes::class.java).value
            .associate { it.name to it.value.java.kotlin }

    private val root = objectFixture(
        "aggregateId" to scalarFixture(),
        "tenantId" to scalarFixture(),
        "eventTime" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
        "state" to objectFixture(
            "name" to scalarFixture(),
            "count" to scalarFixture(QueryValueType.INTEGER),
            "amount" to scalarFixture(QueryValueType.DECIMAL),
            "active" to scalarFixture(QueryValueType.BOOLEAN),
            "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
            "tags" to arrayFixture(scalarFixture()),
            "items" to arrayFixture(
                objectFixture(
                    "sku" to scalarFixture(),
                    "qty" to scalarFixture(QueryValueType.INTEGER),
                    "attrs" to mapFixture(scalarFixture()),
                ),
            ),
            // An element scope whose items are a union of objects (a sealed class), as on every storage.
            "shapes" to arrayFixture(
                QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(
                        objectFixture("kind" to scalarFixture(), "radius" to scalarFixture(QueryValueType.INTEGER)),
                        objectFixture("kind" to scalarFixture(), "side" to scalarFixture(QueryValueType.INTEGER)),
                    ),
                ),
            ),
            "phone" to scalarFixture(mask = MaskRule(SensitivityLevel.DISPLAY)),
            "idCard" to scalarFixture(mask = MaskRule(SensitivityLevel.CONFIDENTIAL)),
            "attributes" to mapFixture(scalarFixture()),
            "secrets" to mapFixture(scalarFixture(mask = MaskRule(SensitivityLevel.CONFIDENTIAL))),
        ),
    )

    /** Compiled through [QueryStorageFacts.compile], so the Catalog's storage-independent rules apply. */
    private val schemas: Map<String, QueryModelSchema> = mapOf(
        "full capabilities" to compiled(boundSchemaFixture(root)),
        "exact match and presence only" to compiled(
            boundSchemaFixture(root, fieldCapabilities = setOf(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)),
        ),
        "storage without keyset, HAVING, top-N, dense fill, PERCENTILE, DISTINCT_COUNT or FIRST/LAST" to compiled(
            boundSchemaFixture(root),
            StorageSupport(
                paging = PagingSupport(keyset = SupportMode.NONE),
                aggregation = AggregationSupport(
                    having = SupportMode.NONE,
                    topN = SupportMode.NONE,
                    denseFill = SupportMode.NONE,
                    percentile = SupportMode.NONE,
                    distinctCount = SupportMode.NONE,
                    firstLast = SupportMode.NONE,
                ),
            ),
        ),
    )

    private fun compiled(bound: QueryModelSchema, storage: StorageSupport = StorageSupport.NATIVE): QueryModelSchema =
        QueryStorageFacts(bound.bindings, storage = storage).compile(bound.model, bound.definition)

    private val budgets: Map<String, QueryBudget> = mapOf(
        "HTTP" to QueryBudget.HTTP_DEFAULT,
        "HTTP without expensive operators" to QueryBudget(QueryBudget.HTTP_LABEL, allowExpensiveOperators = false),
    )

    @TestFactory
    fun `listed filter operators are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, budget, field ->
            FilterOperator.entries.filter { it.isFieldOperator() }.mapNotNull { operator ->
                val listed = operator in field.filter.operators
                val filter = fieldFilter(operator, field.path, field.scope, field.types, listed)
                    ?: return@mapNotNull null
                Case("${field.path} ${operator.name}", listed) {
                    val query = ListQuery(filter, limit = 1)
                    budget.check(query)
                    QueryAdmission.Trusted.list(query, schema)
                }
            }
        }

    /** Sorts are gated by no cost class; `COUNT_REQUIRES_FILTER` states the paged query's own gate. */
    @TestFactory
    fun `listed sorts are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, _, field ->
            if (field.scope != null) return@cases emptyList()
            val sort = listOf(Sort(QueryField(field.path), Sort.Direction.ASC))
            listOf(
                Case("${field.path} paged sort", field.sort.paged) {
                    QueryAdmission.Trusted.paged(
                        PagedQuery(MatchAllFilter, sort = sort, pagination = Pagination(1, 1)),
                        schema
                    )
                },
                Case("${field.path} cursor sort", field.sort.cursor) {
                    QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 1), schema)
                },
            )
        }

    @TestFactory
    fun `listed groups and metrics are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, budget, field ->
            if (field.scope != null) return@cases emptyList()
            val aggregate = field.aggregate
            val path = QueryField(field.path)
            val count = AggregationMetric.Count("count")
            val groups = mapOf(
                "TERMS" to AggregationGroup.Terms(path, "g"),
                "HISTOGRAM" to AggregationGroup.Histogram(path, "g", interval = 1.0),
                "DATE_HISTOGRAM" to AggregationGroup.DateHistogram(path, "g", AggregationDateUnit.DAY),
                "DATE_PART" to AggregationGroup.DatePart(path, "g", AggregationDatePart.HOUR_OF_DAY),
            ).map { (name, group) ->
                Case("${field.path} group $name", aggregate?.groups?.contains(name) == true) {
                    aggregate(AggregationQuery(groupBy = listOf(group), metrics = listOf(count)), schema, budget)
                }
            }
            val input = AggregationExpression.Field(path)
            val metrics = AggregationFunction.entries.map { function ->
                function.name to (aggregate?.functions?.contains(function.name) == true) to
                    AggregationMetric.Numeric(function, input, "m")
            } + listOf(
                "DISTINCT_COUNT" to (aggregate?.distinctCount == true) to AggregationMetric.DistinctCount(input, "m"),
                "PERCENTILE" to (aggregate?.percentile == true) to AggregationMetric.Percentile(input, 50.0, "m"),
                "ANY" to (aggregate?.any == true) to AggregationMetric.Any(path, "m"),
                "FIRST" to (aggregate?.firstLast == true) to AggregationMetric.First(path, "m"),
            )
            groups + metrics.map { (named, metric) ->
                val (name, listed) = named
                Case("${field.path} metric $name", listed) {
                    aggregate(AggregationQuery(metrics = listOf(metric)), schema, budget)
                }
            }
        }

    /**
     * A dynamic pattern lists what a concrete key admits: its operators come from the key's compiled record, so a
     * protected key or one inside an element that grants no scope lists none.
     */
    @TestFactory
    fun `listed dynamic operators are admitted for a concrete key and unlisted ones rejected`(): List<DynamicTest> =
        schemas.flatMap { (schemaName, schema) ->
            budgets.flatMap { (budgetName, budget) ->
                schema.describe(budget, defaultListSize = null).dynamic.flatMap { dynamic ->
                    val path = dynamic.pattern.replace("{key}", "k")
                    val scope = schema.field(QueryField(path))?.elementAncestors?.lastOrNull()?.path
                    FilterOperator.entries.filter { it.isFieldOperator() }.mapNotNull { operator ->
                        val listed = operator in dynamic.filter.operators
                        val filter = fieldFilter(operator, path, scope, dynamic.types, listed) ?: return@mapNotNull null
                        test(
                            "$schemaName, $budgetName",
                            Case("${dynamic.pattern} ${operator.name}", listed) {
                                val query = ListQuery(filter, limit = 1)
                                budget.check(query)
                                QueryAdmission.Trusted.list(query, schema)
                            }
                        )
                    }
                }
            }
        }

    /** An element is listed exactly when `ELEMENT_MATCH` on it is admitted: an array of objects, unions included. */
    @TestFactory
    fun `listed elements are admitted and unlisted arrays rejected`(): List<DynamicTest> =
        cases { schema, budget, field ->
            if (field.kind != QueryValueKind.ARRAY || field.scope != null) return@cases emptyList()
            val elements = schema.describe(budget, defaultListSize = null).elements.map { it.path }
            val child = schema.describe(budget, defaultListSize = null).fields
                .firstOrNull { it.scope == field.path } ?: return@cases emptyList()
            val predicate = construct(FilterOperator.EXISTS, child.path.removePrefix("${field.path}."), child.types)
            listOf(
                Case("${field.path} element", field.path in elements) {
                    val query = ListQuery(ElementMatchFilter(QueryField(field.path), predicate), limit = 1)
                    budget.check(query)
                    QueryAdmission.Trusted.list(query, schema)
                },
            )
        }

    /**
     * The model-wide analysis features: a metric type, HAVING, a metric sort and dense fill are listed exactly when
     * the storage and the entry admit them, on a field whose own capabilities admit the rest of the query.
     */
    @TestFactory
    fun `listed analysis features are admitted and unlisted ones rejected`(): List<DynamicTest> =
        schemas.flatMap { (schemaName, schema) ->
            budgets.flatMap { (budgetName, budget) ->
                val descriptor = schema.describe(budget, defaultListSize = null)
                val analysis = descriptor.analysis
                val aggregates = descriptor.fields.associate { it.path to it.aggregate }
                val numeric = aggregates["state.count"]?.functions?.isNotEmpty() == true
                val grouped = aggregates["state.name"]?.groups?.contains("TERMS") == true
                val dated = aggregates["state.createdAt"]?.groups?.contains("DATE_HISTOGRAM") == true
                val count = AggregationMetric.Count("count")
                val terms = listOf(AggregationGroup.Terms(QueryField("state.name"), "name"))
                val input = AggregationExpression.Field(QueryField("state.count"))
                val day = AggregationGroup.DateHistogram(
                    QueryField("state.createdAt"),
                    "d",
                    AggregationDateUnit.DAY,
                    dense = true
                )
                val having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0)
                val metricSort = listOf(Sort(QueryField("count"), Sort.Direction.DESC))
                fun admits(query: AggregationQuery): () -> Any = { aggregate(query, schema, budget) }
                val metrics = listOf(
                    "PERCENTILE" to AggregationMetric.Percentile(input, 50.0, "m"),
                    "DISTINCT_COUNT" to AggregationMetric.DistinctCount(input, "m"),
                    "FIRST" to AggregationMetric.First(QueryField("state.count"), "m"),
                ).map { (name, metric) ->
                    Case(
                        "metric $name",
                        name in analysis.metrics,
                        numeric,
                        admits(AggregationQuery(metrics = listOf(metric)))
                    )
                }
                val features = listOf(
                    Case(
                        "HAVING on COUNT",
                        "COUNT" in analysis.having.metrics,
                        grouped,
                        admits(AggregationQuery(groupBy = terms, metrics = listOf(count), having = having)),
                    ),
                    Case(
                        "metric sort",
                        analysis.sort.metrics,
                        grouped,
                        admits(AggregationQuery(groupBy = terms, metrics = listOf(count), sort = metricSort)),
                    ),
                    Case(
                        "dense DATE_HISTOGRAM",
                        analysis.dense,
                        dated,
                        admits(AggregationQuery(groupBy = listOf(day), metrics = listOf(count))),
                    ),
                )
                (metrics + features).filter { it.applies }.map { test("$schemaName, $budgetName", it) }
            }
        }

    private fun aggregate(query: AggregationQuery, schema: QueryModelSchema, budget: QueryBudget): Any {
        budget.check(query)
        return QueryAdmission.Trusted.aggregate(query, schema)
    }

    /** One listed or unlisted item as a minimal query; it [applies] where the rest of that query is admissible. */
    private class Case(val name: String, val listed: Boolean, val applies: Boolean = true, val admit: () -> Any)

    private fun cases(build: (QueryModelSchema, QueryBudget, FieldDescriptor) -> List<Case>): List<DynamicTest> =
        schemas.flatMap { (schemaName, schema) ->
            budgets.flatMap { (budgetName, budget) ->
                schema.describe(budget, defaultListSize = null).fields.map { budgetName to it }
            }.flatMap { (budgetName, field) ->
                build(schema, budgets.getValue(budgetName), field).map { test("$schemaName, $budgetName", it) }
            }
        }

    private fun test(context: String, case: Case): DynamicTest =
        DynamicTest.dynamicTest("$context: ${case.name} (${if (case.listed) "listed" else "unlisted"})") {
            val outcome = runCatching { case.admit() }
            if (case.listed) {
                outcome.exceptionOrNull()?.let { throw AssertionError("listed but rejected: ${it.message}", it) }
            } else {
                outcome.isFailure.assert().describedAs("unlisted but admitted").isTrue()
            }
        }

    private fun FilterOperator.isFieldOperator(): Boolean =
        spec.target == OperatorTarget.FIELD && spec.valueRule != ValueRule.ELEMENT_SCOPE

    /**
     * A minimal filter applying [operator] to [field], wrapped in the field's element scope. `null` when no
     * well-formed node exists for this pair (an unlisted combination the AST itself cannot express), which counts
     * as rejected; a listed pair must always build.
     */
    private fun fieldFilter(
        operator: FilterOperator,
        absolute: String,
        scope: String?,
        types: Set<QueryValueType>,
        listed: Boolean,
    ): FilterExpression? {
        val path = if (scope == null) absolute else absolute.removePrefix("$scope.")
        val node = runCatching { construct(operator, path, types) }
        if (node.isFailure) {
            if (listed) {
                throw AssertionError("cannot build a listed ${operator.name} on $absolute", node.exceptionOrNull())
            }
            return null
        }
        val predicate = node.getOrThrow()
        return if (scope == null) predicate else ElementMatchFilter(QueryField(scope), predicate)
    }

    private fun construct(operator: FilterOperator, path: String, types: Set<QueryValueType>): FilterExpression {
        val filterClass = requireNotNull(filterClasses[operator.name]) { "No filter class for [$operator]." }
        val constructor = requireNotNull(filterClass.primaryConstructor)
        val arguments = constructor.parameters.filterNot { it.isOptional }.associateWith { sample(it, path, types) }
        return constructor.callBy(arguments) as FilterExpression
    }

    private fun sample(parameter: KParameter, path: String, types: Set<QueryValueType>): Any? {
        val classifier = parameter.type.classifier
        return when {
            classifier == QueryField::class -> QueryField(path)
            classifier == JsonNode::class -> valueOf(types)
            classifier == List::class -> listOf(valueOf(types))
            classifier == Set::class -> setOf(QueryField(path))
            classifier == Int::class -> 1
            classifier == Double::class -> 1.0
            parameter.name == "time" -> "00:00"
            parameter.name == "offset" -> "PT1H"
            classifier == String::class -> "a"
            else -> error("No sample for [${parameter.name}: ${parameter.type}].")
        }
    }

    private fun valueOf(types: Set<QueryValueType>): JsonNode = when (types.firstOrNull()) {
        QueryValueType.INTEGER -> nodes.numberNode(1)
        QueryValueType.DECIMAL -> nodes.numberNode(1.5)
        QueryValueType.BOOLEAN -> nodes.booleanNode(true)
        QueryValueType.OBJECT -> nodes.objectNode().put("sku", "a")
        else -> nodes.stringNode("a")
    }
}
