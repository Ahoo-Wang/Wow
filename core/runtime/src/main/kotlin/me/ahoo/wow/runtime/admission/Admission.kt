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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.CommandType

/**
 * What a caller asks to do, as action-level authorization sees it before the body is decoded: a
 * command of [type] on [ref], in [scope], and whether the command may create the aggregate ([creation]).
 *
 * A request a transformer handles is authorized as it arrives, and again as the command it becomes
 * when the transformer changed its type or its aggregate. As it arrives, its ref names the aggregate id
 * [CommandGateway.UNADDRESSED] when the request names none, and a mounted command, which no aggregate decides, has
 * [Creation.NONE].
 */
class CommandAction @InternalWowApi constructor(
    val type: CommandType,
    val ref: AggregateRef,
    val scope: Scope,
    val creation: Creation
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandAction && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CommandAction(type=$type, ref=$ref, scope=$scope, creation=$creation)"

    private fun valueFields(): List<Any?> = listOf(type, ref, scope, creation)

    companion object {
        /** A CommandAction of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(type: CommandType, ref: AggregateRef, scope: Scope, creation: Creation): CommandAction = CommandAction(
            type,
            ref,
            scope,
            creation
        )
    }
}

/** An authorizer's answer: [Allowed], or [Denied] with a [Denied.code] and a [Denied.message]. */
sealed interface Authorization {
    data object Allowed : Authorization

    class Denied @InternalWowApi constructor(val message: String, val code: String = FrameworkCodes.AUTH_FORBIDDEN) : Authorization {
        init {
            require(code.isNotBlank()) { "A denial has a code." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Denied && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Denied(message=$message, code=$code)"

        private fun valueFields(): List<Any?> = listOf(message, code)

        companion object {
            /** A Denied of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            @JvmOverloads
            fun of(message: String, code: String = FrameworkCodes.AUTH_FORBIDDEN): Denied = Denied(message, code)
        }
    }
}

/**
 * Decides whether a caller may send a command, before its body is decoded (step 3). The instance-level
 * check, whether the aggregate belongs to the caller's scope, is the kernel's, not an authorizer's.
 *
 * A node's authorizers are all asked ([allOf]): [Authorization.Allowed] means this authorizer has no objection, not
 * that the command is allowed whatever the others say, and there is no abstaining. An authorizer of one concern (the
 * service identities' calls, the commands no caller may forge) allows the actions outside it.
 */
fun interface CommandAuthorizer {
    suspend fun authorize(caller: Caller, action: CommandAction): Authorization

    companion object {
        /** Allows every action. */
        @JvmField
        val ALLOW_ALL: CommandAuthorizer = CommandAuthorizer { _, _ -> Authorization.Allowed }

        /**
         * [authorizers] as one: asked in their order, the first [Authorization.Denied] is the answer and the rest are
         * not asked; a command every one allows is allowed. [ALLOW_ALL] for none, the authorizer itself for one.
         */
        @JvmStatic
        fun allOf(authorizers: List<CommandAuthorizer>): CommandAuthorizer = when (authorizers.size) {
            0 -> ALLOW_ALL
            1 -> authorizers.single()
            else -> AllOf(authorizers)
        }
    }

    private class AllOf(private val authorizers: List<CommandAuthorizer>) : CommandAuthorizer {
        override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
            authorizers.forEach { authorizer ->
                val decision = authorizer.authorize(caller, action)
                if (decision is Authorization.Denied) return decision
            }
            return Authorization.Allowed
        }

        override fun toString(): String = "CommandAuthorizer.allOf($authorizers)"
    }
}

/** The authorizer's denial of [action], or null when it allows it. */
internal suspend fun CommandAuthorizer.authorize(
    request: ServerCommandRequest,
    action: CommandAction
): Authorization.Denied? =
    authorize(request.caller, action) as? Authorization.Denied

/**
 * Validates a command that is decoded and transformed and not yet decided ([CommandCandidate]): the violations it
 * finds, none when it is valid. Any violation refuses the command with [FrameworkCodes.COMMAND_INVALID], listing them
 * all. The gateway asks it of an admitted command, and so of a command from a peer, a saga's derived command, a
 * command taken from a topic and a timer's.
 */
fun interface CommandValidator {
    fun validate(command: CommandCandidate): List<Violation>
}

/**
 * A command as a [CommandValidator] sees it: decoded and transformed, not yet decided. Fields may be added: construct
 * it with [of]; the constructor is internal to Wow.
 *
 * - [body], of [type], sent to [ref], the target aggregate (its tenant and its id with it), in [scope].
 * - [operator] is who the command records as acting, whatever it came through, and what its event stream records;
 *   null for an anonymous caller's command.
 * - [caller] is the request's caller, with its claims, when the command carries one: a command a request admitted,
 *   here, on a peer or through a command topic; null for a saga's derived command, a timer's and an older writer's.
 */
class CommandCandidate @InternalWowApi constructor(
    val type: CommandType,
    val body: Any,
    val ref: AggregateRef,
    val scope: Scope,
    val operator: Operator?,
    val caller: Caller?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandCandidate && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandCandidate(type=$type, body=$body, ref=$ref, scope=$scope, operator=$operator, caller=$caller)"

    private fun valueFields(): List<Any?> = listOf(type, body, ref, scope, operator, caller)

    /** This CommandCandidate with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        type: CommandType = this.type,
        body: Any = this.body,
        ref: AggregateRef = this.ref,
        scope: Scope = this.scope,
        operator: Operator? = this.operator,
        caller: Caller? = this.caller,
    ): CommandCandidate = CommandCandidate(type, body, ref, scope, operator, caller)

    companion object {
        /** A CommandCandidate of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(
            type: CommandType,
            body: Any,
            ref: AggregateRef,
            scope: Scope,
            operator: Operator?,
            caller: Caller?,
        ): CommandCandidate = CommandCandidate(type, body, ref, scope, operator, caller)
    }
}

/**
 * The violations every one of these validators finds in the command [candidate] builds, together, the command built
 * once and only when there is a validator: the gateway's check of an admitted command, which a command from a peer, a
 * saga's derived command, a command taken from a topic and a timer's go through too.
 */
internal inline fun List<CommandValidator>.violationsOf(candidate: () -> CommandCandidate): List<Violation> {
    if (isEmpty()) {
        return emptyList()
    }
    val command = candidate()
    return flatMap { it.validate(command) }
}

/** [command], of [type], as a validator sees it: its target, scope, operator and the caller it carries, if any. */
internal fun candidateOf(type: CommandType, command: Command<*>): CommandCandidate =
    CommandCandidate(type, command.body, command.ref, command.scope, command.operator, command.caller)

/** The command an admitted request becomes, [command], as a validator sees it: sent to [ref] in [scope]. */
internal fun candidateOf(command: Resolved, ref: AggregateRef, scope: Scope, operator: Operator?, caller: Caller?) =
    CommandCandidate(command.type, command.body, ref, scope, operator, caller)
