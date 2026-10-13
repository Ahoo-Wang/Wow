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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo

/**
 * The rejection of a command of type [C] a saga derived (decisions D136, D153), as an `@OnRejected` handler receives
 * it: the command [commandId] of the full type name [commandType], sent to [ref], was refused with [error]. Its
 * [causation] links it to the commit the saga handled and to the root of the chain, and [executionId] is the saga's
 * execution that derived it, so the compensation it derives in turn is deterministic too. [operator] is the rejected
 * command's, the root command's (decision D163), which the compensation keeps.
 *
 * The command's body is not here: a rejection from another context comes back as a receipt (design 08 §8), which
 * carries the outcome only. [C] says which commands' rejections a handler takes; `CommandRejected<*>` takes them all.
 * The runtime hands each rejection over once per [commandId].
 */
@Suppress("unused") // C selects the handler by the command's type.
data class CommandRejected<C : Any>(
    val commandId: String,
    val commandType: String,
    val ref: AggregateRef,
    val error: ErrorInfo,
    val causation: Causation,
    val executionId: ExecutionId,
    val operator: Operator? = null,
) {
    init {
        require(commandId.isNotBlank()) { "A rejection names its command." }
        require(commandType.isNotBlank()) { "A rejection names the type of its command." }
    }
}
