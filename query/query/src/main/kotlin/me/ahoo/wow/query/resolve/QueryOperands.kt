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

package me.ahoo.wow.query.resolve

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.schema.QueryViolation
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/*
 * The one rule every backend applies to a filter operand before handing it to its driver. An operand a backend
 * cannot compile is the caller's fault, so it is a client violation (STORAGE_UNSUPPORTED), never a server fault:
 * - a map, an array, a binary value or a runtime value that writes as one is not an operand;
 * - `NaN` and the infinities are not operands: no storage orders them as JSON numbers;
 * - a runtime value (an enum or a value object built in process) is compared as the JSON it writes as, as admission
 *   judged it, unless the storage's driver encodes it natively ([RuntimeOperands.NATIVE]).
 */

/**
 * How a backend hands a runtime value operand (9.x: a POJO) to its driver.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class RuntimeOperands {
    /** As the JSON it writes as, the form a record stores it in. */
    NORMALIZE,

    /** As the object itself, for a driver that encodes it natively (a MongoDB `ObjectId` or `Date`). */
    NATIVE,
}

/** Whether this comparison ignores case. */
val StringComparison.ignoreCase: Boolean
    get() = this == StringComparison.CASE_INSENSITIVE

/**
 * This operand as a driver value: `null`, a [String], a [Number], a [Boolean], or a [List] of those for an array
 * operand (exact array equality, `IN` values). A runtime value is handled as [runtime] says.
 */
fun Any?.operandValue(runtime: RuntimeOperands = RuntimeOperands.NORMALIZE): Any? = when (this) {
    null -> null
    is String, is Boolean -> this
    is Number -> also { it.requireFinite() }
    is List<*> -> map { it.operandValue(runtime) }
    is JsonNode -> jsonOperand(runtime)
    is Map<*, *>, is Collection<*>, is Array<*>, is ByteArray -> throw NON_SCALAR.rejection()
    else -> if (runtime == RuntimeOperands.NATIVE) this else normalized().operandValue(runtime)
}

/**
 * This operand as a non-null scalar driver value, as a range bound or a term needs: a [String], [Number] or [Boolean],
 * or a native runtime value under [RuntimeOperands.NATIVE].
 */
fun Any?.requiredOperandValue(runtime: RuntimeOperands = RuntimeOperands.NORMALIZE): Any {
    val value = operandValue(runtime)
    return when {
        value == null -> throw QueryViolation.StorageUnsupported("null range or term filter operands").rejection()
        value is String || value is Number || value is Boolean -> value
        runtime == RuntimeOperands.NATIVE && value === this -> value
        else -> throw NON_SCALAR.rejection()
    }
}

private fun JsonNode.jsonOperand(runtime: RuntimeOperands): Any? = when {
    isNull -> null
    isString -> asString()
    isNumber -> numberValue().also { it.requireFinite() }
    isBoolean -> booleanValue()
    isArray -> asSequence().map { it.jsonOperand(runtime) }.toList()
    else -> throw NON_SCALAR.rejection()
}

private fun Any.normalized(): JsonNode = jsonValueOrNull() ?: throw NON_SCALAR.rejection()

private fun Number.requireFinite() {
    val finite = when (this) {
        is Double -> isFinite()
        is Float -> isFinite()
        else -> true
    }
    if (!finite) throw QueryViolation.StorageUnsupported("non-finite numeric filter operands").rejection()
}

private val NON_SCALAR = QueryViolation.StorageUnsupported("non-scalar filter operands")

/**
 * The most fractional digits of a numeric operand: those of PostgreSQL's `numeric`, the widest exact decimal of the
 * stores ([requireStoredDigits]).
 */
internal const val MAX_OPERAND_SCALE: Int = 16_383

/** The most integer digits of a numeric operand: those of PostgreSQL's `numeric` ([requireStoredDigits]). */
internal const val MAX_OPERAND_INTEGER_DIGITS: Int = 131_072

/**
 * Refuses this operand of [field], a number whose digits pass what any store's exact decimal holds: more than
 * [MAX_OPERAND_SCALE] digits after the point or [MAX_OPERAND_INTEGER_DIGITS] before it. A query body's number is a
 * `BigDecimal` of whatever exponent its text writes, `1e-500000000` in 12 characters, and a driver pays for its digits
 * when it binds it (pgjdbc computes `10^scale`; a float column's text would write each digit out, #7070); so it is
 * refused before any store is asked, as a number no storage orders is (`NaN`, the infinities). Any other value passes.
 */
internal fun Any?.requireStoredDigits(field: QueryField<*>) {
    // As the JSON it writes as, which admission has already found it to have.
    val value = jsonValue()
    val digits = when {
        value.isBigDecimal -> value.decimalValue()
        value.isBigInteger -> BigDecimal(value.bigIntegerValue())
        else -> return
    }
    val scale = digits.scale()
    if (scale > MAX_OPERAND_SCALE || digits.precision() - scale > MAX_OPERAND_INTEGER_DIGITS) {
        throw QueryViolation.StorageUnsupported(
            "a numeric operand of more than $MAX_OPERAND_SCALE digits after the point or " +
                "$MAX_OPERAND_INTEGER_DIGITS before it",
            field,
        ).rejection()
    }
}
