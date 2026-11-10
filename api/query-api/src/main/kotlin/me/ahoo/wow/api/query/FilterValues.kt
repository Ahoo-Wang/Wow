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

package me.ahoo.wow.api.query

/*
 * The values a filter carries are plain JSON values, the shape every JSON library reads and writes, so this module
 * needs none:
 *
 * - a scalar: `null`, a String, a Number or a Boolean;
 * - for EQ and NE only, also a List of scalars (an array equality);
 * - a runtime value: any other object that is not a collection, a map or an array, such as an enum or a value object
 *   built in process. The backend compares it in its own representation. Decoding JSON never yields one: a JSON
 *   object reads as a Map and an array as a List, which the filters reject where they do not accept them.
 */

/** Whether this is a JSON scalar: `null`, a string, a number or a boolean. */
internal fun Any?.isFilterScalar(): Boolean = this == null || this is String || this is Number || this is Boolean

/** Whether this is a runtime value: a non-null object that is neither a scalar nor a collection, map or array. */
internal fun Any?.isRuntimeFilterValue(): Boolean =
    this != null && !isFilterScalar() && this !is Collection<*> && this !is Map<*, *> && !javaClass.isArray

private const val EQUALITY_VALUE_MESSAGE = "EQ/NE value must be a JSON scalar, scalar array, or runtime value."

internal fun Any?.requireFilterLiteral() {
    require(isFilterScalar()) { "Filter value must be a JSON scalar." }
}

internal fun Any?.requireEqualityFilterValue() {
    if (this is List<*>) {
        forEach { require(it.isRuntimeFilterValue() || it.isFilterScalar()) { EQUALITY_VALUE_MESSAGE } }
    } else {
        require(isRuntimeFilterValue() || isFilterScalar()) { EQUALITY_VALUE_MESSAGE }
    }
}

internal fun Any?.requireComparableFilterLiteral() {
    if (!isRuntimeFilterValue()) requireFilterLiteral()
    require(this != null) { "Comparison filter value cannot be null." }
}

internal fun List<Any?>.requireFilterLiterals(operator: FilterOperator) {
    require(isNotEmpty()) { "$operator values cannot be empty." }
    forEach {
        if (!it.isRuntimeFilterValue()) it.requireFilterLiteral()
        require(it != null) { "$operator values cannot contain null." }
    }
}
