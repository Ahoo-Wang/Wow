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

package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.id.generateGlobalId

/**
 * Prefix for all compensation-related header keys.
 */
const val COMPENSATION_PREFIX = "compensate."

/**
 * Header key for compensation ID.
 */
const val COMPENSATION_ID = "${COMPENSATION_PREFIX}id"

/**
 * Header key for compensation context name.
 */
const val COMPENSATION_CONTEXT = "${COMPENSATION_PREFIX}context"

/**
 * Header key for compensation processor name.
 */
const val COMPENSATION_PROCESSOR = "${COMPENSATION_PREFIX}processor"

/**
 * Header key for compensation function name.
 */
const val COMPENSATION_FUNCTION = "${COMPENSATION_PREFIX}function"

/**
 * Represents a target for compensation operations.
 *
 * @property id Unique identifier for this compensation target (auto-generated if not provided)
 * @property function The function information that should be compensated
 */
data class CompensationTarget(
    val id: String = generateGlobalId(),
    val function: FunctionInfoData
)

/**
 * Utility object for handling compensation matching and header manipulation.
 *
 * Provides functions to mark messages for compensation and check if messages
 * match specific compensation targets.
 */
object CompensationMatcher {
    /**
     * Marks this message for compensation with the specified target.
     *
     * @param target The compensation target to associate with this message
     * @return This message with compensation headers set
     */
    fun <M : Message<out M, *>> M.withCompensation(target: CompensationTarget): M {
        header.withCompensation(target)
        return this
    }

    /**
     * Adds compensation information to this header.
     *
     * @param target The compensation target containing the function to compensate
     * @return A new header with compensation information
     */
    fun Header.withCompensation(target: CompensationTarget): Header =
        with(COMPENSATION_ID, target.id)
            .with(COMPENSATION_CONTEXT, target.function.contextName)
            .with(COMPENSATION_PROCESSOR, target.function.processorName)
            .with(COMPENSATION_FUNCTION, target.function.name)

    /**
     * Checks if this message matches the given function for compensation purposes.
     *
     * @param function The function to check against
     * @return true if the message matches the function, false otherwise
     */
    fun Message<*, *>.match(function: FunctionInfo): Boolean = header.match(function)

    /**
     * Gets the compensation ID from the header, if present.
     */
    val Header.compensationId: String?
        get() = this[COMPENSATION_ID]

    /**
     * Checks if this header contains compensation information.
     */
    val Header.isCompensation: Boolean
        get() = containsKey(COMPENSATION_ID)

    /**
     * Checks if this header's compensation information matches the given function.
     *
     * For non-compensation messages, always returns true.
     * For compensation messages, checks if the context, processor, and function names match.
     *
     * @param function The function to match against
     * @return true if the header matches the function, false otherwise
     */
    fun Header.match(function: FunctionInfo): Boolean {
        if (!isCompensation) {
            return true
        }
        val context = this[COMPENSATION_CONTEXT]
        if (context != function.contextName) {
            return false
        }
        val processorName = this[COMPENSATION_PROCESSOR]
        if (processorName != function.processorName) {
            return false
        }
        val functionName = this[COMPENSATION_FUNCTION]
        return functionName == function.name
    }
}
