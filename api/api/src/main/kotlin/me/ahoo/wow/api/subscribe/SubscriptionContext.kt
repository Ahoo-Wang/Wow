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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import java.time.Instant
import kotlin.time.Duration

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
     * The ids this execution takes (decision D171), such as the id of an aggregate a derived command creates. They are
     * deterministic, as the derived commands' ids are: name-based UUIDs of the execution (for an `@OnRejected`
     * handler, of the execution and the rejected command) and the order of the calls, not the node's generators. So
     * handling the commit again, after a redelivery, a handover or a resumed parked entry, takes the same ids, and a
     * command creating an aggregate of a new id creates it once.
     */
    val ids: Ids

    /**
     * A command to the aggregate [ref] asking [body], derived from this execution. Its id and request id are
     * deterministic (from [executionId] and the order of the calls), so handling the commit again derives the same
     * requests and they are not decided twice; its causation links it to the commit and to the root of the chain.
     */
    fun <C : Any> command(ref: AggregateRef, body: C): Command<C>

    /**
     * Schedules [command] to be sent to [target] at [at] (decision D166). [key] names the schedule among this saga's
     * schedules of [target]: scheduling it again replaces it, and [cancel] cancels it, from any execution of the saga.
     *
     * The schedule is written before the saga's checkpoint passes this commit, with an id deterministic from
     * [executionId] and the order of the calls, so handling the commit again writes the same schedule. The command is
     * sent at least once, not before [at], and decided once; a rejection when it is due is a normal guard, not handed
     * to `@OnRejected`.
     */
    fun schedule(key: String, at: Instant, target: AggregateRef, command: Any)

    /** Schedules [command] to be sent to [target] [after] the commit this execution handles; see the other [schedule]. */
    fun schedule(key: String, after: Duration, target: AggregateRef, command: Any)

    /** Cancels this saga's schedule of [target] under [key]; nothing when there is none. */
    fun cancel(key: String, target: AggregateRef)
}
