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

package me.ahoo.wow.modeling.state

import me.ahoo.wow.api.exception.ConflictException
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.WowException
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream

object StateAggregateErrorCodes {
    const val PREFIX = "${ErrorCodes.PREFIX}MS-"
    const val SOURCING_VERSION_CONFLICT = PREFIX + ErrorCodes.CONFLICT
}

class SourcingVersionConflictException(
    val eventStream: DomainEventStream,
    val expectVersion: Int,
) : ConflictException,
    WowException(
        StateAggregateErrorCodes.SOURCING_VERSION_CONFLICT,
        "Failed to Sourcing eventStream[${eventStream.id}]: Expected EventStream version[$expectVersion] does not match the actual version:[${eventStream.version}].",
    ),
    NamedAggregate by eventStream
