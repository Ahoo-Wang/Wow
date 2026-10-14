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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule

/**
 * One execution of a subscription's handler for one commit (design 01 §1): the [subscription] (`processor.function`,
 * design 04 §1), the aggregate [ref] and the committed [version]. It is deterministic: handling the same commit again,
 * after a retry, a replay or a lease handover, gives the same id, so its text is the idempotency key a handler gives
 * an outside call.
 */
data class ExecutionId(val subscription: String, val ref: AggregateRef, val version: Int) {
    init {
        require(subscription.isNotBlank()) { "An execution names its subscription." }
        require(version >= 1) { "A committed version starts at 1." }
    }

    /** `subscription:ref@version`, such as `example.cart-projection.onEvent:example.cart/tenant/c-1@3`. */
    override fun toString(): String = "$subscription:$ref@$version"
}

/** What every subscription handler can ask of the runtime besides the [StateEvent] it handles. */
interface SubscriptionContext {
    /** This execution: stable across every time the same commit is handled by the same subscription. */
    val executionId: ExecutionId
}

/** The context of a [me.ahoo.wow.api.annotation.Projection]'s handler. */
interface ProjectContext : SubscriptionContext

/** The context of a [me.ahoo.wow.api.annotation.Reactor]'s handler. */
interface ReactContext : SubscriptionContext

/** The context of a [me.ahoo.wow.api.annotation.Saga]'s handler: it derives commands from the commit. */
interface SagaContext : SubscriptionContext {
    /**
     * The ids this execution takes (decision D171): the node's generators, each aggregate type's the one the host
     * configures for it (`wow.aggregates.<ctx.agg>.id-generator`), fixed ids in the test DSL.
     *
     * Handling the commit again (a retry, a replay) takes new ones: an id drawn here for an aggregate a derived
     * command creates may be used only for that same creation command, whose request id is the same every time, so
     * the target creates it once (decision D162) and keeps the first id; any other use would see the id change. To
     * leave the id to the target, derive the command with [command] of an [AggregateType] instead (decision D191).
     */
    val ids: Ids

    /**
     * A command to the aggregate [ref] asking [body], derived from this execution. Its id and request id are
     * deterministic (from [executionId] and the order of the calls), so handling the commit again derives the same
     * requests and they are not decided twice; its causation links it to the commit and to the root of the chain.
     * Its operator and its scope (owner and space) are the commit's (decisions D163, D189), so an aggregate it
     * creates belongs to the owner the chain acts for, and one it changes is guarded as for that owner.
     */
    fun <C : Any> command(ref: AggregateRef, body: C): Command<C>

    /**
     * A creation command asking [body] of a new aggregate of [type], in the tenant of the commit this execution
     * handles, that leaves its id to the target's admission (decision D191): the target gives it the id of the
     * aggregate its request already created, or a new one from [type]'s configured generator, so handling the commit
     * again keeps the first id. The command must create its aggregate; otherwise the target refuses it.
     */
    fun <C : Any> command(type: AggregateType, body: C): Command<C> =
        command(type.unassigned(executionId.ref.tenantId), body)

    /**
     * The system command `wow.schedule.Set` to [target], asking it to schedule [command] for itself under [key] as
     * [rule] says (decision D198), derived from this execution as [command] derives one: its id and request id come
     * from [executionId] and the order of the calls, so handling the commit again derives the same request. Return
     * it with the commands the handler derives. [target] must exist: otherwise it rejects the command, which the
     * saga's `@OnRejected` handler for [SetSchedule] gets. [key] names the schedule among [target]'s: setting it
     * again replaces it, and [cancel] cancels it. An `after` rule counts from the commit that sets it on [target].
     */
    fun schedule(key: String, rule: ScheduleRule, target: AggregateRef, command: Any): Command<SetSchedule>

    /** The system command `wow.schedule.Cancel` to [target]: cancels its schedule [key]; see [schedule]. */
    fun cancel(key: String, target: AggregateRef): Command<CancelSchedule>
}
