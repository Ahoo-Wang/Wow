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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.exception.ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT
import me.ahoo.wow.exception.ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE
import me.ahoo.wow.exception.ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE
import me.ahoo.wow.exception.WowException

class CommandExpectVersionConflictException(
    val command: CommandMessage<*>,
    val expectVersion: Int,
    val actualVersion: Int,
    cause: Throwable? = null,
) : WowException(
    errorCode = COMMAND_EXPECT_VERSION_CONFLICT,
    errorMsg = "The expected version[$expectVersion] of the command is inconsistent with the actual version[$actualVersion].",
    cause = cause
)

class IllegalAccessDeletedAggregateException(
    override val aggregateId: AggregateId,
    errorMsg: String = "Illegal access to a deleted aggregate[${aggregateId.id}]."
) : AggregateIdCapable, WowException(
    errorCode = ILLEGAL_ACCESS_DELETED_AGGREGATE,
    errorMsg = errorMsg
)

/**
 * Exception thrown when attempting to access an aggregate owned by another tenant or user.
 *
 * This exception enforces multi-tenancy and ownership boundaries, preventing unauthorized
 * access to aggregates that belong to different owners.
 *
 * @param aggregateId The ID of the owner-restricted aggregate being accessed.
 * @param errorMsg Custom error message describing the access violation.
 */
class IllegalAccessOwnerAggregateException(
    override val aggregateId: AggregateId,
    errorMsg: String = "Illegal access to a owner aggregate[${aggregateId.id}]."
) : WowException(
    errorCode = ILLEGAL_ACCESS_OWNER_AGGREGATE,
    errorMsg = errorMsg,
),
    AggregateIdCapable
