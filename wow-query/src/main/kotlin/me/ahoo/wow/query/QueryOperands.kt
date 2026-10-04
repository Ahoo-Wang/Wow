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

package me.ahoo.wow.query

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode

/*
 * The one rule every backend applies to a filter operand before handing it to its driver. An operand a backend
 * cannot compile is the caller's fault, so it is a client violation (HTTP 400, STORAGE_UNSUPPORTED), never a server
 * fault:
 * - an object, a binary node or a POJO that serializes to one is not an operand;
 * - `NaN` and the infinities are not operands: no storage orders them as JSON numbers;
 * - a runtime POJO (from the Kotlin DSL) is compared as the JSON it serializes to, as admission judged it, unless the
 *   storage's driver encodes POJOs natively ([PojoOperands.NATIVE]).
 */

/** How a backend hands a runtime POJO operand to its driver. */
@WowSpi
enum class PojoOperands {
    /** As the JSON it serializes to, the form a record stores it in. */
    NORMALIZE,

    /**
     * As the object itself, for a driver that encodes it natively: MongoDB's codecs encode an `ObjectId` or a `Date`
     * operand of the deprecated Condition API as BSON, which legacy collections store.
     */
    NATIVE,
}

/** Whether this comparison ignores case. */
@WowSpi
val StringComparison.ignoreCase: Boolean
    get() = this == StringComparison.CASE_INSENSITIVE

/**
 * This operand as a driver value: `null`, a [String], a [Number], a [Boolean], or a [List] of those for an array
 * operand (exact array equality, `IN` values). A runtime POJO is handled as [pojos] says.
 */
@WowSpi
fun JsonNode.operandValue(pojos: PojoOperands = PojoOperands.NORMALIZE): Any? = when {
    isPojo -> if (pojos == PojoOperands.NATIVE) (this as POJONode).pojo else normalizedPojo().operandValue(pojos)
    isNull -> null
    isString -> asString()
    isNumber -> numberValue().also { it.requireFinite() }
    isBoolean -> booleanValue()
    isArray -> asSequence().map { it.operandValue(pojos) }.toList()
    else -> throw NON_SCALAR.rejection()
}

/**
 * This operand as a non-null scalar driver value, as a range bound or a term needs: a [String], [Number] or [Boolean],
 * or a native POJO under [PojoOperands.NATIVE].
 */
@WowSpi
fun JsonNode.requiredOperandValue(pojos: PojoOperands = PojoOperands.NORMALIZE): Any {
    val value = operandValue(pojos)
    return when {
        value == null -> throw QueryViolation.StorageUnsupported("null range or term filter operands").rejection()
        value is String || value is Number || value is Boolean -> value
        isPojo && pojos == PojoOperands.NATIVE -> value
        else -> throw NON_SCALAR.rejection()
    }
}

private fun JsonNode.normalizedPojo(): JsonNode {
    val tree = JsonSerializer.valueToTree<JsonNode>((this as POJONode).pojo)
    if (tree.isPojo) throw NON_SCALAR.rejection()
    return tree
}

private fun Number.requireFinite() {
    val finite = when (this) {
        is Double -> isFinite()
        is Float -> isFinite()
        else -> true
    }
    if (!finite) throw QueryViolation.StorageUnsupported("non-finite numeric filter operands").rejection()
}

private val NON_SCALAR = QueryViolation.StorageUnsupported("non-scalar filter operands")
