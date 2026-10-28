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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo

/**
 * The rejection of a command of type [C] a saga derived, as an `@OnRejected` handler receives
 * it: the command [commandId] of the full type name [commandType], sent to [ref], was refused with [error]. Its
 * [causation] links it to the commit the saga handled and to the root of the chain, and [executionId] is the saga's
 * execution that derived it, so the compensation it derives in turn is deterministic too. [operator] is the rejected
 * command's, the root command's, which the compensation keeps; so is [scope], its owner and space,
 * and so are [headers], the request facts the command inherited from the commit the saga handled
 * ([me.ahoo.wow.api.decide.RequestFacts.PROPAGATED]), such as the calling application.
 *
 * The command's body is not here: a rejection from another context comes back as a receipt, which
 * carries the outcome only. [C] says which commands' rejections a handler takes; `CommandRejected<*>` takes them all.
 * The runtime hands each rejection over once per [commandId].
 *
 * Fields may be added: the runtime builds it, and a test hands one to a saga with the test DSL's
 * `whenRejected`; the constructor is internal to Wow.
 */
@Suppress("unused") // C selects the handler by the command's type.
class CommandRejected<C : Any> @InternalWowApi constructor(
    val commandId: String,
    val commandType: String,
    val ref: AggregateRef,
    val error: ErrorInfo,
    val causation: Causation,
    val executionId: ExecutionId,
    val operator: Operator? = null,
    val scope: Scope = Scope.NONE,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(commandId.isNotBlank()) { "A rejection names its command." }
        require(commandType.isNotBlank()) { "A rejection names the type of its command." }
    }

    /**
     * The aggregate whose commit derived the command ([ExecutionId.ref] of the execution that derived it): the order a
     * saga compensates, say; [ref] is the aggregate that refused the command.
     */
    val source: AggregateRef get() = executionId.ref

    /** This CommandRejected with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        commandId: String = this.commandId,
        commandType: String = this.commandType,
        ref: AggregateRef = this.ref,
        error: ErrorInfo = this.error,
        causation: Causation = this.causation,
        executionId: ExecutionId = this.executionId,
        operator: Operator? = this.operator,
        scope: Scope = this.scope,
        headers: Map<String, String> = this.headers,
    ): CommandRejected<C> = CommandRejected(
        commandId,
        commandType,
        ref,
        error,
        causation,
        executionId,
        operator,
        scope,
        headers,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandRejected<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandRejected(commandId=$commandId, commandType=$commandType, ref=$ref, error=$error, " +
            "causation=$causation, executionId=$executionId, operator=$operator, scope=$scope, " +
            "headers=$headers)"

    private fun valueFields(): List<Any?> = listOf(
        commandId,
        commandType,
        ref,
        error,
        causation,
        executionId,
        operator,
        scope,
        headers,
    )
}
