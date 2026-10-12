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

package me.ahoo.wow.api.result

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How far a command has come (decision D61). There are three stages; which kind of subscription handled a commit
 * (a projection, a reactor, a saga, the snapshot) is the subscription's own business, not a stage of its own.
 */
enum class Stage {
    /** The command passed admission. Durable only when it was routed through Kafka. */
    ACCEPTED,

    /** The command's events are appended to the log (or the command was settled without appending). */
    COMMITTED,

    /** A named subscription has handled the commit. */
    HANDLED,
}

/**
 * One hop of a wait chain (decision D62): a [stage], in a bounded [context], of a [subscription] when the stage is
 * [Stage.HANDLED].
 *
 * A null [context] means the context of the command the wait started with.
 */
data class Hop(val stage: Stage, val context: String? = null, val subscription: String? = null) {
    init {
        require(context == null || context.isNotBlank()) { "A hop's context, when present, is not blank." }
        if (stage == Stage.HANDLED) {
            require(!subscription.isNullOrBlank()) { "A HANDLED hop names its subscription." }
        } else {
            require(subscription == null) { "Only a HANDLED hop names a subscription." }
        }
    }

    override fun toString(): String {
        val where = listOfNotNull(context, subscription).joinToString(".")
        return if (where.isEmpty()) stage.name.lowercase() else "${stage.name.lowercase()}($where)"
    }

    companion object {
        /** The name of the built-in subscription that writes snapshots. */
        const val SNAPSHOT_SUBSCRIPTION: String = "snapshot"

        /** The name of the built-in subscription that confirms reservations (decision D50). */
        const val RESERVATION_SUBSCRIPTION: String = "reservation"

        /** [Stage.ACCEPTED] in [context]. */
        fun accepted(context: String? = null): Hop = Hop(Stage.ACCEPTED, context)

        /** [Stage.COMMITTED] in [context]. */
        fun committed(context: String? = null): Hop = Hop(Stage.COMMITTED, context)

        /** [Stage.HANDLED] by [subscription] of [context]. */
        fun handled(subscription: String, context: String? = null): Hop = Hop(Stage.HANDLED, context, subscription)

        /** [Stage.HANDLED] by the snapshot subscription: the snapshot holds the commit (decision D69). */
        fun snapshot(context: String? = null): Hop = handled(SNAPSHOT_SUBSCRIPTION, context)
    }
}

/**
 * What the caller of a command waits for (design 04 §6; decisions D61–D64, D69): the [hops] of a chain, reached in
 * order, within [timeout]. It stays on the node that took the command; what travels with the command's messages is a
 * wait context instead.
 *
 * Within one command the stages come in order (accepted, committed, handled); a HANDLED hop of a saga may be followed
 * by more hops, which then apply to the commands that saga derived from the commit. The runtime checks the rest of
 * the chain's shape at admission.
 */
data class WaitPlan(val hops: List<Hop>, val timeout: Duration = DEFAULT_TIMEOUT) {
    init {
        require(hops.isNotEmpty()) { "A wait plan has at least one hop." }
        require(timeout.isPositive()) { "A wait timeout is positive." }
    }

    /** This plan waiting at most [timeout]. */
    fun within(timeout: Duration): WaitPlan = copy(timeout = timeout)

    companion object {
        /** The timeout of a wait whose caller gives none (decision D64). */
        val DEFAULT_TIMEOUT: Duration = 10.seconds

        /** The default: until the snapshot holds the commit, so the caller reads its own write (decision D69). */
        @JvmField
        val SNAPSHOT: WaitPlan = WaitPlan(listOf(Hop.snapshot()))

        /** Until the events are appended. */
        @JvmField
        val COMMITTED: WaitPlan = WaitPlan(listOf(Hop.committed()))

        /** Until the command passed admission. */
        @JvmField
        val ACCEPTED: WaitPlan = WaitPlan(listOf(Hop.accepted()))

        /** Until [hops] are reached, in order. */
        fun of(vararg hops: Hop): WaitPlan = WaitPlan(hops.toList())
    }
}
