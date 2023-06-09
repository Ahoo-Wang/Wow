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

package me.ahoo.wow.command

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.exception.ErrorCodes.COMMAND_VALIDATION
import me.ahoo.wow.exception.ErrorCodes.DUPLICATE_REQUEST_ID
import me.ahoo.wow.exception.WowException
import javax.validation.ConstraintViolation

class DuplicateRequestIdException(val aggregateId: AggregateId, val requestId: String, cause: Throwable? = null) :
    WowException(
        errorCode = DUPLICATE_REQUEST_ID,
        errorMsg = "Duplicate request ID[$requestId].",
        cause = cause,
    ),
    NamedAggregate by aggregateId

class CommandValidationException(
    val commandMessage: CommandMessage<*>,
    val constraintViolations: Set<ConstraintViolation<*>>
) :
    WowException(
        COMMAND_VALIDATION,
        constraintViolations.asErrorMessage(),
    ),
    NamedAggregate by commandMessage {

    companion object {
        private fun Set<ConstraintViolation<*>>.asErrorMessage(): String {
            val constraintViolations = this
            return buildString {
                constraintViolations.forEach {
                    append("[")
                    append(it.propertyPath)
                    append("]:")
                    append(it.message)
                    appendLine()
                }
            }
        }
    }
}

class CommandResultException(val commandResult: CommandResult) :
    WowException(errorCode = commandResult.errorCode, errorMsg = commandResult.errorMsg)
