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
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.id.GlobalIdGenerator

const val COMPENSATION_PREFIX = "compensate."
const val COMPENSATION_ID = "${COMPENSATION_PREFIX}id"
const val COMPENSATION_CONTEXT = "${COMPENSATION_PREFIX}context"
const val COMPENSATION_PROCESSOR = "${COMPENSATION_PREFIX}processor"

data class CompensationTarget(
    val id: String = GlobalIdGenerator.generateAsString(),
    val processor: ProcessorInfoData
)

object CompensationMatcher {
    fun <M : Message<out M, *>> M.withCompensation(target: CompensationTarget): M {
        header.withCompensation(target)
        return this
    }

    fun Header.withCompensation(target: CompensationTarget): Header {
        return with(COMPENSATION_ID, target.id)
            .with(COMPENSATION_CONTEXT, target.processor.contextName)
            .with(COMPENSATION_PROCESSOR, target.processor.processorName)
    }

    fun Message<*, *>.match(processor: ProcessorInfo): Boolean {
        return header.match(processor)
    }

    val Header.compensationId: String?
        get() = this[COMPENSATION_ID]

    fun Header.match(processor: ProcessorInfo): Boolean {
        if (!containsKey(COMPENSATION_ID)) {
            return true
        }
        val context = this[COMPENSATION_CONTEXT]
        val processorName = this[COMPENSATION_PROCESSOR]
        if (context != processor.contextName) {
            return false
        }
        if (processorName != processor.processorName) {
            return false
        }
        return true
    }
}
