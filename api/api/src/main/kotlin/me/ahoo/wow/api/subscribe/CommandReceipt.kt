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
import me.ahoo.wow.api.result.ErrorInfo

/**
 * The receipt of a command of type [C] a saga derived: [Rejected] (an `@OnRejected` handler takes it) or [Committed]
 * (an `@OnCommitted` handler takes it). The command [commandId] of the full type name [commandType] was sent to [ref].
 * Its [causation] links it to the commit the saga handled and to the root of the chain, and [executionId] is the
 * saga's execution that derived it, so what a handler derives in turn is deterministic too. [operator] is the
 * command's, the root command's, which the handler's commands keep; so is [scope], its owner and space, and so are
 * [headers], the request facts the command inherited from the commit the saga handled
 * ([me.ahoo.wow.api.decide.RequestFacts.PROPAGATED]), such as the calling application. [version] is the version of
 * [ref] the receipt answers; a rejection may have none.
 *
 * A receipt carries the outcome only: neither the command's body nor the target's state. [C] says which commands'
 * receipts a handler takes; `*` takes every command the saga derives. The runtime hands each receipt over once per
 * [commandId].
 *
 * Fields may be added: the runtime builds a receipt, and a test hands one to a saga with the test DSL's
 * `whenRejected` or `whenCommitted`; the constructors are internal to Wow.
 */
sealed interface CommandReceipt<C : Any> {
    val commandId: String
    val commandType: String
    val ref: AggregateRef
    val version: Int?
    val causation: Causation
    val executionId: ExecutionId
    val operator: Operator?
    val scope: Scope
    val headers: Map<String, String>

    /**
     * The aggregate whose commit derived the command ([ExecutionId.ref] of the execution that derived it): the order a
     * saga compensates or follows up, say; [ref] is the aggregate the command was sent to.
     */
    val source: AggregateRef get() = executionId.ref

    /**
     * The command was refused with [error]. [version] is the version of [ref] the rejection answered: the version
     * its events reached when it was committed with events, else the one the decision saw; null when the command was
     * refused before any decision, a creation's. A rejection is a business outcome: the runtime hands it to the saga
     * instead of parking the aggregate, whether the command was rejected in the saga's own context or in another,
     * whose receipt comes back.
     */
    @Suppress("unused") // C selects the handler by the command's type.
    class Rejected<C : Any> @InternalWowApi constructor(
        override val commandId: String,
        override val commandType: String,
        override val ref: AggregateRef,
        val error: ErrorInfo,
        override val causation: Causation,
        override val executionId: ExecutionId,
        override val operator: Operator? = null,
        override val scope: Scope = Scope.NONE,
        override val headers: Map<String, String> = emptyMap(),
        override val version: Int? = null,
    ) : CommandReceipt<C> {
        init {
            require(commandId.isNotBlank()) { "A receipt names its command." }
            require(commandType.isNotBlank()) { "A receipt names the type of its command." }
        }

        /** This Rejected with the given fields replaced: Wow's own copy, left out of the ABI. */
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
            version: Int? = this.version,
        ): Rejected<C> = Rejected(
            commandId,
            commandType,
            ref,
            error,
            causation,
            executionId,
            operator,
            scope,
            headers,
            version,
        )

        override fun equals(other: Any?): Boolean =
            this === other || other is Rejected<*> && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "CommandReceipt.Rejected(commandId=$commandId, commandType=$commandType, ref=$ref, error=$error, " +
                "causation=$causation, executionId=$executionId, operator=$operator, scope=$scope, " +
                "headers=$headers, version=$version)"

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
            version,
        )
    }

    /**
     * The command was committed on [ref] (its id the one the target gave it when the command named none) at
     * [version], with [result], the command's outcome at its `committed` hop. A request the target had already
     * committed is answered with its original aggregate and version, [CommandResult.duplicate] set.
     *
     * Only the commands an `@OnCommitted` handler takes ask for this receipt: a command the saga sends to another
     * context carries the request in its `replyTo`, and the target sends the receipt once the command is committed. It
     * carries no state: the contract across contexts is the command's result; a saga that follows the target's later
     * state reads its published stream.
     */
    @Suppress("unused") // C selects the handler by the command's type.
    class Committed<C : Any> @InternalWowApi constructor(
        override val commandId: String,
        override val commandType: String,
        override val ref: AggregateRef,
        override val version: Int,
        val result: CommandResult,
        override val causation: Causation,
        override val executionId: ExecutionId,
        override val operator: Operator? = null,
        override val scope: Scope = Scope.NONE,
        override val headers: Map<String, String> = emptyMap(),
    ) : CommandReceipt<C> {
        init {
            require(commandId.isNotBlank()) { "A receipt names its command." }
            require(commandType.isNotBlank()) { "A receipt names the type of its command." }
            require(version >= 0) { "A version is never negative." }
            require(result.status == CommandResult.Status.SUCCEEDED) {
                "A success receipt holds a result that succeeded."
            }
        }

        /** This Committed with the given fields replaced: Wow's own copy, left out of the ABI. */
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
            headers: Map<String, String> = this.headers,
        ): Committed<C> = Committed(
            commandId,
            commandType,
            ref,
            version,
            result,
            causation,
            executionId,
            operator,
            scope,
            headers,
        )

        override fun equals(other: Any?): Boolean =
            this === other || other is Committed<*> && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "CommandReceipt.Committed(commandId=$commandId, commandType=$commandType, ref=$ref, version=$version, " +
                "result=$result, causation=$causation, executionId=$executionId, operator=$operator, scope=$scope, " +
                "headers=$headers)"

        private fun valueFields(): List<Any?> = listOf(
            commandId,
            commandType,
            ref,
            version,
            result,
            causation,
            executionId,
            operator,
            scope,
            headers,
        )
    }
}
