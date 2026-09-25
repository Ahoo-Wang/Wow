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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.descriptor.FieldDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
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
 * field operator, sort, group and metric it leaves out is rejected. For each schema below, each listed or unlisted
 * item becomes a minimal query and is run through admission.
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
            "items" to arrayFixture(objectFixture("sku" to scalarFixture(), "qty" to scalarFixture(QueryValueType.INTEGER))),
            "phone" to scalarFixture(mask = MaskRule(SensitivityLevel.DISPLAY)),
            "idCard" to scalarFixture(mask = MaskRule(SensitivityLevel.CONFIDENTIAL)),
        ),
    )

    private val schemas: Map<String, QueryModelSchema> = mapOf(
        "full capabilities" to boundSchemaFixture(root),
        "exact match and presence only" to boundSchemaFixture(
            root,
            fieldCapabilities = setOf(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH),
        ),
    )

    @TestFactory
    fun `listed filter operators are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, field ->
            FilterOperator.entries.filter { it.isFieldOperator() }.mapNotNull { operator ->
                val listed = operator in field.filter.operators
                val filter = fieldFilter(operator, field, listed) ?: return@mapNotNull null
                Case(
                    "${field.path} ${operator.name}",
                    listed
                ) { QueryAdmission.list(ListQuery(filter, limit = 1), schema) }
            }
        }

    @TestFactory
    fun `listed sorts are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, field ->
            if (field.scope != null) return@cases emptyList()
            val sort = listOf(Sort(QueryField(field.path), Sort.Direction.ASC))
            listOf(
                Case("${field.path} paged sort", field.sort.paged) {
                    QueryAdmission.paged(PagedQuery(MatchAllFilter, sort = sort, pagination = Pagination(1, 1)), schema)
                },
                Case("${field.path} cursor sort", field.sort.cursor) {
                    QueryAdmission.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 1), schema)
                },
            )
        }

    @TestFactory
    fun `listed groups and metrics are admitted and unlisted ones rejected`(): List<DynamicTest> =
        cases { schema, field ->
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
                    QueryAdmission.aggregate(AggregationQuery(groupBy = listOf(group), metrics = listOf(count)), schema)
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
                    QueryAdmission.aggregate(AggregationQuery(metrics = listOf(metric)), schema)
                }
            }
        }

    private class Case(val name: String, val listed: Boolean, val admit: () -> Any)

    private fun cases(build: (QueryModelSchema, FieldDescriptor) -> List<Case>): List<DynamicTest> =
        schemas.flatMap { (schemaName, schema) ->
            schema.describe(QueryBudget.HTTP_DEFAULT, defaultListSize = null).fields.flatMap { field ->
                build(schema, field).map { case ->
                    DynamicTest.dynamicTest(
                        "$schemaName: ${case.name} (${if (case.listed) "listed" else "unlisted"})"
                    ) {
                        val outcome = runCatching { case.admit() }
                        if (case.listed) {
                            outcome.exceptionOrNull()?.let {
                                throw AssertionError(
                                    "listed but rejected: ${it.message}",
                                    it
                                )
                            }
                        } else {
                            outcome.isFailure.assert().describedAs("unlisted but admitted").isTrue()
                        }
                    }
                }
            }
        }

    private fun FilterOperator.isFieldOperator(): Boolean =
        spec.target == OperatorTarget.FIELD && spec.valueRule != ValueRule.ELEMENT_SCOPE

    /**
     * A minimal filter applying [operator] to [field], wrapped in the field's element scope. `null` when no
     * well-formed node exists for this pair (an unlisted combination the AST itself cannot express), which counts
     * as rejected; a listed pair must always build.
     */
    private fun fieldFilter(operator: FilterOperator, field: FieldDescriptor, listed: Boolean): FilterExpression? {
        val scope = field.scope
        val path = if (scope == null) field.path else field.path.removePrefix("$scope.")
        val node = runCatching { construct(operator, path, field) }
        if (node.isFailure) {
            if (listed) {
                throw AssertionError(
                    "cannot build a listed ${operator.name} on ${field.path}",
                    node.exceptionOrNull()
                )
            }
            return null
        }
        val predicate = node.getOrThrow()
        return if (scope == null) predicate else ElementMatchFilter(QueryField(scope), predicate)
    }

    private fun construct(operator: FilterOperator, path: String, field: FieldDescriptor): FilterExpression {
        val filterClass = requireNotNull(filterClasses[operator.name]) { "No filter class for [$operator]." }
        val constructor = requireNotNull(filterClass.primaryConstructor)
        val arguments = constructor.parameters.filterNot { it.isOptional }.associateWith { sample(it, path, field) }
        return constructor.callBy(arguments) as FilterExpression
    }

    private fun sample(parameter: KParameter, path: String, field: FieldDescriptor): Any? {
        val classifier = parameter.type.classifier
        return when {
            classifier == QueryField::class -> QueryField(path)
            classifier == JsonNode::class -> valueOf(field)
            classifier == List::class -> listOf(valueOf(field))
            classifier == Set::class -> setOf(QueryField(path))
            classifier == Int::class -> 1
            classifier == Double::class -> 1.0
            parameter.name == "time" -> "00:00"
            parameter.name == "offset" -> "PT1H"
            classifier == String::class -> "a"
            else -> error("No sample for [${parameter.name}: ${parameter.type}].")
        }
    }

    private fun valueOf(field: FieldDescriptor): JsonNode = when (field.types.firstOrNull()) {
        QueryValueType.INTEGER -> nodes.numberNode(1)
        QueryValueType.DECIMAL -> nodes.numberNode(1.5)
        QueryValueType.BOOLEAN -> nodes.booleanNode(true)
        QueryValueType.OBJECT -> nodes.objectNode().put("sku", "a")
        else -> nodes.stringNode("a")
    }
}
