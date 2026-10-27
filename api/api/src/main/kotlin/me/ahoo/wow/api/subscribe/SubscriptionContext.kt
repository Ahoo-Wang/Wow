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
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule

/**
 * One execution of a subscription's handler for one commit: the [subscription] (`processor.function`),
 * the aggregate [ref] and the committed [version]. It is deterministic: handling the same commit again,
 * after a retry, a replay or a lease handover, gives the same id. It is per stream: the idempotency key of an outside
 * call is [SubscriptionContext.invocationKey], which tells apart the calls of a handler for each event.
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
    /**
     * This execution: stable across every time the same commit is handled by the same subscription. It is per stream:
     * a handler called once per event of a type gets the same one for each of the stream's events, so
     * an outside call takes [invocationKey] as its idempotency key instead.
     */
    val executionId: ExecutionId

    /**
     * The idempotency key of this call of the handler: [executionId] for a handler of the whole stream,
     * `executionId:eventIndex` for a handler of one event of the stream ([StateTypedEvent.eventIndex]). Stable across
     * every time the same commit is handled again, so a payment gateway, say, takes the same call once.
     */
    val invocationKey: String
        get() = executionId.toString()

    /**
     * The read side of the node: the snapshots, events and datasets of the tenant of the commit this
     * execution handles, the whole tenant; another tenant's only through [SubscriptionQueries.trusted]. Reads are not
     * part of the execution's determinism: handling the commit again reads the read side as it is then.
     *
     * @throws UnsupportedOperationException when the node gives its handlers no queries.
     */
    val queries: SubscriptionQueries
}

/** The context of a [me.ahoo.wow.api.annotation.Projection]'s handler. */
interface ProjectContext : SubscriptionContext

/** The context of a [me.ahoo.wow.api.annotation.Reactor]'s handler. */
interface ReactContext : SubscriptionContext

/** The context of a [me.ahoo.wow.api.annotation.Saga]'s handler: it derives commands from the commit. */
interface SagaContext : SubscriptionContext {
    /**
     * The ids this execution takes: the node's generators, each aggregate type's the one the host
     * configures for it (`wow.aggregates.<ctx.agg>.id-generator`), fixed ids in the test DSL.
     *
     * Handling the commit again (a retry, a replay) takes new ones: an id drawn here for an aggregate a derived
     * command creates may be used only for that same creation command, whose request id is the same every time, so
     * the target creates it once and keeps the first id; any other use would see the id change. To
     * leave the id to the target, derive the command with [command] of an [AggregateType] instead.
     */
    val ids: Ids

    /**
     * A command to the aggregate [ref] asking [body], derived from this execution. Its id and request id are
     * deterministic (from [executionId] and the order of the calls), so handling the commit again derives the same
     * requests and they are not decided twice; its causation links it to the commit and to the root of the chain.
     * Its operator and its scope (owner and space) are the commit's, so an aggregate it
     * creates belongs to the owner the chain acts for, and one it changes is guarded as for that owner.
     */
    fun <C : Any> command(ref: AggregateRef, body: C): Command<C>

    /**
     * A creation command asking [body] of a new aggregate of [type], in the tenant of the commit this execution
     * handles, that leaves its id to the target's admission: the target gives it the id of the
     * aggregate its request already created, or a new one from [type]'s configured generator, so handling the commit
     * again keeps the first id. The command must create its aggregate; otherwise the target refuses it.
     */
    fun <C : Any> command(type: AggregateType, body: C): Command<C> =
        command(type.unassigned(executionId.ref.tenantId), body)

    /**
     * A command asking [body] of the aggregate [id] of the type that decides [body]'s class, in the tenant of the
     * upstream commit: the local Manifest and the peer contracts on the class path (each api jar's
     * `META-INF/wow/contract.json`) assign each command class its aggregate, so the target's type can be left out.
     * Otherwise it is [command] of the full ref, `command(type.ref(executionId.ref.tenantId, id), body)`.
     *
     * Only an `@OnEvent`, `@OnRejected` or `@OnCommitted` handler leaves the ref out: it inherits the tenant of the
     * commit or receipt it handles. An `@OnSchedule` job writes the full ref (its tick belongs to the job, not to a
     * tenant's commit), and so does a command to another tenant.
     *
     * @throws IllegalArgumentException when no contract on the class path assigns [body]'s class (its context's api
     * module is not a dependency), when several aggregates decide it (the message names them: write the full ref),
     * or when the handler is an `@OnSchedule` job's.
     */
    fun <C : Any> command(id: String, body: C): Command<C>

    /**
     * A creation command asking [body] of a new aggregate of the type that decides [body]'s class, in the tenant of
     * the upstream commit, that leaves its id to the target's admission as [command] of an [AggregateType] does:
     * `command(type, body)` with the type the contracts assign to [body]'s class. The same
     * rules as [command] of an id apply: an `@OnEvent`, `@OnRejected` or `@OnCommitted` handler only, one aggregate
     * type deciding the class, and its contract on the class path.
     */
    fun <C : Any> command(body: C): Command<C>

    /**
     * The system command `wow.schedule.Set` to [target], asking it to schedule [command] for itself under [key] as
     * [rule] says, derived from this execution as [command] derives one: its id and request id come
     * from [executionId] and the order of the calls, so handling the commit again derives the same request. Return
     * it with the commands the handler derives. [target] must exist: otherwise it rejects the command, which the
     * saga's `@OnRejected` handler for [SetSchedule] gets. [key] names the schedule among [target]'s: setting it
     * again replaces it, and [cancel] cancels it. An `after` rule counts from the commit that sets it on [target].
     */
    fun schedule(key: String, rule: ScheduleRule, target: AggregateRef, command: Any): Command<SetSchedule>

    /** The system command `wow.schedule.Cancel` to [target]: cancels its schedule [key]; see [schedule]. */
    fun cancel(key: String, target: AggregateRef): Command<CancelSchedule>

    /**
     * Sends [command], to an aggregate of another bounded context, through that context's HTTP command gateway and
     * returns its result once it is committed there: the aggregate it acted on (the id the target
     * gave a creation without one), its version, `duplicate` when the target had already committed the
     * request. Build [command] with [command] first: its deterministic request id makes every retry and replay of
     * this execution the same request, which the target decides once and answers with the same aggregate; its
     * operator, scope and causation are the root command's, which the client asserts with the service's credential.
     * A wait that follows the saga goes on with it: the target signals the next hops as for a derived command.
     *
     * The default is to derive the command (return it), which its context's command topic delivers. Call only
     * where the target being online is a precondition and the saga needs the result now; the target's address is the
     * host's configuration (`wow.peers.<context>.url`). The handler declares the class of [command] in its
     * `@OnEvent(calls)`, which puts it in the Manifest; a command of a class it does not declare is
     * refused with an [IllegalArgumentException].
     *
     * It is a `suspend` function, so a Java saga cannot call it (a blocking bridge is planned for
     * 10.x): a Java saga derives its commands instead.
     *
     * @throws CallRejectedException when the target refuses it, a business outcome that is not retried.
     * @throws CallFailedException when it reaches no result: the handler fails, is retried within its subscription's
     * budget, then parks its aggregate.
     */
    suspend fun call(command: Command<*>): CommandResult
}
