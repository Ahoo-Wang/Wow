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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.*
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
        element.filter.validateElementFilter(catalog, expected, element.path)
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

private fun FilterExpression.validateElementFilter(
    catalog: AggregationFieldCatalog,
    elementPaths: List<String>,
    elementPath: String,
) {
    when (this) {
        MatchAllFilter, MatchNoneFilter -> return
        is AndFilter -> {
            operands.forEach { it.validateElementFilter(catalog, elementPaths, elementPath) }
            return
        }
        is OrFilter -> {
            operands.forEach { it.validateElementFilter(catalog, elementPaths, elementPath) }
            return
        }
        is NorFilter -> {
            operands.forEach { it.validateElementFilter(catalog, elementPaths, elementPath) }
            return
        }
        is ElementMatchFilter, is SearchFilter, is DeletionFilter -> throw IllegalArgumentException(
            "Aggregation element filters must not use [$operator]."
        )
        else -> Unit
    }
    val field = requiredElementField()
    require(field.startsWith("$elementPath.")) {
        "Aggregation element filter field [$field] must belong to element [$elementPath]."
    }
    val resolved = catalog.requirePath(field)
    require(resolved.collectionPaths == elementPaths) {
        "Aggregation element filter field [$field] must not traverse an undeclared collection."
    }
    require(resolved.supportsElementFilter(this)) {
        "Aggregation element filter [$operator] does not support field [$field] of type [${resolved.type.rawClass.name}]."
    }
}

@Suppress("CyclomaticComplexMethod")
private fun AggregationField.supportsElementFilter(filter: FilterExpression): Boolean = when (filter) {
    is EqualFilter -> kind == AggregationFieldKind.SCALAR || filter.value.isNull && kind == AggregationFieldKind.OBJECT
    is NotEqualFilter ->
        kind == AggregationFieldKind.SCALAR || filter.value.isNull && kind == AggregationFieldKind.OBJECT
    is InFilter, is NotInFilter -> kind == AggregationFieldKind.SCALAR
    is GreaterThanFilter,
    is GreaterThanOrEqualFilter,
    is LessThanFilter,
    is LessThanOrEqualFilter,
    is BetweenFilter,
    -> kind == AggregationFieldKind.SCALAR && isRangeComparable
    is ContainsFilter, is StartsWithFilter, is EndsWithFilter ->
        kind == AggregationFieldKind.SCALAR && isTextual
    is IsNullFilter, is IsNotNullFilter, is ExistsFilter, is NotExistsFilter ->
        kind == AggregationFieldKind.SCALAR || kind == AggregationFieldKind.OBJECT
    is RelativeTimeFilter -> kind == AggregationFieldKind.SCALAR && isTemporal
    else -> false
}

private val AggregationField.isTextual: Boolean
    get() = type.rawClass.isEnum ||
        CharSequence::class.java.isAssignableFrom(type.rawClass) ||
        type.rawClass == Char::class.javaPrimitiveType ||
        type.rawClass == Char::class.javaObjectType

private val AggregationField.isRangeComparable: Boolean
    get() = isNumeric || isTemporal || isTextual

@Suppress("CyclomaticComplexMethod")
private fun FilterExpression.requiredElementField(): String = when (this) {
    is EqualFilter -> field.value
    is NotEqualFilter -> field.value
    is GreaterThanFilter -> field.value
    is GreaterThanOrEqualFilter -> field.value
    is LessThanFilter -> field.value
    is LessThanOrEqualFilter -> field.value
    is ContainsFilter -> field.value
    is StartsWithFilter -> field.value
    is EndsWithFilter -> field.value
    is InFilter -> field.value
    is NotInFilter -> field.value
    is BetweenFilter -> field.value
    is ContainsAllFilter -> field.value
    is IsEmptyFilter -> field.value
    is IsNullFilter -> field.value
    is IsNotNullFilter -> field.value
    is ExistsFilter -> field.value
    is NotExistsFilter -> field.value
    is TodayFilter -> field.value
    is BeforeTodayFilter -> field.value
    is TomorrowFilter -> field.value
    is ThisWeekFilter -> field.value
    is NextWeekFilter -> field.value
    is LastWeekFilter -> field.value
    is ThisMonthFilter -> field.value
    is LastMonthFilter -> field.value
    is RecentDaysFilter -> field.value
    is EarlierDaysFilter -> field.value
    else -> error("Aggregation element filter [$operator] is not supported.")
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
