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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateIds
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CallerAdmission

/**
 * The rules that decide a request's identity facts, step 2 of admission ([CommandGateway]), which a command of this
 * node's aggregates and one of a peer context's share, beside its tenant and scope ([CallerAdmission.admit]): the
 * call, the aggregate id an owner's aggregate or a body names, the caller the command acts as, the request facts it
 * records and the facts no request may give.
 */
internal object AdmissionIdentity {
    /**
     * The request facts of [request]'s command, sent by the caller [origin] admits: the ones it carries, as the caller
     * records them ([CallerAdmission.headersOf]). A service's synchronous call carries those of its root request that
     * propagate ([CallContext.facts]) instead: its caller is the calling service, whose application is no fact of the
     * request.
     */
    fun headersOf(request: ServerCommandRequest, origin: CallerAdmission): Map<String, String> {
        val call = request.call ?: return origin.headersOf(request.headers)
        return request.headers.filterKeys { it !in RequestFacts.PROPAGATED } + call.facts
    }

    /**
     * Why [request]'s synchronous call is refused, with its code, or null when it has none or may
     * make it: only a service identity asserts the root command a call acts for, and it names the call's scope in
     * the call alone.
     */
    fun callProblem(request: ServerCommandRequest): Pair<String, String>? = when {
        request.call == null -> null
        !request.caller.service ->
            FrameworkCodes.AUTH_FORBIDDEN to
                "Only a service identity calls on behalf of a root command."

        request.target != null -> FrameworkCodes.COMMAND_MALFORMED to "A call names its scope in the call, not in Wow-Scope."
        else -> null
    }

    /**
     * The id of an aggregate whose id is its owner's, for a request of [caller] naming [requested] (null for none) in
     * [scope]: the one it names, else an owner-level caller's own; a caller with no owner names it, having no aggregate
     * of its own: unauthenticated, it is answered as such. An owner taken as the id is an aggregate id like any other:
     * one admission refuses as an id ([AggregateIds.refused]) is refused as [FrameworkCodes.COMMAND_MALFORMED], as the
     * same value named as the id would be. Whether the caller may act on the aggregate it names is the
     * kernel's to answer, as for any aggregate: one of another owner is out of its reach.
     *
     * The one rule of every path a command takes to such an aggregate: admission of this node's aggregates and of a
     * peer's, and, for a command no caller sent ([caller] null: a derived or scheduled one, or one a producer wrote on
     * a command topic), the intake and a saga's derived commands ([unaddressed]).
     */
    fun ownId(requested: String?, scope: Scope, caller: Caller?): OwnId {
        val owner = scope.ownerId
        return when {
            requested != null -> OwnId.Of(requested)
            owner != null -> AggregateIds.refused(owner)
                ?.let { OwnId.Refused(FrameworkCodes.COMMAND_MALFORMED, "The caller's owner is its aggregate's id: $it") }
                ?: OwnId.Of(owner)
            caller != null && caller.subject == null -> OwnId.Refused(
                FrameworkCodes.AUTH_UNAUTHENTICATED,
                "An unauthenticated caller has no aggregate of its own.",
            )

            else -> OwnId.Refused(
                FrameworkCodes.COMMAND_MALFORMED,
                "This caller has no owner; address the aggregate by its id.",
            )
        }
    }

    /**
     * [caller] as its command's caller ([Command.caller]): acting in the tenant of [ref] and in [scope], the target's
     * when `Wow-Scope` names one, as a read of it acts. The access policy of the aggregate compares its claims.
     */
    fun actingCaller(caller: Caller, ref: AggregateRef, scope: Scope): Caller =
        caller.copyWith(tenantId = ref.tenantId, scope = scope)

    /**
     * Why [command], of [model], which names no aggregate, can be given none ([ownId]): its aggregate's id is its
     * owner's and it has no owner; null when it names one, or is given one. A command that reaches the intake or a
     * saga's executor so is rejected with it, a rejection of the sender's, as admission refuses its request.
     */
    fun unaddressed(model: AggregateModel<*>, command: Command<*>): OwnId.Refused? =
        if (command.ref.isAssigned || model.idSource != AggregateIdSource.OWNER) {
            null
        } else {
            ownId(null, command.scope, command.caller) as? OwnId.Refused
        }

    /** The id [ownId] resolves, or why it refuses the request. */
    sealed interface OwnId {
        class Of(val id: String) : OwnId

        class Refused(val code: String, val message: String) : OwnId
    }

    /**
     * The aggregate a request of [command] names in its body, [read] from it (`@AggregateId`), beside the one it
     * names elsewhere, [requested] (a ref beside the body; null for none): the body's, which [requested] may repeat but
     * not contradict, an identity fact given twice; [requested] when the body leaves it out; null when neither names one
     * and the command is a [creation], which is given an id. A blank one, or one that cannot be read, is refused.
     */
    // A computed @AggregateId property may throw whatever its code does on the fields it was given.
    @Suppress("TooGenericExceptionCaught")
    fun bodyId(command: String, requested: String?, creation: Creation, read: () -> String?): BodyId {
        val inBody = try {
            read()
        } catch (failure: RuntimeException) {
            return BodyId.Refused("The aggregate the body of $command names cannot be read: ${failure.message}")
        }
        AggregateIds.refused(inBody)?.let { return BodyId.Refused(it) }
        return when {
            inBody != null && inBody.isBlank() -> BodyId.Refused("The body of $command names a blank aggregate id.")
            inBody != null && requested != null && inBody != requested ->
                BodyId.Refused("The request names the aggregate $requested, but the body of $command names $inBody.")

            inBody == null && requested == null && creation == Creation.NONE ->
                BodyId.Refused("The body of $command names no aggregate.")

            else -> BodyId.Of(inBody ?: requested)
        }
    }

    /** The aggregate [bodyId] finds, or why it refuses the request. */
    sealed interface BodyId {
        class Of(val id: String?) : BodyId

        class Refused(val message: String) : BodyId
    }

    /** Why [request]'s identity facts cannot be decided, or null when they can. */
    fun identityConflict(request: ServerCommandRequest, creation: Creation, transformed: Boolean): String? {
        val reserved = request.headers.keys.firstOrNull(::isReservedHeader)
        return when {
            reserved != null -> "Header $reserved is reserved: identity, ids and the wait have fields of their own."
            request.requestId?.isBlank() == true -> "A request id, when given, is not blank."
            request.aggregateId?.isBlank() == true -> "An aggregate id, when given, is not blank."
            request.aggregateId == AggregateRef.UNASSIGNED ->
                "An aggregate id is never ${AggregateRef.UNASSIGNED}: leave it out for admission to choose one."
            request.aggregateId == null && creation == Creation.NONE && !transformed ->
                "${request.command} needs an aggregate id."

            (request.expectedVersion ?: 0) < 0 -> "An expected version is never negative."
            else -> null
        }
    }
}
