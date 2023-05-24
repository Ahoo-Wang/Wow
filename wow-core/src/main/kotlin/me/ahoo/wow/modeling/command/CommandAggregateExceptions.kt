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

package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.exception.ConflictException
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.GoneException
import me.ahoo.wow.api.exception.WowException
import me.ahoo.wow.api.modeling.AggregateId

object CommandAggregateErrorCodes {
    const val PREFIX = "${ErrorCodes.PREFIX}MC-"
    const val INCOMPATIBLE_VERSION = PREFIX + ErrorCodes.CONFLICT
    const val ILLEGAL_ACCESS_DELETED_AGGREGATE = PREFIX + ErrorCodes.GONE
}

class IncompatibleVersionException(
    val command: CommandMessage<*>,
    val expectVersion: Int,
    val actualVersion: Int
) : ConflictException, WowException(
    CommandAggregateErrorCodes.INCOMPATIBLE_VERSION,
    "Failed to process command[${command.id}]: The expected version[$expectVersion] of the command is inconsistent with the actual version[$actualVersion].",
)

class IllegalAccessDeletedAggregateException(
    val aggregateId: AggregateId
) : GoneException, WowException(
    CommandAggregateErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE,
    "Illegal access to a deleted aggregate[${aggregateId.id}].",
)
