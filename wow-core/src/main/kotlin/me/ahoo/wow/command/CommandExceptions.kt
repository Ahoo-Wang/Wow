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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.exception.ConflictException
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.PreconditionFailedException
import me.ahoo.wow.api.exception.WowException
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandErrorCodes.COMMAND_DUPLICATE
import me.ahoo.wow.command.CommandErrorCodes.COMMAND_NOT_VALID
import javax.validation.ConstraintViolation

object CommandErrorCodes {
    const val PREFIX = "${ErrorCodes.PREFIX}CMD-"
    const val COMMAND_DUPLICATE = PREFIX + ErrorCodes.CONFLICT
    const val COMMAND_NOT_VALID = PREFIX + ErrorCodes.ILLEGAL_ARGUMENT
}

class DuplicateCommandException(val commandMessage: CommandMessage<*>) :
    ConflictException,
    WowException(
        COMMAND_DUPLICATE,
        "Failed to send command[${commandMessage.id}]: Duplicate request ID[${commandMessage.requestId}].",
    ),
    NamedAggregate by commandMessage

class CommandNotValidException(
    val commandMessage: CommandMessage<*>,
    val constraintViolations: Set<ConstraintViolation<*>>
) : PreconditionFailedException,
    WowException(
        COMMAND_NOT_VALID,
        commandMessage.asErrorMessage(constraintViolations),
    ),
    NamedAggregate by commandMessage {

    companion object {
        private fun CommandMessage<*>.asErrorMessage(constraintViolations: Set<ConstraintViolation<*>>): String {
            val commandId = commandId
            return buildString {
                append("Failed to send command[$commandId]: Command validation failed:")
                constraintViolations.forEach {
                    appendLine()
                    append("[")
                    append(it.propertyPath)
                    append("]:")
                    append(it.message)
                }
            }
        }
    }
}

class CommandResultException(val commandResult: CommandResult) :
    PreconditionFailedException,
    WowException(errorCode = commandResult.errorCode, errorMsg = commandResult.errorMsg)
