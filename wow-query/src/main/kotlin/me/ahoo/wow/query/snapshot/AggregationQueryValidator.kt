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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.modeling.metadata.asAggregateMetadata
import me.ahoo.wow.query.AggregationField
import me.ahoo.wow.query.AggregationFieldCatalog
import me.ahoo.wow.query.AggregationFieldKind

internal fun AggregationQuery.validate(namedAggregate: NamedAggregate) {
    val stateType = namedAggregate.asAggregateMetadata<Any, Any>().state.aggregateType
    val catalog = AggregationFieldCatalog.scan(stateType)
    val elementPaths = elements.map { it.path }

    elements.forEachIndexed { index, element ->
        val resolved = catalog.requirePath(element.path)
        require(
            resolved.kind == AggregationFieldKind.OBJECT_COLLECTION &&
                resolved.type.rawClass != Any::class.java
        ) {
            "Aggregation element [${element.path}] must contain objects."
        }
        val expected = elementPaths.take(index + 1)
        require(resolved.collectionPaths == expected) {
            "Aggregation element [${element.path}] must declare every parent collection in order $expected."
        }
        element.condition.validateElementCondition(catalog, expected, element.path)
    }

    groupBy.forEach { group ->
        val field = catalog.requireLeafField(group.field, elementPaths)
        when (group) {
            is AggregationGroup.Terms -> require(field.supportsTerms) {
                "Terms field [${group.field}] must be a non-temporal scalar."
            }

            is AggregationGroup.Histogram -> require(field.isNumeric) {
                "Histogram field [${group.field}] must be numeric."
            }

            is AggregationGroup.DateHistogram -> require(field.isTemporal) {
                "Date histogram field [${group.field}] must be temporal."
            }
        }
    }
    metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
        when (val expression = metric.expression) {
            is AggregationExpression.Field -> require(
                catalog.requireLeafField(expression.field, elementPaths).isNumeric
            ) {
                "Aggregation metric field [${expression.field}] must be numeric."
            }
        }
    }
}

private fun Condition.validateElementCondition(
    catalog: AggregationFieldCatalog,
    elementPaths: List<String>,
    elementPath: String,
) {
    require(operator != Operator.ELEM_MATCH) { "Aggregation element conditions must not use ELEM_MATCH." }
    if (field.isNotEmpty()) {
        require(field.startsWith("$elementPath.")) {
            "Aggregation element condition field [$field] must belong to element [$elementPath]."
        }
        val resolved = catalog.requirePath(field)
        require(resolved.collectionPaths == elementPaths) {
            "Aggregation element condition field [$field] must not traverse an undeclared collection."
        }
        require(resolved.kind == AggregationFieldKind.SCALAR || resolved.kind == AggregationFieldKind.OBJECT) {
            "Aggregation element condition field [$field] must not be a collection."
        }
    } else {
        require(operator in ELEMENT_FIELDLESS_OPERATORS) {
            "Aggregation element condition operator [$operator] requires a field in element [$elementPath]."
        }
    }
    children.forEach { it.validateElementCondition(catalog, elementPaths, elementPath) }
}

private fun AggregationFieldCatalog.requireLeafField(field: String, elementPaths: List<String>): AggregationField {
    val resolved = requirePath(field)
    require(resolved.collectionPaths == elementPaths) {
        val source = elementPaths.lastOrNull() ?: "snapshot"
        "Aggregation field [$field] must belong to innermost source [$source] without traversing another collection."
    }
    require(elementPaths.isEmpty() || field.startsWith("${elementPaths.last()}.")) {
        "Aggregation field [$field] must belong to innermost element [${elementPaths.last()}]."
    }
    require(resolved.kind == AggregationFieldKind.SCALAR) {
        "Aggregation field [$field] must be scalar."
    }
    return resolved
}

private fun AggregationFieldCatalog.requirePath(path: String): AggregationField =
    paths[path] ?: throw IllegalArgumentException("Aggregation field [$path] is not declared or traverses a map.")

private val ELEMENT_FIELDLESS_OPERATORS = setOf(Operator.ALL, Operator.AND, Operator.OR, Operator.NOR)
