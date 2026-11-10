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

package me.ahoo.wow.query.backend

import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/*
 * The identity and the order of JSON values wherever the core compares them itself (the in-memory reference backend,
 * the residual windows): a number by its exact value, whatever its JSON width (an int, a long, a big integer, a
 * double or a decimal); a string by its characters; a boolean false before true.
 */

/** This number's exact value. */
internal fun JsonNode.decimal(): BigDecimal = if (isIntegralNumber) BigDecimal(bigIntegerValue()) else decimalValue()

/** The order of two values of one kind (numbers, strings, booleans), or `null` when they do not compare. */
internal fun compareScalars(left: JsonNode, right: JsonNode): Int? = when {
    left.isNumber && right.isNumber -> left.decimal().compareTo(right.decimal())
    left.isString && right.isString -> left.stringValue().compareTo(right.stringValue())
    left.isBoolean && right.isBoolean -> left.booleanValue().compareTo(right.booleanValue())
    else -> null
}

/**
 * A total order over values: a missing value and `null` first, then numbers, strings and booleans, each by value, then
 * anything else by its JSON text; values of different kinds by kind, so a mixed set never fails.
 */
internal fun orderOf(left: JsonNode?, right: JsonNode?): Int {
    val leftRank = left.kindRank()
    val rightRank = right.kindRank()
    if (leftRank != rightRank) return leftRank.compareTo(rightRank)
    if (left == null || right == null) return 0
    return compareScalars(left, right) ?: left.toString().compareTo(right.toString())
}

/**
 * A key equal for values equal by value, to group or deduplicate them: a number's exact value without trailing zeros
 * (`1`, `1L` and `1.0` are one key), any other value itself.
 */
internal fun JsonNode?.valueKey(): Any? = if (this != null && isNumber) decimal().stripTrailingZeros() else this

private fun JsonNode?.kindRank(): Int = when {
    this == null || isNull -> 0
    isNumber -> 1
    isString -> 2
    isBoolean -> 3
    else -> 4
}
