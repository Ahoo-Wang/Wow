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
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleTick
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

/**
 * What every subscription handler can ask of the runtime besides the [StateEvent] it handles: the context of a
 * [me.ahoo.wow.api.annotation.Projection]'s or a [me.ahoo.wow.api.annotation.Reactor]'s handler, and what every other
 * context adds to.
 */
interface SubscriptionContext {
    /**
     * This execution: stable across every time the same commit is handled by the same subscription. It is per stream:
     * a handler called once per event of a type gets the same one for each of the stream's events, so
     * an outside call takes [invocationKey] as its idempotency key instead.
     */
    val executionId: ExecutionId

    /**
     * The idempotency key of this call of the handler, the one key an outside call takes: [executionId] for a handler
     * of the whole stream, `executionId:eventIndex` for a handler of one event of the stream
     * ([StateTypedEvent.eventIndex]). Stable across every time the same commit is handled again, so a payment gateway,
     * say, takes the same call once.
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

/**
 * The context of a handler that derives commands: a [SagaContext] (`@OnEvent`), a [ReceiptContext] (`@OnRejected`,
 * `@OnCommitted`) and a [JobContext] (`@OnSchedule`). The handler returns the commands it derives as
 * [CommandRequest]s, one, several (an `Iterable`, a `Sequence`, a `Flow`) or none, and the subscription sends each,
 * deterministic, so handling the same commit, receipt or tick again derives the same requests, which their targets
 * decide once:
 *
 * - its target is the request's: [CommandRequest.type], else the aggregate type the local Manifest and the peer
 *   contracts on the class path (each api jar's `META-INF/wow/contract.json`) assign the command's class; its tenant
 *   [CommandRequest.targetTenant], else this execution's (the commit's, the receipt's or the job instance's); its id
 *   [CommandRequest.id], else none: a creation whose id the target's admission gives, the id of the aggregate the
 *   request already created when it is handled again, so the target keeps the first one;
 * - its id and request id are name-based on [executionId], the target, the command's class and, when the request
 *   gives a [CommandRequest.requestId], that business key, else how many such requests to the same target the
 *   execution derived before it; the order the handler builds them in does not matter;
 * - its scope is [CommandRequest.targetScope], else the commit's, so an aggregate it creates belongs to the owner the
 *   chain acts for; its operator is the commit's, and its causation links it to the commit and to the root of the
 *   chain; its [CommandRequest.expectedVersion] is kept;
 * - a request with a [CommandRequest.wait] or a [CommandRequest.timeout] fails the handler with an
 *   [IllegalArgumentException]: the subscription sends derived commands and waits for none.
 *
 * Derived commands are trusted: the target's scope guard does not check one it changes, so a wrong target is not
 * refused; test the targets in a saga spec. A command class no contract assigns, or several aggregates decide, fails
 * the handler with an [IllegalArgumentException] naming them: give the request its [CommandRequest.type].
 */
interface DerivingContext : SubscriptionContext {
    /**
     * The ids this execution takes: the node's generators, each aggregate type's the one the host
     * configures for it (`wow.aggregates.<ctx.agg>.id-generator`), fixed ids in the test DSL.
     *
     * Handling the commit again (a retry, a replay) takes new ones: an id drawn here for an aggregate a derived
     * command creates may be used only for that same creation command, whose request id is the same every time, so
     * the target creates it once and keeps the first id; any other use would see the id change. To
     * leave the id to the target, derive the creation without an id instead.
     */
    val ids: Ids
}

/** The context of a [me.ahoo.wow.api.annotation.Saga]'s `@OnEvent` handler: it derives commands from the commit. */
interface SagaContext : DerivingContext {
    /**
     * Schedules [request]'s command on its target under [key] as [rule] says: the execution derives the system
     * command `wow.schedule.Set` to that target with the commands the handler returns, after them, keyed as a
     * returned request is (see [DerivingContext]), so handling the commit again derives the same request. The target
     * must exist: otherwise it rejects the command, which the saga's `@OnRejected` handler for [SetSchedule] gets.
     * [key] names the schedule among the target's: setting it again replaces it, and [cancel] cancels it. An `after`
     * rule counts from the commit that sets it on the target.
     */
    fun schedule(key: String, rule: ScheduleRule, request: CommandRequest<*>)

    /**
     * Cancels the schedule [key] of [target]: the execution derives the system command `wow.schedule.Cancel` to it,
     * after the commands the handler returns; see [schedule].
     */
    fun cancel(key: String, target: AggregateRef)

    /**
     * Sends [request], to an aggregate of another bounded context, through that context's HTTP command gateway and
     * returns its result once it is committed there: the aggregate it acted on (the id the target
     * gave a creation without one), its version, `duplicate` when the target had already committed the
     * request. It becomes a command as a returned request does (see [DerivingContext]): its deterministic request id
     * makes every retry and replay of this execution the same request, which the target decides once and answers with
     * the same aggregate; its operator, scope and causation are the root command's, which the client asserts on the
     * target's internal port, naming its service, a port only the platform's own services reach.
     * A wait that follows the saga goes on with it: the target signals the next hops as for a derived command.
     *
     * The default is to derive the command (return it), which its context's command topic delivers. Call only
     * where the target being online is a precondition and the saga needs the result now; the target's address is the
     * host's configuration (`wow.peers.<context>.url`). The handler declares the class of the command in its
     * `@OnEvent(calls)`, which puts it in the Manifest; a command of a class it does not declare is
     * refused with an [IllegalArgumentException].
     *
     * It is a `suspend` function, so a Java saga cannot call it (a blocking bridge is planned for
     * 10.x): a Java saga derives its commands instead.
     *
     * @throws CommandRejectedException when the target refuses it, a business outcome that is not retried.
     * @throws WowProblemException when it reaches no result or fails there: the handler fails, is retried within its
     * subscription's budget, then parks its aggregate.
     */
    suspend fun call(request: CommandRequest<*>): CommandResult
}

/**
 * The context of a saga's `@OnRejected` or `@OnCommitted` handler: it derives the commands that compensate or follow
 * a command an `@OnEvent` handler of the saga derived, from the receipt of it ([CommandRejected], [CommandCommitted]).
 */
interface ReceiptContext : DerivingContext {
    /**
     * The aggregate whose commit derived the command the receipt is of ([ExecutionId.ref] of the execution that
     * derived it): the order a saga compensates, say.
     */
    val source: AggregateRef
        get() = executionId.ref
}

/**
 * The context of a saga's `@OnSchedule` handler: it derives the commands a [tick] of its job asks, in the tenant of
 * the job's instance ([ScheduleTick.tenantId]): a command of an id or a body names its target's type by its class
 * alone, as from a commit.
 */
interface JobContext : DerivingContext {
    /** The tick this execution handles: the job, its slot and the instance's tenant. */
    val tick: ScheduleTick
}
