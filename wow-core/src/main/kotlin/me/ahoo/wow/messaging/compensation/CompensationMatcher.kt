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

const val COMPENSATION_PREFIX = "compensate."
const val COMPENSATION_ID = "${COMPENSATION_PREFIX}id"
const val COMPENSATION_CONTEXT = "${COMPENSATION_PREFIX}context"
const val COMPENSATION_PROCESSOR = "${COMPENSATION_PREFIX}processor"
const val COMPENSATION_FUNCTION = "${COMPENSATION_PREFIX}function"

data class CompensationTarget(
    val id: String = generateGlobalId(),
    val function: FunctionInfoData,
)

object CompensationMatcher {
    fun <M : Message<out M, *>> M.withCompensation(target: CompensationTarget): M {
        header.withCompensation(target)
        return this
    }

    fun Header.withCompensation(target: CompensationTarget): Header {
        return with(COMPENSATION_ID, target.id)
            .with(COMPENSATION_CONTEXT, target.function.contextName)
            .with(COMPENSATION_PROCESSOR, target.function.processorName)
            .with(COMPENSATION_FUNCTION, target.function.name)
    }

    fun Message<*, *>.match(function: FunctionInfo): Boolean {
        return header.match(function)
    }

    val Header.compensationId: String?
        get() = this[COMPENSATION_ID]

    /**
     * 是否是补偿消息
     */
    val Header.isCompensation: Boolean
        get() = containsKey(COMPENSATION_ID)

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
