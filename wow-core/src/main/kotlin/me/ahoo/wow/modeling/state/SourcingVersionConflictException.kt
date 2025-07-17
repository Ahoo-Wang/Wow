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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.exception.ErrorCodes.SOURCING_VERSION_CONFLICT
import me.ahoo.wow.exception.WowException

class SourcingVersionConflictException(
    val eventStream: DomainEventStream,
    val expectVersion: Int,
    cause: Throwable? = null,
) :
    WowException(
        errorCode = SOURCING_VERSION_CONFLICT,
        errorMsg = "Expected EventStream version[$expectVersion] does not match the actual version:[${eventStream.version}].",
        cause = cause
    ),
    NamedAggregate by eventStream
