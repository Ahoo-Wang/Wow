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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.query.resolve.jsonValueOrNull
import tools.jackson.databind.JsonNode

/**
 * The values an operation on this value compares: the value itself, an array's items (one member layer, without
 * flattening the definition) and a union's alternatives, each a non-union value. A storage adapter asks each one which
 * storage families a capability needs ([storageFamilies]).
 */
fun QueryValueSchema.operationValues(): List<QueryValueSchema> = when (kind) {
    QueryValueKind.ARRAY -> checkNotNull(items).alternativesOrSelf()
    QueryValueKind.UNION -> alternatives.flatMap { it.operationValues() }
    else -> listOf(this)
}

/** Whether this value may be an array holding a `null` element: an array whose items the schema lets be `null`. */
fun QueryValueSchema.mayHoldNullElement(): Boolean = alternativesOrSelf().any { value ->
    value.kind == QueryValueKind.ARRAY && checkNotNull(value.items).alternativesOrSelf().any { it.nullable }
}

/** Flattens nested unions into their non-union alternatives; any other value is its own single alternative. */
fun QueryValueSchema.alternativesOrSelf(): List<QueryValueSchema> =
    if (kind == QueryValueKind.UNION) alternatives.flatMap { it.alternativesOrSelf() } else listOf(this)

/**
 * How this value stores time: the one temporal semantic type every non-null operation value shares, or `null` when
 * they declare none, or different ones.
 */
internal fun QueryValueSchema.sharedTemporal(): Temporal? = operationValues().filter { it.kind != QueryValueKind.NULL }
    .map { it.semanticType as? Temporal }.distinct().singleOrNull()

/** Whether this encoding holds instants date groups and date differences can read: a date or an epoch. */
internal val Temporal?.encodesInstant: Boolean
    get() = this == Temporal.Date || this is Temporal.Epoch

/** Whether any alternative of this value is an array, so storage may flatten it into multiple values. */
internal fun QueryValueSchema.hasArrayBranch(): Boolean = alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }

internal fun QueryValueSchema.accepts(values: Iterable<Any?>): Boolean {
    val domains = if (kind == QueryValueKind.SCALAR || kind == QueryValueKind.OBJECT) {
        listOf(this)
    } else {
        operationValues().filter { it.kind != QueryValueKind.NULL && it.kind != QueryValueKind.UNKNOWN }
    }
    return values.all { input ->
        if (input == null) return@all true
        // A runtime value is compared as the JSON it serializes to, converted once for every domain and type.
        val canonical = input.jsonValueOrNull() ?: return@all false
        domains.any { domain -> domain.valueTypes.any { canonical.matches(it) } }
    }
}

private fun JsonNode.matches(type: QueryValueType): Boolean = when (type) {
    QueryValueType.STRING -> isString
    QueryValueType.INTEGER -> isNumber && canConvertToExactIntegral()
    QueryValueType.DECIMAL -> isNumber
    QueryValueType.BOOLEAN -> isBoolean
    QueryValueType.OBJECT -> isObject
    else -> true
}

/**
 * The encoding this relative-time filter's window is written in: the field's [temporal] encoding (its compiled
 * [QueryFieldCapabilities.temporal]). A field that keeps no time takes no relative-time filter.
 */
internal fun RelativeTimeFilter.temporal(temporal: Temporal?, logical: QueryField<*> = field): Temporal {
    requireValid(temporal != null) { QueryViolation.TemporalRepresentationRequired(logical) }
    return checkNotNull(temporal)
}
