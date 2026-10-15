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
import me.ahoo.wow.api.result.CommandResult

/**
 * The success receipt of a command of type [C] a saga derived (decision D192), as an `@OnCommitted` handler receives
 * it: the command [commandId] of the full type name [commandType] was committed on [ref] (its id the one the target
 * gave it when the command named none, decision D191, see [aggregateId]) at [version], with [result], the command's
 * outcome at its `committed` hop. A request the target had already committed is answered with its original aggregate
 * and version, [CommandResult.duplicate] set. [causation], [executionId], [operator] and [scope] are a
 * [CommandRejected]'s: they link the receipt to the saga's execution that derived the command, so what the handler
 * derives in turn is deterministic too.
 *
 * The receipt carries no state: the contract across contexts is the command's result (decisions D33, D192); a saga
 * that follows the target's later state reads its published stream. [C] says which commands' receipts a handler
 * takes; `CommandCommitted<*>` takes them all. The runtime hands each receipt over once per [commandId].
 *
 * Fields may be added (decision D59): the runtime builds it, and a test hands one to a saga with the test DSL's
 * `whenCommitted`; the constructor is internal to Wow.
 */
@Suppress("unused") // C selects the handler by the command's type.
class CommandCommitted<C : Any> @InternalWowApi constructor(
    val commandId: String,
    val commandType: String,
    val ref: AggregateRef,
    val version: Int,
    val result: CommandResult,
    val causation: Causation,
    val executionId: ExecutionId,
    val operator: Operator? = null,
    val scope: Scope = Scope.NONE,
) {
    init {
        require(commandId.isNotBlank()) { "A receipt names its command." }
        require(commandType.isNotBlank()) { "A receipt names the type of its command." }
        require(version >= 0) { "A version is never negative." }
        require(result.status == CommandResult.Status.SUCCEEDED) { "A success receipt holds a result that succeeded." }
    }

    /** The id of the aggregate the command was committed on: the target's, when the command named none (D191). */
    val aggregateId: String get() = ref.id

    /** This CommandCommitted with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        commandId: String = this.commandId,
        commandType: String = this.commandType,
        ref: AggregateRef = this.ref,
        version: Int = this.version,
        result: CommandResult = this.result,
        causation: Causation = this.causation,
        executionId: ExecutionId = this.executionId,
        operator: Operator? = this.operator,
        scope: Scope = this.scope,
    ): CommandCommitted<C> = CommandCommitted(
        commandId,
        commandType,
        ref,
        version,
        result,
        causation,
        executionId,
        operator,
        scope,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandCommitted<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandCommitted(commandId=$commandId, commandType=$commandType, ref=$ref, version=$version, " +
            "result=$result, causation=$causation, executionId=$executionId, operator=$operator, scope=$scope)"

    private fun valueFields(): List<Any?> = listOf(
        commandId, commandType, ref, version, result, causation, executionId, operator, scope
    )
}
