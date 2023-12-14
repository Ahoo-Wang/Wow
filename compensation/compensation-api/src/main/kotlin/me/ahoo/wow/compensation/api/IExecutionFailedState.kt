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

package me.ahoo.wow.compensation.api

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable

data class ErrorDetails(override val errorCode: String, override val errorMsg: String, val stackTrace: String) :
    ErrorInfo

data class EventId(override val id: String, override val aggregateId: AggregateId, override val version: Int) :
    Identifier, Version,
    AggregateIdCapable

interface IExecutionFailedState : Identifier {
    val eventId: EventId
    val functionKind: FunctionKind
    val error: ErrorDetails
    val executionTime: Long
}