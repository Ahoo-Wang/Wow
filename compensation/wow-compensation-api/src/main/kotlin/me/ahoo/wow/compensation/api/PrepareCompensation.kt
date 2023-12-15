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
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CommandRoute.AppendPath
import me.ahoo.wow.api.annotation.CommandRoute.PathVariable
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData

@CommandRoute(appendIdPath = AppendPath.ALWAYS)
data class PrepareCompensation(@PathVariable override val id: String) : Identifier

data class CompensationPrepared(
    val eventId: EventId,
    val processor: ProcessorInfoData,
    val functionKind: FunctionKind
)
