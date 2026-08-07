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

package me.ahoo.wow.query.internal.admission

import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/** Per-admission cumulative budget shared by every condition value, field and option. */
internal class AdmissionBudget(
    private val limits: QueryAdmissionLimits,
) {
    private var valueNodes: Int = 0
    private var payloadBytes: Long = 0

    fun enterValue(path: QueryRejectionPath) {
        if (valueNodes == limits.maxValueNodes) {
            rejectBudget(path, QueryRejectionCode.VALUE_NODE_LIMIT_EXCEEDED)
        }
        valueNodes++
    }

    fun consumeString(value: String, path: QueryRejectionPath) {
        if (value.length > limits.maxStringLength) {
            rejectBudget(path, QueryRejectionCode.STRING_LIMIT_EXCEEDED)
        }
        consumeUtf8(value, path)
    }

    fun consumeUtf8(value: String, path: QueryRejectionPath) {
        consumePayload(value.toByteArray(StandardCharsets.UTF_8).size.toLong(), path)
    }

    fun consumeBytes(size: Int, path: QueryRejectionPath) {
        if (size > limits.maxByteArrayLength) {
            rejectBudget(path, QueryRejectionCode.BYTE_ARRAY_LIMIT_EXCEEDED)
        }
        consumePayload(size.toLong(), path)
    }

    fun consumeNumber(number: Number, path: QueryRejectionPath): String {
        when (number) {
            is BigDecimal -> if (number.precision() > limits.maxNumericPrecision) {
                rejectBudget(path, QueryRejectionCode.NUMERIC_PRECISION_LIMIT_EXCEEDED)
            }
            is BigInteger -> {
                val maxBits = limits.maxNumericPrecision.toLong() * 4 + 1
                if (number.abs().bitLength().toLong() > maxBits) {
                    rejectBudget(path, QueryRejectionCode.NUMERIC_PRECISION_LIMIT_EXCEEDED)
                }
            }
        }
        val text = number.toString()
        val precision = text.trimStart('-').count(Char::isDigit)
        if (precision > limits.maxNumericPrecision) {
            rejectBudget(path, QueryRejectionCode.NUMERIC_PRECISION_LIMIT_EXCEEDED)
        }
        consumeString(text, path)
        return text
    }

    private fun consumePayload(size: Long, path: QueryRejectionPath) {
        if (size > limits.maxValuePayloadBytes - payloadBytes) {
            rejectBudget(path, QueryRejectionCode.PAYLOAD_LIMIT_EXCEEDED)
        }
        payloadBytes += size
    }

    private fun rejectBudget(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, code)
}
