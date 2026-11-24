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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.spi.SystemCommandGroups
import me.ahoo.wow.spi.SystemCommandSet

/**
 * What a caller asks to do, as action-level authorization sees it before the body is decoded: a
 * command of [type] on [ref], in [scope], and whether the command may create the aggregate ([creation]).
 *
 * A creation that names no aggregate is authorized before admission chooses its id: its ref names the aggregate id
 * [CommandGateway.UNADDRESSED], as does a transformer's result that names none.
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
    /**
     * Whether the command is one of Wow's system commands (`wow.*`), of any group: an aggregate's lifecycle commands
     * (`wow.lifecycle.DeleteAggregate`, `RestoreAggregate`, `TransferOwner`, `TransferSpace`) and its schedule's, a
     * context's schedule jobs, an aggregate type's snapshot rebuild, the failure ledger's actions and its read.
     *
     * It includes the lifecycle commands, which an aggregate's owner sends for its own aggregate: a rule such as "only
     * an employee sends it" put on [isSystem] keeps an owner from deleting its own aggregate too. Such a rule most
     * likely wants [isOperational].
     */
    val isSystem: Boolean get() = isIn(SystemCommandGroups.ALL)

    /**
     * Whether the command runs the service rather than its domain: a context's schedule jobs
     * (`wow.schedule.EnableJob`, `RescheduleJob`, `DisableJob`), an aggregate type's snapshot rebuild
     * (`wow.snapshot.RebuildSnapshot`), and an operator's actions on the failure ledger (`wow.ledger.*`, its read
     * `wow.ledger.Read` among them). These are the commands Wow allows no caller by default: a node without an
     * authorizer refuses them to every caller. The commands of an aggregate's own lifecycle are not among them.
     */
    val isOperational: Boolean get() = isIn(SystemCommandGroups.OPERATIONAL)

    /**
     * Whether the command is a [commandClass], or a subtype of it: a command class, or a marker interface or sealed
     * family several commands share. It compares the class, so a command's former names ([CommandType.aliases]) are the
     * same command. A mounted command is authorized as it arrives and, when a transformer changed its type, again as
     * the command it became: it is the one, then the other.
     */
    fun commandIs(commandClass: Class<*>): Boolean = commandClass.isAssignableFrom(type.type)

    private fun isIn(set: SystemCommandSet): Boolean = type.type in set || type.name in set.actions

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

/** Whether [this] action's command is a [C], or a subtype of it ([CommandAction.commandIs]). */
inline fun <reified C : Any> CommandAction.commandIs(): Boolean = commandIs(C::class.java)

/**
 * An authorizer's answer: [Allowed], or [Denied] with a [Denied.code] and a [Denied.message].
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
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
 *
 * Wow's operational commands ([CommandAction.isOperational]: a context's jobs, an aggregate type's snapshot rebuild,
 * the failure ledger's actions and read) are allowed on the public port and the operator's routes only when one of the
 * node's authorizers [decidesOperational]: an authorizer of another concern, which allows what it does not know, never
 * opens them. [operational] makes one.
 */
fun interface CommandAuthorizer {
    suspend fun authorize(caller: Caller, action: CommandAction): Authorization

    /**
     * Whether this authorizer decides who sends Wow's operational commands ([CommandAction.isOperational]), so that
     * its [Authorization.Allowed] of one is a grant rather than no objection. False unless it says so: a node whose
     * authorizers none decide them refuses them on the public port and the operator's routes (403
     * `wow.auth.forbidden`). CoSec's authorizer decides them by its policies; [operational] makes an authorizer that
     * does.
     */
    val decidesOperational: Boolean get() = false

    companion object {
        /** Allows every action; it decides no operational command ([decidesOperational] is false). */
        @JvmField
        val ALLOW_ALL: CommandAuthorizer = CommandAuthorizer { _, _ -> Authorization.Allowed }

        /**
         * An authorizer that decides Wow's operational commands by [decide] and has no objection to any other action:
         * `CommandAuthorizer.operational { caller, action -> if (caller.hasRole("operator")) Allowed else
         * Denied.of("Only an operator sends ${action.type.name}.") }`. A node with one allows an operational command
         * when [decide] does and its other authorizers do not object. A service identity ([Caller.service]) is not
         * asked about: a call between services is trusted, as CoSec's authorizer trusts it, and the internal port
         * serves a system command only to a service whose `wow.callers` entries name it.
         *
         * The exemption holds by construction: a request reaches an authorizer as a service identity only through
         * the internal port, where a service names itself; the public port, the operator's routes and the
         * subscription routes refuse a resolver's service identity (403 `wow.auth.forbidden`), and a service's own
         * code in the process is the service itself.
         */
        @JvmStatic
        fun operational(decide: suspend (Caller, CommandAction) -> Authorization): CommandAuthorizer =
            Operational(decide)

        /**
         * [authorizers] as one: asked in their order, the first [Authorization.Denied] is the answer and the rest are
         * not asked; a command every one allows is allowed. It [decidesOperational] when one of them does. [ALLOW_ALL]
         * for none, the authorizer itself for one.
         */
        @JvmStatic
        fun allOf(authorizers: List<CommandAuthorizer>): CommandAuthorizer = when (authorizers.size) {
            0 -> ALLOW_ALL
            1 -> authorizers.single()
            else -> AllOf(authorizers)
        }
    }

    private class Operational(
        private val decide: suspend (Caller, CommandAction) -> Authorization,
    ) : CommandAuthorizer {
        override val decidesOperational: Boolean get() = true

        override suspend fun authorize(caller: Caller, action: CommandAction): Authorization =
            if (action.isOperational && !caller.service) decide(caller, action) else Authorization.Allowed

        override fun toString(): String = "CommandAuthorizer.operational($decide)"
    }

    private class AllOf(private val authorizers: List<CommandAuthorizer>) : CommandAuthorizer {
        override val decidesOperational: Boolean = authorizers.any { it.decidesOperational }

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
 * - [body], of [type], sent to [ref], the target aggregate (its tenant and its id with it), in [scope]. A creation
 *   that names no aggregate is validated before its id is chosen, on every path: its ref names the id
 *   [AggregateRef.UNASSIGNED].
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

/** The command an admitted request becomes, [command], as a validator sees it: sent to [ref] in [scope]. */
internal fun candidateOf(command: Resolved, ref: AggregateRef, scope: Scope, operator: Operator?, caller: Caller?) =
    CommandCandidate(command.type, command.body, ref, scope, operator, caller)
